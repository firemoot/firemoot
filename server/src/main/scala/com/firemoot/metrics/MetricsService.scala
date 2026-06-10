package com.firemoot.metrics

import java.time.{LocalDate, OffsetDateTime}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.db.MetricsRepo
import com.firemoot.db.SessionSyntax.*
import io.circe.Json
import io.circe.syntax.*
import skunk.Session

/** A point-in-time snapshot for the dashboard's live tiles (M3). */
final case class LiveMetrics(
    dau: Long,
    wau: Long,
    mau: Long,
    messagesByType: Map[String, Long],
    mediaBytes: Long,
    dbSizeBytes: Long,
    ccuNow: Int,
)

/**
 * Activity capture, rollup and read-back (SPEC.md §8, M3.1/M3.2). Live tiles are
 * computed on demand from base tables; the daily snapshot and hourly CCU rollup
 * retain the history the charts render, and raw facts/samples are pruned behind
 * them.
 */
final class MetricsService(pool: Resource[IO, Session[IO]]):

  def record(userId: String): IO[Unit] =
    pool.use(_.run(MetricsRepo.recordActivity, userId))

  def sampleCcu(at: OffsetDateTime, value: Int): IO[Unit] =
    pool.use(_.run(MetricsRepo.sampleCcu, (at, value)))

  def live(ccuNow: Int): IO[LiveMetrics] =
    pool.use { s =>
      for
        dau <- s.runUnique(MetricsRepo.activeUsers, 1)
        wau <- s.runUnique(MetricsRepo.activeUsers, 7)
        mau <- s.runUnique(MetricsRepo.activeUsers, 30)
        byType <- s.runList(MetricsRepo.messagesByType, 1)
        media <- s.execute(MetricsRepo.mediaBytes).map(_.headOption.getOrElse(0L))
        db <- s.execute(MetricsRepo.dbSizeBytes).map(_.headOption.getOrElse(0L))
      yield LiveMetrics(dau, wau, mau, byType.toMap, media, db, ccuNow)
    }

  /** Snapshots the day's MAU/DAU/WAU, messages-by-type and storage gauges. */
  def snapshotDay(day: LocalDate): IO[Unit] =
    pool.use { s =>
      for
        dau <- s.runUnique(MetricsRepo.activeUsers, 1)
        wau <- s.runUnique(MetricsRepo.activeUsers, 7)
        mau <- s.runUnique(MetricsRepo.activeUsers, 30)
        byType <- s.runList(MetricsRepo.messagesByType, 1)
        media <- s.execute(MetricsRepo.mediaBytes).map(_.headOption.getOrElse(0L))
        db <- s.execute(MetricsRepo.dbSizeBytes).map(_.headOption.getOrElse(0L))
        _ <- s.run(MetricsRepo.upsertDaily, (day, "dau", Json.obj(), dau.toDouble))
        _ <- s.run(MetricsRepo.upsertDaily, (day, "wau", Json.obj(), wau.toDouble))
        _ <- s.run(MetricsRepo.upsertDaily, (day, "mau", Json.obj(), mau.toDouble))
        _ <- byType.traverse_ { (channelType, count) =>
          s.run(
            MetricsRepo.upsertDaily,
            (day, "messages", Json.obj("channelType" -> channelType.asJson), count.toDouble),
          )
        }
        _ <- s.run(MetricsRepo.upsertDaily, (day, "media_bytes", Json.obj(), media.toDouble))
        _ <- s.run(MetricsRepo.upsertDaily, (day, "db_size_bytes", Json.obj(), db.toDouble))
      yield ()
    }

  /** Rolls the CCU samples in [from, to) up to hourly max and p95. */
  def rollupHour(from: OffsetDateTime, to: OffsetDateTime): IO[Unit] =
    pool.use { s =>
      s.runUnique(MetricsRepo.ccuRollup, (from, to)).flatMap { (max, p95) =>
        s.run(MetricsRepo.upsertHourly, (from, "ccu_max", Json.obj(), max.toDouble)) >>
          s.run(MetricsRepo.upsertHourly, (from, "ccu_p95", Json.obj(), p95))
      }
    }

  def prune(
      factsBefore: LocalDate,
      ccuBefore: OffsetDateTime,
      hourlyBefore: OffsetDateTime,
  ): IO[Unit] =
    pool.use { s =>
      s.run(MetricsRepo.pruneFactsBefore, factsBefore) >>
        s.run(MetricsRepo.pruneCcuBefore, ccuBefore) >>
        s.run(MetricsRepo.pruneHourlyBefore, hourlyBefore)
    }

  def dailySeries(metric: String, from: LocalDate): IO[List[(LocalDate, Json, Double)]] =
    pool.use(_.runList(MetricsRepo.dailySeries, (metric, from)))
