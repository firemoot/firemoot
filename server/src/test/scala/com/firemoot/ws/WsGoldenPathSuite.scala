package com.firemoot.ws

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.Application
import com.firemoot.api.{CreateChannelRequest, SendMessageRequest, UpsertUserRequest}
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, HttpConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.http.HttpServer
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.websocket.{WSFrame, WSRequest}
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}
import org.http4s.{Request, Uri}
import org.testcontainers.utility.DockerImageName

/**
 * End-to-end golden path (SPEC.md §14, M0.7): a REST `sendMessage` is delivered
 * as a `message.new` frame to a subscribed WebSocket - exercising the riskiest
 * seam, fs2 WS fan-out through the backplane.
 */
class WsGoldenPathSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val serverCfg = ServerConfig("test-key", Secret("test-secret"))

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
    req.putHeaders("X-Firemoot-Key" -> serverCfg.apiKeyId)

  test("REST send is delivered as message.new over a subscribed WebSocket") {
    withContainers { pg =>
      val dbCfg = dbConfig(pg)
      (Backplane.inProcess, ConnectionRegistry.create).flatMapN { (backplane, registry) =>
        Migrations.run(dbCfg) >> Database.pool(dbCfg).use { pool =>
          val app = Application.httpApp(serverCfg, pool, backplane, registry)
          HttpServer.resource(HttpConfig("127.0.0.1", 0), app).use { server =>
            val base = server.baseUri
            val wsUri = base
              .copy(scheme = Some(Uri.Scheme.unsafeFromString("ws")))
              .withPath(Uri.Path.unsafeFromString("/v1/ws"))
              .withQueryParam("user", "alice")
            val subscribe = """{"type":"subscribe","channels":{"messaging:general":0}}"""

            for
              http <- JdkHttpClient.simple[IO]
              ws <- JdkWSClient.simple[IO]
              _ <- http.status(
                authed(Request[IO](POST, base / "v1" / "users"))
                  .withEntity(UpsertUserRequest("alice", Some("Alice"), None, None, None))
              )
              _ <- http.status(
                authed(Request[IO](POST, base / "v1" / "channels"))
                  .withEntity(CreateChannelRequest("messaging", "general", Some("alice"), None))
              )
              frames <- ws.connectHighLevel(WSRequest(wsUri)).use { conn =>
                for
                  _ <- conn.send(WSFrame.Text(subscribe))
                  collector <- conn.receiveStream
                    .collect { case WSFrame.Text(body, _) => body }
                    .takeThrough(!_.contains("message.new"))
                    .interruptAfter(20.seconds)
                    .compile
                    .toList
                    .start
                  _ <- IO.sleep(500.millis)
                  _ <- http.status(
                    authed(Request[IO](
                      POST,
                      base / "v1" / "channels" / "messaging" / "general" / "messages",
                    ))
                      .withEntity(SendMessageRequest(
                        Some("alice"),
                        Some("hi there"),
                        None,
                        None,
                        None,
                      ))
                  )
                  collected <- collector.joinWithNever
                yield collected
              }
            yield assert(
              frames.exists(f => f.contains("message.new") && f.contains("hi there")),
              s"expected a message.new frame, got: $frames",
            )
          }
        }
      }
    }
  }
