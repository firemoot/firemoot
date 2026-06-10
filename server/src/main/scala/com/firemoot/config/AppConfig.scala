package com.firemoot.config

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import ciris.*

final case class HttpConfig(host: String, port: Int)

final case class DbConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: Secret[String],
    maxConnections: Int,
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
):
  /** The public URL for an object key (`publicBaseUrl` override, else path-style). */
  def objectUrl(key: String): String =
    publicBaseUrl match
      case Some(base) => s"${base.stripSuffix("/")}/$key"
      case None => s"${endpoint.stripSuffix("/")}/$bucket/$key"

final case class AppConfig(
    http: HttpConfig,
    db: DbConfig,
    server: ServerConfig,
    devDemo: Boolean,
    media: Option[MediaConfig],
    adminPassword: Option[String],
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
    ).parMapN(DbConfig.apply)

  private val server: ConfigValue[Effect, ServerConfig] =
    (
      env("FIREMOOT_API_KEY_ID").default("firemoot"),
      env("FIREMOOT_API_SECRET").default("dev-secret").secret,
    ).parMapN(ServerConfig.apply)

  private val devDemo: ConfigValue[Effect, Boolean] =
    env("FIREMOOT_DEV_DEMO").as[Boolean].default(false)

  // Set at install to enable the admin dashboard; no default (admin stays locked).
  private val adminPassword: ConfigValue[Effect, Option[String]] =
    env("FIREMOOT_ADMIN_PASSWORD").option

  private val defaultMime =
    "image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain"

  // Media is enabled iff an S3 endpoint is configured; the rest then load (the
  // access key/secret being required, so a half-configured store fails fast).
  private val media: ConfigValue[Effect, Option[MediaConfig]] =
    env("FIREMOOT_S3_ENDPOINT").option.flatMap {
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
        ).parMapN {
          (region, bucket, accessKey, secret, publicUrl, expiry, maxImage, maxFile, mimes) =>
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
            ))
        }
    }

  val load: IO[AppConfig] =
    (http, db, server, devDemo, media, adminPassword).parMapN(AppConfig.apply).load[IO]
