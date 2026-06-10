package com.firemoot.ws

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.Application
import com.firemoot.api.{
  AddMemberRequest,
  CreateChannelRequest,
  MarkReadRequest,
  SendMessageRequest,
  UpsertUserRequest,
}
import com.firemoot.auth.JwtAuth
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, HttpConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.Message
import com.firemoot.http.HttpServer
import com.firemoot.testkit.Signing
import io.circe.parser
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.client.websocket.{WSClient, WSConnectionHighLevel, WSFrame, WSRequest}
import org.http4s.jdkhttpclient.{JdkHttpClient, JdkWSClient}
import org.http4s.{Status, Uri}
import org.testcontainers.utility.DockerImageName

/**
 * The WebSocket protocol's executable spec (SPEC.md §5, M1.13). Raw-frame tests
 * over a real Ember server: the `hello` handshake, ordered subscribe/replay,
 * resume from a seq, the ping/pong heartbeat, and multi-device fan-out of a
 * user-directed event. Grow this suite with every new event type.
 */
class WsProtocolSuite extends CatsEffectSuite, TestContainerForAll:

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

  private def wsUri(base: Uri, user: String): Uri =
    base
      .copy(scheme = Some(Uri.Scheme.unsafeFromString("ws")))
      .withPath(Uri.Path.unsafeFromString("/v1/ws"))
      .withQueryParam("token", JwtAuth.sign(secret, user, None, Instant.now().plusSeconds(3600)))

  private def messageSeqs(frames: List[String]): List[Long] =
    frames
      .filter(_.contains("\"type\":\"message.new\""))
      .flatMap(f => parser.parse(f).toOption.flatMap(_.hcursor.get[Long]("seq").toOption))

  private def subscribe(cid: String, lastSeen: Long): String =
    s"""{"type":"subscribe","channels":{"$cid":$lastSeen}}"""

  /** Runs a body with an HTTP client, WS client and a live server over a fresh DB. */
  private def withServer[A](
      pg: PostgreSQLContainer
  )(body: (Client[IO], WSClient[IO], Uri) => IO[A]): IO[A] =
    val cfg = dbConfig(pg)
    (Backplane.inProcess, ConnectionRegistry.create).flatMapN { (backplane, registry) =>
      Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
        val app = Application.httpApp(serverCfg, pool, backplane, registry)
        HttpServer.resource(HttpConfig("127.0.0.1", 0), app).use { server =>
          (JdkHttpClient.simple[IO], JdkWSClient.simple[IO]).flatMapN { (http, ws) =>
            body(http, ws, server.baseUri)
          }
        }
      }
    }

  private def seed[A: io.circe.Encoder](http: Client[IO], uri: Uri, body: A): IO[Status] =
    http.status(Signing.signedRequest(POST, uri, body, apiKey, secret))

  test("handshake: the first frame is hello with identity and total unread") {
    withContainers { pg =>
      withServer(pg) { (http, ws, base) =>
        for
          _ <- seed(
            http,
            base / "v1" / "users",
            UpsertUserRequest("ann", Some("Ann"), None, None, None),
          )
          hello <- ws.connectHighLevel(WSRequest(wsUri(base, "ann"))).use { conn =>
            conn.receiveStream.collect { case WSFrame.Text(b, _) => b }.head.compile.lastOrError
          }
        yield
          val json = parser.parse(hello).toOption.get.hcursor
          assertEquals(json.get[String]("type").toOption, Some("hello"))
          assert(json.get[String]("connectionId").toOption.exists(_.nonEmpty))
          assertEquals(json.downField("me").get[String]("id").toOption, Some("ann"))
          assertEquals(json.get[Long]("totalUnread").toOption, Some(0L))
      }
    }
  }

  test("subscribe replays persisted messages in ascending seq order, then live") {
    withContainers { pg =>
      val cid = "messaging:order"
      withServer(pg) { (http, ws, base) =>
        def send(text: String) =
          http
            .run(Signing.signedRequest(
              POST,
              base / "v1" / "channels" / "messaging" / "order" / "messages",
              SendMessageRequest(Some("ann"), Some(text), None, None, None),
              apiKey,
              secret,
            ))
            .use(_.as[Message])

        for
          _ <- seed(http, base / "v1" / "users", UpsertUserRequest("ann", None, None, None, None))
          _ <- seed(
            http,
            base / "v1" / "channels",
            CreateChannelRequest("messaging", "order", Some("ann"), None),
          )
          _ <- List("m1", "m2", "m3").traverse_(t => send(t).void)
          frames <- ws.connectHighLevel(WSRequest(wsUri(base, "ann"))).use { conn =>
            for
              _ <- conn.send(WSFrame.Text(subscribe(cid, 0)))
              collector <- conn.receiveStream
                .collect { case WSFrame.Text(b, _) => b }
                .takeThrough(!_.contains("\"text\":\"DONE\""))
                .interruptAfter(20.seconds)
                .compile
                .toList
                .start
              _ <- IO.sleep(400.millis)
              _ <- send("DONE")
              out <- collector.joinWithNever
            yield out
          }
        yield
          val seqs = messageSeqs(frames)
          assertEquals(seqs.size, 4, s"three replayed plus one live: $seqs")
          assertEquals(seqs, seqs.sorted, s"ascending seq order: $seqs")
      }
    }
  }

  test("resume from a seq replays only the gap after it") {
    withContainers { pg =>
      val cid = "messaging:gap"
      withServer(pg) { (http, ws, base) =>
        def send(text: String) =
          http
            .run(Signing.signedRequest(
              POST,
              base / "v1" / "channels" / "messaging" / "gap" / "messages",
              SendMessageRequest(Some("ann"), Some(text), None, None, None),
              apiKey,
              secret,
            ))
            .use(_.as[Message])

        for
          _ <- seed(http, base / "v1" / "users", UpsertUserRequest("ann", None, None, None, None))
          _ <- seed(
            http,
            base / "v1" / "channels",
            CreateChannelRequest("messaging", "gap", Some("ann"), None),
          )
          sent <- (1 to 5).toList.traverse(i => send(s"m$i"))
          cutoff = sent(2).seq // resume right after the third message
          frames <- ws.connectHighLevel(WSRequest(wsUri(base, "ann"))).use { conn =>
            for
              _ <- conn.send(WSFrame.Text(subscribe(cid, cutoff)))
              collector <- conn.receiveStream
                .collect { case WSFrame.Text(b, _) => b }
                .takeThrough(!_.contains("\"text\":\"DONE\""))
                .interruptAfter(20.seconds)
                .compile
                .toList
                .start
              _ <- IO.sleep(400.millis)
              _ <- send("DONE")
              out <- collector.joinWithNever
            yield out
          }
        yield
          val seqs = messageSeqs(frames)
          assert(seqs.nonEmpty, "the gap and the live message arrive")
          assert(seqs.forall(_ > cutoff), s"nothing at or before the resume point ($cutoff): $seqs")
          assertEquals(seqs, seqs.sorted)
      }
    }
  }

  test("a client ping is answered with a pong") {
    withContainers { pg =>
      withServer(pg) { (http, ws, base) =>
        for
          _ <- seed(http, base / "v1" / "users", UpsertUserRequest("ann", None, None, None, None))
          frames <- ws.connectHighLevel(WSRequest(wsUri(base, "ann"))).use { conn =>
            conn.send(WSFrame.Text("""{"type":"ping"}""")) >>
              conn.receiveStream
                .collect { case WSFrame.Text(b, _) => b }
                .takeThrough(!_.contains("\"type\":\"pong\""))
                .interruptAfter(20.seconds)
                .compile
                .toList
          }
        yield assert(
          frames.exists(f => f.contains("\"type\":\"pong\"") && f.contains("serverTime")),
          s"expected a pong, got: $frames",
        )
      }
    }
  }

  test("multi-device: a user-directed read.updated reaches all of a user's connections") {
    withContainers { pg =>
      withServer(pg) { (http, ws, base) =>
        def collectRead(conn: WSConnectionHighLevel[IO]) =
          conn.receiveStream
            .collect { case WSFrame.Text(b, _) => b }
            .takeThrough(!_.contains("\"type\":\"read.updated\""))
            .interruptAfter(20.seconds)
            .compile
            .toList
            .start

        for
          _ <- seed(http, base / "v1" / "users", UpsertUserRequest("ann", None, None, None, None))
          _ <- seed(http, base / "v1" / "users", UpsertUserRequest("ben", None, None, None, None))
          _ <- seed(
            http,
            base / "v1" / "channels",
            CreateChannelRequest("messaging", "sync", Some("ann"), None),
          )
          _ <- seed(
            http,
            base / "v1" / "channels" / "messaging" / "sync" / "members",
            AddMemberRequest("ben", Some("member")),
          )
          _ <- seed(
            http,
            base / "v1" / "channels" / "messaging" / "sync" / "messages",
            SendMessageRequest(Some("ben"), Some("unread for ann"), None, None, None),
          )
          result <- ws.connectHighLevel(WSRequest(wsUri(base, "ann"))).use { deviceA =>
            ws.connectHighLevel(WSRequest(wsUri(base, "ann"))).use { deviceB =>
              for
                ca <- collectRead(deviceA)
                cb <- collectRead(deviceB)
                _ <- IO.sleep(400.millis)
                _ <- seed(
                  http,
                  base / "v1" / "channels" / "messaging" / "sync" / "read",
                  MarkReadRequest("ann", None),
                )
                a <- ca.joinWithNever
                b <- cb.joinWithNever
              yield (a, b)
            }
          }
        yield
          val (a, b) = result
          assert(a.exists(_.contains("read.updated")), s"device A synced: $a")
          assert(b.exists(_.contains("read.updated")), s"device B synced: $b")
      }
    }
  }
