package com.firemoot.backplane

import cats.effect.IO
import com.firemoot.domain.Event
import fs2.Stream
import fs2.concurrent.Topic

/**
 * Cross-connection event fan-out (SPEC.md §4). v1 ships only the in-process
 * implementation; the trait is the seam for a future Postgres LISTEN/NOTIFY or
 * Redis backplane. No sticky sessions: any connection subscribes to everything
 * and filters by its own channel set.
 */
trait Backplane:
  def publish(event: Event): IO[Unit]
  def subscribe: Stream[IO, Event]

object Backplane:

  def inProcess: IO[Backplane] =
    Topic[IO, Event].map { topic =>
      new Backplane:
        def publish(event: Event): IO[Unit] = topic.publish1(event).void
        def subscribe: Stream[IO, Event] = topic.subscribe(maxQueued = 1024)
    }
