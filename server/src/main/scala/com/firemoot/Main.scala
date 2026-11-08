package com.firemoot

import scala.concurrent.duration.*

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.firemoot.admin.AdminService
import com.firemoot.backplane.Backplane
import com.firemoot.config.AppConfig
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations, UserRepo}
import com.firemoot.http.HttpServer
import com.firemoot.media.{MediaService, ObjectStore, ThumbnailWorker}
import com.firemoot.metrics.{CcuSampler, MetricsService, RollupWorker}
import com.firemoot.ratelimit.RateGuard
import com.firemoot.service.{LastActiveTracker, MessageService, WebhookService}
import com.firemoot.webhook.{UnreadReminderWorker, WebhookConfig, WebhookDispatcher}
import com.firemoot.ws.ConnectionRegistry
import fs2.Stream
import org.http4s.ember.client.EmberClientBuilder
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
        // When media is configured, build the presigner (for uploads) and an
        // object store + thumbnail worker (for the write-back loop).
        val media: Resource[IO, Option[(MediaService, ThumbnailWorker)]] =
          cfg.media match
            case None => Resource.pure(None)
            case Some(mc) =>
              (MediaService.presigner(mc), ObjectStore.s3(mc)).tupled.map { (presigner, store) =>
                Some((
                  new MediaService(mc, pool, presigner),
                  ThumbnailWorker(mc, pool, store, MessageService(pool, backplane), log),
                ))
              }
        (EmberClientBuilder.default[IO].build, media).tupled.use { (httpClient, mediaParts) =>
          val mediaService = mediaParts.map(_._1)
          val thumbnails = mediaParts.fold(Stream.empty.covary[IO])(_._2.stream)
          for
            _ <-
              log.info(s"Media uploads ${if mediaService.isDefined then "enabled" else "disabled"}")
            _ <- log.info(cfg.unreadReminders.fold("Unread reminders disabled") { rc =>
              s"Unread reminders enabled: ${rc.interval.toSeconds}s, channels of up to " +
                s"${rc.maxMembers} members"
            })
            lastActive <- LastActiveTracker.create(60.seconds) { userId =>
              pool.use(_.run(UserRepo.touchLastActive, userId))
            }
            _ <- log.info(s"Rate limits: ${cfg.rateLimits.describe}")
            rate <- RateGuard.inMemory(cfg.rateLimits)
            // Install/reset the admin password from the environment (no default).
            _ <- cfg.adminPassword.traverse_(AdminService(
              pool,
              cfg.server.apiSecret.value,
            ).setPassword)
            metrics = MetricsService(pool)
            ccuSampler = CcuSampler(registry, metrics)
            rollup = RollupWorker(metrics)
            // A connecting user touches last_active_at and counts toward MAU/DAU.
            onActive =
              (userId: String) => lastActive.touch(userId) >> metrics.record(userId).attempt.void
            webhooks = WebhookService(pool)
            dispatcher =
              WebhookDispatcher(pool, httpClient, WebhookConfig.default, cfg.server.apiKeyId)
            // Persisted channel events fan out to registered webhook endpoints.
            // Rendering a Stream-format body reads a few rows, and a slow subscriber
            // backpressures every publisher on the topic, so enqueue concurrently
            // (delivery is unordered anyway) and never let one failure end the stream.
            enqueue = backplane.subscribe
              .filter(WebhookService.isDeliverable)
              .parEvalMapUnordered(4) { event =>
                webhooks.enqueue(event).handleErrorWith { e =>
                  log.error(e)(s"webhook enqueue failed for ${event.`type`} on ${event.cid}")
                }
              }
              .drain
            app = Application.httpApp(
              cfg.server,
              pool,
              backplane,
              registry,
              cfg.devDemo,
              onActive,
              rate,
              mediaService,
            )
            reminders = cfg.unreadReminders.fold(Stream.empty.covary[IO]) { rc =>
              UnreadReminderWorker(pool, rc, log).stream
            }
            workers = dispatcher.stream
              .merge(enqueue)
              .merge(reminders)
              .merge(thumbnails)
              .merge(ccuSampler.stream)
              .merge(rollup.stream)
            _ <- HttpServer.resource(cfg.http, app).use(_ => workers.compile.drain)
          yield ()
        }
      }
    yield ()
