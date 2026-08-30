package com.firemoot.db

import cats.effect.{IO, Resource}
import com.firemoot.config.DbConfig
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

/**
 * Skunk connection pool. Tracing is disabled (otel4s no-op); the outer pool is
 * lazy, so the app boots even when Postgres is unavailable and `/readyz`
 * exercises connectivity on demand.
 */
object Database:

  private given Tracer[IO] = Tracer.noop
  private given Meter[IO] = Meter.noop

  def pool(cfg: DbConfig): Resource[IO, Resource[IO, Session[IO]]] =
    Session
      .Builder[IO]
      .withHost(cfg.host)
      .withPort(cfg.port)
      .withUserAndPassword(cfg.user, cfg.password.value)
      .withDatabase(cfg.database)
      // Prepared statements switch to a value-blind generic plan after five
      // executions, and for the channel queries the generic plan degrades to a
      // full scan/index walk (a light member's query went 5ms -> 545ms the
      // moment the cached plan flipped, observed live). Per-value planning
      // costs well under a millisecond; correctness of the plan dominates.
      .withConnectionParameters(
        Session.DefaultConnectionParameters + ("plan_cache_mode" -> "force_custom_plan")
      )
      .pooled(cfg.maxConnections)
