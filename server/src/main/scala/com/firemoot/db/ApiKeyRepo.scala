package com.firemoot.db

import java.time.OffsetDateTime

import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

/** Server API key/secret pairs with rotation (SPEC.md §5, M3.5). */
object ApiKeyRepo:

  /** The secret for a live (non-revoked) key. */
  val secretFor: Query[String, String] =
    sql"select secret from api_keys where id = $text and revoked_at is null".query(text)

  /** All keys (newest first); secrets are never returned here. */
  val list: Query[Void, (String, OffsetDateTime, Option[OffsetDateTime])] =
    sql"select id, created_at, revoked_at from api_keys order by created_at desc"
      .query(text *: timestamptz *: timestamptz.opt)

  val insert: Command[(String, String)] =
    sql"insert into api_keys (id, secret) values ($text, $text)".command

  val revoke: Query[String, String] =
    sql"update api_keys set revoked_at = now() where id = $text and revoked_at is null returning id"
      .query(text)
