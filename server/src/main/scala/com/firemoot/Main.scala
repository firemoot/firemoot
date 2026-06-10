package com.firemoot

import cats.effect.{IO, IOApp}
import cats.syntax.semigroupk.*
import com.firemoot.api.ApiRoutes
import com.firemoot.config.AppConfig
import com.firemoot.db.{Database, Migrations}
import com.firemoot.http.{HealthRoutes, HttpServer}
import com.firemoot.service.{ChannelService, MessageService, UserService}
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.Logger
import org.http4s.{HttpRoutes, MediaType}
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
        val api =
          ApiRoutes(cfg.server, UserService(pool), ChannelService(pool), MessageService(pool))
        val openApi = HttpRoutes.of[IO] { case GET -> Root / "v1" / "openapi.json" =>
          Ok(api.openApiJson).map(_.withContentType(`Content-Type`(MediaType.application.json)))
        }
        val httpApp = Logger.httpApp(logHeaders = true, logBody = false)(
          (HealthRoutes(pool).routes <+> api.routes <+> openApi).orNotFound
        )
        HttpServer.resource(cfg.http, httpApp).useForever
      }
    yield ()
