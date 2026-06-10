package com.firemoot.ratelimit

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

class RateLimiterSuite extends CatsEffectSuite:

  private def allowed(d: RateLimitDecision): Boolean = d == RateLimitDecision.Allowed

  test("a token bucket allows a burst, then limits, then refills - per key") {
    RateLimiter.inMemory.flatMap { limiter =>
      val cfg = RateLimitConfig(capacity = 2, refillPerSecond = 50.0)
      for
        a1 <- limiter.check("a", cfg)
        a2 <- limiter.check("a", cfg)
        a3 <- limiter.check("a", cfg)
        b1 <- limiter.check("b", cfg)
        _ <- IO.sleep(80.millis)
        a4 <- limiter.check("a", cfg)
      yield
        assert(allowed(a1) && allowed(a2), "the first two calls spend the burst capacity")
        a3 match
          case RateLimitDecision.Retry(after) =>
            assert(after.toMillis > 0 && after.toMillis <= 40, s"retry-after ~20ms, got $after")
          case RateLimitDecision.Allowed => fail("the third call should be limited")
        assert(allowed(b1), "a different key has an independent bucket")
        assert(allowed(a4), "the bucket refills over time")
    }
  }

  test("the noop limiter never limits") {
    val cfg = RateLimitConfig(capacity = 1, refillPerSecond = 0.0)
    (1 to 5).toList
      .traverse(_ => RateLimiter.noop.check("x", cfg))
      .map(ds => assert(ds.forall(allowed), "noop allows every call"))
  }
