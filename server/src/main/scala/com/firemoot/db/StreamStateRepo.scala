package com.firemoot.db

import java.time.OffsetDateTime

import com.firemoot.domain.Message
import skunk.*
import skunk.codec.all.*
import skunk.data.Arr
import skunk.implicits.*

/**
 * Batch reads behind getstream.io's channel-state responses (the v2 wire API):
 * channel message pages, per-member read rows, and per-message reactions. Pages
 * hold channel-level messages only (thread replies stay in their thread) and
 * include soft-deleted ones, which Stream returns as `type: "deleted"`.
 */
object StreamStateRepo:

  private val columns: Fragment[Void] =
    sql"""m.id, m.cid, m.seq, m.user_id, m.type, m.text, m.custom, m.attachments,
          m.parent_message_id, m.reply_count, m.created_at, m.updated_at, m.deleted_at"""

  /** The newest `limit` channel-level messages of each channel, newest first. */
  val newestPerChannel: Query[(Arr[String], Int), Message] =
    sql"""
      select $columns
      from unnest(${_text}) as c(cid)
      cross join lateral (
        select * from messages
        where messages.cid = c.cid and messages.parent_message_id is null
        order by messages.seq desc
        limit $int4
      ) m
      order by m.cid, m.seq desc
    """.query(Codecs.message)

  /** Channel-level messages strictly older than `seq`, newest first. Params: cid, seq, limit. */
  val before: Query[(String, Long, Int), Message] =
    sql"""
      select $columns from messages m
      where m.cid = $text and m.parent_message_id is null and m.seq < $int8
      order by m.seq desc
      limit $int4
    """.query(Codecs.message)

  /** Channel-level messages strictly newer than `seq`, oldest first. Params: cid, seq, limit. */
  val after: Query[(String, Long, Int), Message] =
    sql"""
      select $columns from messages m
      where m.cid = $text and m.parent_message_id is null and m.seq > $int8
      order by m.seq asc
      limit $int4
    """.query(Codecs.message)

  /** A message's seq within its channel. Params: cid, message id. */
  val seqOf: Query[(String, String), Long] =
    sql"select seq from messages where cid = $text and id = $text".query(int8)

  /** The seq of the newest message created at or before a time. Params: cid, time. */
  val seqAtOrBefore: Query[(String, OffsetDateTime), Long] =
    sql"""
      select coalesce(max(seq), 0) from messages
      where cid = $text and parent_message_id is null and created_at <= $timestamptz
    """.query(int8)

  /**
   * One read row per member: the newest message at or before `last_read_seq`
   * (its id and time stand in for Stream's `last_read_message_id` and
   * `last_read`; the membership time when nothing has been read), and the unread
   * count, capped at 1000 like the total badge.
   */
  val readRows: Query[
    Arr[String],
    (String, String, Option[String], Option[OffsetDateTime], OffsetDateTime, Long),
  ] =
    sql"""
      select cm.cid, cm.user_id, last_read.id, last_read.created_at, cm.created_at,
             (select count(*) from (
                select 1 from messages m
                where m.cid = cm.cid and m.seq > cm.last_read_seq
                  and m.user_id is distinct from cm.user_id
                  and m.type <> 'system' and m.deleted_at is null
                limit 1000
              ) unread)
      from channel_members cm
      left join lateral (
        select m.id, m.created_at from messages m
        where m.cid = cm.cid and m.seq <= cm.last_read_seq and cm.last_read_seq > 0
        order by m.seq desc
        limit 1
      ) last_read on true
      where cm.cid = any(${_text})
    """.query(text *: text *: text.opt *: timestamptz.opt *: timestamptz *: int8)

  val memberCounts: Query[Arr[String], (String, Long)] =
    sql"""
      select cid, count(*) from channel_members where cid = any(${_text}) group by cid
    """.query(text *: int8)

  val reactionCounts: Query[Arr[String], (String, String, Long)] =
    sql"""
      select message_id, type, count(*) from reactions
      where message_id = any(${_text})
      group by message_id, type
    """.query(text *: text *: int8)

  /** Each message's newest `limit` reactions. */
  val latestReactions: Query[(Arr[String], Int), ReactionRow] =
    sql"""
      select r.message_id, r.user_id, r.type, r.created_at
      from unnest(${_text}) as m(id)
      cross join lateral (
        select * from reactions where reactions.message_id = m.id
        order by reactions.created_at desc
        limit $int4
      ) r
    """.query((text *: text *: text *: timestamptz).to[ReactionRow])

  /** A user's own reactions on the given messages. Params: message ids, user. */
  val ownReactions: Query[(Arr[String], String), ReactionRow] =
    sql"""
      select message_id, user_id, type, created_at from reactions
      where message_id = any(${_text}) and user_id = $text
    """.query((text *: text *: text *: timestamptz).to[ReactionRow])

  /** How many of a user's channels have an unread message (capped at 1000). */
  val unreadChannels: Query[String, Long] =
    sql"""
      select count(*) from (
        select 1 from channel_members cm
        where cm.user_id = $text
          and exists (
            select 1 from messages m
            where m.cid = cm.cid and m.seq > cm.last_read_seq
              and m.user_id is distinct from cm.user_id
              and m.type <> 'system' and m.deleted_at is null
          )
        limit 1000
      ) unread
    """.query(int8)

  /** A page of users by creation time. Params: limit, offset. */
  val usersNewestFirst: Query[(Int, Int), com.firemoot.domain.User] =
    sql"""
      select id, name, image, role, custom, created_at, updated_at, last_active_at, deleted_at
      from users order by created_at desc, id limit $int4 offset $int4
    """.query(Codecs.user)

  /** A page of users by creation time, oldest first. Params: limit, offset. */
  val usersOldestFirst: Query[(Int, Int), com.firemoot.domain.User] =
    sql"""
      select id, name, image, role, custom, created_at, updated_at, last_active_at, deleted_at
      from users order by created_at asc, id limit $int4 offset $int4
    """.query(Codecs.user)
