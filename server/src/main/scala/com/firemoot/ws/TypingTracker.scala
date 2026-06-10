package com.firemoot.ws

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.firemoot.domain.Event
import io.circe.Json
import io.circe.syntax.*

/**
 * Per-connection typing state (SPEC.md §5, M1.7). Typing events are ephemeral:
 * no seq, never persisted, never replayed - they are published straight to the
 * backplane and delivered to the channel's current subscribers.
 *
 *   - throttle: a `typing.start` is broadcast at most once per `throttle` while
 *     the user keeps typing, so keystrokes don't spam the channel;
 *   - auto-expiry: if no further `typing.start` arrives within `expiry`, the
 *     server emits `typing.stop` itself, so a silent client (or a dropped stop)
 *     never leaves a stuck indicator.
 *
 * Each `start` arms a fresh expiry timer tagged with a generation; a timer fires
 * only while it is still the current one for that channel, so timers superseded
 * by a newer `start` (or pre-empted by `stop`) harmlessly no-op - no fiber
 * bookkeeping or cancellation needed.
 */
final class TypingTracker(
    userId: String,
    publish: Event => IO[Unit],
    throttle: FiniteDuration,
    expiry: FiniteDuration,
    state: Ref[IO, Map[String, TypingTracker.Entry]],
    generations: Ref[IO, Long],
):

  import TypingTracker.Entry

  def start(cid: String): IO[Unit] =
    for
      now <- IO.realTime
      gen <- generations.updateAndGet(_ + 1)
      emit <- state.modify { active =>
        active.get(cid) match
          case Some(entry) =>
            val emit = now - entry.lastEmit >= throttle
            (active + (cid -> Entry(gen, if emit then now else entry.lastEmit)), emit)
          case None =>
            (active + (cid -> Entry(gen, now)), true)
      }
      _ <- (IO.sleep(expiry) >> expire(cid, gen)).start
      _ <- publish(frame(cid, "typing.start")).whenA(emit)
    yield ()

  def stop(cid: String): IO[Unit] =
    state
      .modify(active => if active.contains(cid) then (active - cid, true) else (active, false))
      .flatMap(stopped => publish(frame(cid, "typing.stop")).whenA(stopped))

  /** On disconnect: clear all active typing and tell the channels it stopped. */
  def shutdown: IO[Unit] =
    state
      .getAndSet(Map.empty)
      .flatMap(_.keys.toList.traverse_(cid => publish(frame(cid, "typing.stop"))))

  private def expire(cid: String, gen: Long): IO[Unit] =
    state
      .modify { active =>
        active.get(cid) match
          case Some(entry) if entry.gen == gen => (active - cid, true)
          case _ => (active, false)
      }
      .flatMap(expired => publish(frame(cid, "typing.stop")).whenA(expired))

  private def frame(cid: String, eventType: String): Event =
    Event(eventType, cid, seq = 0L, data = Json.obj("userId" -> userId.asJson))

object TypingTracker:

  final case class Entry(gen: Long, lastEmit: FiniteDuration)

  def create(
      userId: String,
      throttle: FiniteDuration,
      expiry: FiniteDuration,
  )(publish: Event => IO[Unit]): IO[TypingTracker] =
    (Ref[IO].of(Map.empty[String, Entry]), Ref[IO].of(0L)).mapN { (state, generations) =>
      new TypingTracker(userId, publish, throttle, expiry, state, generations)
    }
