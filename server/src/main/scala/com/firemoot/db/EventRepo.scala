package com.firemoot.db

import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

/**
 * The `channel_events` replay log: written transactionally with each message,
 * read back on WebSocket resume (`seq > last_seen`). See SPEC.md §5.
 */
object EventRepo:

  val insert: Command[(String, Long, String, Json)] =
    sql"""
      insert into channel_events (cid, seq, type, payload)
      values ($text, $int8, $text, ${jsonb[Json]})
    """.command

  val since: Query[(String, Long), (Long, String, Json)] =
    sql"""
      select seq, type, payload
      from channel_events
      where cid = $text and seq > $int8
      order by seq
    """.query(int8 *: text *: jsonb[Json])
