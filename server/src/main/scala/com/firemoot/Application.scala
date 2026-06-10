package com.firemoot

import cats.effect.{IO, Resource}
import cats.syntax.semigroupk.*
import com.firemoot.api.ApiRoutes
import com.firemoot.backplane.Backplane
import com.firemoot.config.ServerConfig
import com.firemoot.http.{DemoRoutes, HealthRoutes}
import com.firemoot.service.{ChannelService, MessageService, UserService}
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
  ): WebSocketBuilder2[IO] => HttpApp[IO] =
    val api = ApiRoutes(
      cfg,
      UserService(pool),
      ChannelService(pool),
      MessageService(pool, backplane),
    )
    val ws = WsRoutes(backplane, registry, EventReplay(pool), pool)
    val openApi = HttpRoutes.of[IO] { case GET -> Root / "v1" / "openapi.json" =>
      Ok(api.openApiJson).map(_.withContentType(`Content-Type`(MediaType.application.json)))
    }
    val demo = if devDemo then DemoRoutes.routes else HttpRoutes.empty[IO]

    wsb =>
      Logger.httpApp(logHeaders = true, logBody = false)(
        (HealthRoutes(pool).routes <+> api.routes <+> openApi <+> demo <+>
          ws.routes(wsb)).orNotFound
      )
