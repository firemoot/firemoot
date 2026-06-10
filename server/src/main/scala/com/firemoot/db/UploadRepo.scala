package com.firemoot.db

import java.util.UUID

import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

/** Upload lifecycle rows (SPEC.md §3, M2). */
object UploadRepo:

  /** Records a presigned upload as pending. Params: id, userId, objectKey, mime, size. */
  val insert: Command[(UUID, Option[String], String, String, Long)] =
    sql"""
      insert into uploads (id, user_id, object_key, mime, size_bytes)
      values ($uuid, ${text.opt}, $text, $text, $int8)
    """.command

  val statusOf: Query[UUID, String] =
    sql"select status from uploads where id = $uuid".query(text)

  /** Marks an upload stored (after the client confirms the PUT). */
  val markStored: Query[UUID, UUID] =
    sql"""
      update uploads set status = 'stored'
      where id = $uuid and status = 'pending'
      returning id
    """.query(uuid)

  /** Claims pending image uploads for thumbnailing (M2.3 worker). */
  val claimStoredImages: Query[Int, (UUID, String, String)] =
    sql"""
      select id, object_key, mime
      from uploads
      where status = 'stored' and mime like 'image/%'
      order by created_at
      limit $int4
    """.query(uuid *: text *: text)

  /** Records the generated thumbnail and advances to thumbnailed (M2.3). */
  val markThumbnailed: Command[(String, UUID)] =
    sql"update uploads set status = 'thumbnailed', thumb_key = $text where id = $uuid".command
