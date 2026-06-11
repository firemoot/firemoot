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
