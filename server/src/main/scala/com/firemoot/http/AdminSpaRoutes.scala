package com.firemoot.http

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.StaticFile
import org.http4s.dsl.io.*

/**
 * Serves the admin dashboard single-page app (M3.5) from classpath resources
 * baked in at build time (`server/src/main/resources/admin`). The shell lives at
 * `/admin`; its bundle is a single flat `admin/assets/<file>` segment. None of
 * these paths overlap the admin **API** routes under `/admin` (login, metrics,
 * webhooks, api-keys), which the SPA calls via fetch.
 */
object AdminSpaRoutes:

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "admin" =>
      StaticFile.fromResource("/admin/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "admin" / "" =>
      StaticFile.fromResource("/admin/index.html", Some(req)).getOrElseF(NotFound())

    case req @ GET -> Root / "admin" / "assets" / file =>
      StaticFile.fromResource(s"/admin/assets/$file", Some(req)).getOrElseF(NotFound())
  }
