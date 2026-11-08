package com.firemoot.db

import java.time.OffsetDateTime

import com.firemoot.domain.{Channel, Message, User}
import skunk.*
import skunk.codec.all.*
import skunk.data.Arr
import skunk.implicits.*

/**
 * Unread-message reminders. A member is due when an unread message they have not
 * been reminded about - past both `last_read_seq` and `reminded_seq`, not their
 * own, not a system message, not deleted - has waited out the interval. Claiming
 * advances `reminded_seq` to the channel's `current_seq`, so a burst is reminded
 * once and a later message starts a fresh clock.
 */
object ReminderRepo:

  /**
   * Claims up to `limit` due (userId, cid) pairs. Only messages created after
   * `windowStart` count, so turning reminders on (or a long outage) never
   * replays reminders for old unread history. The update re-checks the unread
   * condition against the row it locks, so a read that commits mid-sweep
   * cancels the reminder rather than racing it.
   *
   * The plan is pinned on purpose. Postgres underestimates rows in a recent
   * `created_at` window (newer than its histogram), and left to itself picks a
   * hash join that seq-scans `messages` and `channel_members` - cost that grows
   * with all history. The materialized CTE walks `channels_activity_idx` for the
   * recently active channels, and the `limit 1` lateral forces a per-member
   * probe of `messages (cid, seq)`, so a sweep costs in proportion to recent
   * activity instead.
   *
   * Params: windowStart, maxMembers, windowStart, dueBefore, limit.
   */
  val claimDue
      : Query[(OffsetDateTime, Int, OffsetDateTime, OffsetDateTime, Int), (String, String)] =
    sql"""
      with recent as materialized (
        select c.cid, c.current_seq
        from channels c
        where coalesce(c.last_message_at, c.created_at) > $timestamptz
          and c.deleted_at is null
          and (select count(*) from channel_members x where x.cid = c.cid) <= $int4
      ),
      due as (
        select cm.cid, cm.user_id
        from recent c
        join channel_members cm on cm.cid = c.cid
        cross join lateral (
          select 1 from messages m
          where m.cid = cm.cid
            and m.seq > greatest(cm.last_read_seq, cm.reminded_seq)
            and m.user_id is distinct from cm.user_id
            and m.type <> 'system' and m.deleted_at is null
            and m.created_at > $timestamptz
            and m.created_at <= $timestamptz
          limit 1
        ) unread
        where c.current_seq > greatest(cm.last_read_seq, cm.reminded_seq)
        limit $int4
      )
      update channel_members cm
      set reminded_seq = c.current_seq
      from due, channels c
      where cm.cid = due.cid and cm.user_id = due.user_id and c.cid = cm.cid
        and c.current_seq > greatest(cm.last_read_seq, cm.reminded_seq)
      returning cm.user_id, cm.cid
    """.query(text *: text)

  val users: Query[Arr[String], User] =
    sql"""
      select id, name, image, role, custom, created_at, updated_at, last_active_at, deleted_at
      from users where id = any(${_text})
    """.query(Codecs.user)

  val channels: Query[Arr[String], Channel] =
    sql"""
      select cid, type, id, created_by, custom, frozen, archived, current_seq,
             last_message_at, created_at, updated_at, deleted_at
      from channels where cid = any(${_text})
    """.query(Codecs.channel)

  /** (cid, userId, role, lastReadSeq) for every member of the given channels. */
  val members: Query[Arr[String], (String, String, String, Long)] =
    sql"""
      select cid, user_id, role, last_read_seq
      from channel_members
      where cid = any(${_text})
      order by cid, created_at
    """.query(text *: text *: text *: int8)

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
