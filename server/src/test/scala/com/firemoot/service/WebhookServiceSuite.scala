package com.firemoot.service

import com.firemoot.domain.Event
import io.circe.Json
import munit.FunSuite

class WebhookServiceSuite extends FunSuite:

  test("only persisted channel-broadcast events are deliverable") {
    val persisted = Event("message.new", "messaging:general", 3, Json.obj())
    val typing = Event("typing.start", "messaging:general", 0, Json.obj())
    val targeted = Event.notification("presence.changed", "", "alice", Json.obj())

    assert(WebhookService.isDeliverable(persisted))
    assert(!WebhookService.isDeliverable(typing), "ephemeral (seq 0) events are not webhooked")
    assert(!WebhookService.isDeliverable(targeted), "user-directed events are not webhooked")
  }
