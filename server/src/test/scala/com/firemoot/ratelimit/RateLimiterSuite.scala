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

  test("a bucket that is switched off allows everything; the others still limit") {
    val tight = Some(RateLimitConfig(capacity = 1, refillPerSecond = 0.001))
    RateGuard.inMemory(RateLimits(apiKey = None, send = tight, connect = tight, upload = tight))
      .flatMap { rate =>
        for
          keyCalls <- (1 to 50).toList.traverse(_ => rate.apiKey("ci"))
          sends <- (1 to 2).toList.traverse(_ => rate.send("u1"))
        yield
          assert(keyCalls.forall(allowed), "the api-key bucket is off")
          assertEquals(sends.map(allowed), List(true, false), "send still limits")
      }
  }

  test("the defaults are unchanged and describe themselves for the startup log") {
    assertEquals(
      RateLimits.default.describe,
      "apiKey 200/100.0, send 30/10.0, connect 10/1.0, upload 20/2.0, " +
        "serverSend shared, serverUpload shared",
    )
    assertEquals(
      RateLimits.default
        .copy(apiKey = None, serverSend = ServerUserBucket.Own(Some(RateLimitConfig(150, 60.0))))
        .describe,
      "apiKey off, send 30/10.0, connect 10/1.0, upload 20/2.0, " +
        "serverSend 150/60.0, serverUpload shared",
    )
  }

  test("Retry-After rounds a wait up to whole seconds and never says 0") {
    assertEquals(RateLimitDecision.retryAfterSeconds(0.millis), 1L)
    assertEquals(RateLimitDecision.retryAfterSeconds(10.millis), 1L)
    assertEquals(RateLimitDecision.retryAfterSeconds(1000.millis), 1L)
    assertEquals(RateLimitDecision.retryAfterSeconds(1001.millis), 2L)
    assertEquals(RateLimitDecision.retryAfterSeconds(2500.millis), 3L)
  }

  test("server-key sends share the user's bucket by default, or get their own") {
    val tight = Some(RateLimitConfig(capacity = 1, refillPerSecond = 0.001))
    val base = RateLimits(apiKey = None, send = tight, connect = None, upload = tight)
    for
      shared <- RateGuard.inMemory(base)
      own <- RateGuard.inMemory(
        base.copy(
          serverSend = ServerUserBucket.Own(Some(RateLimitConfig(3, 0.001))),
          serverUpload = ServerUserBucket.Own(None),
        )
      )
      sharedServer <- shared.serverSend("system")
      sharedUser <- shared.send("system")
      ownServer <- (1 to 4).toList.traverse(_ => own.serverSend("system"))
      ownUser <- own.send("system")
      ownUploads <- (1 to 5).toList.traverse(_ => own.serverUpload("system"))
    yield
      assert(allowed(sharedServer), "the server send spends the shared token")
      assert(!allowed(sharedUser), "so the user's own send is limited")
      assertEquals(ownServer.map(allowed), List(true, true, true, false), "its own burst of 3")
      assert(allowed(ownUser), "end-user sends keep their own budget")
      assert(ownUploads.forall(allowed), "a server bucket can be switched off")
  }
