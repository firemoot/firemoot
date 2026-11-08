package com.firemoot.config

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import ciris.*
import com.firemoot.ratelimit.{RateLimitConfig, RateLimits}

final case class HttpConfig(host: String, port: Int)

final case class DbConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: Secret[String],
    maxConnections: Int,
    sslMode: String = "prefer",
)

final case class ServerConfig(apiKeyId: String, apiSecret: Secret[String])

/**
 * S3-compatible media storage (SPEC.md §7, M2). Absent config means media is
 * disabled (uploads return 501). Generic S3 only: a path-style endpoint plus
 * static credentials, no vendor admin APIs.
 */
final case class MediaConfig(
    endpoint: String,
    region: String,
    bucket: String,
    accessKey: String,
    secretKey: Secret[String],
    publicBaseUrl: Option[String],
    presignExpiry: FiniteDuration,
    maxImageBytes: Long,
    maxFileBytes: Long,
    allowedMime: Set[String],
    // Path-style addressing (endpoint/bucket/key) suits MinIO and friends;
    // stores that serve public objects virtual-host style only (e.g. Tigris,
    // where path-style public reads 403) need this off so presigned PUTs and
    // public GETs share the bucket-subdomain origin.
    forcePathStyle: Boolean = true,
):
  /** The public URL for an object key (`publicBaseUrl` override, else path-style). */
  def objectUrl(key: String): String =
    publicBaseUrl match
      case Some(base) => s"${base.stripSuffix("/")}/$key"
      case None => s"${endpoint.stripSuffix("/")}/$bucket/$key"

/**
 * Unread-message reminders (the `user.unread_message_reminder` webhook). Absent
 * config means reminders are off. `maxMembers` skips larger channels, where a
 * reminder per member would be a notification storm rather than a nudge.
 */
final case class UnreadReminderConfig(interval: FiniteDuration, maxMembers: Int)

final case class AppConfig(
    http: HttpConfig,
    db: DbConfig,
    server: ServerConfig,
    devDemo: Boolean,
    media: Option[MediaConfig],
    adminPassword: Option[String],
    unreadReminders: Option[UnreadReminderConfig],
    rateLimits: RateLimits,
)

