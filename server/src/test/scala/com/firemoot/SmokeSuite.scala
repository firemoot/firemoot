package com.firemoot

import cats.effect.IO
import munit.CatsEffectSuite

class SmokeSuite extends CatsEffectSuite:

  test("cats-effect IO evaluates under munit"):
    IO.pure(2 + 2).map(assertEquals(_, 4))

  test("pure smoke check"):
    assertEquals("firemoot".length, 8)
