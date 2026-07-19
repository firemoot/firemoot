package com.firemoot

import scala.concurrent.duration.*

import cats.data.{Kleisli, OptionT}
import cats.effect.{IO, Resource}
import cats.syntax.semigroupk.*
import com.firemoot.admin.{AdminRoutes, AdminService}
import com.firemoot.api.ApiRoutes
import com.firemoot.auth.{ApiAuth, ApiKeys, ApiKeyService}
import com.firemoot.backplane.Backplane
import com.firemoot.config.ServerConfig
import com.firemoot.http.{AdminSpaRoutes, DemoRoutes, HealthRoutes}
import com.firemoot.media.MediaService
import com.firemoot.metrics.{MetricsRoutes, MetricsService}
import com.firemoot.ratelimit.RateGuard
import com.firemoot.service.{
  ChannelService,
  HydrationService,
  MessageService,
  ModerationService,
  PresenceService,
  QueryService,
  ReactionService,
  ReadService,
  UserService,
  WebhookService,
}
import com.firemoot.ws.{ConnectionRegistry, EventReplay, WsRoutes}
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.{CORS, Logger}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{HttpApp, HttpRoutes, MediaType}
import skunk.Session

/**
 * Assembles the full HTTP+WS application from its dependencies. Shared by
 * [[Main]] and the integration tests so both exercise the identical wiring.
 */
object Application:

  def httpApp(
      cfg: ServerConfig,
      pool: Resource[IO, Session[IO]],
      backplane: Backplane,
      registry: ConnectionRegistry,
      devDemo: Boolean = false,
      onUserActive: String => IO[Unit] = _ => IO.unit,
      rate: RateGuard = RateGuard.unlimited,
      media: Option[MediaService] = None,
  ): WebSocketBuilder2[IO] => HttpApp[IO] =
    val webhooks = WebhookService(pool)
    val api = ApiRoutes(
      UserService(pool),
      ChannelService(pool, backplane),
      MessageService(pool, backplane),
      ReactionService(pool, backplane),
      ReadService(pool, backplane),
      QueryService(pool),
      webhooks,
      ModerationService(pool, webhooks),
      HydrationService(pool),
      rate,
      media,
    )
    val securedApi =
      ApiAuth(ApiKeys.fromConfigAndDb(cfg, pool), rate, jwtSecret = Some(cfg.apiSecret.value))(
        api.routes
      )
    val ws =
      WsRoutes(
        backplane,
        registry,
        EventReplay(pool),
        pool,
        PresenceService(pool, backplane),
        cfg.apiSecret.value,
        devDemo,
        onUserActive,
        typingThrottle = 3.seconds,
        typingExpiry = 7.seconds,
        rate = rate,
      )
    val openApi = HttpRoutes.of[IO] { case GET -> Root / "v1" / "openapi.json" =>
      Ok(api.openApiJson).map(_.withContentType(`Content-Type`(MediaType.application.json)))
    }
    val metricsService = MetricsService(pool)
    val metrics = MetricsRoutes(metricsService, registry.count).routes
    val admin =
      AdminRoutes(
        AdminService(pool, cfg.apiSecret.value),
        metricsService,
        webhooks,
        ApiKeyService(pool),
        registry.count,
        secureCookies = !devDemo,
      ).routes
    val demo = if devDemo then DemoRoutes.routes else HttpRoutes.empty[IO]

    // Firemoot is an embeddable, token-authenticated backend: end-user browsers
    // connect from arbitrary customer origins and authorise per request (HS256
    // bearer / HMAC), so access is gated by the credential, not the origin -
    // hence an origin-agnostic CORS policy (the same posture as hosted chat
    // backends). Restrict via a reverse proxy if a single-origin deployment wants
    // a tighter policy.
    // `withAllowHeadersReflect` echoes the requested headers: a literal `*` in
    // Access-Control-Allow-Headers does NOT cover `Authorization` (Fetch spec),
    // which the end-user bearer + HMAC headers rely on.
    val cors =
      CORS.policy.withAllowOriginAll.withAllowMethodsAll.withAllowHeadersReflect
        .withMaxAge(1.day)

    // securedApi is last: health, metrics, admin, openapi and demo own their
    // paths and must not be intercepted by the HMAC middleware (which 401s them).
    // The WebSocket route is composed OUTSIDE the Logger/CORS middleware: both
    // reconstruct the Response, which drops the upgrade context Ember needs and
    // makes the browser handshake fail with 501. WS upgrades are not subject to
    // CORS (the browser enforces it on fetch/XHR, not WebSocket), so they need
    // neither wrapper.
    // Log any exception escaping a route before Ember turns it into a bare 500 -
    // without this, production 500s leave no trace at all.
    def logUnhandled(routes: HttpRoutes[IO]): HttpRoutes[IO] =
      Kleisli { req =>
        OptionT {
          routes.run(req).value.onError { case e =>
            org.typelevel.log4cats.slf4j.Slf4jLogger
              .getLogger[IO]
              .error(e)(s"unhandled exception serving ${req.method} ${req.uri.path}")
          }
        }
      }

    wsb =>
      val httpRoutes =
        HealthRoutes(pool).routes <+> metrics <+> admin <+> AdminSpaRoutes.routes <+>
          openApi <+> demo <+> securedApi
      val wrapped =
        cors(Logger.httpRoutes(logHeaders = true, logBody = false)(logUnhandled(httpRoutes)))
      (ws.routes(wsb) <+> wrapped).orNotFound
