package com.firemoot.api

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.auth.{ApiKeys, ServerHmacAuth}
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.{Channel, Message, User}
import com.firemoot.service.{ChannelService, MessageService, UserService}
import com.firemoot.testkit.Signing
import io.circe.Encoder
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import org.http4s.{Status, Uri}
import org.testcontainers.utility.DockerImageName

class ApiSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val apiKey = "test-key"
  private val secret = "test-secret"
  private val serverCfg = ServerConfig(apiKey, Secret(secret))

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  private def post[A: Encoder](path: String, dto: A, signingSecret: String = secret) =
    Signing.signedRequest(POST, Uri.unsafeFromString(path), dto, apiKey, signingSecret)

  test("users, channels and messages: happy path, seq allocation, auth, openapi") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val api =
            ApiRoutes(
              UserService(pool),
              ChannelService(pool, backplane),
              MessageService(pool, backplane),
            )
          val app = ServerHmacAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound

          for
            userRes <- app.run(post(
              "/v1/users",
              UpsertUserRequest("alice", Some("Alice"), None, None, None),
            ))
            _ = assertEquals(userRes.status, Status.Ok)
            user <- userRes.as[User]
            _ = assertEquals(user.id, "alice")
            _ = assertEquals(user.name, Some("Alice"))

            chRes <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "general", Some("alice"), None),
            ))
            _ = assertEquals(chRes.status, Status.Created)
            channel <- chRes.as[Channel]
            _ = assertEquals(channel.cid, "messaging:general")
            _ = assertEquals(channel.currentSeq, 0L)

            m1Res <- app.run(
              post(
                "/v1/channels/messaging/general/messages",
                SendMessageRequest(Some("alice"), Some("hello"), None, None, None),
              )
            )
            _ = assertEquals(m1Res.status, Status.Created)
            m1 <- m1Res.as[Message]
            _ = assertEquals(m1.seq, 1L)
            _ = assertEquals(m1.text, Some("hello"))

            m2Res <- app.run(
              post(
                "/v1/channels/messaging/general/messages",
                SendMessageRequest(Some("alice"), Some("world"), None, None, None),
              )
            )
            m2 <- m2Res.as[Message]
            _ = assertEquals(m2.seq, 2L)

            badSig <- app.run(post(
              "/v1/users",
              UpsertUserRequest("bob", None, None, None, None),
              signingSecret = "wrong",
            ))
            _ = assertEquals(badSig.status, Status.Unauthorized)

            delOk <- app.run(Signing.signedNoBody(DELETE, uri"/v1/users/alice", apiKey, secret))
            _ = assertEquals(delOk.status, Status.NoContent)
            delMissing <-
              app.run(Signing.signedNoBody(DELETE, uri"/v1/users/alice", apiKey, secret))
            _ = assertEquals(delMissing.status, Status.NotFound)
          yield
            assert(OpenApiDocs.compact.contains("/v1/users"), "openapi should document /v1/users")
            assert(OpenApiDocs.compact.contains("Firemoot"), "openapi should carry the title")
        }
      }
    }
  }

  test("channel endpoints: get / update / members / frozen / delete status codes") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val api =
            ApiRoutes(
              UserService(pool),
              ChannelService(pool, backplane),
              MessageService(pool, backplane),
            )
          val app = ServerHmacAuth(ApiKeys.fromConfig(serverCfg))(api.routes).orNotFound
          val chPath = "/v1/channels/messaging/room2"
          def get(path: String) =
            Signing.signedNoBody(GET, Uri.unsafeFromString(path), apiKey, secret)
          def del(path: String) =
            Signing.signedNoBody(DELETE, Uri.unsafeFromString(path), apiKey, secret)
          def send[A: Encoder](method: org.http4s.Method, path: String, dto: A) =
            Signing.signedRequest(method, Uri.unsafeFromString(path), dto, apiKey, secret)

          for
            _ <- app.run(post("/v1/users", UpsertUserRequest("carol", None, None, None, None)))
            missing <- app.run(get(chPath))
            _ = assertEquals(missing.status, Status.NotFound)
            created <- app.run(post(
              "/v1/channels",
              CreateChannelRequest("messaging", "room2", Some("carol"), None),
            ))
            _ = assertEquals(created.status, Status.Created)
            got <- app.run(get(chPath))
            _ = assertEquals(got.status, Status.Ok)
            badRole <-
              app.run(send(POST, s"$chPath/members", AddMemberRequest("dave", Some("king"))))
            _ = assertEquals(badRole.status, Status.BadRequest)
            _ <- app.run(post("/v1/users", UpsertUserRequest("dave", None, None, None, None)))
            addOk <-
              app.run(send(POST, s"$chPath/members", AddMemberRequest("dave", Some("moderator"))))
            _ = assertEquals(addOk.status, Status.NoContent)
            removeOk <- app.run(del(s"$chPath/members/dave"))
            _ = assertEquals(removeOk.status, Status.NoContent)
            frozen <- app.run(send(PATCH, chPath, UpdateChannelRequest(None, Some(true), None)))
            _ = assertEquals(frozen.status, Status.Ok)
            sendFrozen <- app.run(send(
              POST,
              s"$chPath/messages",
              SendMessageRequest(Some("carol"), Some("hi"), None, None, None),
            ))
            _ = assertEquals(sendFrozen.status, Status.Conflict)
            deleted <- app.run(del(chPath))
            _ = assertEquals(deleted.status, Status.NoContent)
            gone <- app.run(get(chPath))
          yield assertEquals(gone.status, Status.NotFound)
        }
      }
    }
  }
