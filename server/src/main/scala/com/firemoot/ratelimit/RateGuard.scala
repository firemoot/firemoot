package com.firemoot.ratelimit

import cats.effect.IO

/** How server-key requests made as a user (sends, uploads) are budgeted. */
enum ServerUserBucket:
  /** They share that user's own bucket with the user's end-user requests. */
  case SharedWithUser

  /** A per-user bucket of their own, apart from end-user traffic; `None` is off. */
  case Own(bucket: Option[RateLimitConfig])

/** Per-category bucket configs; `None` switches that bucket off. */
final case class RateLimits(
    apiKey: Option[RateLimitConfig],
    send: Option[RateLimitConfig],
    connect: Option[RateLimitConfig],
    upload: Option[RateLimitConfig],
    serverSend: ServerUserBucket = ServerUserBucket.SharedWithUser,
    serverUpload: ServerUserBucket = ServerUserBucket.SharedWithUser,
):
  /** One entry per bucket for the startup log, e.g. `apiKey 200/100.0`. */
  def describe: String =
    def show(bucket: Option[RateLimitConfig]) =
      bucket.fold("off")(c => s"${c.capacity}/${c.refillPerSecond}")
    def showServer(bucket: ServerUserBucket) = bucket match
      case ServerUserBucket.SharedWithUser => "shared"
      case ServerUserBucket.Own(own) => show(own)
    List(
      s"apiKey ${show(apiKey)}",
      s"send ${show(send)}",
      s"connect ${show(connect)}",
      s"upload ${show(upload)}",
      s"serverSend ${showServer(serverSend)}",
      s"serverUpload ${showServer(serverUpload)}",
    ).mkString(", ")

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

  /** A send a server key makes as `userId`. */
  def serverSend(userId: String): IO[RateLimitDecision] =
    limits.serverSend match
      case ServerUserBucket.SharedWithUser => send(userId)
      case ServerUserBucket.Own(bucket) => check(bucket, s"server-send:$userId")

  /** An upload presign a server key makes for `userId`. */
  def serverUpload(userId: String): IO[RateLimitDecision] =
    limits.serverUpload match
      case ServerUserBucket.SharedWithUser => upload(userId)
      case ServerUserBucket.Own(bucket) => check(bucket, s"server-upload:$userId")

object RateGuard:

  /** A guard that never limits - the default when rate limiting is not wired. */
  val unlimited: RateGuard = new RateGuard(RateLimiter.noop, RateLimits.default)

  def inMemory(limits: RateLimits = RateLimits.default): IO[RateGuard] =
    RateLimiter.inMemory.map(new RateGuard(_, limits))
