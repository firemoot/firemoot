package com.firemoot.ratelimit

import cats.effect.IO

/** Per-category bucket configs; `None` switches that bucket off. */
final case class RateLimits(
    apiKey: Option[RateLimitConfig],
    send: Option[RateLimitConfig],
    connect: Option[RateLimitConfig],
    upload: Option[RateLimitConfig],
):
  /** One line per bucket for the startup log, e.g. `apiKey 200/100`. */
  def describe: String =
    List("apiKey" -> apiKey, "send" -> send, "connect" -> connect, "upload" -> upload)
      .map((name, bucket) =>
        s"$name ${bucket.fold("off")(c => s"${c.capacity}/${c.refillPerSecond}")}"
      )
      .mkString(", ")

object RateLimits:
  val default: RateLimits = RateLimits(
    apiKey = Some(RateLimitConfig(capacity = 200, refillPerSecond = 100.0)),
    send = Some(RateLimitConfig(capacity = 30, refillPerSecond = 10.0)),
    connect = Some(RateLimitConfig(capacity = 10, refillPerSecond = 1.0)),
    upload = Some(RateLimitConfig(capacity = 20, refillPerSecond = 2.0)),
  )

/**
 * Names the rate-limit buckets the application cares about (SPEC.md §13, M1.12):
 * a per-API-key budget across all server REST, plus per-user budgets on the
 * abuse-prone surfaces (sends, WebSocket connects; uploads join in M2). It is a
 * thin facade over a [[RateLimiter]] so call sites stay readable. A bucket that
 * is switched off allows everything without touching the limiter.
 */
final class RateGuard(limiter: RateLimiter, limits: RateLimits):
  private def check(bucket: Option[RateLimitConfig], key: String): IO[RateLimitDecision] =
    bucket.fold(IO.pure(RateLimitDecision.Allowed))(limiter.check(key, _))

  def apiKey(keyId: String): IO[RateLimitDecision] = check(limits.apiKey, s"apikey:$keyId")
  def send(userId: String): IO[RateLimitDecision] = check(limits.send, s"send:$userId")
  def connect(userId: String): IO[RateLimitDecision] = check(limits.connect, s"connect:$userId")
  def upload(userId: String): IO[RateLimitDecision] = check(limits.upload, s"upload:$userId")

object RateGuard:

  /** A guard that never limits - the default when rate limiting is not wired. */
  val unlimited: RateGuard = new RateGuard(RateLimiter.noop, RateLimits.default)

  def inMemory(limits: RateLimits = RateLimits.default): IO[RateGuard] =
    RateLimiter.inMemory.map(new RateGuard(_, limits))
