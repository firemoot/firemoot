package com.firemoot.db

import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

object ReactionRepo:

  /**
   * Adds a reaction, returning the user id only when newly added (idempotent on
   * the (message, user, type) primary key).
   */
  val add: Query[(String, String, String), String] =
    sql"""
      insert into reactions (message_id, user_id, type)
      values ($text, $text, $text)
      on conflict (message_id, user_id, type) do nothing
      returning user_id
    """.query(text)

  val remove: Query[(String, String, String), String] =
    sql"""
      delete from reactions
      where message_id = $text and user_id = $text and type = $text
      returning user_id
    """.query(text)

  /** Per-type reaction counts for a message. */
  val countsByType: Query[String, (String, Long)] =
    sql"select type, count(*) from reactions where message_id = $text group by type"
      .query(text *: int8)
