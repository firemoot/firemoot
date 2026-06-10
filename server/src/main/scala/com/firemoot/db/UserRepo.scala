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
