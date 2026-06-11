package com.firemoot.db

import java.time.{LocalDate, OffsetDateTime}

import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

/** Metrics capture, rollup and read-back (SPEC.md §8, M3.1/M3.2). */
object MetricsRepo:

  // --- capture ---

  val recordActivity: Command[String] =
    sql"""
      insert into activity_facts (day, user_id) values (current_date, $text)
      on conflict do nothing
    """.command

  /** Backfill a fact on a specific day (rollup tests, imports). */
  val recordActivityOn: Command[(LocalDate, String)] =
    sql"insert into activity_facts (day, user_id) values ($date, $text) on conflict do nothing".command

  val sampleCcu: Command[(OffsetDateTime, Int)] =
    sql"insert into ccu_samples (ts, value) values ($timestamptz, $int4) on conflict (ts) do nothing".command

  // --- live reads (on demand from base tables) ---

  /** Distinct users active within the trailing `days` (DAU=1, WAU=7, MAU=30). */
  val activeUsers: Query[Int, Long] =
    sql"select count(distinct user_id) from activity_facts where day > current_date - $int4"
      .query(int8)

  /** Regular-message counts per channel type within the trailing `days`. */
  val messagesByType: Query[Int, (String, Long)] =
    sql"""
      select c.type, count(*)
      from messages m join channels c on c.cid = m.cid
      where m.type = 'regular' and m.created_at >= current_date - $int4
      group by c.type
    """.query(text *: int8)

  val mediaBytes: Query[Void, Long] =
    sql"select coalesce(sum(size_bytes), 0)::bigint from uploads".query(int8)

  val dbSizeBytes: Query[Void, Long] =
    sql"select pg_database_size(current_database())".query(int8)

  // --- rollup ---

  /** max and p95 concurrent connections in the half-open window [from, to). */
  val ccuRollup: Query[(OffsetDateTime, OffsetDateTime), (Int, Double)] =
    sql"""
      select coalesce(max(value), 0),
             coalesce(percentile_cont(0.95) within group (order by value), 0)
      from ccu_samples where ts >= $timestamptz and ts < $timestamptz
    """.query(int4 *: float8)

  val upsertDaily: Command[(LocalDate, String, Json, Double)] =
    sql"""
      insert into metrics_daily (day, metric, labels, value)
      values ($date, $text, ${jsonb[Json]}, $float8)
      on conflict (day, metric, labels) do update set value = excluded.value
    """.command

  val upsertHourly: Command[(OffsetDateTime, String, Json, Double)] =
    sql"""
      insert into metrics_hourly (ts, metric, labels, value)
      values ($timestamptz, $text, ${jsonb[Json]}, $float8)
      on conflict (ts, metric, labels) do update set value = excluded.value
    """.command

  // --- read-back for the dashboard ---

  /** A metric's daily series since `from` (with labels, for breakdowns). */
  val dailySeries: Query[(String, LocalDate), (LocalDate, Json, Double)] =
    sql"""
      select day, labels, value from metrics_daily
      where metric = $text and day >= $date
      order by day
    """.query(date *: jsonb[Json] *: float8)

  /** A metric's hourly series since `from` (the CCU max/p95 rollups). */
  val hourlySeries: Query[(String, OffsetDateTime), (OffsetDateTime, Json, Double)] =
    sql"""
      select ts, labels, value from metrics_hourly
      where metric = $text and ts >= $timestamptz
      order by ts
    """.query(timestamptz *: jsonb[Json] *: float8)

  // --- retention ---

  val pruneFactsBefore: Command[LocalDate] =
    sql"delete from activity_facts where day < $date".command

  val pruneCcuBefore: Command[OffsetDateTime] =
    sql"delete from ccu_samples where ts < $timestamptz".command

  val pruneHourlyBefore: Command[OffsetDateTime] =
    sql"delete from metrics_hourly where ts < $timestamptz".command
