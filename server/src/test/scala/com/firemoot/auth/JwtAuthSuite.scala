package com.firemoot.auth

import java.time.Instant

import munit.FunSuite
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}

class JwtAuthSuite extends FunSuite:

  private val secret = "top-secret"

  test("sign then verify round-trips sub and role") {
    val token = JwtAuth.sign(secret, "alice", Some("admin"), Instant.now().plusSeconds(3600))
    assertEquals(JwtAuth.verify(secret, token), Right(TokenClaims("alice", Some("admin"))))
  }

  test("verify rejects a token signed with a different secret") {
    val token = JwtAuth.sign(secret, "alice", None, Instant.now().plusSeconds(3600))
    assert(JwtAuth.verify("other-secret", token).isLeft)
  }

  test("verify rejects an expired token beyond the leeway") {
    val token = JwtAuth.sign(secret, "alice", None, Instant.now().minusSeconds(120))
    assert(JwtAuth.verify(secret, token).isLeft)
  }

  test("verify accepts a token expired within the 60s leeway") {
    val token = JwtAuth.sign(secret, "alice", None, Instant.now().minusSeconds(30))
    assertEquals(JwtAuth.verify(secret, token).map(_.sub), Right("alice"))
  }

  test("verify rejects a token with no exp claim") {
    val token = JwtCirce.encode(JwtClaim(subject = Some("alice")), secret, JwtAlgorithm.HS256)
    assert(JwtAuth.verify(secret, token).isLeft)
  }
