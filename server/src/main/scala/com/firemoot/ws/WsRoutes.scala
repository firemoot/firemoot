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
import com.firemoot.domain.{Event, UuidV7}
import com.firemoot.service.PresenceService
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
 * The replay-then-live splice is made exact by [[ResumeBuffer]] (M1.8): live
 * persisted events that arrive while the replay query is in flight are buffered,
 * then flushed in seq order and deduped against what was replayed, so resume
 * loses nothing and duplicates nothing. A resume point older than the retained
 * events yields a `resync_required` frame instead.
 */
final class WsRoutes(
    backplane: Backplane,
    registry: ConnectionRegistry,
    replay: EventReplay,
    pool: Resource[IO, Session[IO]],
    presence: PresenceService,
    jwtSecret: String,
    devDemo: Boolean,
    userActive: String => IO[Unit],
    typingThrottle: FiniteDuration,
    typingExpiry: FiniteDuration,
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
      resume <- ResumeBuffer.create
      lastPong <- IO.realTime.flatMap(Ref[IO].of)
      typing <- TypingTracker.create(userId, typingThrottle, typingExpiry)(backplane.publish)
      me <- lookupUser(userId)
      totalUnread <- pool.use(_.runUnique(ReadRepo.totalUnread, userId))
      now <- IO.realTimeInstant.map(_.atOffset(ZoneOffset.UTC))
      _ <- userActive(userId)
      first <- registry.register(connectionId, userId)
      // Presence fan-out is best-effort and detached: a hiccup must neither delay
      // nor fail the handshake. PresenceService is independently tested.
      _ <- presence.online(userId).attempt.void.start.whenA(first)
      _ <- outbound.offer(Some(text(WsFrames.hello(connectionId, now, me, totalUnread))))
      response <- build(wsb, connectionId, userId, outbound, resume, lastPong, typing)
    yield response

  private def build(
      wsb: WebSocketBuilder2[IO],
      connectionId: String,
      userId: String,
      outbound: Queue[IO, Option[WebSocketFrame]],
      resume: ResumeBuffer,
      lastPong: Ref[IO, FiniteDuration],
      typing: TypingTracker,
  ): IO[Response[IO]] =
    val cleanup: IO[Unit] =
      typing.shutdown.attempt.void >>
        registry.unregister(connectionId).flatMap {
          case Some((uid, true)) => presence.offline(uid).attempt.void
          case _ => IO.unit
        }

    val send: Stream[IO, WebSocketFrame] =
      Stream.fromQueueNoneTerminated(outbound).onFinalize(cleanup)

    val emit: Event => IO[Unit] = event => outbound.offer(Some(text(WsFrames.event(event))))

    // Routing per event:
    //   - user-directed (target set): delivered to this user regardless of subscription;
    //   - ephemeral channel event (seq 0, e.g. typing): delivered if subscribed, no dedupe;
    //   - persisted channel event (seq > 0): routed through the resume buffer, which
    //     buffers it while replaying and dedupes it once live.
    val live: Stream[IO, Nothing] =
      backplane.subscribe.evalMap { event =>
        event.target match
          case Some(target) => emit(event).whenA(target == userId)
          case None if event.seq == 0L =>
            resume.isSubscribed(event.cid).flatMap(emit(event).whenA)
          case None =>
            resume.onLive(event).flatMap(emit(event).whenA)
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
      case WebSocketFrame.Text(body, _) => onClientFrame(body, resume, outbound, typing)
      case _: WebSocketFrame.Pong => IO.realTime.flatMap(lastPong.set)
      case _ => IO.unit
    }

    wsb.build(send.concurrently(live).concurrently(pings).concurrently(watchdog), receive)

  private def onClientFrame(
      body: String,
      resume: ResumeBuffer,
      outbound: Queue[IO, Option[WebSocketFrame]],
      typing: TypingTracker,
  ): IO[Unit] =
    WsFrames.parse(body) match
      case WsFrames.ClientFrame.Subscribe(channels) =>
        channels.toList.traverse_((cid, lastSeen) =>
          subscribeChannel(cid, lastSeen, resume, outbound)
        )
      case WsFrames.ClientFrame.TypingStart(cid) =>
        // Only members of a channel the connection is watching may signal typing.
        resume.isSubscribed(cid).flatMap(typing.start(cid).whenA)
      case WsFrames.ClientFrame.TypingStop(cid) =>
        typing.stop(cid)
      case WsFrames.ClientFrame.Ping =>
        IO.realTimeInstant.flatMap(i =>
          outbound.offer(Some(text(WsFrames.pong(i.atOffset(ZoneOffset.UTC)))))
        )
      case WsFrames.ClientFrame.Unknown => IO.unit

  /**
   * Resumes one channel: enter the replaying phase first (so live events are
   * buffered, not lost), replay the gap past `lastSeen`, then flush the buffer
   * and go live. If the resume point predates retained events the gap is
   * unrecoverable, so we send `resync_required` and go live from here.
   */
  private def subscribeChannel(
      cid: String,
      lastSeen: Long,
      resume: ResumeBuffer,
      outbound: Queue[IO, Option[WebSocketFrame]],
  ): IO[Unit] =
    val emit: Event => IO[Unit] = event => outbound.offer(Some(text(WsFrames.event(event))))
    resume.beginReplay(cid) >>
      replay.earliestSeq(cid).flatMap { earliest =>
        val unrecoverable = lastSeen > 0 && earliest.exists(_ > lastSeen + 1)
        if unrecoverable then
          outbound.offer(Some(text(WsFrames.resyncRequired(cid)))) >>
            resume.flush(cid, lastSeen)(emit)
        else
          replay.since(cid, lastSeen).flatMap { events =>
            val watermark = events.lastOption.map(_.seq).getOrElse(lastSeen)
            events.traverse_(emit) >> resume.flush(cid, watermark)(emit)
          }
      }
