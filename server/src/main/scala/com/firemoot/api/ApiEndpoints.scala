package com.firemoot.api

import java.nio.charset.StandardCharsets.UTF_8

import com.firemoot.domain.{Channel, Message, User}
import sttp.model.{MediaType, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
 * tapir endpoint definitions - the source of truth for both the http4s routes
 * and the generated `@firemoot/core` TypeScript SDK (M0.8). Server-SDK
 * authentication is enforced by [[com.firemoot.auth.ServerHmacAuth]] middleware,
 * not here, so the signature can cover the request body.
 */
object ApiEndpoints:

  private val problemFormat: CodecFormat = new CodecFormat:
    override val mediaType: MediaType = MediaType("application", "problem+json")

  private val problemBody: EndpointIO.Body[String, Problem] =
    stringBodyAnyFormat(summon[Codec.JsonCodec[Problem]].format(problemFormat), UTF_8)

  /**
   * Error output: a single `Problem` as `application/problem+json` (RFC 9457),
   * with the HTTP status derived from `Problem.status`.
   */
  private val problemOut: EndpointOutput[Problem] =
    statusCode.and(problemBody).map(_._2)(p => (StatusCode(p.status), p))

  private val base = endpoint.errorOut(problemOut).in("v1")

  val upsertUser =
    base.post
      .in("users")
      .in(jsonBody[UpsertUserRequest])
      .out(jsonBody[User])
      .summary("Create or update a user")

  val deleteUser =
    base.delete
      .in("users" / path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .summary("Delete a user (GDPR hard-delete)")

  val createChannel =
    base.post
      .in("channels")
      .in(jsonBody[CreateChannelRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Channel])
      .summary("Create a channel")

  private val channelPath = "channels" / path[String]("type") / path[String]("id")

  val getChannel =
    base.get.in(channelPath).out(jsonBody[Channel]).summary("Get a channel")

  val updateChannel =
    base.patch
      .in(channelPath)
      .in(jsonBody[UpdateChannelRequest])
      .out(jsonBody[Channel])
      .summary("Update a channel (custom data, frozen, archived)")

  val deleteChannel =
    base.delete
      .in(channelPath)
      .out(statusCode(StatusCode.NoContent))
      .summary("Delete a channel (soft)")

  val addMember =
    base.post
      .in(channelPath / "members")
      .in(jsonBody[AddMemberRequest])
      .out(statusCode(StatusCode.NoContent))
      .summary("Add a member to a channel")

  val removeMember =
    base.delete
      .in(channelPath / "members" / path[String]("userId"))
      .out(statusCode(StatusCode.NoContent))
      .summary("Remove a member from a channel")

  val sendMessage =
    base.post
      .in(channelPath / "messages")
      .in(jsonBody[SendMessageRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Message])
      .summary("Send a message to a channel")

  val editMessage =
    base.patch
      .in(channelPath / "messages" / path[java.util.UUID]("messageId"))
      .in(jsonBody[EditMessageRequest])
      .out(jsonBody[Message])
      .summary("Edit a message")

  val deleteMessage =
    base.delete
      .in(channelPath / "messages" / path[java.util.UUID]("messageId"))
      .out(statusCode(StatusCode.NoContent))
      .summary("Delete a message (soft)")

  private val messagePath = channelPath / "messages" / path[java.util.UUID]("messageId")

  val addReaction =
    base.post
      .in(messagePath / "reactions")
      .in(jsonBody[AddReactionRequest])
      .out(jsonBody[ReactionSummary])
      .summary("Add a reaction to a message")

  val removeReaction =
    base.delete
      .in(messagePath / "reactions" / path[String]("reactionType") / path[String]("userId"))
      .out(jsonBody[ReactionSummary])
      .summary("Remove a user's reaction from a message")

  val markRead =
    base.post
      .in(channelPath / "read")
      .in(jsonBody[MarkReadRequest])
      .out(jsonBody[ReadStateResponse])
      .summary("Mark a channel read up to a seq (default: latest)")

  val all: List[AnyEndpoint] = List(
    upsertUser,
    deleteUser,
    createChannel,
    getChannel,
    updateChannel,
    deleteChannel,
    addMember,
    removeMember,
    sendMessage,
    editMessage,
    deleteMessage,
    addReaction,
    removeReaction,
    markRead,
  )
