package com.firemoot.streamapi

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.Application
import com.firemoot.api.SendMessageRequest
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, HttpConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.http.HttpServer
import com.firemoot.testkit.Signing
import com.firemoot.ws.ConnectionRegistry
import fs2.Stream
import io.circe.parser
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.websocket.{WSClient, WSFrame, WSRequest}
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}
import org.testcontainers.utility.DockerImageName
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}
import skunk.Session
import skunk.implicits.*

/**
 * `/api/v2/connect` handshake ordering. stream-chat v10 takes the first frame
 * after its auth message as the handshake and reads `connection_id` from it, so
 * `connection.ok` must precede every event, including ones fanned out while the
 * handshake is still running. The connecting user has ~20k memberships so the
 * handshake's unread counts take real time, as they do for frented's pool users,
 * and a pump keeps `message.new` flowing to one of their channels throughout.
 */
class StreamWsHandshakeSuite extends CatsEffectSuite, TestContainerForAll:

  override val munitIOTimeout: Duration = 3.minutes

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val apiKey = "test-key"
  private val secret = "test-secret"
  private val serverCfg = ServerConfig(apiKey, Secret(secret))
  private val memberships = 20000

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 8,
    )

  private def seed(s: Session[IO]): IO[Unit] =
    List(
      sql"insert into users (id) values ('alice'), ('carol')".command,
      sql"""
        insert into channels (cid, type, id, created_by, current_seq)
        select 'messaging:bulk' || g, 'messaging', 'bulk' || g, 'alice', 1
        from generate_series(1, #${memberships.toString}) g
      """.command,
      sql"""
        insert into messages (id, cid, seq, user_id, text)
        select 'bulk-' || g, 'messaging:bulk' || g, 1, 'alice', 'read already'
        from generate_series(1, #${memberships.toString}) g
      """.command,
      sql"""
        insert into channel_members (cid, user_id, last_read_seq)
        select 'messaging:bulk' || g, 'carol', 1
        from generate_series(1, #${memberships.toString}) g
      """.command,
      sql"""
        insert into channels (cid, type, id, created_by)
        values ('messaging:hot', 'messaging', 'hot', 'alice')
      """.command,
      sql"""
        insert into channel_members (cid, user_id)
        values ('messaging:hot', 'alice'), ('messaging:hot', 'carol')
      """.command,
      sql"analyze".command,
    ).traverse_(s.execute(_).void)

  private def connectUri(base: Uri): Uri =
    base
      .copy(scheme = Some(Uri.Scheme.unsafeFromString("ws")))
      .withPath(Uri.Path.unsafeFromString("/api/v2/connect"))
      .withQueryParam("api_key", apiKey)
      .withQueryParam("stream-auth-type", "jwt")

  private val authFrame: String =
    val token =
      JwtCirce.encode(JwtClaim(content = """{"user_id":"carol"}"""), secret, JwtAlgorithm.HS256)
    s"""{"token":"$token","user_details":{"id":"carol"},"products":["chat"]}"""

  private def typeOf(frame: String): Option[String] =
    parser.parse(frame).toOption.flatMap(_.hcursor.get[String]("type").toOption)

  /** Carol's frames from the first through the first `notification.message_new`. */
  private def framesUntilNotified(ws: WSClient[IO], base: Uri): IO[List[String]] =
    ws.connectHighLevel(WSRequest(connectUri(base))).use { conn =>
      conn.send(WSFrame.Text(authFrame)) >>
        conn.receiveStream
          .collect { case WSFrame.Text(body, _) => body }
          .takeThrough(f => !typeOf(f).contains("notification.message_new"))
          .compile
          .toList
          .timeout(30.seconds)
    }

  private def pump(http: Client[IO], base: Uri): IO[Nothing] =
    Stream
      .repeatEval(
        http
          .status(Signing.signedRequest(
            POST,
            base / "v1" / "channels" / "messaging" / "hot" / "messages",
            SendMessageRequest(Some("alice"), Some("pump"), None, None, None),
            apiKey,
            secret,
          ))
          .void
      )
      .compile
      .drain >> IO.never

  test("connection.ok is the first frame while events flow to the user's channels") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (
        Backplane.inProcess,
        ConnectionRegistry.create,
        StreamConnections.create,
        Slf4jLogger.create[IO],
      )
        .flatMapN { (backplane, registry, connections, log) =>
          Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
            val app =
              Application.httpApp(
                serverCfg,
                pool,
                backplane,
                registry,
                streamConnections = connections,
              )
            val events =
              StreamEvents(
                pool,
                backplane,
                connections,
                StreamStateService(connections, registry),
                log,
              )
            pool.use(seed) >>
              HttpServer.resource(HttpConfig("127.0.0.1", 0), app).use { server =>
                (JdkHttpClient.simple[IO], JdkWSClient.simple[IO]).flatMapN { (http, ws) =>
                  events.stream.compile.drain.background.surround {
                    pump(http, server.baseUri).background.surround {
                      (1 to 10).toList.traverse(_ => framesUntilNotified(ws, server.baseUri))
                    }
                  }
                }
              }
          }
        }
        .map { sockets =>
          sockets.foreach { frames =>
            assertEquals(typeOf(frames.head), Some("connection.ok"), frames.head)
            // The pump really was reaching carol's sockets.
            assertEquals(typeOf(frames.last), Some("notification.message_new"))
          }
        }
    }
  }
