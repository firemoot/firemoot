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

  def remove(id: String): IO[Option[StreamConnection]] =
    ref.modify(m => (m - id, m.get(id)))

  /** Subscribes a socket to a cid; false unless the socket exists and is `userId`'s. */
  def watch(connectionId: String, userId: String, cid: String): IO[Boolean] =
    ref.modify { m =>
      m.get(connectionId).filter(_.userId == userId) match
        case Some(c) => (m.updated(c.id, c.copy(watched = c.watched + cid)), true)
        case None => (m, false)
    }

  def unwatch(connectionId: String, cid: String): IO[Unit] =
    ref.update(m =>
      m.get(connectionId).fold(m)(c => m.updated(c.id, c.copy(watched = c.watched - cid)))
    )

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
