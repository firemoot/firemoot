package com.firemoot.streamapi

import java.time.{OffsetDateTime, ZoneOffset}

import scala.concurrent.duration.*

import cats.effect.std.Queue
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.firemoot.db.ReadRepo
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{StreamStateRepo, UserRepo}
import com.firemoot.domain.{User, UuidV7}
import com.firemoot.service.{PresenceService, UserService}
import fs2.{Pipe, Stream}
import io.circe.syntax.*
import io.circe.{parser, Json}
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import skunk.Session

/**
 * getstream.io's `/api/v2/connect` socket, as `stream-chat` v10 drives it. The
 * upgrade is always accepted; the client's first frame is the auth message
 * `{token, user_details, products}`, and the server's first frame decides the
 * handshake: `connection.ok` (with a fresh `connection_id` and the full `me`) or
 * `connection.error`, after which the socket closes with 1000, the code the SDK
 * reads as a permanent rejection. Refusing the upgrade instead would surface to
 * the SDK only as a 15s connect timeout.
 *
 * Health: the client sends `[{"type":"health.check"}]` every 25s and expects a
 * `health.check` event back each time; 35s of silence makes it reconnect.
 * Connecting upserts the user from `user_details` (name, image, custom), as
 * `connectUser` does on Stream.
 */
