package com.firemoot.db

import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

/** Key/value install settings (admin password hash, install metadata; M3.4). */
object SettingsRepo:

  val get: Query[String, Json] =
    sql"select value from settings where key = $text".query(jsonb[Json])

  val upsert: Command[(String, Json)] =
    sql"""
      insert into settings (key, value) values ($text, ${jsonb[Json]})
      on conflict (key) do update set value = excluded.value
    """.command