object AppConfig:

  private val http: ConfigValue[Effect, HttpConfig] =
    (
      env("FIREMOOT_HTTP_HOST").default("0.0.0.0"),
      env("FIREMOOT_HTTP_PORT").as[Int].default(6668),
    ).parMapN(HttpConfig.apply)

  private val db: ConfigValue[Effect, DbConfig] =
    (
      env("FIREMOOT_DB_HOST").default("localhost"),
      env("FIREMOOT_DB_PORT").as[Int].default(5432),
      env("FIREMOOT_DB_NAME").default("firemoot"),
      env("FIREMOOT_DB_USER").default("firemoot"),
      env("FIREMOOT_DB_PASSWORD").default("firemoot").secret,
      env("FIREMOOT_DB_MAX_CONNECTIONS").as[Int].default(10),
      // JDBC sslmode for the boot-time Flyway connection ("prefer" matches
      // pgjdbc's default). Set "disable" for servers whose proxy breaks the
      // TLS handshake instead of refusing it (e.g. Fly unmanaged Postgres
      // over flycast), where "prefer" cannot fall back to plaintext.
      env("FIREMOOT_DB_SSLMODE").default("prefer"),
    ).parMapN(DbConfig.apply)

  private val server: ConfigValue[Effect, ServerConfig] =
    (
      env("FIREMOOT_API_KEY_ID").default("firemoot"),
      env("FIREMOOT_API_SECRET").default("dev-secret").secret,
    ).parMapN(ServerConfig.apply)

  private val devDemo: ConfigValue[Effect, Boolean] =
    env("FIREMOOT_DEV_DEMO").as[Boolean].default(false)

  /**
   * Treats a present-but-blank env var as absent. Compose's `${VAR:-}` default
   * passes an empty string rather than leaving the variable unset, so without this
   * an "unset" optional like the S3 endpoint would look configured and then fail
   * fast on the missing siblings.
   */
  private[config] def blankToNone(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)

  // Set at install to enable the admin dashboard; no default (admin stays locked).
  private val adminPassword: ConfigValue[Effect, Option[String]] =
    env("FIREMOOT_ADMIN_PASSWORD").option.map(blankToNone)

  private val defaultMime =
    "image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain"

  // Media is enabled iff an S3 endpoint is configured; the rest then load (the
  // access key/secret being required, so a half-configured store fails fast).
  private val media: ConfigValue[Effect, Option[MediaConfig]] =
    env("FIREMOOT_S3_ENDPOINT").option.map(blankToNone).flatMap {
      case None => default(Option.empty[MediaConfig])
      case Some(endpoint) =>
        (
          env("FIREMOOT_S3_REGION").default("us-east-1"),
          env("FIREMOOT_S3_BUCKET").default("firemoot"),
          env("FIREMOOT_S3_ACCESS_KEY"),
          env("FIREMOOT_S3_SECRET_KEY").secret,
          env("FIREMOOT_S3_PUBLIC_URL").option,
          env("FIREMOOT_S3_PRESIGN_EXPIRY_SECONDS").as[Int].default(900),
          env("FIREMOOT_MEDIA_MAX_IMAGE_BYTES").as[Long].default(10L * 1024 * 1024),
          env("FIREMOOT_MEDIA_MAX_FILE_BYTES").as[Long].default(50L * 1024 * 1024),
          env("FIREMOOT_MEDIA_ALLOWED_MIME").default(defaultMime),
          env("FIREMOOT_S3_FORCE_PATH_STYLE").as[Boolean].default(true),
        ).parMapN {
          (
              region,
              bucket,
              accessKey,
              secret,
              publicUrl,
              expiry,
              maxImage,
              maxFile,
              mimes,
              pathStyle,
          ) =>
            Some(MediaConfig(
              endpoint = endpoint,
              region = region,
              bucket = bucket,
              accessKey = accessKey,
              secretKey = secret,
              publicBaseUrl = publicUrl,
              presignExpiry = expiry.seconds,
              maxImageBytes = maxImage,
              maxFileBytes = maxFile,
              allowedMime = mimes.split(",").map(_.trim).filter(_.nonEmpty).toSet,
              forcePathStyle = pathStyle,
            ))
        }
    }

  /** Stream's bounds for `reminders_interval`: one minute to one day. */
  private[config] def reminderInterval(raw: String): Either[String, FiniteDuration] =
    raw.toIntOption match
      case Some(seconds) if seconds >= 60 && seconds <= 86400 => Right(seconds.seconds)
      case _ =>
        Left(
          s"FIREMOOT_UNREAD_REMINDER_INTERVAL_SECONDS must be a whole number of seconds " +
            s"from 60 to 86400, got '$raw'"
        )

  private[config] def reminderMaxMembers(raw: String): Either[String, Int] =
    raw.toIntOption.filter(_ >= 2).toRight(
      s"FIREMOOT_UNREAD_REMINDER_MAX_MEMBERS must be a whole number of at least 2, got '$raw'"
    )

  private def validated[A](result: Either[String, A]): ConfigValue[Effect, A] =
    result.fold(msg => ConfigValue.failed(ConfigError(msg)), default(_))

  // Reminders are enabled iff an interval is configured.
  private val unreadReminders: ConfigValue[Effect, Option[UnreadReminderConfig]] =
    env("FIREMOOT_UNREAD_REMINDER_INTERVAL_SECONDS").option.map(blankToNone).flatMap {
      case None => default(Option.empty[UnreadReminderConfig])
      case Some(rawInterval) =>
        (
          validated(reminderInterval(rawInterval)),
          env("FIREMOOT_UNREAD_REMINDER_MAX_MEMBERS").option.map(blankToNone).flatMap {
            case None => default(10)
            case Some(rawMax) => validated(reminderMaxMembers(rawMax))
          },
        ).parMapN((interval, maxMembers) => Some(UnreadReminderConfig(interval, maxMembers)))
    }

  /**
   * A rate-limit bucket setting: `off`, or `<burst>/<per-second>` (e.g. `200/100`
   * - a burst of 200 requests, refilling at 100 a second).
   */
  private[config] def rateLimit(
      name: String,
      raw: String,
  ): Either[String, Option[RateLimitConfig]] =
    val invalid = s"$name must be 'off' or '<burst>/<per-second>' (e.g. 200/100), got '$raw'"
    raw.trim.toLowerCase match
      case "off" => Right(None)
      case value =>
        value.split("/", -1).map(_.trim) match
          case Array(burst, perSecond) =>
            (
              burst.toIntOption.filter(_ >= 1),
              perSecond.toDoubleOption.filter(d => d > 0 && !d.isInfinite),
            ) match
              case (Some(capacity), Some(refill)) => Right(Some(RateLimitConfig(capacity, refill)))
              case _ => Left(invalid)
          case _ => Left(invalid)

  private def rateLimitBucket(
      name: String,
      fallback: Option[RateLimitConfig],
  ): ConfigValue[Effect, Option[RateLimitConfig]] =
    env(name).option.map(blankToNone).flatMap {
      case None => default(fallback)
      case Some(raw) => validated(rateLimit(name, raw))
    }

  private val rateLimits: ConfigValue[Effect, RateLimits] =
    (
      rateLimitBucket("FIREMOOT_RATE_LIMIT_API_KEY", RateLimits.default.apiKey),
      rateLimitBucket("FIREMOOT_RATE_LIMIT_SEND", RateLimits.default.send),
      rateLimitBucket("FIREMOOT_RATE_LIMIT_CONNECT", RateLimits.default.connect),
      rateLimitBucket("FIREMOOT_RATE_LIMIT_UPLOAD", RateLimits.default.upload),
    ).parMapN(RateLimits.apply)

  val load: IO[AppConfig] =
    (http, db, server, devDemo, media, adminPassword, unreadReminders, rateLimits)
      .parMapN(AppConfig.apply)
      .load[IO]
