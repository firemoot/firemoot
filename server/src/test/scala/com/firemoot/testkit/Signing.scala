package com.firemoot.testkit

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

import cats.effect.IO
import com.firemoot.auth.HmacSigner
import io.circe.Encoder
import io.circe.syntax.*
import org.http4s.headers.`Content-Type`
import org.http4s.{Headers, MediaType, Method, Request, Uri}

/**
 * Builds correctly HMAC-signed requests for the server API, mirroring what the
 * (future) server SDK does. Used by the integration suites.
 */
object Signing:

  def headers(
      method: Method,
      path: String,
      body: String,
      keyId: String,
      secret: String,
      timestamp: Long = Instant.now().getEpochSecond,
  ): Headers =
    val canonical = HmacSigner.canonicalString(method.name, path, timestamp, body.getBytes(UTF_8))
    Headers(
      "X-Firemoot-Key" -> keyId,
      "X-Firemoot-Timestamp" -> timestamp.toString,
      "X-Firemoot-Signature" -> HmacSigner.sign(secret, canonical),
    )

  /**
   * Signs over the path the server will see (`uri.path`), so it works whether the
   * URI is relative (direct route tests) or absolute (real-server tests).
   */
  def signedRequest[A: Encoder](
      method: Method,
      uri: Uri,
      dto: A,
      keyId: String,
      secret: String,
  ): Request[IO] =
    val body = dto.asJson.noSpaces
    Request[IO](method, uri, headers = headers(method, uri.path.renderString, body, keyId, secret))
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

  /**
   * A signed request with no body (e.g. DELETE); the empty body is bound into the
   * signature like any other.
   */
  def signedNoBody(method: Method, uri: Uri, keyId: String, secret: String): Request[IO] =
    Request[IO](method, uri, headers = headers(method, uri.path.renderString, "", keyId, secret))
