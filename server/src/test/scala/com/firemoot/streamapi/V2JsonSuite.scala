package com.firemoot.streamapi

import java.time.{OffsetDateTime, ZoneOffset}

import com.firemoot.domain.Message
import io.circe.Json
import munit.FunSuite

class V2JsonSuite extends FunSuite:

  private val at = OffsetDateTime.of(2026, 11, 20, 10, 0, 0, 123_456_789, ZoneOffset.UTC)

  private def msg(
      deleted: Boolean = false,
      parent: Option[String] = None,
      text: Option[String] = Some("hi"),
  ) =
    Message(
      "m1",
      "messaging:a",
      1,
      Some("alice"),
      "regular",
      text,
      Json.obj("k" -> Json.fromInt(1)),
      Json.arr(Json.obj("type" -> Json.fromString("image"))),
      parent,
      0,
      at,
      at,
      Option.when(deleted)(at),
    )

  private val users = V2Json.Users(Map.empty, Set.empty)

  test("timestamps are unix nanoseconds truncated to microseconds") {
    assertEquals(V2Json.ns(at), Json.fromLong(at.toEpochSecond * 1_000_000_000L + 123_456_000L))
  }

  test(
    "a message nests custom data, gives attachments a custom object and types deletes and replies"
  ) {
    val json = V2Json.message(msg(), users, MessageReactions.empty).hcursor
    assertEquals(json.downField("custom").get[Int]("k"), Right(1))
    assertEquals(json.downField("attachments").downN(0).downField("custom").focus, Some(Json.obj()))
    assertEquals(json.get[String]("type"), Right("regular"))
    assertEquals(json.get[String]("html"), Right("<p>hi</p>\n"))
    assertEquals(
      V2Json.message(
        msg(deleted = true),
        users,
        MessageReactions.empty,
      ).hcursor.get[String]("type"),
      Right("deleted"),
    )
    assertEquals(
      V2Json.message(
        msg(parent = Some("p")),
        users,
        MessageReactions.empty,
      ).hcursor.get[String]("type"),
      Right("reply"),
    )
  }

  test("html escapes markup and an empty text renders no html") {
    assertEquals(V2Json.html(Some("<b>&'\"")), "<p>&lt;b&gt;&amp;&#39;&#34;</p>\n")
    assertEquals(V2Json.html(None), "")
  }

  test("members get capabilities including read-events; moderators get more") {
    assert(V2Json.ownCapabilities(Some("member")).contains("read-events"))
    assert(!V2Json.ownCapabilities(Some("member")).contains("update-channel"))
    assert(V2Json.ownCapabilities(Some("owner")).contains("update-channel"))
    assertEquals(V2Json.ownCapabilities(None), Nil)
  }
