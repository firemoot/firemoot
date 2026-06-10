package com.firemoot.admin

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.api.CreateWebhookRequest
import com.firemoot.config.DbConfig
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.Event
import com.firemoot.metrics.MetricsService
import com.firemoot.service.{DeadLetter, WebhookService}
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Method.{GET, POST}
import org.http4s.Uri
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import org.http4s.{Header, Request, RequestCookie, Status}
import org.testcontainers.utility.DockerImageName
import org.typelevel.ci.CIString
import skunk.implicits.*

class AdminRoutesSuite extends CatsEffectSuite, TestContainerForAll:

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

  test("login gates the session: bad password 401, good password sets a cookie") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
        val service = AdminService(pool, jwtSecret = "sign")
        val app =
          AdminRoutes(
            service,
            MetricsService(pool),
            WebhookService(pool),
            IO.pure(3),
            secureCookies = false,
          ).routes.orNotFound

        for
          _ <- service.setPassword("open-sesame")
          wrong <- app.run(
            Request[IO](POST, uri"/admin/login").withEntity(AdminLoginRequest("nope"))
          )
          _ = assertEquals(wrong.status, Status.Unauthorized)

          noSession <- app.run(Request[IO](GET, uri"/admin/session"))
          _ = assertEquals(noSession.status, Status.Unauthorized, "no cookie -> 401")

          ok <- app.run(
            Request[IO](POST, uri"/admin/login").withEntity(AdminLoginRequest("open-sesame"))
          )
          _ = assertEquals(ok.status, Status.Ok)
          session = ok.cookies.find(_.name == "firemoot_admin").get
          _ = assert(session.httpOnly, "the session cookie is httpOnly")
          _ = assert(ok.cookies.exists(_.name == "firemoot_csrf"), "a CSRF cookie is set")

          authed <- app.run(
            Request[IO](GET, uri"/admin/session")
              .addCookie(RequestCookie("firemoot_admin", session.content))
          )
          _ = assertEquals(authed.status, Status.Ok, "the session cookie authenticates")

          metricsUnauthed <- app.run(Request[IO](GET, uri"/admin/metrics"))
          _ = assertEquals(metricsUnauthed.status, Status.Unauthorized, "metrics need a session")
          metricsRes <- app.run(
            Request[IO](GET, uri"/admin/metrics")
              .addCookie(RequestCookie("firemoot_admin", session.content))
          )
          _ = assertEquals(metricsRes.status, Status.Ok)
          body <- metricsRes.as[io.circe.Json]
        yield assertEquals(
          body.hcursor.get[Int]("ccuNow").toOption,
          Some(3),
          s"live metrics behind the gate: $body",
        )
      }
    }
  }

  test("dead-letter list and replay (CSRF-protected) close the webhook loop") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
        val service = AdminService(pool, jwtSecret = "sign")
        val webhooks = WebhookService(pool)
        val app = AdminRoutes(
          service,
          MetricsService(pool),
          webhooks,
          IO.pure(0),
          secureCookies = false,
        ).routes.orNotFound
        val markAllDead = sql"update webhook_deliveries set status = 'dead'".command

        for
          _ <- service.setPassword("pw")
          _ <- webhooks.register(CreateWebhookRequest("http://x.test/h", None, Some(true)))
          _ <- webhooks.enqueue(Event("message.new", "messaging:general", 1, Json.obj()))
          _ <- pool.use(_.execute(markAllDead))

          login <- app.run(
            Request[IO](POST, uri"/admin/login").withEntity(AdminLoginRequest("pw"))
          )
          session = login.cookies.find(_.name == "firemoot_admin").get.content
          csrf = login.cookies.find(_.name == "firemoot_csrf").get.content

          listed <- app.run(
            Request[IO](GET, uri"/admin/webhooks/dead-letters")
              .addCookie(RequestCookie("firemoot_admin", session))
          )
          letters <- listed.as[List[DeadLetter]]
          dead = letters.head

          // Replay without a CSRF header is forbidden.
          noCsrf <- app.run(
            Request[IO](
              POST,
              Uri.unsafeFromString(s"/admin/webhooks/dead-letters/${dead.id}/replay"),
            )
              .addCookie(RequestCookie("firemoot_admin", session))
              .addCookie(RequestCookie("firemoot_csrf", csrf))
          )
          _ = assertEquals(noCsrf.status, Status.Forbidden, "mutations need the CSRF header")

          replayed <- app.run(
            Request[IO](
              POST,
              Uri.unsafeFromString(s"/admin/webhooks/dead-letters/${dead.id}/replay"),
            )
              .addCookie(RequestCookie("firemoot_admin", session))
              .addCookie(RequestCookie("firemoot_csrf", csrf))
              .putHeaders(Header.Raw(CIString("X-CSRF-Token"), csrf))
          )
          _ = assertEquals(replayed.status, Status.Ok)
          afterReplay <- app.run(
            Request[IO](GET, uri"/admin/webhooks/dead-letters")
              .addCookie(RequestCookie("firemoot_admin", session))
          )
          remaining <- afterReplay.as[List[DeadLetter]]
        yield
          assertEquals(letters.size, 1, "the dead delivery is listed")
          assertEquals(dead.eventType, "message.new")
          assertEquals(remaining, Nil, "after replay the delivery is no longer dead")
      }
    }
  }
