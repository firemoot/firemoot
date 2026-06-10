package com.firemoot

import cats.effect.{IO, IOApp}
import com.firemoot.config.AppConfig
import com.firemoot.db.{Database, Migrations}
import com.firemoot.http.{HealthRoutes, HttpServer}
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      log <- Slf4jLogger.create[IO]
      cfg <- AppConfig.load
      _ <- log.info(s"Firemoot starting on ${cfg.http.host}:${cfg.http.port}")
      applied <- Migrations.run(cfg.db)
      _ <- log.info(s"Flyway applied $applied migration(s)")
      _ <- Database.pool(cfg.db).use { pool =>
        val httpApp = Logger.httpApp(logHeaders = true, logBody = false)(
          HealthRoutes(pool).routes.orNotFound
        )
        HttpServer.resource(cfg.http, httpApp).useForever
      }
    yield ()