final class StreamWsRoutes(
    pool: Resource[IO, Session[IO]],
    auth: StreamApiAuth,
    connections: StreamConnections,
    users: UserService,
    presence: PresenceService,
    onUserActive: String => IO[Unit],
):

  private val authDeadline = 10.seconds

  /**
   * The client pings every 25s (at most), so a minute without any frame means it
   * vanished without a close. Closing with 1001 rather than 1000 tells a client
   * that is in fact alive to reconnect, as it does after a network blip.
   */
  private val silenceLimit = 60.seconds

  def routes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "v2" / "connect" =>
      for
        outbound <- Queue.unbounded[IO, Option[WebSocketFrame]]
        connectionId <- UuidV7.next.map(_.toString)
        authed <- Deferred[IO, Option[String]]
        lastFrame <- IO.monotonic.flatMap(cats.effect.Ref[IO].of)
        apiKey = auth.apiKeyOf(req)
        response <- build(wsb, apiKey, connectionId, outbound, authed, lastFrame)
      yield response
  }

  private def now: IO[OffsetDateTime] = IO.realTimeInstant.map(_.atOffset(ZoneOffset.UTC))

  private def text(json: Json): WebSocketFrame = WebSocketFrame.Text(json.noSpaces)

  private def reject(
      outbound: Queue[IO, Option[WebSocketFrame]],
      connectionId: String,
      error: StreamApiError,
  )
      : IO[Unit] =
    now.flatMap { at =>
      outbound.offer(Some(text(Json.obj(
        "type" -> "connection.error".asJson,
        "connection_id" -> connectionId.asJson,
        "created_at" -> V2Json.ns(at),
        "error" -> error.json,
      )))) >>
        outbound.offer(Some(WebSocketFrame.Close(
          1000,
          "invalid auth",
        ).fold(_ => WebSocketFrame.Close(), identity))) >>
        outbound.offer(None)
    }

  private def build(
      wsb: WebSocketBuilder2[IO],
      apiKey: Option[String],
      connectionId: String,
      outbound: Queue[IO, Option[WebSocketFrame]],
      authed: Deferred[IO, Option[String]],
      lastFrame: cats.effect.Ref[IO, FiniteDuration],
  ): IO[org.http4s.Response[IO]] =
    val cleanup: IO[Unit] =
      authed.tryGet.flatMap {
        case Some(Some(userId)) =>
          connections.remove(connectionId) >>
            connections.ofUser(userId).flatMap(rest =>
              presence.offline(userId).attempt.void.whenA(rest.isEmpty)
            )
        case _ => authed.complete(None).void
      }

    val send: Stream[IO, WebSocketFrame] =
      Stream.fromQueueNoneTerminated(outbound).onFinalize(cleanup)

    val deadline: Stream[IO, Nothing] =
      Stream.sleep[IO](authDeadline).evalMap { _ =>
        authed.tryGet.flatMap {
          case None => reject(
              outbound,
              connectionId,
              StreamApiError.authFailed("auth message was not received in time"),
            )
          case Some(_) => IO.unit
        }
      }.drain

    val watchdog: Stream[IO, Nothing] =
      Stream.awakeEvery[IO](15.seconds).evalMap { _ =>
        (IO.monotonic, lastFrame.get).flatMapN { (now, last) =>
          IO.whenA(now - last > silenceLimit) {
            WebSocketFrame.Close(1001, "no health check received").fold(
              _ => IO.unit,
              close =>
                outbound.offer(Some(close)) >> outbound.offer(None),
            )
          }
        }
      }.drain

    val receive: Pipe[IO, WebSocketFrame, Unit] =
      _.evalTap(_ => IO.monotonic.flatMap(lastFrame.set)).evalMap {
        case WebSocketFrame.Text(body, _) =>
          authed.tryGet.flatMap {
            case None => authenticate(body, apiKey, connectionId, outbound, authed)
            case Some(Some(_)) => onFrame(body, connectionId, outbound)
            case Some(None) => IO.unit
          }
        case _ => IO.unit
      }

    wsb.build(send.concurrently(deadline).concurrently(watchdog), receive)

  private def authenticate(
      body: String,
      apiKey: Option[String],
      connectionId: String,
      outbound: Queue[IO, Option[WebSocketFrame]],
      authed: Deferred[IO, Option[String]],
  ): IO[Unit] =
    val frame = parser.parse(body).toOption.map(_.hcursor)
    val token =
      frame.flatMap(_.get[String]("token").toOption).filter(t => t.nonEmpty && t != "anonymous")
    val details = frame.flatMap(_.downField("user_details").focus).filter(_.isObject)
    (apiKey, token) match
      case (None, _) => authed.complete(None) >>
          reject(outbound, connectionId, StreamApiError.accessKey)
      case (_, None) =>
        authed.complete(None) >>
          reject(
            outbound,
            connectionId,
            StreamApiError.authFailed("anonymous users are not enabled for this app"),
          )
      case (Some(key), Some(t)) =>
        auth.verify(key, t).flatMap {
          case Left(error) => authed.complete(None) >> reject(outbound, connectionId, error)
          case Right(StreamPrincipal.Server) =>
            authed.complete(None) >>
              reject(
                outbound,
                connectionId,
                StreamApiError.authFailed("server-side tokens cannot open a connection"),
              )
          case Right(StreamPrincipal.User(userId)) =>
            val claimed = details.flatMap(_.hcursor.get[String]("id").toOption)
            if claimed.exists(_ != userId) then
              authed.complete(None) >>
                reject(
                  outbound,
                  connectionId,
                  StreamApiError.authFailed("user_details.id does not match the token's user_id"),
                )
            else open(userId, details, connectionId, outbound, authed)
        }

  private def open(
      userId: String,
      details: Option[Json],
      connectionId: String,
      outbound: Queue[IO, Option[WebSocketFrame]],
      authed: Deferred[IO, Option[String]],
  ): IO[Unit] =
    for
      user <- upsertFromDetails(userId, details)
      firstSocket <- connections.ofUser(userId).map(_.isEmpty)
      _ <- connections.add(StreamConnection(connectionId, userId, outbound, Set.empty))
      _ <- authed.complete(Some(userId))
      unread <- pool.use(s =>
        (
          s.runUnique(ReadRepo.totalUnread, (userId, ReadRepo.TotalUnreadCap)),
          s.runUnique(StreamStateRepo.unreadChannels, userId),
        ).tupled
      )
      at <- now
      _ <- outbound.offer(Some(text(Json.obj(
        "type" -> "connection.ok".asJson,
        "connection_id" -> connectionId.asJson,
        "created_at" -> V2Json.ns(at),
        "me" -> V2Json.ownUser(user, unread._1, unread._2),
      ))))
      _ <- onUserActive(userId).attempt.void
      _ <- presence.online(userId).attempt.void.start.whenA(firstSocket)
    yield ()

  /** Creates the user on first connect; afterwards applies the fields `user_details` carries. */
  private def upsertFromDetails(userId: String, details: Option[Json]): IO[User] =
    val c = details.map(_.hcursor)
    def str(k: String) = c.flatMap(_.get[String](k).toOption)
    val custom = c.flatMap(_.downField("custom").focus).filter(_.isObject)
    pool.use(_.runOption(UserRepo.byId, userId)).flatMap {
      case None =>
        users.upsert(userId, str("name"), str("image"), "user", custom.getOrElse(Json.obj()))
      case Some(u) if details.isEmpty => IO.pure(u)
      case Some(u) =>
        users.upsert(
          userId,
          str("name").orElse(u.name),
          str("image").orElse(u.image),
          u.role,
          custom.getOrElse(u.custom),
        )
    }

  /** Answers every client ping (a JSON array of one `health.check`) with a `health.check`. */
  private def onFrame(
      body: String,
      connectionId: String,
      outbound: Queue[IO, Option[WebSocketFrame]],
  ): IO[Unit] =
    val events = parser.parse(body).toOption.toList.flatMap(j => j.asArray.fold(List(j))(_.toList))
    val pinged = events.exists(_.hcursor.get[String]("type").toOption.contains("health.check"))
    IO.whenA(pinged) {
      now.flatMap(at =>
        outbound.offer(Some(text(Json.obj(
          "type" -> "health.check".asJson,
          "connection_id" -> connectionId.asJson,
          "created_at" -> V2Json.ns(at),
          "custom" -> Json.obj(),
        ))))
      )
    }
