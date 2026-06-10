package com.firemoot.ws

import cats.effect.{IO, Ref}

/**
 * In-memory registry of live WebSocket connections (ephemeral by design,
 * SPEC.md §4). Feeds presence and the live CCU gauge later.
 */
final class ConnectionRegistry(ref: Ref[IO, Map[String, String]]):

  def register(connectionId: String, userId: String): IO[Unit] =
    ref.update(_ + (connectionId -> userId))

  def unregister(connectionId: String): IO[Unit] =
    ref.update(_ - connectionId)

  def count: IO[Int] = ref.get.map(_.size)

object ConnectionRegistry:
  def create: IO[ConnectionRegistry] =
    Ref[IO].of(Map.empty[String, String]).map(new ConnectionRegistry(_))
