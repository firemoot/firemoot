package com.firemoot.db

import java.time.OffsetDateTime
import java.util.UUID

import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

/** Moderation flags (SPEC.md §3, M1.11). */
object ModerationRepo:

  /**
   * The author of a live message in the channel, if it exists. The outer option
   * distinguishes "no such message" (none) from "message exists, author scrubbed"
   * (some none); the inner is the nullable `user_id`.
   */
  val flaggableAuthor: Query[(UUID, String), Option[String]] =
    sql"""
      select user_id from messages
      where id = $uuid and cid = $text and deleted_at is null
    """.query(text.opt)

  /** Inserts a flag, returning (id, status, created_at). Params: messageId, cid, flaggedBy, reason. */
  val insert: Query[(UUID, String, String, Option[String]), (UUID, String, OffsetDateTime)] =
    sql"""
      insert into message_flags (message_id, cid, flagged_by, reason)
      values ($uuid, $text, $text, ${text.opt})
      returning id, status, created_at
    """.query(uuid *: text *: timestamptz)

  val listByStatus: Query[
    String,
    (UUID, UUID, String, Option[String], String, Option[String], String, OffsetDateTime),
  ] =
    sql"""
      select f.id, f.message_id, f.cid, m.user_id, f.flagged_by, f.reason, f.status, f.created_at
      from message_flags f
      join messages m on m.id = f.message_id
      where f.status = $text
      order by f.created_at desc
    """.query(uuid *: uuid *: text *: text.opt *: text *: text.opt *: text *: timestamptz)
