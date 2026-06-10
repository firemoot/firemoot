package com.firemoot.metrics

import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

import scala.concurrent.duration.*

import cats.effect.IO
import cats.syntax.all.*
import com.firemoot.ws.ConnectionRegistry
import fs2.Stream

/** Samples live concurrent connections every `interval` (SPEC.md §8, M3.1). */
final class CcuSampler(
    registry: ConnectionRegistry,
    metrics: MetricsService,
    interval: FiniteDuration = 60.seconds,
):
  def stream: Stream[IO, Nothing] =
    Stream
      .awakeEvery[IO](interval)
      .evalMap { _ =>
        (IO.realTimeInstant, registry.count).flatMapN { (now, count) =>
          metrics.sampleCcu(now.atOffset(ZoneOffset.UTC), count).attempt.void
        }
      }
      .drain

/**
 * Periodically snapshots the day's metrics, rolls the previous hour's CCU up to
 * max/p95, and prunes raw facts and samples behind the rollups (SPEC.md §8, M3.2).
 */
final class RollupWorker(
    metrics: MetricsService,
    interval: FiniteDuration = 10.minutes,
    factsRetentionDays: Int = 35,
    ccuRetention: FiniteDuration = 2.days,
    hourlyRetention: FiniteDuration = 8.days,
):
  def stream: Stream[IO, Nothing] =
    Stream.awakeEvery[IO](interval).evalMap(_ => runOnce.attempt.void).drain

  def runOnce: IO[Unit] =
    IO.realTimeInstant.flatMap { instant =>
      val now = instant.atOffset(ZoneOffset.UTC)
      val hourStart = now.truncatedTo(ChronoUnit.HOURS)
      metrics.snapshotDay(now.toLocalDate) >>
        metrics.rollupHour(hourStart.minusHours(1), hourStart) >>
        metrics.prune(
          now.toLocalDate.minusDays(factsRetentionDays.toLong),
          now.minusSeconds(ccuRetention.toSeconds),
          now.minusSeconds(hourlyRetention.toSeconds),
        )
    }
