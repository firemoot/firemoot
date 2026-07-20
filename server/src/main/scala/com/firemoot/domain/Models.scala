package com.firemoot.domain

import java.time.OffsetDateTime

import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Json}
import sttp.tapir.Schema
import sttp.tapir.json.circe.given

final case class User(
    id: String,
    name: Option[String],
    image: Option[String],
    role: String,
    custom: Json,
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime,
    lastActiveAt: Option[OffsetDateTime],
    deletedAt: Option[OffsetDateTime],
)

object User:
  given Codec[User] = deriveCodec
  given Schema[User] = Schema.derived

final case class Channel(
    cid: String,
    `type`: String,
    id: String,
    createdBy: Option[String],
    custom: Json,
    frozen: Boolean,
    archived: Boolean,
    currentSeq: Long,
    lastMessageAt: Option[OffsetDateTime],
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime,
    deletedAt: Option[OffsetDateTime],
)

object Channel:
  given Codec[Channel] = deriveCodec
  given Schema[Channel] = Schema.derived

final case class Message(
    id: String,
    cid: String,
    seq: Long,
    userId: Option[String],
    `type`: String,
    text: Option[String],
    custom: Json,
    attachments: Json,
    parentMessageId: Option[String],
    replyCount: Int,
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime,
    deletedAt: Option[OffsetDateTime],
)

object Message:
  given Codec[Message] = deriveCodec
  given Schema[Message] = Schema.derived
