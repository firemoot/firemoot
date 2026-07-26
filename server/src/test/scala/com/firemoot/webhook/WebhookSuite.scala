package com.firemoot.webhook

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForEach
import com.firemoot.auth.HmacSigner
import com.firemoot.api.CreateWebhookRequest
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, HttpConfig}
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.Event
import com.firemoot.http.HttpServer
import com.firemoot.service.WebhookService
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.{HttpApp, HttpRoutes, Response, Status}
import org.testcontainers.utility.DockerImageName
import org.typelevel.ci.*
import skunk.codec.all.*
import skunk.implicits.*

class WebhookSuite extends CatsEffectSuite, TestContainerForEach:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  final private case class Received(body: String, headers: Map[CIString, String]):
    def header(name: String): Option[String] = headers.get(CIString(name))

  private val secret = "top-secret"

  private val apiKeyId = "test-key"

  private val deliveryStatus =
    sql"""select status, attempts, coalesce(last_error, '')
          from webhook_deliveries where endpoint_id = $text"""
      .query(text *: int4 *: text)

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 8,
    )

  /** A loopback endpoint that records every POST and answers with `status`. */
  private def recorder(received: Ref[IO, Vector[Received]], status: Status): HttpApp[IO] =
    HttpRoutes
      .of[IO] { case req @ POST -> _ =>
        req.as[String].flatMap { body =>
          val headers = req.headers.headers.map(h => h.name -> h.value).toMap
          received.update(_ :+ Received(body, headers)) >> IO.pure(Response[IO](status))
        }
      }
      .orNotFound

  private val fastCfg = WebhookConfig(
    pollInterval = 50.millis,
    visibilityTimeout = 5.seconds,
    batchSize = 16,
    concurrency = 4,
    requestTimeout = 5.seconds,
    backoff = List(10.millis, 10.millis),
  )

  test("fans an event out to every enabled endpoint and POSTs a signed payload") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (Backplane.inProcess, Ref[IO].of(Vector.empty[Received])).flatMapN { (_, received) =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          HttpServer.resource(HttpConfig("127.0.0.1", 0), _ => recorder(received, Status.Ok)).use {
            server =>
              JdkHttpClient.simple[IO].flatMap { client =>
                val webhooks = WebhookService(pool)
                val dispatcher = WebhookDispatcher(pool, client, fastCfg, apiKeyId)
                val hookUrl = (server.baseUri / "hook").renderString
                val event =
                  Event("message.new", "messaging:general", 7, Json.obj("text" -> "hi".asJson))
                for
                  a <- webhooks.register(CreateWebhookRequest(hookUrl, Some(secret), Some(true)))
                  b <- webhooks.register(CreateWebhookRequest(hookUrl, Some(secret), Some(true)))
                  listed <- webhooks.list
                  _ <- webhooks.enqueue(event)
                  _ <- dispatcher.pollOnce
                  got <- received.get
                  statusA <- pool.use(_.runUnique(deliveryStatus, a.id))
                  statusB <- pool.use(_.runUnique(deliveryStatus, b.id))
                yield
                  assertEquals(listed.size, 2, "both endpoints are listed")
                  assert(listed.forall(_.url == hookUrl))
                  assertEquals(got.size, 2, "one delivery per enabled endpoint")
                  got.foreach { r =>
                    val digest = HmacSigner.sign(secret, r.body)
                    assertEquals(
                      r.header("X-Firemoot-Signature"),
                      Some(s"sha256=$digest"),
                      "the body is signed with the endpoint secret",
                    )
                    assertEquals(r.header("X-Firemoot-Event"), Some("message.new"))
                    assert(r.body.contains("\"seq\":7"))
                    assertEquals(
                      r.header("X-Signature"),
                      Some(digest),
                      "the Stream alias is the bare hex digest, with no sha256= prefix",
                    )
                    val delivery = r.header("X-Firemoot-Delivery")
                    assert(delivery.exists(_.nonEmpty), "the delivery id is sent")
                    assertEquals(r.header("X-Webhook-Id"), delivery)
                    assertEquals(r.header("X-Webhook-Attempt"), Some("1"))
                    assertEquals(r.header("X-Api-Key"), Some(apiKeyId))
                  }
                  assertEquals(statusA._1, "delivered")
                  assertEquals(statusB._1, "delivered")
              }
          }
        }
      }
    }
  }

  test("retries a failing endpoint on the backoff, then dead-letters it") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (Backplane.inProcess, Ref[IO].of(Vector.empty[Received])).flatMapN { (_, received) =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          HttpServer.resource(
            HttpConfig("127.0.0.1", 0),
            _ => recorder(received, Status.InternalServerError),
          ).use { server =>
            JdkHttpClient.simple[IO].flatMap { client =>
              val webhooks = WebhookService(pool)
              val dispatcher = WebhookDispatcher(pool, client, fastCfg, apiKeyId)
              val hookUrl = (server.baseUri / "hook").renderString
              val event = Event("message.new", "messaging:general", 1, Json.obj())
              for
                ep <- webhooks.register(CreateWebhookRequest(hookUrl, Some(secret), Some(true)))
                _ <- webhooks.enqueue(event)
                // backoff has length 2, so attempts 1 and 2 retry and attempt 3 dies.
                _ <- dispatcher.pollOnce
                afterFirst <- pool.use(_.runUnique(deliveryStatus, ep.id))
                _ <- IO.sleep(40.millis) >> dispatcher.pollOnce
                _ <- IO.sleep(40.millis) >> dispatcher.pollOnce
                finalStatus <- pool.use(_.runUnique(deliveryStatus, ep.id))
                got <- received.get
              yield
                assertEquals(afterFirst._1, "pending", "the first failure schedules a retry")
                assertEquals(afterFirst._2, 1)
                assertEquals(finalStatus._1, "dead", "retries exhausted -> dead letter")
                assertEquals(finalStatus._2, 3, "three attempts were made")
                assert(finalStatus._3.contains("500"), s"records the failure: ${finalStatus._3}")
                assertEquals(got.size, 3, "the endpoint was hit once per attempt")
                assertEquals(
                  got.flatMap(_.header("X-Webhook-Attempt")),
                  Vector("1", "2", "3"),
                  "X-Webhook-Attempt counts from 1",
                )
                assertEquals(
                  got.flatMap(_.header("X-Webhook-Id")).distinct.size,
                  1,
                  "the dedupe id is stable across retries",
                )
            }
          }
        }
      }
    }
  }
