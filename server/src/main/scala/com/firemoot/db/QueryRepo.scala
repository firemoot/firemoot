package com.firemoot.db

import java.time.OffsetDateTime

import com.firemoot.domain.{Channel, Message}
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

/**
 * The read-only query surface (SPEC.md §5, M1.9): channel filtering with keyset
 * pagination, message history, and full-text search.
 *
 * Injection-safe by construction: every user-supplied value is a bound
 * parameter and the statement shape is fixed regardless of input - there is no
 * dynamic SQL assembly. List filters (`cid $in`, `members $in`) are passed as a
 * single jsonb array parameter and expanded with `jsonb_array_elements_text`;
 * custom-field equality uses jsonb containment (`@>`). A filter bound to NULL
 * means "no constraint", so one prepared statement serves every filter
 * combination. Identifiers are never interpolated.
 */
object QueryRepo:

  private val channelColumns: Fragment[Void] =
    sql"""c.cid, c.type, c.id, c.created_by, c.custom, c.frozen, c.archived,
          c.current_seq, c.last_message_at, c.created_at, c.updated_at, c.deleted_at"""

  private val messageColumns: Fragment[Void] =
    sql"""m.id, m.cid, m.seq, m.user_id, m.type, m.text, m.custom, m.attachments,
          m.parent_message_id, m.reply_count, m.created_at, m.updated_at, m.deleted_at"""

  /**
   * Filtered channels, newest activity first, with keyset (cursor) pagination.
   * Sort key is `coalesce(last_message_at, created_at)` so it is never null and
   * the row-value cursor comparison is total. Params, in order: type, cids,
   * members, custom, archived, cursorTs, cursorCid, limit.
   */
  val channels: Query[
    (
        Option[String],
        Option[Json],
        Option[Json],
        Option[Json],
        Option[Boolean],
        Option[OffsetDateTime],
        Option[String],
        Int,
    ),
    Channel,
  ] =
    sql"""
      with q as (
        select
          ${text.opt}        as f_type,
          ${jsonb[Json].opt} as f_cids,
          ${jsonb[Json].opt} as f_members,
          ${jsonb[Json].opt} as f_custom,
          ${bool.opt}        as f_archived,
          ${timestamptz.opt} as f_cursor_ts,
          ${text.opt}        as f_cursor_cid
      )
      select $channelColumns
      from channels c, q
      where c.deleted_at is null
        and (q.f_type is null or c.type = q.f_type)
        and (q.f_cids is null
             or c.cid in (select jsonb_array_elements_text(q.f_cids)))
        and (q.f_members is null
             or exists (select 1 from channel_members m
                        where m.cid = c.cid
                          and m.user_id in (select jsonb_array_elements_text(q.f_members))))
        and (q.f_custom is null or c.custom @> q.f_custom)
        and (q.f_archived is null or c.archived = q.f_archived)
        and (q.f_cursor_ts is null
             or (coalesce(c.last_message_at, c.created_at), c.cid)
                < (q.f_cursor_ts, q.f_cursor_cid))
      order by coalesce(c.last_message_at, c.created_at) desc, c.cid desc
      limit $int4
    """.query(Codecs.channel)

  /**
   * The seq of a message by its id within a channel, for resolving a `before_id`
   * pagination cursor. Ignores `deleted_at` so a tombstoned cursor message still
   * resolves and pagination stays stable. Params: cid, messageId.
   */
  val messageSeqById: Query[(String, java.util.UUID), Long] =
    sql"""
      select m.seq
      from messages m
      where m.cid = $text and m.id = $uuid
      limit 1
    """.query(int8)

  /**
   * A channel's live messages, newest first, paginated by seq. `beforeSeq` NULL
   * starts from the latest. Params: cid, beforeSeq, limit.
   */
  val messageHistory: Query[(String, Option[Long], Int), Message] =
    sql"""
      select $messageColumns
      from messages m
      where m.cid = $text and m.deleted_at is null
        and m.seq < coalesce(${int8.opt}, 9223372036854775807)
      order by m.seq desc
      limit $int4
    """.query(Codecs.message)

  /**
   * Full-text search over live regular messages, ranked. Uses the `simple`
   * configuration (matching the stored `text_search` vector) and websearch
   * query syntax. Params: query, cid filter, limit.
   */
  val search: Query[(String, Option[String], Int), (Message, Float)] =
    sql"""
      with q as (
        select websearch_to_tsquery('simple', $text) as tsq, ${text.opt} as f_cid
      )
      select $messageColumns, ts_rank(m.text_search, q.tsq) as rank
      from messages m, q
      where m.deleted_at is null and m.type = 'regular'
        and m.text_search @@ q.tsq
        and (q.f_cid is null or m.cid = q.f_cid)
      order by rank desc, m.seq desc
      limit $int4
    """.query(Codecs.message *: float4)

  /**
   * Member-scoped search: as [[search]], but results are restricted to channels
   * the member belongs to (the client-authenticated surface). Params: query, cid
   * filter, member, limit.
   */
  val searchAsMember: Query[(String, Option[String], String, Int), (Message, Float)] =
    sql"""
      with q as (
        select websearch_to_tsquery('simple', $text) as tsq,
               ${text.opt} as f_cid, $text as f_member
      )
      select $messageColumns, ts_rank(m.text_search, q.tsq) as rank
      from messages m, q
      where m.deleted_at is null and m.type = 'regular'
        and m.text_search @@ q.tsq
        and (q.f_cid is null or m.cid = q.f_cid)
        and exists (select 1 from channel_members cm
                    where cm.cid = m.cid and cm.user_id = q.f_member)
      order by rank desc, m.seq desc
      limit $int4
    """.query(Codecs.message *: float4)
