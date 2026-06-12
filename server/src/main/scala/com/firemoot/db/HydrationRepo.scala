package com.firemoot.db

import com.firemoot.domain.Message
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

/**
 * Batch lookups that hydrate the client-authenticated channel responses (M4.3):
 * per-channel members, latest message and the caller's unread count. Each takes
 * the full set of cids as a single jsonb array (expanded with
 * `jsonb_array_elements_text`), so one prepared statement serves a page of any
 * size without N+1 round-trips and without varying the statement shape.
 */
object HydrationRepo:

  private val messageColumns: Fragment[Void] =
    sql"""m.id, m.cid, m.seq, m.user_id, m.type, m.text, m.custom, m.attachments,
          m.parent_message_id, m.reply_count, m.created_at, m.updated_at, m.deleted_at"""

  /** (cid, userId, role, lastReadSeq) for every member of the given channels. */
  val members: Query[Json, (String, String, String, Long)] =
    sql"""
      select cid, user_id, role, last_read_seq
      from channel_members
      where cid in (select jsonb_array_elements_text($jsonb))
      order by cid, created_at
    """.query(text *: text *: text *: int8)

  /** The latest live message per channel (newest by seq), for previews. */
  val latestMessages: Query[Json, Message] =
    sql"""
      select distinct on (m.cid) $messageColumns
      from messages m
      where m.cid in (select jsonb_array_elements_text($jsonb))
        and m.deleted_at is null
      order by m.cid, m.seq desc
    """.query(Codecs.message)

  /**
   * (cid, unreadCount) for the caller across the given channels. Channels with no
   * unread messages are omitted (the caller defaults them to 0). Mirrors
   * [[ReadRepo.channelUnread]]: excludes the caller's own, system and deleted
   * messages. Params: caller, cids.
   */
  val callerUnread: Query[(String, Json), (String, Long)] =
    sql"""
      select m.cid, count(*)
      from messages m
      join channel_members cm on cm.cid = m.cid
      where cm.user_id = $text
        and m.cid in (select jsonb_array_elements_text($jsonb))
        and m.seq > cm.last_read_seq
        and m.user_id is distinct from cm.user_id
        and m.type <> 'system' and m.deleted_at is null
      group by m.cid
    """.query(text *: int8)
