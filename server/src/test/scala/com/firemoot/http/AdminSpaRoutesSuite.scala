package com.firemoot.http

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.Method.GET
import org.http4s.implicits.*
import org.http4s.{Request, Status}

class AdminSpaRoutesSuite extends CatsEffectSuite:

  private val app = AdminSpaRoutes.routes.orNotFound

  test("serves the SPA shell at /admin, referencing its bundle") {
    for
      res <- app.run(Request[IO](GET, uri"/admin"))
      body <- res.as[String]
    yield
      assertEquals(res.status, Status.Ok)
      assert(body.contains("/admin/assets/app.js"), s"shell should load the bundle: $body")
  }

  test("serves the built JS and CSS assets") {
    for
      js <- app.run(Request[IO](GET, uri"/admin/assets/app.js"))
      css <- app.run(Request[IO](GET, uri"/admin/assets/app.css"))
    yield
      assertEquals(js.status, Status.Ok)
      assertEquals(css.status, Status.Ok)
  }

  test("an unknown asset 404s") {
    app.run(Request[IO](GET, uri"/admin/assets/does-not-exist.js")).map { res =>
      assertEquals(res.status, Status.NotFound)
    }
  }
