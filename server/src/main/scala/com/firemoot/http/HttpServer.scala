package com.firemoot.http

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import com.comcast.ip4s.*
import com.firemoot.config.HttpConfig
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.server.websocket.WebSocketBuilder2

object HttpServer:

  def resource(
      cfg: HttpConfig,
      httpApp: WebSocketBuilder2[IO] => HttpApp[IO],
  ): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(cfg.host).getOrElse(host"0.0.0.0"))
      .withPort(Port.fromInt(cfg.port).getOrElse(port"6668"))
      .withHttpWebSocketApp(httpApp)
      .withShutdownTimeout(5.seconds)
      .build
