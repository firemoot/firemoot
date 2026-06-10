package com.firemoot.backplane

import scala.concurrent.duration.*

import cats.effect.IO
import com.firemoot.domain.Event
import io.circe.Json
import munit.CatsEffectSuite

class BackplaneSuite extends CatsEffectSuite:

  private def event(seq: Long) = Event("message.new", "messaging:general", seq, Json.obj())

  test("published events reach an active subscriber in order") {
    for
      bp <- Backplane.inProcess
      collector <- bp.subscribe.take(2).compile.toList.start
      _ <- IO.sleep(250.millis) // let the subscription register before publishing
      _ <- bp.publish(event(1))
      _ <- bp.publish(event(2))
      received <- collector.joinWithNever
    yield assertEquals(received.map(_.seq), List(1L, 2L))
  }
