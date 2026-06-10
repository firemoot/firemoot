package com.firemoot.auth

import cats.syntax.all.*
import munit.CatsEffectSuite

class PasswordHasherSuite extends CatsEffectSuite:

  test("verifies the right password and rejects the wrong one") {
    for
      hash <- PasswordHasher.hash("correct horse battery staple")
      ok <- PasswordHasher.verify("correct horse battery staple", hash)
      bad <- PasswordHasher.verify("Tr0ub4dor&3", hash)
    yield
      assert(ok, "the correct password verifies")
      assert(!bad, "a wrong password is rejected")
  }

  test("the same password hashes differently each time (random salt)") {
    (PasswordHasher.hash("same"), PasswordHasher.hash("same")).mapN { (a, b) =>
      assertNotEquals(a, b, "salts make the encoded hashes differ")
    }
  }

  test("a malformed encoded hash does not verify") {
    PasswordHasher.verify("anything", "not-a-valid-hash").map(ok => assert(!ok))
  }
