package com.firemoot.auth

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import com.firemoot.api.Problem
import com.firemoot.ratelimit.{RateGuard, RateLimitDecision}
import fs2.Stream
import io.circe.syntax.*
import org.http4s.*
import org.http4s.headers.{`Content-Type`, Authorization}
import org.typelevel.ci.CIString

/**
 * REST authentication middleware (SPEC.md §5). Resolves a [[Principal]] and
 * attaches it to the request for the routes' per-operation authorisation:
 *
 *   - `Authorization: Bearer <user JWT>` (HS256, verified with the API secret -
 *     the same tokens the WS gateway accepts) -> [[Principal.User]];
 *   - otherwise the `X-Firemoot-*` HMAC signature -> [[Principal.Server]].
 *
 * HMAC must buffer the body to bind it into the signature (and re-emit it to the
 * inner routes); Bearer is a header check, so the body passes straight through.
 * `jwtSecret = None` disables the client path (HMAC-only - used by the server-API
 * tests). Composed **last** in the route chain so it never intercepts sibling
 * routes before their path is matched.
 */
object ApiAuth:

  private val maxSkewSeconds = 300L

  def apply(
      apiKeys: ApiKeys,
      rate: RateGuard = RateGuard.unlimited,
      jwtSecret: Option[String] = None,
  )(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    Kleisli { req =>
      (bearerToken(req), jwtSecret) match
        case (Some(token), Some(secret)) =>
          authenticateUser(secret, token) match
            case Left(problem) => OptionT.pure[IO](problemResponse(problem))
            case Right(principal) =>
              routes(req.withAttribute(Principal.attribute, principal))
        case _ =>
          OptionT.liftF(req.body.compile.to(Array)).flatMap { body =>
            OptionT.liftF(authenticateServer(apiKeys, req, body)).flatMap {
              case Left(problem) => OptionT.pure[IO](problemResponse(problem))
              case Right(keyId) =>
                OptionT.liftF(rate.apiKey(keyId)).flatMap {
                  case RateLimitDecision.Retry(after) =>
                    OptionT.pure[IO](problemResponse(tooManyRequests(after.toSeconds)))
                  case RateLimitDecision.Allowed =>
                    routes(
                      req
                        .withBodyStream(Stream.emits(body).covary[IO])
                        .withAttribute(Principal.attribute, Principal.Server(keyId))
                    )
                }
            }
          }
    }

  private def bearerToken(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, token)) => token
    }

  private def authenticateUser(secret: String, token: String): Either[Problem, Principal] =
    JwtAuth.verify(secret, token) match
      case Right(claims) => Right(Principal.User(claims.sub, claims.role))
      case Left(error) => Left(unauthorized(s"invalid token: $error"))

  private def headerValue(req: Request[IO], name: String): Option[String] =
    req.headers.get(CIString(name)).map(_.head.value)

  private def authenticateServer(
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
