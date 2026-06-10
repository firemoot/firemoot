package com.firemoot.ws

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.firemoot.domain.Event
import io.circe.Json
import munit.CatsEffectSuite

class ResumeBufferSuite extends CatsEffectSuite:

  private val cid = "messaging:general"
  private def ev(seq: Long): Event = Event("message.new", cid, seq, Json.obj())

  /** A resume buffer plus a collector that records the seqs emitted, in order. */
  private def harness: IO[(ResumeBuffer, Ref[IO, Vector[Long]], Event => IO[Unit])] =
    (ResumeBuffer.create, Ref[IO].of(Vector.empty[Long])).mapN { (resume, seen) =>
      (resume, seen, (e: Event) => seen.update(_ :+ e.seq))
    }

  /** Live delivery as the WS gateway does it: emit only when onLive says so. */
  private def deliver(resume: ResumeBuffer, emit: Event => IO[Unit])(e: Event): IO[Unit] =
    resume.onLive(e).flatMap(emit(e).whenA)

  test("an empty flush goes straight to live, then delivers and dedupes") {
    harness.flatMap { (resume, seen, emit) =>
      for
        _ <- resume.beginReplay(cid)
        _ <- resume.flush(cid, 8L)(emit)
        d9 <- resume.onLive(ev(9))
        d9dup <- resume.onLive(ev(9))
        d10 <- resume.onLive(ev(10))
        seqs <- seen.get
      yield
        assert(d9, "seq 9 is new")
        assert(!d9dup, "a re-delivered seq 9 is dropped")
        assert(d10, "seq 10 is new")
        assertEquals(seqs, Vector.empty, "nothing was buffered to flush")
    }
  }

  test("events arriving during replay are buffered, then flushed in order") {
    harness.flatMap { (resume, seen, emit) =>
      for
        _ <- resume.beginReplay(cid)
        b9 <- resume.onLive(ev(9))
        b10 <- resume.onLive(ev(10))
        duringReplay <- seen.get
        _ <- resume.flush(cid, 8L)(emit)
        afterFlush <- seen.get
        _ <- deliver(resume, emit)(ev(11))
        finalSeqs <- seen.get
      yield
        assert(!b9 && !b10, "live events are buffered, not delivered, while replaying")
        assertEquals(duringReplay, Vector.empty)
        assertEquals(afterFlush, Vector(9L, 10L), "the buffer flushes in seq order")
        assertEquals(finalSeqs, Vector(9L, 10L, 11L), "subsequent live events deliver directly")
    }
  }

  test("the flush dedupes anything already covered by replay") {
    harness.flatMap { (resume, seen, emit) =>
      for
        _ <- resume.beginReplay(cid)
        // 7 overlaps the replayed range (<= watermark 8); 9 is genuinely new.
        _ <- resume.onLive(ev(7))
        _ <- resume.onLive(ev(9))
        _ <- resume.flush(cid, 8L)(emit)
        afterFlush <- seen.get
        dup9 <- resume.onLive(ev(9))
        new10 <- resume.onLive(ev(10))
      yield
        assertEquals(afterFlush, Vector(9L), "seq 7 was already replayed and is dropped")
        assert(!dup9, "seq 9 was flushed and is not re-delivered")
        assert(new10, "seq 10 is new")
    }
  }

  test("out-of-order arrivals during replay flush in ascending seq order") {
    harness.flatMap { (resume, seen, emit) =>
      for
        _ <- resume.beginReplay(cid)
        _ <- resume.onLive(ev(11))
        _ <- resume.onLive(ev(9))
        _ <- resume.onLive(ev(10))
        _ <- resume.flush(cid, 8L)(emit)
        afterFlush <- seen.get
      yield assertEquals(afterFlush, Vector(9L, 10L, 11L))
    }
  }

  test("concurrent flush and live delivery stay gapless, ordered and unique") {
    harness.flatMap { (resume, seen, emit) =>
      val n = 60L
      for
        _ <- resume.beginReplay(cid)
        // Live producer interleaves with the flush; small pauses encourage the race.
        producer = (1L to n).toList.traverse_(s =>
          deliver(resume, emit)(ev(s)) >> IO.sleep(1.millis)
        )
        _ <- (resume.flush(cid, 0L)(emit), producer).parTupled
        // Drain anything the flush left for the live phase to settle.
        _ <- IO.sleep(50.millis)
        seqs <- seen.get
      yield
        assertEquals(seqs, (1L to n).toVector, "every seq delivered once, in order, no gaps")
        assertEquals(seqs.distinct.size, seqs.size, "no duplicates")
    }
  }
