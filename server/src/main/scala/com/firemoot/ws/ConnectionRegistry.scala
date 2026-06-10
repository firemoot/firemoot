package com.firemoot.ws

import cats.effect.{IO, Ref}

/**
 * In-memory registry of live WebSocket connections (ephemeral by design,
 * SPEC.md §4). It counts connections per user so the gateway can detect the
 * online/offline transitions that drive `presence.changed` (a user is online
 * from their first connection until their last one closes), and feeds the live
 * CCU gauge later.
 */
final class ConnectionRegistry(ref: Ref[IO, ConnectionRegistry.State]):

  import ConnectionRegistry.State

  /** Registers a connection; true if it is the user's first (now online). */
  def register(connectionId: String, userId: String): IO[Boolean] =
    ref.modify { s =>
      val already = s.perUser.getOrElse(userId, 0)
      val next = State(
        s.byConnection + (connectionId -> userId),
        s.perUser + (userId -> (already + 1)),
      )
      (next, already == 0)
    }

  /**
   * Removes a connection, returning its owning user and whether that was the
   * user's last connection (now offline). None if the connection was unknown.
   */
  def unregister(connectionId: String): IO[Option[(String, Boolean)]] =
    ref.modify { s =>
      s.byConnection.get(connectionId) match
        case None => (s, None)
        case Some(userId) =>
          val remaining = s.perUser.getOrElse(userId, 1) - 1
          val perUser =
            if remaining <= 0 then s.perUser - userId
            else s.perUser + (userId -> remaining)
          (State(s.byConnection - connectionId, perUser), Some((userId, remaining <= 0)))
    }

  def count: IO[Int] = ref.get.map(_.byConnection.size)

  def onlineUsers: IO[Set[String]] = ref.get.map(_.perUser.keySet)

object ConnectionRegistry:

  final case class State(byConnection: Map[String, String], perUser: Map[String, Int])

  def create: IO[ConnectionRegistry] =
    Ref[IO].of(State(Map.empty, Map.empty)).map(new ConnectionRegistry(_))
