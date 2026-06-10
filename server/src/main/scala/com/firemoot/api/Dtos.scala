package com.firemoot.api

import java.util.UUID

import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Json}
import sttp.tapir.Schema
import sttp.tapir.json.circe.given

final case class UpsertUserRequest(
    id: String,
    name: Option[String],
    image: Option[String],
    role: Option[String],
    custom: Option[Json],
)

object UpsertUserRequest:
  given Codec[UpsertUserRequest] = deriveCodec
  given Schema[UpsertUserRequest] = Schema.derived

final case class CreateChannelRequest(
    `type`: String,
    id: String,
    createdBy: Option[String],
    custom: Option[Json],
)

object CreateChannelRequest:
  given Codec[CreateChannelRequest] = deriveCodec
  given Schema[CreateChannelRequest] = Schema.derived

final case class UpdateChannelRequest(
    custom: Option[Json],
    frozen: Option[Boolean],
    archived: Option[Boolean],
)

object UpdateChannelRequest:
  given Codec[UpdateChannelRequest] = deriveCodec
  given Schema[UpdateChannelRequest] = Schema.derived

final case class AddMemberRequest(userId: String, role: Option[String])

object AddMemberRequest:
  given Codec[AddMemberRequest] = deriveCodec
  given Schema[AddMemberRequest] = Schema.derived

final case class SendMessageRequest(
    userId: Option[String],
    text: Option[String],
    custom: Option[Json],
    attachments: Option[Json],
    parentMessageId: Option[UUID],
)

object SendMessageRequest:
  given Codec[SendMessageRequest] = deriveCodec
  given Schema[SendMessageRequest] = Schema.derived
