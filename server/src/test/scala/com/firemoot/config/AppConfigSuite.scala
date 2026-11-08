package com.firemoot.config

import munit.FunSuite

class AppConfigSuite extends FunSuite:

  test("blankToNone treats an absent, empty or whitespace value as None") {
    // Compose's `${VAR:-}` passes an empty string, so media must read as disabled.
    assertEquals(AppConfig.blankToNone(None), None)
    assertEquals(AppConfig.blankToNone(Some("")), None)
    assertEquals(AppConfig.blankToNone(Some("   ")), None)
    assertEquals(AppConfig.blankToNone(Some("\t\n")), None)
  }

  test("blankToNone keeps and trims a real value") {
    assertEquals(AppConfig.blankToNone(Some("http://minio:9000")), Some("http://minio:9000"))
    assertEquals(AppConfig.blankToNone(Some("  http://x  ")), Some("http://x"))
  }

  test("reminderInterval accepts Stream's 60s to 24h range in whole seconds") {
    import scala.concurrent.duration.*
    assertEquals(AppConfig.reminderInterval("60"), Right(60.seconds))
    assertEquals(AppConfig.reminderInterval("300"), Right(5.minutes))
    assertEquals(AppConfig.reminderInterval("86400"), Right(1.day))
    List("59", "86401", "0", "-300", "5m", "1.5").foreach { raw =>
      assert(AppConfig.reminderInterval(raw).isLeft, s"'$raw' should be rejected")
    }
  }

  test("reminderMaxMembers needs a channel of at least two") {
    assertEquals(AppConfig.reminderMaxMembers("10"), Right(10))
    assertEquals(AppConfig.reminderMaxMembers("2"), Right(2))
    List("1", "0", "ten").foreach { raw =>
      assert(AppConfig.reminderMaxMembers(raw).isLeft, s"'$raw' should be rejected")
    }
  }
