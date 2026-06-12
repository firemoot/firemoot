package com.firemoot.media

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.api.*
import com.firemoot.auth.{ApiAuth, ApiKeys}
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, MediaConfig, ServerConfig}
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations, UploadRepo}
import com.firemoot.ratelimit.{RateGuard, RateLimitConfig, RateLimits}
import com.firemoot.service.*
import com.firemoot.testkit.Signing
import io.circe.Encoder
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.implicits.*
import org.http4s.{HttpApp, Status, Uri}
import org.testcontainers.utility.DockerImageName
import skunk.Session

class MediaSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val apiKey = "test-key"
  private val secret = "test-secret"
  private val serverCfg = ServerConfig(apiKey, Secret(secret))
  private val endpoint = "http://localhost:9999"

  private def mediaConfig(publicBaseUrl: Option[String] = None): MediaConfig =
    MediaConfig(
      endpoint = endpoint,
      region = "us-east-1",
      bucket = "media",
      accessKey = "test-access",
      secretKey = Secret("test-secret-key"),
      publicBaseUrl = publicBaseUrl,
      presignExpiry = 15.minutes,
      maxImageBytes = 1024L * 1024,
      maxFileBytes = 5L * 1024 * 1024,
      allowedMime = Set("image/png", "application/pdf"),
    )

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  private def post[A: Encoder](path: String, dto: A) =
    Signing.signedRequest(POST, Uri.unsafeFromString(path), dto, apiKey, secret)

  private def app(
      pool: Resource[IO, Session[IO]],
      backplane: Backplane,
      rate: RateGuard,
      media: Option[MediaService],
  ): HttpApp[IO] =
    val webhooks = WebhookService(pool)
    val api = ApiRoutes(
      UserService(pool),
      ChannelService(pool, backplane),
      MessageService(pool, backplane),
      ReactionService(pool, backplane),
      ReadService(pool, backplane),
      QueryService(pool),
      webhooks,
      ModerationService(pool, webhooks),
      HydrationService(pool),
      rate,
      media,
    )
    ApiAuth(ApiKeys.fromConfig(serverCfg), rate)(api.routes).orNotFound

  test("presigns a valid upload, records it pending, and builds the object url") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { _ =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          MediaService.resource(mediaConfig(), pool).use { media =>
            for
              _ <- UserService(pool).upsert("up", None, None, "user", io.circe.Json.obj())
              result <- media.presignUpload(
                CreateUploadRequest(Some("up"), "photo.png", "image/png", 2048)
              )
              ticket = result.toOption.get
              status <- pool.use(_.runUnique(UploadRepo.statusOf, ticket.uploadId))
            yield
              assert(result.isRight, s"expected a ticket, got $result")
              assert(
                ticket.uploadUrl.contains("/media/uploads/") &&
                  ticket.uploadUrl.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256") &&
                  ticket.uploadUrl.contains("X-Amz-Signature="),
                s"presigned PUT url: ${ticket.uploadUrl}",
              )
              assertEquals(
                ticket.objectUrl,
                s"$endpoint/media/uploads/${ticket.uploadId}/photo.png",
              )
              assertEquals(ticket.expiresInSeconds, 900L)
              assertEquals(status, "pending")
          }
        }
      }
    }
  }

  test("rejects an unsupported MIME type and an oversize upload") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { _ =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          MediaService.resource(mediaConfig(), pool).use { media =>
            for
              badType <-
                media.presignUpload(CreateUploadRequest(None, "clip.mp4", "video/mp4", 100))
              tooBig <- media.presignUpload(
                CreateUploadRequest(None, "big.png", "image/png", 5L * 1024 * 1024)
              )
            yield
              assertEquals(badType, Left(UploadError.UnsupportedType("video/mp4")))
              assertEquals(tooBig, Left(UploadError.TooLarge(1024L * 1024)))
          }
        }
      }
    }
  }

  test("a public base url overrides the object url host") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { _ =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          MediaService.resource(mediaConfig(Some("https://cdn.firemoot.com/")), pool).use { media =>
            for
              result <-
                media.presignUpload(CreateUploadRequest(None, "a.pdf", "application/pdf", 10))
            yield assert(
              result.toOption.get.objectUrl.startsWith("https://cdn.firemoot.com/uploads/"),
              result.toString,
            )
          }
        }
      }
    }
  }

  test("uploads return 501 when media is not configured") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val httpApp = app(pool, backplane, RateGuard.unlimited, media = None)
          httpApp
            .run(post("/v1/uploads", CreateUploadRequest(Some("x"), "f.png", "image/png", 1)))
            .map(res => assertEquals(res.status, Status.NotImplemented))
        }
      }
    }
  }

  test("the per-user upload budget returns 429 once exhausted") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      val generous = RateLimitConfig(10_000, 10_000.0)
      val limits = RateLimits(
        apiKey = generous,
        send = generous,
        connect = generous,
        upload = RateLimitConfig(1, 0.001),
      )
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          (MediaService.resource(mediaConfig(), pool), Resource.eval(RateGuard.inMemory(limits)))
            .tupled
            .use { (media, rate) =>
              val httpApp = app(pool, backplane, rate, Some(media))
              def upload =
                httpApp.run(post(
                  "/v1/uploads",
                  CreateUploadRequest(Some("up"), "f.png", "image/png", 1),
                ))
              for
                _ <- UserService(pool).upsert("up", None, None, "user", io.circe.Json.obj())
                first <- upload
                second <- upload
              yield
                assertEquals(first.status, Status.Created)
                assertEquals(second.status, Status.TooManyRequests)
            }
        }
      }
    }
  }
