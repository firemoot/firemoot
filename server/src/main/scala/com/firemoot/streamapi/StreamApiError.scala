package com.firemoot.streamapi

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.circe.*
import org.http4s.{Response, Status}

/**
 * getstream.io's `APIError`. The SDKs wrap a failure as a Stream error only when
 * the body carries a numeric `code`, and branch on that code (40 reloads an
 * expired token, 4 + "already exists" marks a duplicate send as delivered). The
 * status goes out under both `StatusCode` (the spec) and `status_code` (the
 * generated models), since the two disagree.
 */
final case class StreamApiError(status: Int, code: Int, message: String):

  def json: Json =
    Json.obj(
      "code" -> code.asJson,
      "message" -> message.asJson,
      "StatusCode" -> status.asJson,
      "status_code" -> status.asJson,
      "duration" -> "0.00ms".asJson,
      "more_info" -> "https://getstream.io/chat/docs/api_errors_response".asJson,
      "details" -> Json.arr(),
    )

  def response: Response[IO] =
    Response[IO](Status.fromInt(status).getOrElse(Status.InternalServerError)).withEntity(json)

object StreamApiError:
  def input(op: String, detail: String): StreamApiError =
    StreamApiError(400, 4, s"$op failed with error: \"$detail\"")

  def accessKey: StreamApiError =
    StreamApiError(401, 2, "api_key not found")

  def authFailed(detail: String): StreamApiError =
    StreamApiError(401, 5, detail)

  def tokenExpired: StreamApiError =
    StreamApiError(401, 40, "JWTAuth error: token has expired")

  def tokenNotValidYet: StreamApiError =
    StreamApiError(401, 41, "JWTAuth error: token is not valid yet")

  def signatureInvalid: StreamApiError =
    StreamApiError(401, 43, "JWTAuth error: signature is not valid")

  def notFound(op: String, detail: String): StreamApiError =
    StreamApiError(404, 16, s"$op failed with error: \"$detail\"")

  def notAllowed(op: String, detail: String): StreamApiError =
    StreamApiError(403, 17, s"$op failed with error: \"$detail\"")

  def rateLimited(op: String): StreamApiError =
    StreamApiError(429, 9, s"$op failed with error: \"Too many requests\"")
