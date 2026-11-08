package com.firemoot.webhook

import scala.concurrent.duration.*

import cats.effect.std.Queue
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForEach
import com.firemoot.api.{CreateWebhookRequest, UpdateWebhookRequest}
import com.firemoot.backplane.Backplane
import com.firemoot.config.DbConfig
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.{Event, WebhookFormat}
import com.firemoot.service.{
  ChannelService,
  MessageService,
  ModerationService,
  ReactionService,
  ReadService,
  UserService,
  WebhookService,
}
import io.circe.syntax.*
import io.circe.{ACursor, Json}
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Session, Void}

class StreamFormatSuite extends CatsEffectSuite, TestContainerForEach:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val cid = "messaging:general"

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  private val deliveries =
    sql"""select e.format, d.event from webhook_deliveries d
          join webhook_endpoints e on e.id = d.endpoint_id"""
      .query(text *: jsonb[Json])

  private val clearDeliveries = sql"delete from webhook_deliveries".command

  private def at(json: Json, path: String*): ACursor =
    path.foldLeft[ACursor](json.hcursor)(_.downField(_))

  private def str(json: Json, path: String*): Option[String] = at(json, path*).as[String].toOption

  private def long(json: Json, path: String*): Option[Long] = at(json, path*).as[Long].toOption

  private def has(json: Json, path: String*): Boolean = at(json, path*).succeeded

  /** Bodies queued per format since the last drain, keyed by event type. */
  final private case class Queued(stream: Map[String, Json], firemoot: Map[String, Json])

  /**
   * Runs `body` with a `settle(n)` that waits for the next `n` deliverable
   * backplane events, enqueues them as Main does, and returns every body queued
   * since the last settle (including ones enqueued directly, like flags).
   */
  private def withPipeline(
      body: (Resource[IO, Session[IO]], Backplane, Int => IO[Queued]) => IO[Unit]
  ): IO[Unit] =
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (Backplane.inProcess, Queue.unbounded[IO, Event]).flatMapN { (backplane, events) =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val webhooks = WebhookService(pool)
          // The same filter Main applies; the test enqueues each event as it lands.
          val collect = backplane.subscribe
            .filter(WebhookService.isDeliverable)
            .evalMap(events.offer)
            .compile
            .drain
          def settle(n: Int): IO[Queued] = (IO.sleep(10.millis) >> events.size).iterateUntil(_ >=
            n).timeout(5.seconds) >>
            events.tryTakeN(None).flatMap(_.traverse_(webhooks.enqueue)) >>
            pool.use { s =>
              s.execute(deliveries).flatTap(_ => s.execute(clearDeliveries))
            }.map { rows =>
              def byType(format: String) =
                rows.collect {
                  case (f, e) if f == format => e.hcursor.get[String]("type").toOption.get -> e
                }.toMap
              Queued(byType("stream"), byType("firemoot"))
            }
          collect.background.use { _ =>
            IO.sleep(250.millis) >> body(pool, backplane, settle)
          }
        }
      }
    }

  test("a stream endpoint gets Stream's payloads while a firemoot endpoint keeps the envelope") {
    withPipeline { (pool, backplane, settle) =>
      val users = UserService(pool)
      val channels = ChannelService(pool, backplane)
      val messages = MessageService(pool, backplane)
      val reactions = ReactionService(pool, backplane)
      val reads = ReadService(pool, backplane)
      val webhooks = WebhookService(pool)
      val moderation = ModerationService(pool, webhooks)

      for
        stream <- webhooks.register(CreateWebhookRequest("http://s.test/h", None, Some(true)))
        legacy <- webhooks.register(
          CreateWebhookRequest("http://f.test/h", None, Some(true), Some(WebhookFormat.Firemoot))
        )
        _ <- users.upsert("alice", Some("Alice"), None, "user", Json.obj("tier" -> "gold".asJson))
        _ <- users.upsert("bob", Some("Bob"), None, "user", Json.obj())
        _ <- channels.create("messaging", "general", Some("alice"), Json.obj("topic" -> "x".asJson))

        added <- channels.addMember(cid, "bob", "member") >> settle(1)
        sent <- messages
          .send(cid, Some("alice"), Some("hi"), Json.obj("lang" -> "en".asJson), Json.arr(), None)
          .map(_.toOption.get)
        newMsg <- settle(1)
        edited <- messages.edit(cid, sent.id, Some("hi there"), None) >> settle(1)
        reacted <- reactions.add(cid, sent.id, "bob", "like") >> settle(1)
        unreacted <- reactions.remove(cid, sent.id, "bob", "like") >> settle(1)
        read <- reads.markRead(cid, "bob", None) >> settle(1)
        flagged <- moderation.flag(cid, sent.id, "bob", Some("spam")) >> settle(0)
        updated <- channels.update(cid, None, Some(true), None) >> settle(1)
        removed <- channels.removeMember(cid, "bob") >> settle(1)
        deleted <- messages.delete(cid, sent.id) >> settle(1)
        channelGone <- channels.softDelete(cid) >> settle(1)
      yield
        assertEquals(stream.format, WebhookFormat.Stream, "new endpoints default to stream")
        assertEquals(legacy.format, WebhookFormat.Firemoot)

        val memberAdded = added.stream("member.added")
        assertEquals(str(memberAdded, "cid"), Some(cid))
        assertEquals(str(memberAdded, "channel_id"), Some("general"))
        assertEquals(str(memberAdded, "channel_type"), Some("messaging"))
        assertEquals(str(memberAdded, "member", "user_id"), Some("bob"))
        assertEquals(str(memberAdded, "member", "user", "name"), Some("Bob"))
        assertEquals(str(memberAdded, "member", "channel_role"), Some("channel_member"))
        assertEquals(str(memberAdded, "user", "id"), Some("bob"))
        assert(has(memberAdded, "created_at"))

        val msgNew = newMsg.stream("message.new")
        assertEquals(str(msgNew, "message", "id"), Some(sent.id))
        assertEquals(str(msgNew, "message", "text"), Some("hi"))
        assertEquals(str(msgNew, "message", "user", "id"), Some("alice"))
        assertEquals(str(msgNew, "message", "user", "tier"), Some("gold"), "user custom is spread")
        assertEquals(str(msgNew, "message", "lang"), Some("en"), "message custom is spread")
        assertEquals(str(msgNew, "user", "id"), Some("alice"))
        assertEquals(
          at(msgNew, "members").as[List[Json]].map(_.flatMap(str(_, "user_id"))),
          Right(List("alice", "bob")),
        )
        assert(!has(msgNew, "data") && !has(msgNew, "seq"), "no Firemoot envelope")

        val legacyNew = newMsg.firemoot("message.new")
        assertEquals(str(legacyNew, "data", "userId"), Some("alice"))
        assertEquals(long(legacyNew, "seq"), Some(sent.seq))

        assertEquals(str(edited.stream("message.updated"), "message", "text"), Some("hi there"))

        val reactionNew = reacted.stream("reaction.new")
        assertEquals(str(reactionNew, "reaction", "type"), Some("like"))
        assertEquals(str(reactionNew, "reaction", "user", "id"), Some("bob"))
        assertEquals(long(reactionNew, "message", "reaction_counts", "like"), Some(1L))
        assertEquals(
          at(reactionNew, "message", "latest_reactions").downN(0).downField("user_id").as[String]
            .toOption,
          Some("bob"),
        )
        assertEquals(str(reactionNew, "user", "id"), Some("bob"))

        val reactionGone = unreacted.stream("reaction.deleted")
        assertEquals(at(reactionGone, "message", "reaction_counts").focus, Some(Json.obj()))
        assertEquals(str(reactionGone, "reaction", "type"), Some("like"))

        assertEquals(
          read.stream.keySet,
          Set("message.read"),
          "read.updated is Stream's message.read",
        )
        assertEquals(read.firemoot.keySet, Set("read.updated"))
        val msgRead = read.stream("message.read")
        assertEquals(str(msgRead, "user", "id"), Some("bob"))
        assertEquals(long(msgRead, "user", "channel_unread_count"), Some(0L))

        assertEquals(flagged.stream.keySet, Set("message.flagged"))
        val msgFlagged = flagged.stream("message.flagged")
        assertEquals(str(msgFlagged, "user", "id"), Some("bob"))
        assertEquals(str(msgFlagged, "message", "user", "id"), Some("alice"))
        assertEquals(str(msgFlagged, "reason"), Some("spam"))
        assertEquals(long(msgFlagged, "total_flags"), Some(1L))

        val chanUpdated = updated.stream("channel.updated")
        assertEquals(str(chanUpdated, "channel", "cid"), Some(cid))
        assertEquals(at(chanUpdated, "channel", "frozen").as[Boolean].toOption, Some(true))
        assertEquals(str(chanUpdated, "channel", "topic"), Some("x"), "channel custom is spread")
        assertEquals(str(chanUpdated, "channel", "created_by", "id"), Some("alice"))
        assertEquals(long(chanUpdated, "channel", "member_count"), Some(2L))

        val memberRemoved = removed.stream("member.removed")
        assertEquals(str(memberRemoved, "user", "id"), Some("bob"))
        assertEquals(str(memberRemoved, "member", "user_id"), Some("bob"))

        val msgDeleted = deleted.stream("message.deleted")
        assertEquals(str(msgDeleted, "message", "id"), Some(sent.id))
        assert(has(msgDeleted, "message", "deleted_at"))
        assert(!has(msgDeleted, "message", "text"), "Firemoot's soft delete scrubs the text")

        assert(has(channelGone.stream("channel.deleted"), "channel", "deleted_at"))
        assertEquals(
          channelGone.firemoot.keySet,
          Set("channel.deleted"),
          "the firemoot endpoint still gets every event",
        )
    }
  }

  test("an endpoint migrates by flipping its format, and unused formats are never built") {
    withPipeline { (pool, backplane, settle) =>
      val users = UserService(pool)
      val channels = ChannelService(pool, backplane)
      val messages = MessageService(pool, backplane)
      val webhooks = WebhookService(pool)
      def send(text: String) =
        messages.send(cid, Some("alice"), Some(text), Json.obj(), Json.arr(), None) >> settle(1)
      for
        ep <- webhooks.register(
          CreateWebhookRequest("http://f.test/h", None, Some(true), Some(WebhookFormat.Firemoot))
        )
        _ <- users.upsert("alice", None, None, "user", Json.obj())
        _ <- channels.create("messaging", "general", Some("alice"), Json.obj())
        before <- send("before")
        flipped <- webhooks.update(ep.id, UpdateWebhookRequest(None, Some(WebhookFormat.Stream)))
        listed <- webhooks.list
        after <- send("after")
        missing <- webhooks.update("no-such-id", UpdateWebhookRequest(Some(false), None))
      yield
        assertEquals(before.firemoot.keySet, Set("message.new"))
        assertEquals(before.stream, Map.empty, "no stream endpoint, so no Stream body")
        assertEquals(flipped.map(_.format), Some(WebhookFormat.Stream))
        assertEquals(flipped.map(_.enabled), Some(true), "an absent field is left unchanged")
        assertEquals(listed.map(_.format), List(WebhookFormat.Stream))
        assertEquals(after.firemoot, Map.empty)
        assertEquals(str(after.stream("message.new"), "message", "text"), Some("after"))
        assertEquals(missing, None)
    }
  }

  test("message.new lists members only for channels of up to 100") {
    withPipeline { (pool, backplane, _) =>
      val users = UserService(pool)
      val channels = ChannelService(pool, backplane)
      val messages = MessageService(pool, backplane)
      val big = "messaging:big"
      for
        _ <- (1 to 101).toList.traverse_(i => users.upsert(s"u$i", None, None, "user", Json.obj()))
        _ <- channels.create("messaging", "big", Some("u1"), Json.obj())
        _ <- (2 to 101).toList.traverse_(i => channels.addMember(big, s"u$i", "member"))
        sent <- messages.send(big, Some("u1"), Some("hi all"), Json.obj(), Json.arr(), None)
          .map(_.toOption.get)
        now <- IO.realTimeInstant.map(_.atOffset(java.time.ZoneOffset.UTC))
        rendered <- pool.use { s =>
          StreamFormat.render(s, Event("message.new", big, sent.seq, sent.asJson), now)
        }
      yield
        val body = rendered.getOrElse(fail("message.new should render"))
        assertEquals(str(body, "message", "text"), Some("hi all"))
        assert(!has(body, "members"), "101 members is over the cap, so none are listed")
    }
  }
