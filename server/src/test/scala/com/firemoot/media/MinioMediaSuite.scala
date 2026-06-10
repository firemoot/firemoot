package com.firemoot.media

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.URI

import javax.imageio.ImageIO

import scala.concurrent.duration.*

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.{GenericContainer, PostgreSQLContainer}
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.api.CreateUploadRequest
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, MediaConfig}
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations, UploadRepo}
import com.firemoot.domain.Event
import com.firemoot.service.{ChannelService, MessageService, UserService}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.Method.PUT
import org.http4s.headers.`Content-Type`
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.{MediaType, Request, Uri}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.typelevel.log4cats.slf4j.Slf4jLogger
import skunk.codec.all.*
import skunk.implicits.*
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

/**
 * The full media loop against a real S3 store (M2.5): presign an upload, PUT the
 * bytes to MinIO over HTTP, then let the thumbnail worker download, resize and
 * write back through the live `ObjectStore` - proving the generic-S3 path end to
 * end (the only seam the in-memory M2.3 test couldn't cover).
 */
class MinioMediaSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val minioImage =
    "pgsty/minio@sha256:83885c27b3b5b673049e33ddf4029afe2c134fd51ce4309e65e4f39d3b9ca282"

  private val minioResource: Resource[IO, GenericContainer] =
    Resource.make(IO.blocking {
      val container = GenericContainer(
        dockerImage = minioImage,
        exposedPorts = Seq(9000),
        env = Map("MINIO_ROOT_USER" -> "minioadmin", "MINIO_ROOT_PASSWORD" -> "minioadmin"),
        command = Seq("server", "/data"),
        waitStrategy = Wait.forHttp("/minio/health/ready").forPort(9000),
      )
      container.start()
      container
    })(c => IO.blocking(c.stop()))

  private def mediaConfig(endpoint: String): MediaConfig =
    MediaConfig(
      endpoint = endpoint,
      region = "us-east-1",
      bucket = "media",
      accessKey = "minioadmin",
      secretKey = Secret("minioadmin"),
      publicBaseUrl = None,
      presignExpiry = 15.minutes,
      maxImageBytes = 10L * 1024 * 1024,
      maxFileBytes = 50L * 1024 * 1024,
      allowedMime = Set("image/png"),
    )

  private val uploadRow =
    sql"select status, thumb_key from uploads where id = $uuid".query(text *: text.opt)

  private def png(width: Int, height: Int): Array[Byte] =
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setColor(Color.GREEN)
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = new ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    out.toByteArray

  private def createBucket(cfg: MediaConfig): IO[Unit] =
    IO.blocking {
      val client = S3Client
        .builder()
        .endpointOverride(URI.create(cfg.endpoint))
        .region(Region.of(cfg.region))
        .credentialsProvider(
          StaticCredentialsProvider.create(
            AwsBasicCredentials.create(cfg.accessKey, cfg.secretKey.value)
          )
        )
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClient(UrlConnectionHttpClient.create())
        .build()
      try client.createBucket(CreateBucketRequest.builder().bucket(cfg.bucket).build())
      finally client.close()
    }.void

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  test("presign -> PUT to MinIO -> worker thumbnails -> message.updated") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      minioResource.use { minio =>
        val endpoint = s"http://${minio.containerIpAddress}:${minio.mappedPort(9000)}"
        val mediaCfg = mediaConfig(endpoint)
        (Backplane.inProcess, Ref[IO].of(Vector.empty[Event])).flatMapN { (backplane, seen) =>
          Migrations.run(cfg) >> createBucket(mediaCfg) >> Database.pool(cfg).use { pool =>
            (
              MediaService.presigner(mediaCfg),
              ObjectStore.s3(mediaCfg),
              Resource.eval(JdkHttpClient.simple[IO]),
              Resource.eval(Slf4jLogger.create[IO]),
            ).tupled.use { (presigner, store, http, logger) =>
              val media = new MediaService(mediaCfg, pool, presigner)
              val messages = MessageService(pool, backplane)
              val worker = ThumbnailWorker(mediaCfg, pool, store, messages, logger)
              val image = png(900, 300)
              for
                collector <-
                  backplane.subscribe.evalMap(e => seen.update(_ :+ e)).compile.drain.start
                _ <- IO.sleep(200.millis)
                _ <- UserService(pool).upsert("ann", None, None, "user", Json.obj())
                _ <- ChannelService(
                  pool,
                  backplane,
                ).create("messaging", "general", Some("ann"), Json.obj())
                ticket <- media
                  .presignUpload(CreateUploadRequest(
                    Some("ann"),
                    "pic.png",
                    "image/png",
                    image.length.toLong,
                  ))
                  .map(_.toOption.get)
                _ <- messages.send(
                  "messaging:general",
                  Some("ann"),
                  Some("look"),
                  Json.obj(),
                  Json.arr(Json.obj("url" -> ticket.objectUrl.asJson)),
                  None,
                  "regular",
                )
                putStatus <- http.status(
                  Request[IO](PUT, Uri.unsafeFromString(ticket.uploadUrl))
                    .withEntity(image)
                    .withContentType(`Content-Type`(MediaType.image.png))
                )
                _ = assert(putStatus.isSuccess, s"PUT to presigned url failed: $putStatus")
                _ <- pool.use(_.runOption(UploadRepo.markStored, ticket.uploadId))
                _ <- worker.runOnce
                _ <- IO.sleep(300.millis)
                _ <- collector.cancel
                row <- pool.use(_.runUnique(uploadRow, ticket.uploadId))
                (status, thumbKey) = row
                thumbBytes <- store.get(thumbKey.get)
                dims <- IO.blocking {
                  val i = ImageIO.read(new ByteArrayInputStream(thumbBytes))
                  (i.getWidth, i.getHeight)
                }
                events <- seen.get
              yield
                assertEquals(status, "thumbnailed", "the upload advanced through the real store")
                assert(thumbKey.exists(_.endsWith(".thumb.png")), s"thumb key: $thumbKey")
                assert(dims._1 <= 512 && dims._2 <= 512, s"thumbnail fits 512px: $dims")
                assert(
                  events.exists(e => e.`type` == "message.updated"),
                  "the attachment patch re-emitted message.updated",
                )
            }
          }
        }
      }
    }
  }
