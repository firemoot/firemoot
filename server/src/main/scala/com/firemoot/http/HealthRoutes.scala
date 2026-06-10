package com.firemoot.http

import cats.effect.{IO, Resource}
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import skunk.*
import skunk.codec.all.int4
import skunk.implicits.*

/**
 * Liveness and readiness probes.
 *
 *   - `/healthz` reports process liveness and never touches the database.
 *   - `/readyz` runs `select 1` through the pool; 503 when the database is
 *     unreachable.
 */
final class HealthRoutes(dbPool: Resource[IO, Session[IO]]):

  private val ping: Query[Void, Int] = sql"select 1".query(int4)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      Ok(Json.obj("status" -> Json.fromString("ok")))

    case GET -> Root / "readyz" =>
      dbPool.use(_.unique(ping)).attempt.flatMap {
        case Right(_) => Ok(Json.obj("status" -> Json.fromString("ready")))
        case Left(_) => ServiceUnavailable(Json.obj("status" -> Json.fromString("unavailable")))
      }
  }
