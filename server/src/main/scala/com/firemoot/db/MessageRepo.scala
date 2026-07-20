package com.firemoot.db

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
        (String, String, Long, Option[String], String, Option[String], Json, Json, Option[String]),
        Message,
      ] =
    sql"""
      insert into messages
        (id, cid, seq, user_id, type, text, custom, attachments, parent_message_id)
      values
        ($text, $text, $int8, ${text.opt}, $text, ${text.opt}, ${jsonb[Json]}, ${jsonb[
        Json
      ]}, ${text.opt})
      returning $columns
    """.query(Codecs.message)

  val byId: Query[String, Message] =
    sql"select $columns from messages where id = $text".query(Codecs.message)

  /**
   * Edits text/custom (COALESCE keeps unspecified fields). Scoped by cid; returns
   * none if the message is missing, in another channel, or already deleted.
   */
  val update: Query[(Option[String], Option[Json], String, String), Message] =
    sql"""
      update messages set
        text = coalesce(${text.opt}, text),
        custom = coalesce(${jsonb[Json].opt}, custom),
        updated_at = now()
      where id = $text and cid = $text and deleted_at is null
      returning $columns
    """.query(Codecs.message)

  /**
   * Soft-deletes and scrubs the text; returns the message's parent id (if any) so
   * the caller can decrement the thread's `reply_count`. None = nothing deleted.
   */
  val softDelete: Query[(String, String), Option[String]] =
    sql"""
      update messages set deleted_at = now(), text = null
      where id = $text and cid = $text and deleted_at is null
      returning parent_message_id
    """.query(text.opt)

  val incrementReplyCount: Command[String] =
    sql"update messages set reply_count = reply_count + 1 where id = $text".command

  val decrementReplyCount: Command[String] =
    sql"update messages set reply_count = greatest(reply_count - 1, 0) where id = $text".command

  /** Returns the id if a live message with that id exists in the channel. */
  val existsInChannel: Query[(String, String), String] =
    sql"select id from messages where id = $text and cid = $text and deleted_at is null".query(text)

  /** The cid of a live message by its id (for the channel-less global delete). */
  val channelOf: Query[String, String] =
    sql"select cid from messages where id = $text and deleted_at is null".query(text)

  /**
   * The author of a live message in the channel: outer none = no such message,
   * inner none = author scrubbed (GDPR). Used to authorise edit/delete.
   */
  val authorInChannel: Query[(String, String), Option[String]] =
    sql"select user_id from messages where id = $text and cid = $text and deleted_at is null"
      .query(text.opt)

  /**
   * Live messages whose attachments contain (jsonb `@>`) the given fragment -
   * used to find the message(s) referencing a freshly-thumbnailed upload.
   */
  val withAttachment: Query[Json, (String, String, Json)] =
    sql"""
      select id, cid, attachments from messages
      where deleted_at is null and attachments @> ${jsonb[Json]}
    """.query(text *: text *: jsonb[Json])

  /** Replaces a message's attachments, returning the updated row. */
  val setAttachments: Query[(Json, String), Message] =
    sql"""
      update messages set attachments = ${jsonb[Json]}, updated_at = now()
      where id = $text
      returning $columns
    """.query(Codecs.message)
