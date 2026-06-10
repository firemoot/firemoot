package com.firemoot.ws

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import com.firemoot.domain.Event
import munit.CatsEffectSuite

class TypingTrackerSuite extends CatsEffectSuite:

  private val cid = "messaging:general"
  private val throttle = 200.millis
  private val expiry = 1.second

  private def tracker(seen: Ref[IO, Vector[Event]]): IO[TypingTracker] =
    TypingTracker.create("alice", throttle, expiry)(e => seen.update(_ :+ e))

  private def types(seen: Ref[IO, Vector[Event]]): IO[List[String]] =
    seen.get.map(_.toList.map(_.`type`))

  test("typing.start is throttled while typing continues") {
    Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
      tracker(seen).flatMap { typing =>
        for
          _ <- typing.start(cid)
          _ <- typing.start(cid)
          afterBurst <- types(seen)
          _ <- IO.sleep(throttle + 100.millis)
          _ <- typing.start(cid)
          afterGap <- types(seen)
        yield
          assertEquals(
            afterBurst.count(_ == "typing.start"),
            1,
            "a rapid second start is suppressed by the throttle",
          )
          assertEquals(
            afterGap.count(_ == "typing.start"),
            2,
            "a start after the throttle window re-broadcasts",
          )
      }
    }
  }

  test("no further start within the expiry window auto-emits typing.stop") {
    Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
      tracker(seen).flatMap { typing =>
        for
          _ <- typing.start(cid)
          _ <- IO.sleep(expiry + 400.millis)
          result <- types(seen)
        yield
          assertEquals(result.count(_ == "typing.start"), 1)
          assertEquals(result.count(_ == "typing.stop"), 1, "the server expires the indicator")
      }
    }
  }

  test("explicit stop emits once and cancels the pending auto-expiry") {
    Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
      tracker(seen).flatMap { typing =>
        for
          _ <- typing.start(cid)
          _ <- IO.sleep(50.millis)
          _ <- typing.stop(cid)
          _ <- typing.stop(cid)
          afterStop <- types(seen)
          _ <- IO.sleep(expiry + 400.millis)
          afterExpiryWindow <- types(seen)
        yield
          assertEquals(afterStop.count(_ == "typing.stop"), 1, "a repeated stop is a no-op")
          assertEquals(
            afterExpiryWindow.count(_ == "typing.stop"),
            1,
            "the superseded expiry timer does not fire a second stop",
          )
      }
    }
  }

  test("a refresh resets the expiry timer") {
    Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
      tracker(seen).flatMap { typing =>
        for
          _ <- typing.start(cid)
          _ <- IO.sleep(700.millis)
          _ <- typing.start(cid)
          // 1.2s after the first start the original timer would have fired, but the
          // refresh re-armed it; ~500ms before the refreshed timer is due.
          _ <- IO.sleep(500.millis)
          beforeRefreshExpiry <- types(seen)
          _ <- IO.sleep(700.millis)
          afterRefreshExpiry <- types(seen)
        yield
          assertEquals(
            beforeRefreshExpiry.count(_ == "typing.stop"),
            0,
            "refreshing typing keeps the indicator alive past the original expiry",
          )
          assertEquals(
            afterRefreshExpiry.count(_ == "typing.stop"),
            1,
            "the indicator expires once typing actually stops",
          )
      }
    }
  }

  test("shutdown stops every active channel") {
    Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
      tracker(seen).flatMap { typing =>
        for
          _ <- typing.start("messaging:a")
          _ <- typing.start("messaging:b")
          _ <- typing.shutdown
          result <- seen.get
        yield
          val stops = result.filter(_.`type` == "typing.stop").map(_.cid).toSet
          assertEquals(stops, Set("messaging:a", "messaging:b"))
      }
    }
  }
