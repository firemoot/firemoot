package com.firemoot.db

import java.util.UUID

import com.firemoot.domain.Message
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

object MessageRepo:

  private val columns: Fragment[Void] =
    sql"""id, cid, seq, user_id, type, text, custom, attachments,
          parent_message_id, reply_count, created_at, updated_at, deleted_at"""

  val insert
      : Query[
        (UUID, String, Long, Option[String], String, Option[String], Json, Json, Option[UUID]),
        Message,
      ] =
    sql"""
      insert into messages
        (id, cid, seq, user_id, type, text, custom, attachments, parent_message_id)
      values
        ($uuid, $text, $int8, ${text.opt}, $text, ${text.opt}, ${jsonb[Json]}, ${jsonb[
        Json
      ]}, ${uuid.opt})
      returning $columns
    """.query(Codecs.message)

  val byId: Query[UUID, Message] =
    sql"select $columns from messages where id = $uuid".query(Codecs.message)

  /**
   * Edits text/custom (COALESCE keeps unspecified fields). Scoped by cid; returns
   * none if the message is missing, in another channel, or already deleted.
   */
  val update: Query[(Option[String], Option[Json], UUID, String), Message] =
    sql"""
      update messages set
        text = coalesce(${text.opt}, text),
        custom = coalesce(${jsonb[Json].opt}, custom),
        updated_at = now()
      where id = $uuid and cid = $text and deleted_at is null
      returning $columns
    """.query(Codecs.message)

  /**
   * Soft-deletes and scrubs the text; returns the message's parent id (if any) so
   * the caller can decrement the thread's `reply_count`. None = nothing deleted.
   */
  val softDelete: Query[(UUID, String), Option[UUID]] =
    sql"""
      update messages set deleted_at = now(), text = null
      where id = $uuid and cid = $text and deleted_at is null
      returning parent_message_id
    """.query(uuid.opt)

  val incrementReplyCount: Command[UUID] =
    sql"update messages set reply_count = reply_count + 1 where id = $uuid".command

  val decrementReplyCount: Command[UUID] =
    sql"update messages set reply_count = greatest(reply_count - 1, 0) where id = $uuid".command

  /** Returns the id if a live message with that id exists in the channel. */
  val existsInChannel: Query[(UUID, String), UUID] =
    sql"select id from messages where id = $uuid and cid = $text and deleted_at is null".query(uuid)

  /**
   * Live messages whose attachments contain (jsonb `@>`) the given fragment -
   * used to find the message(s) referencing a freshly-thumbnailed upload.
   */
  val withAttachment: Query[Json, (UUID, String, Json)] =
    sql"""
      select id, cid, attachments from messages
      where deleted_at is null and attachments @> ${jsonb[Json]}
    """.query(uuid *: text *: jsonb[Json])

  /** Replaces a message's attachments, returning the updated row. */
  val setAttachments: Query[(Json, UUID), Message] =
    sql"""
      update messages set attachments = ${jsonb[Json]}, updated_at = now()
      where id = $uuid
      returning $columns
    """.query(Codecs.message)
