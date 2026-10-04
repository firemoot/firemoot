package com.firemoot.db

import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

/**
 * Read-state and unread-count queries (SPEC.md §5). `is distinct from
 * cm.user_id` excludes the viewer's own messages while still counting
 * null-authored (GDPR-scrubbed) messages.
 */
object ReadRepo:

  /**
   * Advances (never rewinds) the member's read pointer; returns the new value,
   * or none if the user is not a member.
   */
  val markRead: Query[(Long, String, String), Long] =
    sql"""
      update channel_members set last_read_seq = greatest(last_read_seq, $int8)
      where cid = $text and user_id = $text
      returning last_read_seq
    """.query(int8)

  /** Unread count for (viewer, channel). 0 if the viewer is not a member. */
  val channelUnread: Query[(String, String), Long] =
    sql"""
      select count(*)
      from messages m
      join channel_members cm on cm.cid = m.cid
      where cm.user_id = $text and m.cid = $text
        and m.seq > cm.last_read_seq
        and m.user_id is distinct from cm.user_id
        and m.type <> 'system' and m.deleted_at is null
    """.query(int8)

  /** The badge count saturates here: a total of 1000 means "1000 or more". */
  val TotalUnreadCap: Long = 1000

  /**
   * Total unread across all the viewer's channels (the badge count), capped at
   * the given limit. Uncapped, this is a hash join over every message in every
   * channel the viewer belongs to - cost grows with all history, and runs on
   * each WS connect and markRead. The limit lets Postgres stop early for a
   * member of thousands of unread channels.
   */
  val totalUnread: Query[(String, Long), Long] =
    sql"""
      select count(*) from (
        select
        from messages m
        join channel_members cm on cm.cid = m.cid
        where cm.user_id = $text
          and m.seq > cm.last_read_seq
          and m.user_id is distinct from cm.user_id
          and m.type <> 'system' and m.deleted_at is null
        limit $int8
      ) unread
    """.query(int8)
