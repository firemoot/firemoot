package com.firemoot.auth

import java.time.Instant

import cats.effect.IO
import ciris.Secret
import com.firemoot.config.ServerConfig
import com.firemoot.testkit.Signing
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.Method.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{MediaType, Request, Status}

class ServerHmacAuthSuite extends CatsEffectSuite:

  private val cfg = ServerConfig("key-1", Secret("sekret"))
  private val inner = org.http4s.HttpRoutes.of[IO] { case POST -> Root / "v1" / "users" =>
    Ok("ok")
  }
  private val app = ServerHmacAuth(ApiKeys.fromConfig(cfg))(inner).orNotFound

  private val payload = Json.obj("id" -> Json.fromString("alice"))

  test("a correctly signed request passes through") {
    val req = Signing.signedRequest(POST, uri"/v1/users", payload, "key-1", "sekret")
    app.run(req).map(res => assertEquals(res.status, Status.Ok))
  }

  test("a wrong signature is rejected as 401 problem+json") {
    val req = Signing.signedRequest(POST, uri"/v1/users", payload, "key-1", "WRONG")
    app.run(req).map { res =>
      assertEquals(res.status, Status.Unauthorized)
      assertEquals(
        res.contentType.map(_.mediaType),
        Some(MediaType.unsafeParse("application/problem+json")),
      )
    }
  }

  test("an unknown key id is rejected") {
    val req = Signing.signedRequest(POST, uri"/v1/users", payload, "ghost", "sekret")
    app.run(req).map(res => assertEquals(res.status, Status.Unauthorized))
  }

  test("missing auth headers are rejected") {
    val req = Request[IO](POST, uri"/v1/users").withEntity("""{"id":"alice"}""")
    app.run(req).map(res => assertEquals(res.status, Status.Unauthorized))
  }

  test("a stale timestamp is rejected") {
    val body = """{"id":"alice"}"""
    val stale = Instant.now().getEpochSecond - 1000
    val req = Request[IO](
      POST,
      uri"/v1/users",
      headers = Signing.headers(POST, "/v1/users", body, "key-1", "sekret", stale),
    )
      .withEntity(body)
    app.run(req).map(res => assertEquals(res.status, Status.Unauthorized))
  }
