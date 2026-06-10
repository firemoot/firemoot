package com.firemoot.http

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.StaticFile
import org.http4s.dsl.io.*

/**
 * Serves the M0 two-tab demo page. Mounted only when `FIREMOOT_DEV_DEMO=true`;
 * it uses the server API key from the browser, so it is a dev artefact, never a
 * production surface.
 */
object DemoRoutes:

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ GET -> Root / "demo" =>
    StaticFile.fromResource("/demo.html", Some(req)).getOrElseF(NotFound())
  }
