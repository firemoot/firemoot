package com.firemoot.auth

import java.time.Instant

import scala.util.{Failure, Success}

import io.circe.{parser, Json}
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim, JwtOptions}

/** End-user token claims (SPEC.md §5): `sub` is the user id, `role` is optional. */
final case class TokenClaims(sub: String, role: Option[String])

/**
 * HS256 JWTs for end users, signed with the API secret. Verification is
 * constant-time (jwt-scala uses `constantTimeAreEqual`), requires `exp`, and
 * allows +/-60s clock skew.
 *
 * Minting is normally the customer backend's job via the server SDK; `sign` lives
 * here because the algorithm must match and it is exercised by tests.
 */
object JwtAuth:

  private val leewaySeconds = 60L

  def sign(secret: String, sub: String, role: Option[String], expiresAt: Instant): String =
    val content = role.fold("{}")(r => Json.obj("role" -> Json.fromString(r)).noSpaces)
    val claim = JwtClaim(
      content = content,
      subject = Some(sub),
      expiration = Some(expiresAt.getEpochSecond),
    )
    JwtCirce.encode(claim, secret, JwtAlgorithm.HS256)

  def verify(secret: String, token: String): Either[String, TokenClaims] =
    JwtCirce.decode(
      token,
      secret,
      Seq(JwtAlgorithm.HS256),
      JwtOptions(leeway = leewaySeconds),
    ) match
      case Success(claim) =>
        (claim.subject, claim.expiration) match
          case (Some(sub), Some(_)) =>
            val role =
              parser.parse(claim.content).toOption.flatMap(_.hcursor.get[String]("role").toOption)
            Right(TokenClaims(sub, role))
          case (None, _) => Left("token missing sub")
          case (_, None) => Left("token missing exp")
      case Failure(error) => Left(error.getMessage)
