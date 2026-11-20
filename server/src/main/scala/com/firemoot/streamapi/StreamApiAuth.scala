package com.firemoot.streamapi

import scala.util.{Failure, Success}

import cats.effect.IO
import com.firemoot.auth.ApiKeys
import io.circe.parser
import org.http4s.Request
import org.typelevel.ci.*
import pdi.jwt.exceptions.{JwtExpirationException, JwtNotBeforeException, JwtValidationException}
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtOptions}

/** Who a v2 request acts as. */
enum StreamPrincipal:
  /** `{ "server": true }`, signed by the backend (node-sdk's server token). */
  case Server

  /** `{ "user_id": ... }`: an end user's token. */
  case User(id: String)

/**
 * getstream.io's request auth. `api_key` (a query param on every request and on
 * the socket URL) picks the key, and so the secret. The token is a raw HS256 JWT,
 * no `Bearer` prefix: `{server: true}` for the backend, `{user_id}` for a user.
 * Unlike Firemoot's own tokens, `exp` is optional, as it is on Stream.
 */
final class StreamApiAuth(apiKeys: ApiKeys):

  private val leewaySeconds = 60L

  def apiKeyOf(req: Request[IO]): Option[String] = req.params.get("api_key")

  def tokenOf(req: Request[IO]): Option[String] =
    req.headers.get(ci"Authorization").map(_.head.value.trim).filter(_.nonEmpty)

  /** Authenticates a REST request: `api_key` plus the `Authorization` token. */
  def authenticate(req: Request[IO]): IO[Either[StreamApiError, StreamPrincipal]] =
    req.headers.get(ci"stream-auth-type").map(_.head.value) match
      case Some("anonymous") =>
        IO.pure(Left(StreamApiError.authFailed("anonymous users are not enabled for this app")))
      case _ =>
        (apiKeyOf(req), tokenOf(req)) match
          case (None, _) => IO.pure(Left(StreamApiError.accessKey))
          case (_, None) =>
            IO.pure(Left(StreamApiError.authFailed("JWTAuth error: token is missing")))
          case (Some(key), Some(token)) => verify(key, token)

  /** Verifies `token` against the secret of API key `key`. */
  def verify(key: String, token: String): IO[Either[StreamApiError, StreamPrincipal]] =
    apiKeys.secretFor(key).map {
      case None => Left(StreamApiError.accessKey)
      case Some(secret) => decode(secret, token)
    }

  private def decode(secret: String, token: String): Either[StreamApiError, StreamPrincipal] =
    JwtCirce.decode(
      token,
      secret,
      Seq(JwtAlgorithm.HS256),
      JwtOptions(leeway = leewaySeconds),
    ) match
      case Failure(_: JwtExpirationException) => Left(StreamApiError.tokenExpired)
      case Failure(_: JwtNotBeforeException) => Left(StreamApiError.tokenNotValidYet)
      case Failure(_: JwtValidationException) => Left(StreamApiError.signatureInvalid)
      case Failure(e) => Left(StreamApiError.authFailed(s"JWTAuth error: ${e.getMessage}"))
      case Success(claim) =>
        val content = parser.parse(claim.content).toOption.map(_.hcursor)
        val server = content.flatMap(_.get[Boolean]("server").toOption).contains(true)
        val userId = content.flatMap(_.get[String]("user_id").toOption).filter(_.nonEmpty)
        (server, userId) match
          case (true, _) => Right(StreamPrincipal.Server)
          case (false, Some(id)) => Right(StreamPrincipal.User(id))
          case _ =>
            Left(StreamApiError.authFailed("JWTAuth error: token has neither user_id nor server"))
