package com.firemoot.streamapi

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

class StreamFiltersSuite extends FunSuite:

  private def json(s: String): Option[Json] = parse(s).toOption

  test("type, id and members become a cid- and member-scoped channel query") {
    val q = StreamFilters.channelQuery(
      json("""{"type":"messaging","id":{"$in":["a","b"]},"members":{"$in":["alice"]}}"""),
      "QueryChannels",
    )
    assertEquals(q.map(_.`type`), Right(Some("messaging")))
    assertEquals(q.map(_.cids), Right(Some(List("messaging:a", "messaging:b"))))
    assertEquals(q.map(_.members), Right(Some(List("alice"))))
  }

  test("a bare id and an $eq cid are single-value lists; $and merges clauses") {
    val q = StreamFilters.channelQuery(
      json("""{"$and":[{"type":"messaging"},{"cid":{"$eq":"messaging:a"}},{"id":"a"}]}"""),
      "QueryChannels",
    )
    assertEquals(q.map(_.cids), Right(Some(List("messaging:a"))))
  }

  test("unknown fields are custom-data equality, with or without the custom. prefix") {
    val q =
      StreamFilters.channelQuery(json("""{"booking_id":"b1","custom.kind":{"$eq":"stay"}}"""), "Q")
    assertEquals(
      q.map(_.custom),
      Right(Some(Json.obj(
        "booking_id" -> Json.fromString("b1"),
        "kind" -> Json.fromString("stay"),
      ))),
    )
  }

  test("unsupported fields and operators fail loudly with Stream's input error") {
    List(
      """{"frozen":true}""",
      """{"$or":[{"type":"messaging"}]}""",
      """{"topic":{"$gt":"a"}}""",
    ).foreach { f =>
      val result = StreamFilters.channelQuery(json(f), "QueryChannels")
      assertEquals(result.left.map(_.code), Left(4), f)
    }
  }

  test("user filters take {} or an id list") {
    assertEquals(StreamFilters.userIds(json("{}"), "QueryUsers"), Right(None))
    assertEquals(
      StreamFilters.userIds(json("""{"id":{"$in":["a","b"]}}"""), "QueryUsers"),
      Right(Some(List("a", "b"))),
    )
    assertEquals(
      StreamFilters.userIds(json("""{"role":"admin"}"""), "QueryUsers").left.map(_.code),
      Left(4),
    )
  }
