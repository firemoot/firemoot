package com.firemoot.ratelimit

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}

/** A token bucket: `capacity` tokens, refilling at `refillPerSecond`. */
final case class RateLimitConfig(capacity: Int, refillPerSecond: Double)

enum RateLimitDecision:
  case Allowed
  case Retry(after: FiniteDuration)

/**
 * Per-key rate limiting (SPEC.md §13, M1.12). The seam is the trait; v1 ships an
 * in-memory token-bucket implementation. The bucket config travels with each
 * call, so one limiter serves every category (api key, send, connect, ...) with
 * independent buckets keyed by a caller-chosen string.
 */
trait RateLimiter:
  def check(key: String, config: RateLimitConfig): IO[RateLimitDecision]

object RateLimiter:

  /** Never limits - the default everywhere rate limiting is not configured. */
  val noop: RateLimiter = (_, _) => IO.pure(RateLimitDecision.Allowed)

  def inMemory: IO[RateLimiter] =
    Ref[IO].of(Map.empty[String, RateLimiter.Bucket]).map(new TokenBucketRateLimiter(_))

  final private[ratelimit] case class Bucket(tokens: Double, updatedAt: FiniteDuration)

/**
 * Lazily-created token buckets in a single `Ref`. Each `check` refills the bucket
 * for the elapsed time (capped at capacity), then consumes a token if one is
 * available, otherwise reports how long until the next token.
 */
final class TokenBucketRateLimiter(state: Ref[IO, Map[String, RateLimiter.Bucket]])
    extends RateLimiter:

  import RateLimiter.Bucket

  def check(key: String, config: RateLimitConfig): IO[RateLimitDecision] =
    IO.realTime.flatMap { now =>
      state.modify { buckets =>
        val current = buckets.getOrElse(key, Bucket(config.capacity.toDouble, now))
        val elapsedSeconds = (now - current.updatedAt).toMillis.max(0L) / 1000.0
        val refilled =
          math.min(
            config.capacity.toDouble,
            current.tokens + elapsedSeconds * config.refillPerSecond,
          )
        if refilled >= 1.0 then
          (buckets + (key -> Bucket(refilled - 1.0, now)), RateLimitDecision.Allowed)
        else
          val waitMillis = ((1.0 - refilled) / config.refillPerSecond * 1000.0).ceil.toLong
          (buckets + (key -> Bucket(refilled, now)), RateLimitDecision.Retry(waitMillis.millis))
      }
    }
