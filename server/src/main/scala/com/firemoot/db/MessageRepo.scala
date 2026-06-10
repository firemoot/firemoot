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
