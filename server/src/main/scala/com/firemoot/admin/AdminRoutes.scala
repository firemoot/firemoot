package com.firemoot.admin

import java.security.SecureRandom
import java.time.ZoneOffset

import cats.effect.IO
import com.firemoot.metrics.MetricsService
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax.*
import io.circe.{Codec, Json}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

final case class AdminLoginRequest(password: String)

object AdminLoginRequest:
  given Codec[AdminLoginRequest] = deriveCodec

/**
 * Admin auth routes and the session gate (SPEC.md §8, M3.4). Login sets an
 * httpOnly, SameSite=Strict session cookie and returns a CSRF token (also set as
 * a readable cookie); `withSession` guards protected routes, additionally
 * requiring a matching `X-CSRF-Token` on mutating requests (double-submit). The
 * admin data routes (M3.5) build on `withSession`.
 */
final class AdminRoutes(
    admin: AdminService,
    metrics: MetricsService,
    ccuNow: IO[Int],
    secureCookies: Boolean,
):

  private val SessionCookie = "firemoot_admin"
  private val CsrfCookie = "firemoot_csrf"

  private object MetricParam extends QueryParamDecoderMatcher[String]("metric")
  private object DaysParam extends OptionalQueryParamDecoderMatcher[Int]("days")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "admin" / "login" =>
      req.as[AdminLoginRequest].flatMap { body =>
        admin.login(body.password).flatMap {
          case None => IO.pure(Response[IO](Status.Unauthorized))
          case Some(token) =>
            randomToken.flatMap { csrf =>
              Ok(Json.obj("csrfToken" -> csrf.asJson)).map { res =>
                res.addCookie(sessionCookie(token)).addCookie(csrfCookieFor(csrf))
              }
            }
        }
      }

    case req @ GET -> Root / "admin" / "session" =>
      withSession(req)(Ok(Json.obj("authenticated" -> true.asJson)))

    case req @ GET -> Root / "admin" / "metrics" =>
      withSession(req)(ccuNow.flatMap(metrics.live).flatMap(m => Ok(m.asJson)))

    case req @ GET -> Root / "admin" / "metrics" / "daily" :?
        MetricParam(metric) +& DaysParam(days) =>
      withSession(req) {
        IO.realTimeInstant.map(_.atOffset(ZoneOffset.UTC).toLocalDate).flatMap { today =>
          metrics.dailySeries(metric, today.minusDays(days.getOrElse(90).toLong)).flatMap { rows =>
            val series = rows.map { (day, labels, value) =>
              Json.obj("day" -> day.toString.asJson, "labels" -> labels, "value" -> value.asJson)
            }
            Ok(Json.obj("metric" -> metric.asJson, "series" -> series.asJson))
          }
        }
      }
  }

  /**
   * Runs `onAuthed` only with a valid admin session cookie (and, for mutating
   * methods, a matching CSRF header); otherwise 401/403.
   */
  def withSession(req: Request[IO])(onAuthed: => IO[Response[IO]]): IO[Response[IO]] =
    sessionToken(req) match
      case Some(token) if admin.verifySession(token) =>
        if isMutating(req.method) && !csrfOk(req) then IO.pure(Response[IO](Status.Forbidden))
        else onAuthed
      case _ => IO.pure(Response[IO](Status.Unauthorized))

  private def sessionToken(req: Request[IO]): Option[String] =
    req.cookies.find(_.name == SessionCookie).map(_.content)

  private def isMutating(method: Method): Boolean =
    method != Method.GET && method != Method.HEAD

  private def csrfOk(req: Request[IO]): Boolean =
    val cookie = req.cookies.find(_.name == CsrfCookie).map(_.content)
    val header = req.headers.get(CIString("X-CSRF-Token")).map(_.head.value)
    cookie.isDefined && cookie == header

  private def sessionCookie(token: String): ResponseCookie =
    ResponseCookie(
      SessionCookie,
      token,
      path = Some("/"),
      sameSite = Some(SameSite.Strict),
      secure = secureCookies,
      httpOnly = true,
    )

  private def csrfCookieFor(csrf: String): ResponseCookie =
    ResponseCookie(
      CsrfCookie,
      csrf,
      path = Some("/"),
      sameSite = Some(SameSite.Strict),
      secure = secureCookies,
      httpOnly = false,
    )

  private def randomToken: IO[String] =
    IO.blocking {
      val bytes = new Array[Byte](24)
      SecureRandom().nextBytes(bytes)
      bytes.map("%02x".format(_)).mkString
    }
