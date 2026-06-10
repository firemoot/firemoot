package com.firemoot.ws

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.Application
import com.firemoot.api.{CreateChannelRequest, SendMessageRequest, UpsertUserRequest}
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
 * End-to-end golden path (SPEC.md §14, M0.7) with real M1.1 auth: HMAC-signed
 * REST seeds, a verified JWT on the socket, and a REST `sendMessage` delivered
 * as a `message.new` frame.
 */
class WsGoldenPathSuite extends CatsEffectSuite, TestContainerForAll:

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

  test("REST send is delivered as message.new over a JWT-authenticated WebSocket") {
    withContainers { pg =>
      val dbCfg = dbConfig(pg)
      (Backplane.inProcess, ConnectionRegistry.create).flatMapN { (backplane, registry) =>
        Migrations.run(dbCfg) >> Database.pool(dbCfg).use { pool =>
          val app = Application.httpApp(serverCfg, pool, backplane, registry)
          HttpServer.resource(HttpConfig("127.0.0.1", 0), app).use { server =>
            val base = server.baseUri
            val jwt = JwtAuth.sign(secret, "alice", None, Instant.now().plusSeconds(3600))
            val wsUri = base
              .copy(scheme = Some(Uri.Scheme.unsafeFromString("ws")))
              .withPath(Uri.Path.unsafeFromString("/v1/ws"))
              .withQueryParam("token", jwt)
            val subscribe = """{"type":"subscribe","channels":{"messaging:general":0}}"""

            for
              http <- JdkHttpClient.simple[IO]
              ws <- JdkWSClient.simple[IO]
              s1 <- http.status(
                Signing.signedRequest(
                  POST,
                  base / "v1" / "users",
                  UpsertUserRequest("alice", Some("Alice"), None, None, None),
                  apiKey,
                  secret,
                )
              )
              _ = assert(s1.isSuccess, s"user seed failed: $s1")
              s2 <- http.status(
                Signing.signedRequest(
                  POST,
                  base / "v1" / "channels",
                  CreateChannelRequest("messaging", "general", Some("alice"), None),
                  apiKey,
                  secret,
                )
              )
              _ = assert(s2.isSuccess, s"channel seed failed: $s2")
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
                    Signing.signedRequest(
                      POST,
                      base / "v1" / "channels" / "messaging" / "general" / "messages",
                      SendMessageRequest(Some("alice"), Some("hi there"), None, None, None),
                      apiKey,
                      secret,
                    )
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
