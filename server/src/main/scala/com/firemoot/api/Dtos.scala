package com.firemoot.api

import java.time.OffsetDateTime
import java.util.UUID

import com.firemoot.domain.{Channel, Message}
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
    `type`: Option[String] = None,
)

object SendMessageRequest:
  given Codec[SendMessageRequest] = deriveCodec
  given Schema[SendMessageRequest] = Schema.derived

final case class EditMessageRequest(text: Option[String], custom: Option[Json])

object EditMessageRequest:
  given Codec[EditMessageRequest] = deriveCodec
  given Schema[EditMessageRequest] = Schema.derived

final case class AddReactionRequest(userId: String, `type`: String)

object AddReactionRequest:
  given Codec[AddReactionRequest] = deriveCodec
  given Schema[AddReactionRequest] = Schema.derived

/** A message's per-type reaction counts after a reaction change. */
final case class ReactionSummary(messageId: UUID, counts: Map[String, Long])

object ReactionSummary:
  given Codec[ReactionSummary] = deriveCodec
  given Schema[ReactionSummary] = Schema.derived

final case class MarkReadRequest(userId: String, seq: Option[Long])

object MarkReadRequest:
  given Codec[MarkReadRequest] = deriveCodec
  given Schema[MarkReadRequest] = Schema.derived

final case class ReadStateResponse(lastReadSeq: Long, unreadCount: Long, totalUnread: Long)

object ReadStateResponse:
  given Codec[ReadStateResponse] = deriveCodec
  given Schema[ReadStateResponse] = Schema.derived

/** Keyset cursor for channel paging: the sort timestamp and cid of the last row. */
final case class ChannelCursor(ts: OffsetDateTime, cid: String)

object ChannelCursor:
  given Codec[ChannelCursor] = deriveCodec
  given Schema[ChannelCursor] = Schema.derived

/**
 * A channel query (M1.9). All filters are optional and combine with AND:
 * `type` equality, `cids`/`members` membership ($in), `custom` jsonb containment,
 * `archived` flag. Results sort by most-recent activity; pass `cursor` (from a
 * previous page's `nextCursor`) to page.
 */
final case class ChannelQuery(
    `type`: Option[String],
    cids: Option[List[String]],
    members: Option[List[String]],
    custom: Option[Json],
    archived: Option[Boolean],
    limit: Option[Int],
    cursor: Option[ChannelCursor],
)

object ChannelQuery:
  given Codec[ChannelQuery] = deriveCodec
  given Schema[ChannelQuery] = Schema.derived

final case class ChannelPage(channels: List[Channel], nextCursor: Option[ChannelCursor])

object ChannelPage:
  given Codec[ChannelPage] = deriveCodec
  given Schema[ChannelPage] = Schema.derived

final case class MessagePage(messages: List[Message], nextBeforeSeq: Option[Long])

object MessagePage:
  given Codec[MessagePage] = deriveCodec
  given Schema[MessagePage] = Schema.derived

final case class SearchRequest(query: String, cid: Option[String], limit: Option[Int])

object SearchRequest:
  given Codec[SearchRequest] = deriveCodec
  given Schema[SearchRequest] = Schema.derived

/** A full-text search hit: the matching message and its relevance rank. */
final case class SearchHit(message: Message, score: Double)

object SearchHit:
  given Codec[SearchHit] = deriveCodec
  given Schema[SearchHit] = Schema.derived

final case class SearchPage(hits: List[SearchHit])

object SearchPage:
  given Codec[SearchPage] = deriveCodec
  given Schema[SearchPage] = Schema.derived

final case class CreateWebhookRequest(
    url: String,
    secret: Option[String],
    enabled: Option[Boolean],
)

object CreateWebhookRequest:
  given Codec[CreateWebhookRequest] = deriveCodec
  given Schema[CreateWebhookRequest] = Schema.derived

/** The created endpoint, including the signing secret (returned only here). */
final case class WebhookCreated(id: String, url: String, secret: String, enabled: Boolean)

object WebhookCreated:
  given Codec[WebhookCreated] = deriveCodec
  given Schema[WebhookCreated] = Schema.derived

final case class WebhookEndpoint(
    id: String,
    url: String,
    enabled: Boolean,
    createdAt: OffsetDateTime,
)

object WebhookEndpoint:
  given Codec[WebhookEndpoint] = deriveCodec
  given Schema[WebhookEndpoint] = Schema.derived

final case class FlagMessageRequest(userId: String, reason: Option[String])

object FlagMessageRequest:
  given Codec[FlagMessageRequest] = deriveCodec
  given Schema[FlagMessageRequest] = Schema.derived

/** A moderation flag against a message. `flaggedUser` is the message's author. */
final case class Flag(
    id: UUID,
    messageId: UUID,
    cid: String,
    flaggedUser: Option[String],
    flaggedBy: String,
    reason: Option[String],
    status: String,
    createdAt: OffsetDateTime,
)

object Flag:
  given Codec[Flag] = deriveCodec
  given Schema[Flag] = Schema.derived
