package com.firemoot.ws

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.Application
import com.firemoot.api.{AddMemberRequest, CreateChannelRequest, UpsertUserRequest}
import com.firemoot.auth.JwtAuth
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, HttpConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.http.HttpServer
import com.firemoot.testkit.Signing
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.Uri
import org.http4s.client.websocket.{WSFrame, WSRequest}
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}
import org.testcontainers.utility.DockerImageName

/**
 * Two sockets sharing a channel: when bob connects, the co-member alice gets a
 * `presence.changed` online frame; when bob types, alice gets a `typing.start`
 * frame (M1.7). Both are delivered over live WebSockets end to end.
 */
class WsPresenceTypingSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val apiKey = "test-key"
  private val secret = "test-secret"
  private val serverCfg = ServerConfig(apiKey, Secret(secret))
  private val cid = "messaging:general"
  private val subscribe = """{"type":"subscribe","channels":{"messaging:general":0}}"""

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  test("a co-member receives presence.changed online and typing.start over the socket") {
    withContainers { pg =>
      val dbCfg = dbConfig(pg)
      (Backplane.inProcess, ConnectionRegistry.create).flatMapN { (backplane, registry) =>
        Migrations.run(dbCfg) >> Database.pool(dbCfg).use { pool =>
          val app = Application.httpApp(serverCfg, pool, backplane, registry)
          HttpServer.resource(HttpConfig("127.0.0.1", 0), app).use { server =>
            val base = server.baseUri

            def wsUri(user: String): Uri =
              base
                .copy(scheme = Some(Uri.Scheme.unsafeFromString("ws")))
                .withPath(Uri.Path.unsafeFromString("/v1/ws"))
                .withQueryParam(
                  "token",
                  JwtAuth.sign(secret, user, None, Instant.now().plusSeconds(3600)),
                )

            def seed[A: io.circe.Encoder](path: Uri, body: A) =
              Signing.signedRequest(POST, path, body, apiKey, secret)

            for
              http <- JdkHttpClient.simple[IO]
              ws <- JdkWSClient.simple[IO]
              _ <- http.status(seed(
                base / "v1" / "users",
                UpsertUserRequest("alice", Some("Alice"), None, None, None),
              ))
              _ <- http.status(seed(
                base / "v1" / "users",
                UpsertUserRequest("bob", Some("Bob"), None, None, None),
              ))
              _ <- http.status(
                seed(
                  base / "v1" / "channels",
                  CreateChannelRequest("messaging", "general", Some("alice"), None),
                )
              )
              _ <- http.status(
                seed(
                  base / "v1" / "channels" / "messaging" / "general" / "members",
                  AddMemberRequest("bob", Some("member")),
                )
              )

              frames <- ws.connectHighLevel(WSRequest(wsUri("alice"))).use { alice =>
                for
                  _ <- alice.send(WSFrame.Text(subscribe))
                  collector <- alice.receiveStream
                    .collect { case WSFrame.Text(body, _) => body }
                    .takeThrough(!_.contains("typing.start"))
                    .interruptAfter(20.seconds)
                    .compile
                    .toList
                    .start
                  _ <- IO.sleep(500.millis)
                  collected <- ws.connectHighLevel(WSRequest(wsUri("bob"))).use { bob =>
                    for
                      _ <- bob.send(WSFrame.Text(subscribe))
                      _ <- IO.sleep(500.millis)
                      _ <- bob.send(
                        WSFrame.Text("""{"type":"typing.start","cid":"messaging:general"}""")
                      )
                      result <- collector.joinWithNever
                    yield result
                  }
                yield collected
              }
            yield
              assert(
                frames.exists(f =>
                  f.contains("presence.changed") && f.contains("\"bob\"") && f.contains("online")
                ),
                s"expected a presence.changed online frame for bob, got: $frames",
              )
              assert(
                frames.exists(f =>
                  f.contains("typing.start") && f.contains(cid) && f.contains("\"bob\"")
                ),
                s"expected a typing.start frame from bob, got: $frames",
              )
          }
        }
      }
    }
  }
