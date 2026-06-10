package com.firemoot.service

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.backplane.Backplane
import com.firemoot.config.DbConfig
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations, ReadRepo}
import com.firemoot.domain.Event
import com.firemoot.domain.Unread.Msg
import com.firemoot.domain.Unread
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

class ReadServiceSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  private val cid = "messaging:general"

  test("markRead, unread counts (own/system/deleted excluded), read.updated and total") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (Backplane.inProcess, Ref[IO].of(Vector.empty[Event])).flatMapN { (backplane, seen) =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val messages = MessageService(pool, backplane)
          val reads = ReadService(pool, backplane)

          def send(author: Option[String], text: String, msgType: String = "regular") =
            messages.send(
              cid,
              author,
              Some(text),
              Json.obj(),
              Json.arr(),
              None,
              msgType,
            ).map(_.toOption.get)

          for
            collector <- backplane.subscribe.evalMap(e => seen.update(_ :+ e)).compile.drain.start
            _ <- IO.sleep(250.millis)

            _ <- users.upsert("alice", None, None, "user", Json.obj())
            _ <- users.upsert("bob", None, None, "user", Json.obj())
            _ <- channels.create("messaging", "general", Some("alice"), Json.obj())
            _ <- channels.addMember(cid, "bob", "member")

            _ <- send(Some("alice"), "a1")
            _ <- send(Some("bob"), "b1")
            _ <- send(Some("alice"), "a2")
            _ <- send(None, "joined", "system")
            doomed <- send(Some("alice"), "a3")
            _ <- messages.delete(cid, doomed.id)

            unreadBob <- pool.use(_.runUnique(ReadRepo.channelUnread, ("bob", cid)))
            unreadAlice <- pool.use(_.runUnique(ReadRepo.channelUnread, ("alice", cid)))

            marked <- reads.markRead(cid, "bob", None)
            unreadBobAfter <- pool.use(_.runUnique(ReadRepo.channelUnread, ("bob", cid)))
            totalBobAfter <- pool.use(_.runUnique(ReadRepo.totalUnread, "bob"))
            markedNonMember <- reads.markRead(cid, "stranger", None)

            _ <- IO.sleep(300.millis)
            _ <- collector.cancel
            events <- seen.get
          yield
            // Formula oracle for bob: a1 + a2 count; b1 own, system & deleted excluded.
            val oracle = Unread.count(
              List(
                Msg(1, Some("alice"), system = false, deleted = false),
                Msg(2, Some("bob"), system = false, deleted = false),
                Msg(3, Some("alice"), system = false, deleted = false),
                Msg(4, None, system = true, deleted = false),
                Msg(5, Some("alice"), system = false, deleted = true),
              ),
              lastReadSeq = 0,
              viewer = "bob",
            )
            assertEquals(oracle, 2)
            assertEquals(unreadBob, 2L, "SQL unread for bob matches the formula")
            assertEquals(unreadAlice, 1L, "alice's only unread is b1")
            assert(marked.exists(_.unreadCount == 0L), "after markRead bob has 0 unread")
            assertEquals(unreadBobAfter, 0L)
            assertEquals(totalBobAfter, 0L)
            assertEquals(markedNonMember, None, "marking read as a non-member returns None")

            val readEvents = events.filter(_.`type` == "read.updated")
            assert(
              readEvents.exists(_.target.isEmpty),
              "a channel-broadcast read receipt is emitted",
            )
            assert(
              readEvents.exists(_.target.contains("bob")),
              "a targeted badge event is sent to bob",
            )
        }
      }
    }
  }
