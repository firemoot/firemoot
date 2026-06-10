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
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations}
import com.firemoot.http.HttpServer
import com.firemoot.testkit.Signing
import io.circe.parser
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.client.Client
import org.http4s.client.websocket.{WSFrame, WSRequest}
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}
import org.http4s.{Status, Uri}
import org.testcontainers.utility.DockerImageName
import skunk.codec.all.*
import skunk.implicits.*

/**
 * The resume splice (M1.8): messages written concurrently with a re-subscribe
 * arrive exactly once, in seq order, neither lost nor duplicated; and a resume
 * point that predates retained events yields `resync_required`.
 */
class WsResumeSuite extends CatsEffectSuite, TestContainerForAll:

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
      maxConnections = 8,
    )

  private def seed[A: io.circe.Encoder](http: Client[IO], uri: Uri, body: A): IO[Status] =
    http.status(Signing.signedRequest(POST, uri, body, apiKey, secret))

  private def messageSeqs(frames: List[String]): List[Long] =
    frames
      .filter(_.contains("\"type\":\"message.new\""))
      .flatMap(f => parser.parse(f).toOption.flatMap(_.hcursor.get[Long]("seq").toOption))

  test("messages sent during a re-subscribe arrive once each, in seq order") {
    withContainers { pg =>
      val dbCfg = dbConfig(pg)
      val user = "racer"
      val cid = "messaging:race"
      (Backplane.inProcess, ConnectionRegistry.create).flatMapN { (backplane, registry) =>
        Migrations.run(dbCfg) >> Database.pool(dbCfg).use { pool =>
          val app = Application.httpApp(serverCfg, pool, backplane, registry)
          HttpServer.resource(HttpConfig("127.0.0.1", 0), app).use { server =>
            val base = server.baseUri
            val jwt = JwtAuth.sign(secret, user, None, Instant.now().plusSeconds(3600))
            val wsUri = base
              .copy(scheme = Some(Uri.Scheme.unsafeFromString("ws")))
              .withPath(Uri.Path.unsafeFromString("/v1/ws"))
              .withQueryParam("token", jwt)
            val subscribe = s"""{"type":"subscribe","channels":{"$cid":0}}"""

            def post(http: Client[IO], text: String): IO[Status] =
              seed(
                http,
                base / "v1" / "channels" / "messaging" / "race" / "messages",
                SendMessageRequest(Some(user), Some(text), None, None, None),
              )

            for
              http <- JdkHttpClient.simple[IO]
              ws <- JdkWSClient.simple[IO]
              _ <- seed(
                http,
                base / "v1" / "users",
                UpsertUserRequest(user, Some("Racer"), None, None, None),
              )
              _ <- seed(
                http,
                base / "v1" / "channels",
                CreateChannelRequest("messaging", "race", Some(user), None),
              )
              // Five messages committed before connecting (replayed on resume)...
              _ <- (1 to 5).toList.traverse_(i => post(http, s"pre-$i"))

              frames <- ws.connectHighLevel(WSRequest(wsUri)).use { conn =>
                for
                  collector <- conn.receiveStream
                    .collect { case WSFrame.Text(body, _) => body }
                    .takeThrough(!_.contains("LAST"))
                    .interruptAfter(25.seconds)
                    .compile
                    .toList
                    .start
                  _ <- conn.send(WSFrame.Text(subscribe))
                  // ...and five more fired with no delay, to race the replay/live splice.
                  sender <- (
                    (1 to 4).toList.traverse_(i => post(http, s"mid-$i")) >> post(http, "LAST")
                  ).start
                  _ <- sender.join
                  collected <- collector.joinWithNever
                yield collected
              }
            yield
              val seqs = messageSeqs(frames)
              assertEquals(seqs.size, 10, s"all ten messages delivered exactly once: $seqs")
              assertEquals(seqs.distinct.size, 10, s"no duplicates: $seqs")
              assertEquals(seqs, seqs.sorted, s"delivered in ascending seq order: $seqs")
          }
        }
      }
    }
  }

  test("a resume point older than retained events yields resync_required") {
    withContainers { pg =>
      val dbCfg = dbConfig(pg)
      val user = "laggard"
      val cid = "messaging:resync"
      val prune =
        sql"delete from channel_events where cid = $text and seq <= $int8".command
      (Backplane.inProcess, ConnectionRegistry.create).flatMapN { (backplane, registry) =>
        Migrations.run(dbCfg) >> Database.pool(dbCfg).use { pool =>
          val app = Application.httpApp(serverCfg, pool, backplane, registry)
          HttpServer.resource(HttpConfig("127.0.0.1", 0), app).use { server =>
            val base = server.baseUri
            val jwt = JwtAuth.sign(secret, user, None, Instant.now().plusSeconds(3600))
            val wsUri = base
              .copy(scheme = Some(Uri.Scheme.unsafeFromString("ws")))
              .withPath(Uri.Path.unsafeFromString("/v1/ws"))
              .withQueryParam("token", jwt)
            // Resume from seq 1, but everything up to seq 4 has been pruned.
            val subscribe = s"""{"type":"subscribe","channels":{"$cid":1}}"""

            def post(http: Client[IO], text: String): IO[Status] =
              seed(
                http,
                base / "v1" / "channels" / "messaging" / "resync" / "messages",
                SendMessageRequest(Some(user), Some(text), None, None, None),
              )

            for
              http <- JdkHttpClient.simple[IO]
              ws <- JdkWSClient.simple[IO]
              _ <- seed(
                http,
                base / "v1" / "users",
                UpsertUserRequest(user, Some("Laggard"), None, None, None),
              )
              _ <- seed(
                http,
                base / "v1" / "channels",
                CreateChannelRequest("messaging", "resync", Some(user), None),
              )
              _ <- (1 to 6).toList.traverse_(i => post(http, s"m-$i"))
              _ <- pool.use(_.run(prune, (cid, 4L)))

              frames <- ws.connectHighLevel(WSRequest(wsUri)).use { conn =>
                for
                  collector <- conn.receiveStream
                    .collect { case WSFrame.Text(body, _) => body }
                    .takeThrough(!_.contains("resync_required"))
                    .interruptAfter(20.seconds)
                    .compile
                    .toList
                    .start
                  _ <- conn.send(WSFrame.Text(subscribe))
                  collected <- collector.joinWithNever
                yield collected
              }
            yield assert(
              frames.exists(f => f.contains("resync_required") && f.contains(cid)),
              s"expected a resync_required frame for $cid, got: $frames",
            )
          }
        }
      }
    }
  }
