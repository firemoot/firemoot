package com.firemoot

import scala.concurrent.duration.*

import cats.effect.{IO, IOApp}
import com.firemoot.backplane.Backplane
import com.firemoot.config.AppConfig
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations, UserRepo}
import com.firemoot.http.HttpServer
import com.firemoot.service.LastActiveTracker
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
        for
          lastActive <- LastActiveTracker.create(60.seconds) { userId =>
            pool.use(_.run(UserRepo.touchLastActive, userId))
          }
          app = Application.httpApp(
            cfg.server,
            pool,
            backplane,
            registry,
            cfg.devDemo,
            lastActive.touch,
          )
          _ <- HttpServer.resource(cfg.http, app).useForever
        yield ()
      }
    yield ()
