package com.firemoot.db

import com.firemoot.domain.Channel
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

object ChannelRepo:

  private val columns: Fragment[Void] =
    sql"""cid, type, id, created_by, custom, frozen, archived, current_seq,
          last_message_at, created_at, updated_at, deleted_at"""

  val insert: Query[(String, String, String, Option[String], Json), Channel] =
    sql"""
      insert into channels (cid, type, id, created_by, custom)
      values ($text, $text, $text, ${text.opt}, ${jsonb[Json]})
      returning $columns
    """.query(Codecs.channel)

  val byCid: Query[String, Channel] =
    sql"select $columns from channels where cid = $text".query(Codecs.channel)

  val addMember: Command[(String, String, String)] =
    sql"""
      insert into channel_members (cid, user_id, role)
      values ($text, $text, $text)
      on conflict (cid, user_id) do nothing
    """.command

  /**
   * Atomically allocates the next per-channel seq. Run inside the send-message
   * transaction so seq, message and event share one commit (SPEC.md §3).
   */
  val bumpSeq: Query[String, Long] =
    sql"""
      update channels
      set current_seq = current_seq + 1, last_message_at = now(), updated_at = now()
      where cid = $text
      returning current_seq
    """.query(int8)
