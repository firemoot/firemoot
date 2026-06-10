package com.firemoot

import cats.effect.{IO, IOApp}
import com.firemoot.backplane.Backplane
import com.firemoot.config.AppConfig
import com.firemoot.db.{Database, Migrations}
import com.firemoot.http.HttpServer
import com.firemoot.ws.ConnectionRegistry
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple:

  def run: IO[Unit] =
    for
      log <- Slf4jLogger.create[IO]
      cfg <- AppConfig.load
      _ <- log.info(s"Firemoot starting on ${cfg.http.host}:${cfg.http.port}")
      applied <- Migrations.run(cfg.db)
      _ <- log.info(s"Flyway applied $applied migration(s)")
      backplane <- Backplane.inProcess
      registry <- ConnectionRegistry.create
      _ <- Database.pool(cfg.db).use { pool =>
        HttpServer.resource(
          cfg.http,
          Application.httpApp(cfg.server, pool, backplane, registry, cfg.devDemo),
        ).useForever
      }
    yield ()
