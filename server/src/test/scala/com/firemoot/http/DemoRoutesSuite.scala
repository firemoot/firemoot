package com.firemoot.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Method, Request, Status}

class DemoRoutesSuite extends CatsEffectSuite:

  test("GET /demo serves the demo page") {
    DemoRoutes.routes.orNotFound.run(Request[IO](Method.GET, uri"/demo")).flatMap { res =>
      assertEquals(res.status, Status.Ok)
      res.as[String].map(body => assert(body.contains("Firemoot - M0 demo")))
    }
  }
