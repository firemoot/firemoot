package com.firemoot.webhook

import java.time.{OffsetDateTime, ZoneOffset}

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForEach
import com.firemoot.api.CreateWebhookRequest
import com.firemoot.backplane.Backplane
import com.firemoot.config.{DbConfig, UnreadReminderConfig}
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.Message
import com.firemoot.service.{
  ChannelService,
  MessageService,
  ReadService,
  UserService,
  WebhookService,
}
import io.circe.{ACursor, Json}
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import org.typelevel.log4cats.noop.NoOpLogger
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Session, Void}

class UnreadReminderSuite extends CatsEffectSuite, TestContainerForEach:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val reminderCfg = UnreadReminderConfig(interval = 5.minutes, maxMembers = 10)

  private val tuning = ReminderTuning(sweepInterval = 1.second, lookback = 1.hour, batchSize = 500)

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  private val backdateMessage =
    sql"update messages set created_at = now() - make_interval(mins => $int4) where id = $text"
      .command

  private val queuedEvents =
    sql"select event from webhook_deliveries order by created_at, id".query(jsonb[Json])

  private val clearQueue = sql"delete from webhook_deliveries".command

  final private class Fixture(pool: Resource[IO, Session[IO]], backplane: Backplane):
    val users = UserService(pool)
    val channels = ChannelService(pool, backplane)
    val messages = MessageService(pool, backplane)
    val reads = ReadService(pool, backplane)

    def worker(cfg: UnreadReminderConfig = reminderCfg, t: ReminderTuning = tuning) =
      UnreadReminderWorker(pool, cfg, NoOpLogger[IO], t)

    def setup(userIds: String*): IO[Unit] =
      userIds.toList.traverse_(users.upsert(_, None, None, "user", Json.obj())) >>
        WebhookService(pool)
          .register(CreateWebhookRequest("http://127.0.0.1:1/hook", None, Some(true)))
          .void

    def channel(id: String, owner: String, members: String*): IO[String] =
      channels.create("messaging", id, Some(owner), Json.obj()).map(_.toOption.get) >>
        members.toList.traverse_(channels.addMember(s"messaging:$id", _, "member")).as(
          s"messaging:$id"
        )

    def send(cid: String, author: Option[String], msgType: String = "regular"): IO[Message] =
      messages.send(cid, author, Some("hi"), Json.obj(), Json.arr(), None, msgType)
        .map(_.toOption.get)

    def backdate(message: Message, minutes: Int): IO[Unit] =
      pool.use(_.run(backdateMessage, (minutes, message.id)))

    def queued: IO[List[Json]] = pool.use(_.runList(queuedEvents, Void))

    def drain: IO[List[Json]] = queued <* pool.use(_.run(clearQueue, Void))

  private def withFixture(body: Fixture => IO[Unit]): IO[Unit] =
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use(pool => body(Fixture(pool, backplane)))
      }
    }

  private def now: IO[OffsetDateTime] = IO.realTimeInstant.map(_.atOffset(ZoneOffset.UTC))

  private def str(json: Json, path: String*): Option[String] =
    path.foldLeft[ACursor](json.hcursor)(_.downField(_)).as[String].toOption

  private def userOf(event: Json): Option[String] = str(event, "data", "user", "id")

  private def channelsOf(event: Json): List[Json] =
    event.hcursor.downField("data").downField("channels").as[List[Json]].getOrElse(Nil)

  test("reminds once the oldest unread message waits out the interval, once per burst") {
    withFixture { f =>
      for
        _ <- f.setup("alice", "bob")
        cid <- f.channel("dm", "alice", "bob")
        fromAlice <- (1 to 6).toList.traverse(_ => f.send(cid, Some("alice")))
        fromBob <- f.send(cid, Some("bob"))
        first = fromAlice.head

        _ <- f.backdate(first, 2)
        notYet <- now.flatMap(f.worker().sweepAt)

        _ <- f.backdate(first, 6)
        sent <- now.flatMap(f.worker().sweepAt)
        events <- f.drain

        again <- now.flatMap(f.worker().sweepAt)

        next <- f.send(cid, Some("alice"))
        _ <- f.backdate(next, 6)
        rearmed <- now.flatMap(f.worker().sweepAt)
        rearmedEvents <- f.drain
      yield
        assertEquals(notYet, 0, "two minutes is inside the five-minute interval")
        assertEquals(sent, 1)
        assertEquals(events.size, 1, "bob is reminded; alice's only unread is bob's fresh message")
        val event = events.head
        assertEquals(str(event, "type"), Some(UnreadReminder.EventType))
        assertEquals(userOf(event), Some("bob"))
        assertEquals(event.hcursor.downField("cid").focus, None, "a user-level event has no cid")
        val channel = channelsOf(event).head
        assertEquals(str(channel, "channel", "cid"), Some(cid))
        assertEquals(
          channel.hcursor.downField("members").as[List[Json]].map(_.flatMap(str(_, "userId"))),
          Right(List("alice", "bob")),
        )
        val seqs = channel.hcursor.downField("messages").as[List[Json]]
          .map(_.flatMap(_.hcursor.get[Long]("seq").toOption))
        val newestFive = (fromAlice :+ fromBob).map(_.seq).sorted.reverse.take(5)
        assertEquals(seqs, Right(newestFive), "the newest five, newest first, bob's own included")
        assertEquals(again, 0, "an unread burst is reminded once")
        assertEquals(rearmed, 1, "a new message re-arms the reminder")
        assertEquals(rearmedEvents.flatMap(userOf), List("bob"))
    }
  }

  test("own, system and deleted messages never trigger a reminder, and reading cancels one") {
    withFixture { f =>
      for
        _ <- f.setup("alice", "bob")
        cid <- f.channel("dm", "alice", "bob")
        system <- f.send(cid, None, "system")
        doomed <- f.send(cid, Some("alice"))
        _ <- f.messages.delete(cid, doomed.id)
        _ <- f.backdate(system, 10) >> f.backdate(doomed, 10)
        ignored <- now.flatMap(f.worker().sweepAt)

        unread <- f.send(cid, Some("alice"))
        _ <- f.backdate(unread, 10)
        _ <- f.reads.markRead(cid, "bob", None)
        afterRead <- now.flatMap(f.worker().sweepAt)
        queued <- f.queued
      yield
        assertEquals(ignored, 0)
        assertEquals(afterRead, 0, "bob read the message before the sweep")
        assertEquals(queued, Nil)
    }
  }

  test("skips channels above the member cap and deleted channels") {
    withFixture { f =>
      val crowd = (1 to 10).map(i => s"user$i")
      for
        _ <- f.setup(("alice" +: "bob" +: crowd)*)
        big <- f.channel("big", "alice", crowd*)
        bigMsg <- f.send(big, Some("alice"))
        gone <- f.channel("gone", "alice", "bob")
        goneMsg <- f.send(gone, Some("alice"))
        _ <- f.channels.softDelete(gone)
        _ <- f.backdate(bigMsg, 10) >> f.backdate(goneMsg, 10)
        skipped <- now.flatMap(f.worker().sweepAt)
        widened <- now.flatMap(f.worker(reminderCfg.copy(maxMembers = 11)).sweepAt)
      yield
        assertEquals(skipped, 0, "11 members is over the cap of 10; the other channel is deleted")
        assertEquals(widened, 10, "raising the cap reminds each of the ten other members")
    }
  }

  test("unread history older than the lookback window is never reminded") {
    withFixture { f =>
      for
        _ <- f.setup("alice", "bob")
        cid <- f.channel("dm", "alice", "bob")
        old <- f.send(cid, Some("alice"))
        _ <- f.backdate(old, 180)
        stale <- now.flatMap(f.worker().sweepAt)

        fresh <- f.send(cid, Some("alice"))
        early <- now.flatMap(f.worker().sweepAt)

        _ <- f.backdate(fresh, 6)
        due <- now.flatMap(f.worker().sweepAt)
      yield
        assertEquals(stale, 0, "three hours old is past the one-hour lookback")
        assertEquals(early, 0, "the stale message does not make a fresh one due early")
        assertEquals(due, 1, "the fresh message is reminded on its own clock")
    }
  }

  test("splits a user's channels into events of ten and drains past the batch size") {
    withFixture { f =>
      for
        _ <- f.setup("alice", "bob")
        cids <- (1 to 12).toList.traverse(i => f.channel(s"c$i", "alice", "bob"))
        _ <- cids.traverse_(cid => f.send(cid, Some("alice")).flatMap(f.backdate(_, 6)))
        sent <- now.flatMap(f.worker(t = tuning.copy(batchSize = 5)).sweepAt)
        events <- f.queued
      yield
        assertEquals(sent, 3, "batches of 5, 5 and 2 claims each make one event for bob")
        assertEquals(events.flatMap(userOf).distinct, List("bob"))
        assertEquals(events.map(channelsOf(_).size).sum, 12, "every channel is reminded once")
        assert(events.forall(channelsOf(_).size <= UnreadReminder.MaxChannelsPerEvent))
    }
  }

  test("a whole sweep's claims for one user share events of up to ten channels") {
    withFixture { f =>
      for
        _ <- f.setup("alice", "bob")
        cids <- (1 to 12).toList.traverse(i => f.channel(s"c$i", "alice", "bob"))
        _ <- cids.traverse_(cid => f.send(cid, Some("alice")).flatMap(f.backdate(_, 6)))
        sent <- now.flatMap(f.worker().sweepAt)
        events <- f.queued
      yield
        assertEquals(sent, 2)
        assertEquals(events.map(channelsOf(_).size).sorted, List(2, 10))
    }
  }
