package com.firemoot.db

import cats.effect.IO
import com.firemoot.config.DbConfig
import org.flywaydb.core.Flyway

/**
 * Runs Flyway migrations on boot. This is the one place a JDBC connection is
 * used; the application itself talks to Postgres only through skunk (ADR 0001).
 */
object Migrations:

  /**
   * Applies pending migrations and returns the number executed. Retries the
   * initial connection so a not-yet-ready Postgres (compose startup race) does
   * not crash boot.
   */
  def run(cfg: DbConfig): IO[Int] = IO.blocking {
    val url = s"jdbc:postgresql://${cfg.host}:${cfg.port}/${cfg.database}?sslmode=${cfg.sslMode}"
    Flyway
      .configure()
      .dataSource(url, cfg.user, cfg.password.value)
      .locations("classpath:db/migration")
      .connectRetries(15)
      .connectRetriesInterval(1)
      .load()
      .migrate()
      .migrationsExecuted
  }
