package com.firemoot.ratelimit

import cats.effect.{IO, Resource}
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.api.*
import com.firemoot.auth.{ApiKeys, ServerHmacAuth}
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.service.*
import com.firemoot.testkit.Signing
import io.circe.Encoder
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.implicits.*
import org.http4s.{HttpApp, Status, Uri}
import org.testcontainers.utility.DockerImageName
import skunk.Session

/** Rate limiting applied through the real auth + routing stack (M1.12). */
class RateLimitSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val apiKey = "test-key"
  private val secret = "test-secret"
  private val serverCfg = ServerConfig(apiKey, Secret(secret))
  private val generous = RateLimitConfig(10_000, 10_000.0)

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
      rate,
    )
    ServerHmacAuth(ApiKeys.fromConfig(serverCfg), rate)(api.routes).orNotFound

  test("per-API-key budget returns 429 once exhausted") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      val limits =
        RateLimits(apiKey = RateLimitConfig(1, 0.001), send = generous, connect = generous)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          RateGuard.inMemory(limits).flatMap { rate =>
            val httpApp = app(pool, backplane, rate)
            for
              first <- httpApp.run(post("/v1/search", SearchRequest("hi", None, None)))
              second <- httpApp.run(post("/v1/search", SearchRequest("hi", None, None)))
            yield
              assertEquals(first.status, Status.Ok, "the first request is within budget")
              assertEquals(
                second.status,
                Status.TooManyRequests,
                "the second exceeds the key budget",
              )
          }
        }
      }
    }
  }

  test("per-user send budget returns 429 once exhausted, independently per user") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      val limits =
        RateLimits(apiKey = generous, send = RateLimitConfig(1, 0.001), connect = generous)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          RateGuard.inMemory(limits).flatMap { rate =>
            val httpApp = app(pool, backplane, rate)
            val msgs = "/v1/channels/rl/room/messages"
            def send(user: String) =
              httpApp.run(post(msgs, SendMessageRequest(Some(user), Some("hi"), None, None, None)))
            for
              _ <- httpApp.run(post("/v1/users", UpsertUserRequest("u1", None, None, None, None)))
              _ <- httpApp.run(post("/v1/users", UpsertUserRequest("u2", None, None, None, None)))
              _ <- httpApp.run(
                post("/v1/channels", CreateChannelRequest("rl", "room", Some("u1"), None))
              )
              first <- send("u1")
              second <- send("u1")
              otherUser <- send("u2")
            yield
              assertEquals(first.status, Status.Created)
              assertEquals(
                second.status,
                Status.TooManyRequests,
                "u1's send budget is exhausted",
              )
              assertEquals(otherUser.status, Status.Created, "u2 has an independent budget")
          }
        }
      }
    }
  }
