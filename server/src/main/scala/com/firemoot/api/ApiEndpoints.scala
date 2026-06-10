package com.firemoot.api

import com.firemoot.domain.{Channel, Message, User}
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.*

/**
 * tapir endpoint definitions - the source of truth for both the http4s routes
 * and the generated `@firemoot/core` TypeScript SDK (M0.8).
 */
object ApiEndpoints:

  /**
   * Error output: a single `Problem`, with the HTTP status derived from
   * `Problem.status` (so the body and the status line always agree).
   */
  private val problemOut: EndpointOutput[Problem] =
    statusCode.and(jsonBody[Problem]).map(_._2)(p => (StatusCode(p.status), p))

  private val secured =
    endpoint
      .securityIn(auth.apiKey(header[String]("X-Firemoot-Key")))
      .errorOut(problemOut)
      .in("v1")

  val upsertUser =
    secured.post
      .in("users")
      .in(jsonBody[UpsertUserRequest])
      .out(jsonBody[User])
      .summary("Create or update a user")

  val createChannel =
    secured.post
      .in("channels")
      .in(jsonBody[CreateChannelRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Channel])
      .summary("Create a channel")

  val sendMessage =
    secured.post
      .in("channels" / path[String]("type") / path[String]("id") / "messages")
      .in(jsonBody[SendMessageRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[Message])
      .summary("Send a message to a channel")

  val all: List[AnyEndpoint] = List(upsertUser, createChannel, sendMessage)
