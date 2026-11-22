package com.firemoot.metrics

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.config.DbConfig
import com.firemoot.db.{Database, Migrations}
import munit.CatsEffectSuite
import org.http4s.Method.GET
import org.http4s.implicits.*
import org.http4s.{Request, Status}
import org.testcontainers.utility.DockerImageName

class MetricsRoutesSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  test("/metrics renders Prometheus gauges including live CCU") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
        val metrics = MetricsService(pool)
        val app = MetricsRoutes(metrics, IO.pure(7)).routes.orNotFound
        for
          _ <- metrics.record("alice")
          res <- app.run(Request[IO](GET, uri"/metrics"))
          body <- res.as[String]
        yield
          assertEquals(res.status, Status.Ok)
          assert(body.contains("# TYPE firemoot_ccu gauge"), body)
          assert(body.contains("firemoot_ccu 7.0"), body)
          assert(body.contains("firemoot_dau 1.0"), body)
          assert(body.contains("firemoot_db_size_bytes"), body)
          assert(body.contains("# TYPE firemoot_jvm_heap_after_gc_bytes gauge"), body)
          assert(body.contains("firemoot_jvm_heap_max_bytes"), body)
          assert(body.contains("firemoot_jvm_nonheap_used_bytes"), body)
          assertEquals(
            body.contains("firemoot_process_rss_bytes"),
            java.nio.file.Files.isReadable(java.nio.file.Path.of("/proc/self/status")),
          )
      }
    }
  }
