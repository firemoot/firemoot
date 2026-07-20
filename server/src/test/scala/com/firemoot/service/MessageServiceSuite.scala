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
import com.firemoot.db.{Database, Migrations}
import com.firemoot.domain.Event
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import skunk.Query
import skunk.codec.all.*
import skunk.implicits.*

class MessageServiceSuite extends CatsEffectSuite, TestContainerForAll:

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

  private val replyCount: Query[String, Int] =
    sql"select reply_count from messages where id = $text".query(int4)

  private val cid = "messaging:general"

  test("edit, soft-delete, thread reply_count, system messages and their events") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (Backplane.inProcess, Ref[IO].of(Vector.empty[Event])).flatMapN { (backplane, seen) =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val messages = MessageService(pool, backplane)

          def send(text: String, parent: Option[String] = None, msgType: String = "regular") =
            messages
              .send(cid, Some("alice"), Some(text), Json.obj(), Json.arr(), parent, msgType)
              .map(_.toOption.get)

          for
            collector <- backplane.subscribe.evalMap(e => seen.update(_ :+ e)).compile.drain.start
            _ <- IO.sleep(250.millis)

            _ <- users.upsert("alice", None, None, "user", Json.obj())
            _ <- channels.create("messaging", "general", Some("alice"), Json.obj())

            parent <- send("parent")
            reply <- send("reply", parent = Some(parent.id))
            replyCountAfterReply <- pool.use(_.runUnique(replyCount, parent.id))
            system <- send("alice joined", msgType = "system")

            edited <- messages.edit(cid, parent.id, Some("edited parent"), None)
            editMissing <- messages.edit(cid, "no-such-message", Some("x"), None)

            deletedReply <- messages.delete(cid, reply.id)
            replyCountAfterDelete <- pool.use(_.runUnique(replyCount, parent.id))
            deleteMissing <- messages.delete(cid, "no-such-message")

            _ <- IO.sleep(300.millis)
            _ <- collector.cancel
            events <- seen.get
          yield
            assertEquals(parent.seq, 1L)
            assertEquals(reply.seq, 2L)
            assertEquals(reply.parentMessageId, Some(parent.id))
            assertEquals(replyCountAfterReply, 1, "a reply increments the parent's reply_count")
            assertEquals(system.`type`, "system")
            assertEquals(edited.map(_.text), Some(Some("edited parent")))
            assertEquals(editMissing, None, "editing a missing message returns None")
            assert(deletedReply)
            assertEquals(
              replyCountAfterDelete,
              0,
              "deleting a reply decrements the parent's reply_count",
            )
            assert(!deleteMissing, "deleting a missing message returns false")

            val types = events.map(_.`type`)
            assertEquals(types.count(_ == "message.new"), 3, "parent, reply and system message")
            assert(types.contains("message.updated"))
            assert(types.contains("message.deleted"))
        }
      }
    }
  }

  test("a client-supplied id round-trips; a duplicate id is refused") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val messages = MessageService(pool, backplane)

          val dedupeCid = "messaging:dedupe"
          def send(id: Option[String]) =
            messages.send(
              dedupeCid,
              Some("alice"),
              Some("hi"),
              Json.obj(),
              Json.arr(),
              None,
              "regular",
              id,
            )

          for
            _ <- users.upsert("alice", None, None, "user", Json.obj())
            _ <- channels.create("messaging", "dedupe", Some("alice"), Json.obj())

            first <- send(Some("cmrxyz_first"))
            duplicate <- send(Some("cmrxyz_first"))
            generated <- send(None)
          yield
            assertEquals(first.map(_.id), Right("cmrxyz_first"), "the client id is used verbatim")
            assertEquals(
              duplicate,
              Left(SendError.DuplicateId("cmrxyz_first")),
              "re-sending an existing id is a DuplicateId, not a second row",
            )
            assert(generated.isRight, "an absent id still server-generates")
            assertNotEquals(
              generated.toOption.map(_.id),
              Some("cmrxyz_first"),
              "the generated id is distinct",
            )
        }
      }
    }
  }
