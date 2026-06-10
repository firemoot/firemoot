package com.firemoot.ratelimit

import cats.effect.IO

/** Per-category bucket configs. */
final case class RateLimits(
    apiKey: RateLimitConfig,
    send: RateLimitConfig,
    connect: RateLimitConfig,
)

object RateLimits:
  val default: RateLimits = RateLimits(
    apiKey = RateLimitConfig(capacity = 200, refillPerSecond = 100.0),
    send = RateLimitConfig(capacity = 30, refillPerSecond = 10.0),
    connect = RateLimitConfig(capacity = 10, refillPerSecond = 1.0),
  )

/**
 * Names the rate-limit buckets the application cares about (SPEC.md §13, M1.12):
 * a per-API-key budget across all server REST, plus per-user budgets on the
 * abuse-prone surfaces (sends, WebSocket connects; uploads join in M2). It is a
 * thin facade over a [[RateLimiter]] so call sites stay readable.
 */
final class RateGuard(limiter: RateLimiter, limits: RateLimits):
  def apiKey(keyId: String): IO[RateLimitDecision] =
    limiter.check(s"apikey:$keyId", limits.apiKey)
  def send(userId: String): IO[RateLimitDecision] =
    limiter.check(s"send:$userId", limits.send)
  def connect(userId: String): IO[RateLimitDecision] =
    limiter.check(s"connect:$userId", limits.connect)

object RateGuard:

  /** A guard that never limits - the default when rate limiting is not wired. */
  val unlimited: RateGuard = new RateGuard(RateLimiter.noop, RateLimits.default)

  def inMemory(limits: RateLimits = RateLimits.default): IO[RateGuard] =
    RateLimiter.inMemory.map(new RateGuard(_, limits))
