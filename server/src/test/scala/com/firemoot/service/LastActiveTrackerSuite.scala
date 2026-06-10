package com.firemoot.service

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

class LastActiveTrackerSuite extends CatsEffectSuite:

  test("debounces writes within the interval, then writes again after it") {
    for
      writes <- Ref[IO].of(0)
      tracker <- LastActiveTracker.create(100.millis)(_ => writes.update(_ + 1))
      _ <- tracker.touch("alice")
      _ <- tracker.touch("alice")
      duringBurst <- writes.get
      _ <- IO.sleep(150.millis)
      _ <- tracker.touch("alice")
      afterInterval <- writes.get
    yield
      assertEquals(duringBurst, 1, "the second touch within the interval must not write")
      assertEquals(afterInterval, 2, "a touch after the interval writes again")
  }

  test("tracks users independently") {
    for
      writes <- Ref[IO].of(0)
      tracker <- LastActiveTracker.create(1.hour)(_ => writes.update(_ + 1))
      _ <- tracker.touch("alice")
      _ <- tracker.touch("bob")
      count <- writes.get
    yield assertEquals(count, 2)
  }
