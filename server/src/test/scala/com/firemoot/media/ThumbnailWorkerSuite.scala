package com.firemoot.media

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

import javax.imageio.ImageIO

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, MediaConfig}
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations, UploadRepo}
import com.firemoot.domain.Event
import com.firemoot.service.{ChannelService, MessageService, UserService}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * The full thumbnail loop (M2.3) against an in-memory object store: a stored
 * image upload is downscaled, the thumbnail written back, the upload advanced to
 * thumbnailed, and the referencing message's attachment patched with `thumbUrl`
 * via a re-emitted `message.updated`. The real S3 store is exercised in M2.5.
 */
class ThumbnailWorkerSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val mediaCfg = MediaConfig(
    endpoint = "http://localhost:9999",
    region = "us-east-1",
    bucket = "media",
    accessKey = "k",
    secretKey = Secret("s"),
    publicBaseUrl = None,
    presignExpiry = 15.minutes,
    maxImageBytes = 10L * 1024 * 1024,
    maxFileBytes = 50L * 1024 * 1024,
    allowedMime = Set("image/png"),
  )

  final private class MapStore(ref: Ref[IO, Map[String, Array[Byte]]]) extends ObjectStore:
    def get(key: String): IO[Array[Byte]] =
      ref.get.flatMap(_.get(key).liftTo[IO](new NoSuchElementException(key)))
    def put(key: String, bytes: Array[Byte], contentType: String): IO[Unit] =
      ref.update(_ + (key -> bytes))

  private def png(width: Int, height: Int): Array[Byte] =
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setColor(Color.RED)
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = new ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    out.toByteArray

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  test("thumbnails a stored image and patches the referencing message") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (
        Backplane.inProcess,
        Ref[IO].of(Map.empty[String, Array[Byte]]),
        Ref[IO].of(Vector.empty[Event]),
      )
        .flatMapN { (backplane, objects, seen) =>
          Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
            val users = UserService(pool)
            val channels = ChannelService(pool, backplane)
            val messages = MessageService(pool, backplane)
            val store = new MapStore(objects)
            val cid = "messaging:general"
            val objectKey = "uploads/abc/pic.png"
            val objectUrl = mediaCfg.objectUrl(objectKey)
            Slf4jLogger.create[IO].flatMap { logger =>
              val worker = ThumbnailWorker(mediaCfg, pool, store, messages, logger)
              for
                collector <-
                  backplane.subscribe.evalMap(e => seen.update(_ :+ e)).compile.drain.start
                _ <- IO.sleep(200.millis)

                _ <- users.upsert("ann", None, None, "user", Json.obj())
                _ <- channels.create("messaging", "general", Some("ann"), Json.obj())
                // A message referencing the upload by url.
                attachments =
                  Json.arr(Json.obj("type" -> "image".asJson, "url" -> objectUrl.asJson))
                msg <- messages
                  .send(cid, Some("ann"), Some("look"), Json.obj(), attachments, None, "regular")
                  .map(_.toOption.get)

                // A stored image upload plus its bytes in the object store.
                ticketId <- com.firemoot.domain.UuidV7.next
                _ <- pool.use(_.run(
                  UploadRepo.insert,
                  (ticketId, Some("ann"), objectKey, "image/png", png(800, 600).length.toLong),
                ))
                _ <- pool.use(_.runOption(UploadRepo.markStored, ticketId))
                _ <- objects.update(_ + (objectKey -> png(800, 600)))

                _ <- worker.runOnce
                _ <- IO.sleep(300.millis)
                _ <- collector.cancel

                status <- pool.use(_.runUnique(UploadRepo.statusOf, ticketId))
                stored <- objects.get
                events <- seen.get
                patched <- pool.use(_.runUnique(
                  com.firemoot.db.MessageRepo.byId,
                  msg.id,
                ))
              yield
                assertEquals(status, "thumbnailed", "the upload is advanced")
                assert(stored.contains(s"$objectKey.thumb.png"), "the thumbnail was written back")
                val thumbUrl = mediaCfg.objectUrl(s"$objectKey.thumb.png")
                val attachment = patched.attachments.asArray.get.head.hcursor
                assertEquals(attachment.get[String]("thumbUrl").toOption, Some(thumbUrl))
                assert(
                  events.exists(e =>
                    e.`type` == "message.updated" && e.cid == cid && e.seq > msg.seq
                  ),
                  "a message.updated was re-emitted",
                )
            }
          }
        }
    }
  }
