package com.firemoot

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.semigroupk.*
import com.firemoot.api.ApiRoutes
import com.firemoot.auth.{ApiKeys, ServerHmacAuth}
import com.firemoot.backplane.Backplane
import com.firemoot.config.ServerConfig
import com.firemoot.http.{DemoRoutes, HealthRoutes}
import com.firemoot.media.MediaService
import com.firemoot.metrics.{MetricsRoutes, MetricsService}
import com.firemoot.ratelimit.RateGuard
import com.firemoot.service.{
  ChannelService,
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
import org.http4s.server.middleware.Logger
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
      rate,
      media,
    )
    val securedApi = ServerHmacAuth(ApiKeys.fromConfig(cfg), rate)(api.routes)
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
    val metrics = MetricsRoutes(MetricsService(pool), registry.count).routes
    val demo = if devDemo then DemoRoutes.routes else HttpRoutes.empty[IO]

    // securedApi is last: health, metrics, openapi, demo and ws own their paths and
    // must not be intercepted by the HMAC middleware (which would 401 the handshake).
    wsb =>
      Logger.httpApp(logHeaders = true, logBody = false)(
        (HealthRoutes(pool).routes <+> metrics <+> openApi <+> demo <+>
          ws.routes(wsb) <+> securedApi).orNotFound
      )
