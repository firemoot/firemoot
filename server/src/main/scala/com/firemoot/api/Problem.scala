package com.firemoot.api

import scala.concurrent.duration.FiniteDuration

import com.firemoot.ratelimit.RateLimitDecision
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Codec, Decoder, Encoder}
import sttp.tapir.Schema

/**
 * RFC 9457 problem detail. `retryAfter` is an extension member, set only on a
 * 429: the same whole-second wait the `Retry-After` header carries.
 */
final case class Problem(
    `type`: String,
    title: String,
    status: Int,
    detail: Option[String],
    retryAfter: Option[Long] = None,
)

object Problem:
  private val encoder: Encoder.AsObject[Problem] =
    deriveEncoder[Problem].mapJsonObject(o =>
      if o("retryAfter").exists(_.isNull) then o.remove("retryAfter") else o
    )

  given Codec[Problem] = Codec.from(deriveDecoder[Problem], encoder)
  given Schema[Problem] = Schema.derived

  def of(status: Int, title: String, detail: Option[String] = None): Problem =
    Problem("about:blank", title, status, detail)

  def tooManyRequests(after: FiniteDuration): Problem =
    val seconds = RateLimitDecision.retryAfterSeconds(after)
    Problem(
      "about:blank",
      "Too Many Requests",
      429,
      Some(s"rate limit exceeded; retry after ${seconds}s"),
      Some(seconds),
    )
