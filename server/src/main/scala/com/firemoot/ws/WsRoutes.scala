package com.firemoot.ws

import java.time.ZoneOffset

import scala.concurrent.duration.*

import cats.effect.std.Queue
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.firemoot.auth.JwtAuth
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{ReadRepo, UserRepo}
import com.firemoot.domain.UuidV7
import fs2.{Pipe, Stream}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.{HttpRoutes, MediaType, Response, Status}
import skunk.Session

/**
 * The WebSocket gateway (SPEC.md §5). One endpoint, plain HTTP upgrade:
 *   - first frame is `hello` (connection id, server time, the user);
 *   - `subscribe` replays persisted events past `last_seen_seq` then live-streams;
 *   - server pings every 25s and reaps a connection that misses ~2 pongs.
 *
 * The replay-then-live splice has a small race (an event arriving between the
 * replay read and joining the live set could duplicate); the client dedupes by
 * seq, and M1.8 hardens this with buffer-then-dedupe.
 */
final class WsRoutes(
    backplane: Backplane,
    registry: ConnectionRegistry,
    replay: EventReplay,
    pool: Resource[IO, Session[IO]],
    jwtSecret: String,
    devDemo: Boolean,
    userActive: String => IO[Unit],
):

  private object TokenParam extends OptionalQueryParamDecoderMatcher[String]("token")
  private object UserParam extends OptionalQueryParamDecoderMatcher[String]("user")

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "v1" / "ws" :? TokenParam(token) +& UserParam(userParam) =>
      resolveUser(token, userParam) match
        case Some(userId) => open(wsb, userId)
        case None => IO.pure(unauthorized)
  }

  /**
   * A verified JWT `sub` wins; the `?user=` shortcut is honoured only in dev mode
   * (until token minting in the SDK). No valid credential -> rejected.
   */
  private def resolveUser(token: Option[String], userParam: Option[String]): Option[String] =
    token.flatMap(t => JwtAuth.verify(jwtSecret, t).toOption.map(_.sub))
      .orElse(Option.when(devDemo)(userParam).flatten)

  private val unauthorized: Response[IO] =
    Response[IO](Status.Unauthorized)
      .withEntity("""{"type":"about:blank","title":"Unauthorized","status":401}""")
      .withContentType(`Content-Type`(MediaType.unsafeParse("application/problem+json")))

  private def text(json: Json): WebSocketFrame = WebSocketFrame.Text(json.noSpaces)

  private def lookupUser(userId: String): IO[Json] =
    pool.use(_.runOption(UserRepo.byId, userId)).map {
      case Some(user) => user.asJson
      case None => Json.obj("id" -> userId.asJson)
    }

  private def open(wsb: WebSocketBuilder2[IO], userId: String): IO[Response[IO]] =
    for
      connectionId <- UuidV7.next.map(_.toString)
      outbound <- Queue.unbounded[IO, Option[WebSocketFrame]]
      subscribed <- Ref[IO].of(Map.empty[String, Long])
      lastPong <- IO.realTime.flatMap(Ref[IO].of)
      me <- lookupUser(userId)
      totalUnread <- pool.use(_.runUnique(ReadRepo.totalUnread, userId))
      now <- IO.realTimeInstant.map(_.atOffset(ZoneOffset.UTC))
      _ <- userActive(userId)
      _ <- registry.register(connectionId, userId)
      _ <- outbound.offer(Some(text(WsFrames.hello(connectionId, now, me, totalUnread))))
      response <- build(wsb, connectionId, userId, outbound, subscribed, lastPong)
    yield response

  private def build(
      wsb: WebSocketBuilder2[IO],
      connectionId: String,
      userId: String,
      outbound: Queue[IO, Option[WebSocketFrame]],
      subscribed: Ref[IO, Map[String, Long]],
      lastPong: Ref[IO, FiniteDuration],
  ): IO[Response[IO]] =
    val send: Stream[IO, WebSocketFrame] =
      Stream.fromQueueNoneTerminated(outbound).onFinalize(registry.unregister(connectionId))

    // Deliver user-directed events (target = this user) regardless of subscription;
    // otherwise deliver channel-broadcast events for subscribed channels.
    val live: Stream[IO, Nothing] =
      backplane.subscribe.evalMap { event =>
        event.target match
          case Some(target) =>
            if target == userId then outbound.offer(Some(text(WsFrames.event(event)))) else IO.unit
          case None =>
            subscribed.get.flatMap { subs =>
              if subs.contains(event.cid) then outbound.offer(Some(text(WsFrames.event(event))))
              else IO.unit
            }
      }.drain

    val pings: Stream[IO, Nothing] =
      Stream
        .awakeEvery[IO](25.seconds)
        .evalMap(_ => outbound.offer(Some(WebSocketFrame.Ping())))
        .drain

    val watchdog: Stream[IO, Nothing] =
      Stream
        .awakeEvery[IO](25.seconds)
        .evalMap { _ =>
          (IO.realTime, lastPong.get).flatMapN { (now, last) =>
            if now - last > 55.seconds then outbound.offer(None) else IO.unit
          }
        }
        .drain

    val receive: Pipe[IO, WebSocketFrame, Unit] = _.evalMap {
      case WebSocketFrame.Text(body, _) => onClientFrame(body, subscribed, outbound)
      case _: WebSocketFrame.Pong => IO.realTime.flatMap(lastPong.set)
      case _ => IO.unit
    }

    wsb.build(send.concurrently(live).concurrently(pings).concurrently(watchdog), receive)

  private def onClientFrame(
      body: String,
      subscribed: Ref[IO, Map[String, Long]],
      outbound: Queue[IO, Option[WebSocketFrame]],
  ): IO[Unit] =
    WsFrames.parse(body) match
      case WsFrames.ClientFrame.Subscribe(channels) =>
        channels.toList.traverse_ { (cid, lastSeen) =>
          // Join the live set first so events published during the (possibly slow)
          // replay query are delivered, not dropped. Ordering is reconciled by the
          // client's seq dedupe; M1.8 hardens the splice.
          subscribed.update(_ + (cid -> lastSeen)) >>
            replay
              .since(cid, lastSeen)
              .flatMap(_.traverse_(event => outbound.offer(Some(text(WsFrames.event(event))))))
        }
      case WsFrames.ClientFrame.Ping =>
        IO.realTimeInstant.flatMap(i =>
          outbound.offer(Some(text(WsFrames.pong(i.atOffset(ZoneOffset.UTC)))))
        )
      case WsFrames.ClientFrame.Unknown => IO.unit
