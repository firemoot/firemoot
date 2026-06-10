package com.firemoot.http

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Method, Request, Status}
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

class HealthRoutesSuite extends CatsEffectSuite:

  private val unusedDb: Resource[IO, Session[IO]] =
    Resource.eval(IO.raiseError(new RuntimeException("healthz must not touch the database")))

  test("GET /healthz returns 200 without touching the database") {
    val routes = HealthRoutes(unusedDb).routes
    val req = Request[IO](Method.GET, uri"/healthz")
    routes.orNotFound.run(req).flatMap { res =>
      assertEquals(res.status, Status.Ok)
      res.as[String].map(body => assert(body.contains("ok")))
    }
  }

  test("GET /readyz returns 503 when the database is unreachable") {
    given Tracer[IO] = Tracer.noop
    given Meter[IO] = Meter.noop
    val deadPool = Session
      .Builder[IO]
      .withHost("127.0.0.1")
      .withPort(1)
      .withUserAndPassword("nobody", "nopass")
      .withDatabase("nodb")
      .pooled(1)
    deadPool.use { pool =>
      val routes = HealthRoutes(pool).routes
      val req = Request[IO](Method.GET, uri"/readyz")
      routes.orNotFound.run(req).map(res => assertEquals(res.status, Status.ServiceUnavailable))
    }
  }
