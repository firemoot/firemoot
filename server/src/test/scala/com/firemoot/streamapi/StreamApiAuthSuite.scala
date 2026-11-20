package com.firemoot.streamapi

import java.time.Instant

import cats.effect.IO
import munit.CatsEffectSuite
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}

class StreamApiAuthSuite extends CatsEffectSuite:

  private val auth = StreamApiAuth((key: String) => IO.pure(Option.when(key == "k")("secret")))

  private def token(content: String, secret: String = "secret", exp: Option[Long] = None): String =
    JwtCirce.encode(JwtClaim(content = content, expiration = exp), secret, JwtAlgorithm.HS256)

  test("a server token, and a user token with or without exp") {
    for
      server <- auth.verify("k", token("""{"server":true}"""))
      user <- auth.verify("k", token("""{"user_id":"alice"}"""))
      expiring <- auth.verify(
        "k",
        token("""{"user_id":"bob"}""", exp = Some(Instant.now.getEpochSecond + 600)),
      )
    yield
      assertEquals(server, Right(StreamPrincipal.Server))
      assertEquals(user, Right(StreamPrincipal.User("alice")))
      assertEquals(expiring, Right(StreamPrincipal.User("bob")))
  }

  test("Stream's codes: 40 expired, 43 bad signature, 2 unknown key, 5 no identity") {
    for
      expired <- auth.verify(
        "k",
        token("""{"user_id":"a"}""", exp = Some(Instant.now.getEpochSecond - 3600)),
      )
      forged <- auth.verify("k", token("""{"user_id":"a"}""", secret = "wrong"))
      unknownKey <- auth.verify("nope", token("""{"user_id":"a"}"""))
      anonymous <- auth.verify("k", token("""{"role":"user"}"""))
    yield
      assertEquals(expired.left.map(_.code), Left(40))
      assertEquals(forged.left.map(_.code), Left(43))
      assertEquals(unknownKey.left.map(_.code), Left(2))
      assertEquals(anonymous.left.map(_.code), Left(5))
  }
