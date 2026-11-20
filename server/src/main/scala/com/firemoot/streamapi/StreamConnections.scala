package com.firemoot.streamapi

import cats.effect.std.Queue
import cats.effect.{IO, Ref}
import org.http4s.websocket.WebSocketFrame

/** One live `/api/v2/connect` socket: its user, outbound queue and watched cids. */
final case class StreamConnection(
    id: String,
    userId: String,
    outbound: Queue[IO, Option[WebSocketFrame]],
    watched: Set[String],
)

/**
 * The live v2 sockets. getstream.io keys channel watches by connection id: a REST
 * query carrying `?connection_id=` with `watch: true` subscribes that socket to
 * the channel, `stop-watching` unsubscribes it, and a closed socket drops them
 * all (the SDK re-watches after reconnecting).
 */
final class StreamConnections private (ref: Ref[IO, Map[String, StreamConnection]]):

  def add(c: StreamConnection): IO[Unit] = ref.update(_ + (c.id -> c))

  /**
   * Subscribes a socket to a cid. None unless the socket exists and is
   * `userId`'s; otherwise whether this is the user's first socket watching it
   * (when Stream announces `user.watching.start`).
   */
  def watch(connectionId: String, userId: String, cid: String): IO[Option[Boolean]] =
    ref.modify { m =>
      m.get(connectionId).filter(_.userId == userId) match
        case Some(c) =>
          val already = m.values.exists(o => o.userId == userId && o.watched(cid))
          (m.updated(c.id, c.copy(watched = c.watched + cid)), Some(!already))
        case None => (m, None)
    }

  /** Unsubscribes; the user's id when that was their last socket watching the cid. */
  def unwatch(connectionId: String, cid: String): IO[Option[String]] =
    ref.modify { m =>
      m.get(connectionId).filter(_.watched(cid)) match
        case Some(c) =>
          val updated = m.updated(c.id, c.copy(watched = c.watched - cid))
          val stillWatching = updated.values.exists(o => o.userId == c.userId && o.watched(cid))
          (updated, Option.when(!stillWatching)(c.userId))
        case None => (m, None)
    }

  /** Closes a socket; the cids its user no longer watches from any other socket. */
  def close(id: String): IO[List[String]] =
    ref.modify { m =>
      m.get(id) match
        case Some(c) =>
          val rest = m - id
          val stopped = c.watched.toList.filterNot(cid =>
            rest.values.exists(o => o.userId == c.userId && o.watched(cid))
          )
          (rest, stopped)
        case None => (m, Nil)
    }

  def all: IO[List[StreamConnection]] = ref.get.map(_.values.toList)

  def watching(cid: String): IO[List[StreamConnection]] =
    all.map(_.filter(_.watched(cid)))

  /** Distinct users watching a cid, as Stream counts `watcher_count`. */
  def watcherCount(cid: String): IO[Int] =
    watching(cid).map(_.map(_.userId).distinct.size)

  def ofUser(userId: String): IO[List[StreamConnection]] =
    all.map(_.filter(_.userId == userId))

  def onlineUsers: IO[Set[String]] = all.map(_.map(_.userId).toSet)

object StreamConnections:
  def create: IO[StreamConnections] =
    Ref[IO].of(Map.empty[String, StreamConnection]).map(new StreamConnections(_))

  /** For wiring defaults in tests that never open a v2 socket. */
  def unsafe(): StreamConnections =
    new StreamConnections(Ref.unsafe[IO, Map[String, StreamConnection]](Map.empty))
