package com.firemoot.auth

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import com.firemoot.api.Problem
import com.firemoot.ratelimit.{RateGuard, RateLimitDecision}
import fs2.Stream
import io.circe.syntax.*
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/**
 * http4s middleware that authenticates server-SDK requests by HMAC signature
 * (SPEC.md §5). It buffers the body to bind it into the signature, then re-emits
 * it to the inner routes. Auth lives here, not in the tapir security layer, so
 * the signature can cover the request body (tapir security runs before the body
 * is read).
 */
object ServerHmacAuth:

  private val maxSkewSeconds = 300L

  def apply(apiKeys: ApiKeys, rate: RateGuard = RateGuard.unlimited)(
      routes: HttpRoutes[IO]
  ): HttpRoutes[IO] =
    Kleisli { req =>
      OptionT.liftF(req.body.compile.to(Array)).flatMap { body =>
        OptionT.liftF(authenticate(apiKeys, req, body)).flatMap {
          case Left(problem) => OptionT.pure[IO](problemResponse(problem))
          case Right(keyId) =>
            OptionT.liftF(rate.apiKey(keyId)).flatMap {
              case RateLimitDecision.Retry(after) =>
                OptionT.pure[IO](problemResponse(tooManyRequests(after.toSeconds)))
              case RateLimitDecision.Allowed =>
                routes(req.withBodyStream(Stream.emits(body).covary[IO]))
            }
        }
      }
    }

  private def headerValue(req: Request[IO], name: String): Option[String] =
    req.headers.get(CIString(name)).map(_.head.value)

  private def authenticate(
      apiKeys: ApiKeys,
      req: Request[IO],
      body: Array[Byte],
  ): IO[Either[Problem, String]] =
    (
      headerValue(req, "X-Firemoot-Key"),
      headerValue(req, "X-Firemoot-Timestamp"),
      headerValue(req, "X-Firemoot-Signature"),
    ) match
      case (Some(keyId), Some(timestampRaw), Some(signature)) =>
        timestampRaw.toLongOption match
          case None => IO.pure(Left(unauthorized("invalid timestamp")))
          case Some(timestamp) =>
            IO.realTime.flatMap { now =>
              if math.abs(now.toSeconds - timestamp) > maxSkewSeconds then
                IO.pure(Left(unauthorized("timestamp outside the allowed window")))
              else
                apiKeys.secretFor(keyId).map {
                  case None => Left(unauthorized("unknown API key"))
                  case Some(secret) =>
                    val canonical = HmacSigner.canonicalString(
                      req.method.name,
                      req.uri.path.renderString,
                      timestamp,
                      body,
                    )
                    Either.cond(
                      HmacSigner.verify(secret, canonical, signature),
                      keyId,
                      unauthorized("signature mismatch"),
                    )
                }
            }
      case _ => IO.pure(Left(unauthorized("missing authentication headers")))

  private def unauthorized(detail: String): Problem =
    Problem.of(401, "Unauthorized", Some(detail))

  private def tooManyRequests(retryAfterSeconds: Long): Problem =
    Problem.of(
      429,
      "Too Many Requests",
      Some(s"rate limit exceeded; retry after ${retryAfterSeconds}s"),
    )

  private def problemResponse(problem: Problem): Response[IO] =
    Response[IO](Status.fromInt(problem.status).getOrElse(Status.Unauthorized))
      .withEntity(problem.asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.unsafeParse("application/problem+json")))
