package com.firemoot.http

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Method, Request, Status}
import org.testcontainers.utility.DockerImageName
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

class ReadinessSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private given Tracer[IO] = Tracer.noop
  private given Meter[IO] = Meter.noop

  test("GET /readyz returns 200 against a live Postgres") {
    withContainers { pg =>
      val pool = Session
        .Builder[IO]
        .withHost(pg.container.getHost)
        .withPort(pg.container.getMappedPort(5432))
        .withUserAndPassword(pg.username, pg.password)
        .withDatabase(pg.databaseName)
        .pooled(2)
      pool.use { session =>
        val routes = HealthRoutes(session).routes
        val req = Request[IO](Method.GET, uri"/readyz")
        routes.orNotFound.run(req).flatMap { res =>
          assertEquals(res.status, Status.Ok)
          res.as[String].map(body => assert(body.contains("ready")))
        }
      }
    }
  }
