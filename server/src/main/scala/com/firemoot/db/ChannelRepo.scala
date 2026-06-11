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
    sql"select $columns from channels where cid = $text and deleted_at is null".query(
      Codecs.channel
    )

  /** (frozen, deleted) flags - turns a failed seq bump into a precise error. */
  val statusOf: Query[String, (Boolean, Boolean)] =
    sql"select frozen, (deleted_at is not null) from channels where cid = $text"
      .query(bool *: bool)

  val currentSeq: Query[String, Long] =
    sql"select current_seq from channels where cid = $text and deleted_at is null".query(int8)

  /** Partial update via COALESCE; returns the channel, or none if missing/deleted. */
  val update: Query[(Option[Json], Option[Boolean], Option[Boolean], String), Channel] =
    sql"""
      update channels set
        custom = coalesce(${jsonb[Json].opt}, custom),
        frozen = coalesce(${bool.opt}, frozen),
        archived = coalesce(${bool.opt}, archived),
        updated_at = now()
      where cid = $text and deleted_at is null
      returning $columns
    """.query(Codecs.channel)

  val softDelete: Query[String, String] =
    sql"update channels set deleted_at = now() where cid = $text and deleted_at is null returning cid"
      .query(text)

  /**
   * Inserts a membership, returning the user id only when newly added (so
   * `member.added` is emitted once, not on a repeat add).
   */
  val addMember: Query[(String, String, String), String] =
    sql"""
      insert into channel_members (cid, user_id, role)
      values ($text, $text, $text)
      on conflict (cid, user_id) do nothing
      returning user_id
    """.query(text)

  val removeMember: Query[(String, String), String] =
    sql"delete from channel_members where cid = $text and user_id = $text returning user_id"
      .query(text)

  /** The member's role in the channel, or none if they are not a member. */
  val memberRole: Query[(String, String), String] =
    sql"select role from channel_members where cid = $text and user_id = $text".query(text)

  /**
   * Allocates the next per-channel seq for a message and advances
   * `last_message_at`. Returns none when the channel is frozen, deleted or absent
   * (SPEC.md §3) - the caller turns that into a 409/404.
   */
  val bumpSeqForMessage: Query[String, Long] =
    sql"""
      update channels
      set current_seq = current_seq + 1, last_message_at = now(), updated_at = now()
      where cid = $text and not frozen and deleted_at is null
      returning current_seq
    """.query(int8)

  /**
   * Allocates the next per-channel seq for a non-message event (no
   * `last_message_at` bump). Run inside the emitting transaction.
   */
  val bumpSeq: Query[String, Long] =
    sql"""
      update channels
      set current_seq = current_seq + 1, updated_at = now()
      where cid = $text
      returning current_seq
    """.query(int8)
