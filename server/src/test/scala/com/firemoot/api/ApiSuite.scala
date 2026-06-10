package com.firemoot.api

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.config.{DbConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.{Channel, Message, User}
import com.firemoot.service.{ChannelService, MessageService, UserService}
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import org.http4s.{Request, Status}
import org.testcontainers.utility.DockerImageName

class ApiSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val apiKey = "test-key"
  private val serverCfg = ServerConfig(apiKey, Secret("test-secret"))

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  private def authed(req: Request[IO]): Request[IO] =
    req.putHeaders("X-Firemoot-Key" -> apiKey)

  test("users, channels and messages: happy path, seq allocation, auth, openapi") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
        val api =
          ApiRoutes(serverCfg, UserService(pool), ChannelService(pool), MessageService(pool))
        val app = api.routes.orNotFound

        for
          userRes <- app.run(
            authed(Request[IO](POST, uri"/v1/users"))
              .withEntity(UpsertUserRequest("alice", Some("Alice"), None, None, None))
          )
          _ = assertEquals(userRes.status, Status.Ok)
          user <- userRes.as[User]
          _ = assertEquals(user.id, "alice")
          _ = assertEquals(user.name, Some("Alice"))

          chRes <- app.run(
            authed(Request[IO](POST, uri"/v1/channels"))
              .withEntity(CreateChannelRequest("messaging", "general", Some("alice"), None))
          )
          _ = assertEquals(chRes.status, Status.Created)
          channel <- chRes.as[Channel]
          _ = assertEquals(channel.cid, "messaging:general")
          _ = assertEquals(channel.currentSeq, 0L)

          m1Res <- app.run(
            authed(Request[IO](POST, uri"/v1/channels/messaging/general/messages"))
              .withEntity(SendMessageRequest(Some("alice"), Some("hello"), None, None, None))
          )
          _ = assertEquals(m1Res.status, Status.Created)
          m1 <- m1Res.as[Message]
          _ = assertEquals(m1.seq, 1L)
          _ = assertEquals(m1.text, Some("hello"))

          m2Res <- app.run(
            authed(Request[IO](POST, uri"/v1/channels/messaging/general/messages"))
              .withEntity(SendMessageRequest(Some("alice"), Some("world"), None, None, None))
          )
          m2 <- m2Res.as[Message]
          _ = assertEquals(m2.seq, 2L)

          wrongKey <- app.run(
            Request[IO](POST, uri"/v1/users")
              .putHeaders("X-Firemoot-Key" -> "nope")
              .withEntity(UpsertUserRequest("bob", None, None, None, None))
          )
          _ = assertEquals(wrongKey.status, Status.Unauthorized)
        yield
          assert(api.openApiJson.contains("/v1/users"), "openapi should document /v1/users")
          assert(api.openApiJson.contains("Firemoot"), "openapi should carry the title")
      }
    }
  }
