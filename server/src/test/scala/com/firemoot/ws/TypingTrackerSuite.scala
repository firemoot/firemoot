package com.firemoot.ws

import scala.concurrent.duration.*

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}
import com.firemoot.domain.Event
import munit.CatsEffectSuite

/**
 * Runs entirely on [[TestControl]] virtual time: `IO.sleep` advances a
 * deterministic clock instead of the wall clock, so the throttle/expiry timing
 * assertions can never flake on a contended CI runner (and the suite is instant).
 */
class TypingTrackerSuite extends CatsEffectSuite:

  private val cid = "messaging:general"
  private val throttle = 200.millis
  private val expiry = 1.second

  private def tracker(seen: Ref[IO, Vector[Event]]): IO[TypingTracker] =
    TypingTracker.create("alice", throttle, expiry)(e => seen.update(_ :+ e))

  private def types(seen: Ref[IO, Vector[Event]]): IO[List[String]] =
    seen.get.map(_.toList.map(_.`type`))

  test("typing.start is throttled while typing continues") {
    TestControl.executeEmbed {
      Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
        tracker(seen).flatMap { typing =>
          for
            _ <- typing.start(cid)
            _ <- typing.start(cid)
            afterBurst <- types(seen)
            _ <- IO.sleep(throttle + 1.milli)
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
  }

  test("no further start within the expiry window auto-emits typing.stop") {
    TestControl.executeEmbed {
      Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
        tracker(seen).flatMap { typing =>
          for
            _ <- typing.start(cid)
            _ <- IO.sleep(expiry + 1.milli)
            result <- types(seen)
          yield
            assertEquals(result.count(_ == "typing.start"), 1)
            assertEquals(result.count(_ == "typing.stop"), 1, "the server expires the indicator")
        }
      }
    }
  }

  test("explicit stop emits once and cancels the pending auto-expiry") {
    TestControl.executeEmbed {
      Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
        tracker(seen).flatMap { typing =>
          for
            _ <- typing.start(cid)
            _ <- IO.sleep(50.millis)
            _ <- typing.stop(cid)
            _ <- typing.stop(cid)
            afterStop <- types(seen)
            _ <- IO.sleep(expiry + 1.milli)
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
  }

  test("a refresh resets the expiry timer") {
    TestControl.executeEmbed {
      Ref[IO].of(Vector.empty[Event]).flatMap { seen =>
        tracker(seen).flatMap { typing =>
          for
            _ <- typing.start(cid)
            _ <- IO.sleep(700.millis)
            _ <- typing.start(cid)
            // 1.2s after the first start the original timer has come due, but the
            // refresh re-armed it (generation bump), so it must have no-opped;
            // the refreshed timer is not due for another 500ms.
            _ <- IO.sleep(500.millis)
            beforeRefreshExpiry <- types(seen)
            _ <- IO.sleep(500.millis + 1.milli)
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
  }

  test("shutdown stops every active channel") {
    TestControl.executeEmbed {
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
  }
