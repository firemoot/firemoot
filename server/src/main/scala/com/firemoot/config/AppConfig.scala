package com.firemoot.config

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

final case class AppConfig(http: HttpConfig, db: DbConfig)

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

  val load: IO[AppConfig] =
    (http, db).parMapN(AppConfig.apply).load[IO]
