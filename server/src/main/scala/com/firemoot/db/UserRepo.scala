package com.firemoot.db

import com.firemoot.domain.User
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

object UserRepo:

  private val columns: Fragment[Void] =
    sql"id, name, image, role, custom, created_at, updated_at, last_active_at, deleted_at"

  val upsert: Query[(String, Option[String], Option[String], String, Json), User] =
    sql"""
      insert into users (id, name, image, role, custom)
      values ($text, ${text.opt}, ${text.opt}, $text, ${jsonb[Json]})
      on conflict (id) do update set
        name = excluded.name,
        image = excluded.image,
        role = excluded.role,
        custom = excluded.custom,
        updated_at = now()
      returning $columns
    """.query(Codecs.user)

  val byId: Query[String, User] =
    sql"select $columns from users where id = $text".query(Codecs.user)

  val touchLastActive: Command[String] =
    sql"update users set last_active_at = now() where id = $text".command

  /**
   * GDPR scrub: erase the content of a user's authored messages while keeping the
   * rows (seq, reply_count, thread structure) intact, so channels stay consistent.
   */
  val scrubAuthoredMessages: Command[String] =
    sql"""
      update messages
      set text = null, custom = '{}'::jsonb, deleted_at = now()
      where user_id = $text and deleted_at is null
    """.command

  /**
   * Deletes the user, returning its id if it existed. Cascades remove the user's
   * memberships and reactions; the FK nulls `user_id` on their messages.
   */
  val deleteReturningId: Query[String, String] =
    sql"delete from users where id = $text returning id".query(text)
