package com.firemoot.db

import java.time.OffsetDateTime
import java.util.UUID

import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

/**
 * Webhook endpoint registry and the durable delivery queue (SPEC.md §3, M1.10).
 * Deliveries are claimed `for update skip locked` so multiple workers never grab
 * the same row; a claim flips the row to `processing` with a visibility deadline
 * in `next_attempt_at`, and a reaper returns rows whose worker died (deadline
 * passed while still `processing`) to `pending`.
 */
object WebhookRepo:

  val insertEndpoint: Command[(String, String, String, Boolean)] =
    sql"""
      insert into webhook_endpoints (id, url, secret, enabled)
      values ($text, $text, $text, $bool)
    """.command

  val listEndpoints: Query[Void, (String, String, Boolean, OffsetDateTime)] =
    sql"select id, url, enabled, created_at from webhook_endpoints order by created_at"
      .query(text *: text *: bool *: timestamptz)

  val deleteEndpoint: Query[String, String] =
    sql"delete from webhook_endpoints where id = $text returning id".query(text)

  val endpointById: Query[String, (String, String, Boolean)] =
    sql"select url, secret, enabled from webhook_endpoints where id = $text"
      .query(text *: text *: bool)

  /** Fan-out: one pending delivery per enabled endpoint carrying this payload. */
  val enqueue: Command[Json] =
    sql"""
      insert into webhook_deliveries (endpoint_id, event)
      select e.id, ${jsonb[Json]} from webhook_endpoints e where e.enabled
    """.command

  /** Returns rows abandoned mid-flight (still `processing` past their deadline). */
  val reapStuck: Command[Void] =
    sql"""
      update webhook_deliveries set status = 'pending'
      where status = 'processing' and next_attempt_at <= now()
    """.command

  /**
   * Atomically claims up to `limit` due deliveries: flips them to `processing`,
   * bumps `attempts`, and sets the visibility deadline. Params: deadline, limit.
   */
  val claimBatch: Query[(OffsetDateTime, Int), (UUID, String, Json, Int)] =
    sql"""
      update webhook_deliveries d
      set status = 'processing', attempts = d.attempts + 1, next_attempt_at = $timestamptz
      from (
        select id from webhook_deliveries
        where status = 'pending' and next_attempt_at <= now()
        order by next_attempt_at
        for update skip locked
        limit $int4
      ) picked
      where d.id = picked.id
      returning d.id, d.endpoint_id, d.event, d.attempts
    """.query(uuid *: text *: jsonb[Json] *: int4)

  val markDelivered: Command[UUID] =
    sql"update webhook_deliveries set status = 'delivered', last_error = null where id = $uuid".command

  /** Schedules a retry. Params: nextAttemptAt, error, id. */
  val reschedule: Command[(OffsetDateTime, String, UUID)] =
    sql"""
      update webhook_deliveries
      set status = 'pending', next_attempt_at = $timestamptz, last_error = $text
      where id = $uuid
    """.command

  /** Retries exhausted: the delivery becomes a dead letter. Params: error, id. */
  val markDead: Command[(String, UUID)] =
    sql"update webhook_deliveries set status = 'dead', last_error = $text where id = $uuid".command
