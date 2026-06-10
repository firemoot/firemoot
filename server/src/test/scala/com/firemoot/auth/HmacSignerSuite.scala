package com.firemoot.auth

import java.nio.charset.StandardCharsets.UTF_8

import munit.FunSuite

class HmacSignerSuite extends FunSuite:

  private val secret = "shhh"

  private def canonical(body: String) =
    HmacSigner.canonicalString("POST", "/v1/users", 1_700_000_000L, body.getBytes(UTF_8))

  test("a correct signature verifies") {
    val c = canonical("""{"id":"alice"}""")
    assert(HmacSigner.verify(secret, c, HmacSigner.sign(secret, c)))
  }

  test("a tampered body fails verification") {
    val signed = HmacSigner.sign(secret, canonical("""{"id":"alice"}"""))
    assert(!HmacSigner.verify(secret, canonical("""{"id":"mallory"}"""), signed))
  }

  test("a wrong secret fails verification") {
    val c = canonical("""{"id":"alice"}""")
    assert(!HmacSigner.verify("nope", c, HmacSigner.sign(secret, c)))
  }
