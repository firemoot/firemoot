package com.firemoot.ws

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.firemoot.domain.Event

/**
 * Per-connection resume state, one phase per subscribed channel (SPEC.md §5,
 * M1.8). It solves the splice race: an event published while the resume query
 * is in flight must be delivered exactly once, in seq order, neither lost nor
 * duplicated.
 *
 * A channel is `Replaying` from the moment it is subscribed until its gap has
 * been read and flushed; live persisted events that arrive in that window are
 * buffered rather than delivered, so they can't race ahead of the replay and
 * cause either a gap (an earlier replay event suppressed) or a duplicate. Once
 * the buffer drains it flips to `Live`, tracking a high-water seq so any event
 * already delivered via replay is dropped.
 *
 * Ephemeral events (seq 0: typing) never enter here - they carry no resumable
 * position and are delivered straight through.
 */
final class ResumeBuffer(state: Ref[IO, Map[String, ResumeBuffer.Phase]]):

  import ResumeBuffer.{Live, Replaying}

  /** Enter the replaying phase for a channel, capturing concurrent live events. */
  def beginReplay(cid: String): IO[Unit] =
    state.update(_ + (cid -> Replaying(Vector.empty)))

  /**
   * Drains everything buffered during replay - in seq order, deduped, and only
   * past `watermark` (the highest seq already replayed) - then flips to live.
   * Re-checks the buffer between drains so events that arrive mid-flush are
   * caught before the live phase begins; the handoff is atomic, so no live event
   * is delivered before the buffer it belongs after.
   */
  def flush(cid: String, watermark: Long)(emit: Event => IO[Unit]): IO[Unit] =
    def loop(hwm: Long): IO[Unit] =
      state
        .modify { phases =>
          phases.get(cid) match
            case Some(Replaying(buffer)) =>
              val pending = buffer.filter(_.seq > hwm).distinctBy(_.seq).sortBy(_.seq)
              if pending.isEmpty then (phases + (cid -> Live(hwm)), None)
              else (phases + (cid -> Replaying(Vector.empty)), Some(pending))
            case _ => (phases, None)
        }
        .flatMap {
          case None => IO.unit
          case Some(pending) => pending.traverse_(emit) >> loop(pending.last.seq)
        }
    loop(watermark)

  /**
   * Offers a live persisted (seq > 0) channel event. True if it should be
   * emitted now; false if it was buffered (still replaying) or dropped as a
   * duplicate of one already replayed.
   */
  def onLive(event: Event): IO[Boolean] =
    state.modify { phases =>
      phases.get(event.cid) match
        case Some(Replaying(buffer)) => (phases + (event.cid -> Replaying(buffer :+ event)), false)
        case Some(Live(hwm)) if event.seq > hwm => (phases + (event.cid -> Live(event.seq)), true)
        case Some(Live(_)) => (phases, false)
        case None => (phases, false)
    }

  def isSubscribed(cid: String): IO[Boolean] = state.get.map(_.contains(cid))

object ResumeBuffer:

  sealed trait Phase
  final case class Replaying(buffer: Vector[Event]) extends Phase
  final case class Live(hwm: Long) extends Phase

  def create: IO[ResumeBuffer] =
    Ref[IO].of(Map.empty[String, Phase]).map(new ResumeBuffer(_))
