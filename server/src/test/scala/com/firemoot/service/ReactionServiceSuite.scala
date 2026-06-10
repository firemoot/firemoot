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

class ReactionServiceSuite extends CatsEffectSuite, TestContainerForAll:

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

  test("reactions: add (idempotent), remove, per-type counts and events") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (Backplane.inProcess, Ref[IO].of(Vector.empty[Event])).flatMapN { (backplane, seen) =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val messages = MessageService(pool, backplane)
          val reactions = ReactionService(pool, backplane)

          for
            collector <- backplane.subscribe.evalMap(e => seen.update(_ :+ e)).compile.drain.start
            _ <- IO.sleep(250.millis)

            _ <- users.upsert("alice", None, None, "user", Json.obj())
            _ <- users.upsert("bob", None, None, "user", Json.obj())
            _ <- channels.create("messaging", "general", Some("alice"), Json.obj())
            message <- messages
              .send(cid, Some("alice"), Some("hi"), Json.obj(), Json.arr(), None)
              .map(_.toOption.get)

            c1 <- reactions.add(cid, message.id, "alice", "like")
            c2 <- reactions.add(cid, message.id, "bob", "like")
            c3 <- reactions.add(cid, message.id, "bob", "like") // idempotent
            c4 <- reactions.add(cid, message.id, "alice", "heart")
            missing <- reactions.add(cid, java.util.UUID.randomUUID(), "alice", "like")

            afterRemove <- reactions.remove(cid, message.id, "alice", "like")
            removeAgain <- reactions.remove(cid, message.id, "alice", "like")

            _ <- IO.sleep(300.millis)
            _ <- collector.cancel
            events <- seen.get
          yield
            assertEquals(c1, Some(Map("like" -> 1L)))
            assertEquals(c2, Some(Map("like" -> 2L)))
            assertEquals(c3, Some(Map("like" -> 2L)), "repeat add does not change counts")
            assertEquals(c4, Some(Map("like" -> 2L, "heart" -> 1L)))
            assertEquals(missing, None, "reacting to a missing message returns None")
            assertEquals(afterRemove, Some(Map("like" -> 1L, "heart" -> 1L)))
            assertEquals(
              removeAgain,
              Some(Map("like" -> 1L, "heart" -> 1L)),
              "removing an absent reaction is a no-op",
            )

            val types = events.map(_.`type`)
            assertEquals(
              types.count(_ == "reaction.new"),
              3,
              "3 real adds (the repeat emits nothing)",
            )
            assertEquals(types.count(_ == "reaction.deleted"), 1, "1 real remove")
        }
      }
    }
  }
