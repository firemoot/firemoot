package com.firemoot.service

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Ref}

/**
 * Debounces `last_active_at` writes (SPEC.md §3 / M1.2): a user's activity only
 * hits the database at most once per `interval`, so reconnect storms don't hammer
 * Postgres. State is in-memory and ephemeral by design.
 */
final class LastActiveTracker(
    interval: FiniteDuration,
    write: String => IO[Unit],
    lastWrite: Ref[IO, Map[String, FiniteDuration]],
):

  def touch(userId: String): IO[Unit] =
    IO.realTime.flatMap { now =>
      lastWrite
        .modify { seen =>
          if seen.get(userId).forall(prev => now - prev >= interval) then
            (seen + (userId -> now), true)
          else (seen, false)
        }
        .flatMap(shouldWrite => if shouldWrite then write(userId) else IO.unit)
    }

object LastActiveTracker:

  def create(interval: FiniteDuration)(write: String => IO[Unit]): IO[LastActiveTracker] =
    Ref[IO].of(Map.empty[String, FiniteDuration]).map(new LastActiveTracker(interval, write, _))
