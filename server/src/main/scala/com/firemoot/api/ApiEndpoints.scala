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

  val sendMessage =
    base.post
      .in("channels" / path[String]("type") / path[String]("id") / "messages")
      .in(jsonBody[SendMessageRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Message])
      .summary("Send a message to a channel")

  val all: List[AnyEndpoint] = List(upsertUser, deleteUser, createChannel, sendMessage)
