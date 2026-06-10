package com.firemoot.service

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.backplane.Backplane
import com.firemoot.config.DbConfig
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.Event
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

class ChannelServiceSuite extends CatsEffectSuite, TestContainerForAll:

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

  test("channel lifecycle: members, frozen, soft-delete, and the events each emits") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (Backplane.inProcess, Ref[IO].of(Vector.empty[Event])).flatMapN { (backplane, seen) =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val messages = MessageService(pool, backplane)

          for
            collector <- backplane.subscribe.evalMap(e => seen.update(_ :+ e)).compile.drain.start
            _ <- IO.sleep(250.millis)

            _ <- users.upsert("alice", Some("Alice"), None, "user", Json.obj())
            _ <- users.upsert("bob", Some("Bob"), None, "user", Json.obj())
            _ <- channels.create("messaging", "general", Some("alice"), Json.obj())

            addedToMissing <- channels.addMember("messaging:ghost", "bob", "member")
            addedBob <- channels.addMember(cid, "bob", "member")
            addedAgain <- channels.addMember(cid, "bob", "member")

            frozen <- channels.update(cid, None, Some(true), None)
            sendWhileFrozen <-
              messages.send(cid, Some("alice"), Some("hi"), Json.obj(), Json.arr(), None)
            _ <- channels.update(cid, None, Some(false), None)

            removedBob <- channels.removeMember(cid, "bob")
            removedAgain <- channels.removeMember(cid, "bob")

            deleted <- channels.softDelete(cid)
            getAfterDelete <- channels.get(cid)

            _ <- IO.sleep(300.millis)
            _ <- collector.cancel
            events <- seen.get
          yield
            assert(!addedToMissing, "adding to a missing channel returns false")
            assert(addedBob, "first add succeeds")
            assert(addedAgain, "repeat add is idempotent (still true)")
            assert(frozen.exists(_.frozen), "update sets frozen")
            assertEquals(sendWhileFrozen, Left(SendError.ChannelFrozen))
            assert(removedBob, "removing a member returns true")
            assert(!removedAgain, "removing a non-member returns false")
            assert(deleted, "soft-delete returns true")
            assertEquals(getAfterDelete, None, "a soft-deleted channel is not found")

            val types = events.map(_.`type`)
            assert(types.contains("member.added"), s"events: $types")
            assert(
              events.exists(e => e.`type` == "member.added" && e.target.isEmpty),
              "member.added is a channel broadcast",
            )
            assert(
              events.exists(e =>
                e.`type` == "notification.added_to_channel" && e.target.contains("bob")
              ),
              "notification.added targets the added user",
            )
            assertEquals(
              types.count(_ == "channel.updated"),
              2,
              "two updates -> two channel.updated",
            )
            assert(types.contains("member.removed"))
            assert(
              events.exists(e =>
                e.`type` == "notification.removed_from_channel" && e.target.contains("bob")
              ),
              "notification.removed targets the removed user",
            )
            assert(types.contains("channel.deleted"))
            assert(!types.contains("message.new"), "no message is sent while frozen")
        }
      }
    }
  }
