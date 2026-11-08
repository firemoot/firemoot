package com.firemoot.db

import java.time.OffsetDateTime

import com.firemoot.domain.{Channel, Message, User}
import skunk.*
import skunk.codec.all.*
import skunk.data.Arr
import skunk.implicits.*

/** A channel membership row: (cid, userId, role, lastReadSeq, joinedAt). */
final case class MemberRow(
    cid: String,
    userId: String,
    role: String,
    lastReadSeq: Long,
    createdAt: OffsetDateTime,
)

/** A reaction row: (messageId, userId, type, createdAt). */
final case class ReactionRow(
    messageId: String,
    userId: String,
    `type`: String,
    createdAt: OffsetDateTime,
)

/**
 * Batch reads for building webhook bodies (unread reminders and the Stream
 * format). Each takes a `text[]` and matches with `= any(...)`, so one prepared
 * statement serves any batch size and the planner sees the real array size.
 */
object LookupRepo:

  val users: Query[Arr[String], User] =
    sql"""
      select id, name, image, role, custom, created_at, updated_at, last_active_at, deleted_at
      from users where id = any(${_text})
    """.query(Codecs.user)

  /** Channels by cid, soft-deleted ones included. */
  val channels: Query[Arr[String], Channel] =
    sql"""
      select cid, type, id, created_by, custom, frozen, archived, current_seq,
             last_message_at, created_at, updated_at, deleted_at
      from channels where cid = any(${_text})
    """.query(Codecs.channel)

  val members: Query[Arr[String], MemberRow] =
    sql"""
      select cid, user_id, role, last_read_seq, created_at
      from channel_members
      where cid = any(${_text})
      order by cid, created_at
    """.query((text *: text *: text *: int8 *: timestamptz).to[MemberRow])

  /** The newest `limit` live messages of each given channel, newest first. */
  val recentMessages: Query[(Arr[String], Int), Message] =
    sql"""
      select m.id, m.cid, m.seq, m.user_id, m.type, m.text, m.custom, m.attachments,
             m.parent_message_id, m.reply_count, m.created_at, m.updated_at, m.deleted_at
      from unnest(${_text}) as c(cid)
      cross join lateral (
        select * from messages
        where messages.cid = c.cid and messages.deleted_at is null
        order by messages.seq desc
        limit $int4
      ) m
      order by m.cid, m.seq desc
    """.query(Codecs.message)

  /** A message by id whether or not it is soft-deleted. */
  val message: Query[String, Message] =
    sql"""
      select id, cid, seq, user_id, type, text, custom, attachments,
             parent_message_id, reply_count, created_at, updated_at, deleted_at
      from messages where id = $text
    """.query(Codecs.message)

  /** A message's newest `limit` reactions, newest first. */
  val latestReactions: Query[(String, Int), ReactionRow] =
    sql"""
      select message_id, user_id, type, created_at
      from reactions where message_id = $text
      order by created_at desc
      limit $int4
    """.query((text *: text *: text *: timestamptz).to[ReactionRow])

  val reactionCounts: Query[String, (String, Long)] =
    sql"select type, count(*) from reactions where message_id = $text group by type"
      .query(text *: int8)

  val flagCount: Query[String, Long] =
    sql"select count(*) from message_flags where message_id = $text".query(int8)

  val memberCount: Query[String, Long] =
    sql"select count(*) from channel_members where cid = $text".query(int8)

  /** One membership. Params: cid, userId. */
  val member: Query[(String, String), MemberRow] =
    sql"""
      select cid, user_id, role, last_read_seq, created_at
      from channel_members where cid = $text and user_id = $text
    """.query((text *: text *: text *: int8 *: timestamptz).to[MemberRow])
