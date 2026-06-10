package com.firemoot.service

import java.util.UUID

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.backplane.Backplane
import com.firemoot.config.DbConfig
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations, UserRepo}
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import skunk.Query
import skunk.codec.all.*
import skunk.implicits.*

class UserServiceSuite extends CatsEffectSuite, TestContainerForAll:

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

  private val insertReaction =
    sql"insert into reactions (message_id, user_id, type) values ($uuid, $text, $text)".command
  private val messageState: Query[UUID, (Option[String], Option[String], Boolean)] =
    sql"select text, user_id, (deleted_at is not null) from messages where id = $uuid"
      .query(text.opt *: text.opt *: bool)
  private val countMembers: Query[String, Long] =
    sql"select count(*) from channel_members where user_id = $text".query(int8)
  private val countReactions: Query[String, Long] =
    sql"select count(*) from reactions where user_id = $text".query(int8)

  test("GDPR delete scrubs authored messages and removes membership, reactions and the user") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val messages = MessageService(pool, backplane)

          for
            _ <- users.upsert("alice", Some("Alice"), None, "user", Json.obj())
            _ <- channels.create("messaging", "general", Some("alice"), Json.obj())
            msg <- messages
              .send(
                "messaging:general",
                Some("alice"),
                Some("secret text"),
                Json.obj(),
                Json.arr(),
                None,
              )
              .map(_.toOption.get)
            _ <- pool.use(_.run(insertReaction, (msg.id, "alice", "like")))

            membersBefore <- pool.use(_.runUnique(countMembers, "alice"))
            reactionsBefore <- pool.use(_.runUnique(countReactions, "alice"))

            deleted <- users.delete("alice")
            userAfter <- pool.use(_.runOption(UserRepo.byId, "alice"))
            msgAfter <- pool.use(_.runUnique(messageState, msg.id))
            membersAfter <- pool.use(_.runUnique(countMembers, "alice"))
            reactionsAfter <- pool.use(_.runUnique(countReactions, "alice"))
            deletedAgain <- users.delete("alice")
          yield
            assertEquals(membersBefore, 1L)
            assertEquals(reactionsBefore, 1L)
            assert(deleted, "delete should report the user existed")
            assertEquals(userAfter, None, "the user row must be gone")
            assertEquals(
              msgAfter,
              (None, None, true),
              "the authored message must be scrubbed (text null, user_id null, tombstoned)",
            )
            assertEquals(membersAfter, 0L, "memberships must cascade")
            assertEquals(reactionsAfter, 0L, "reactions must cascade")
            assert(!deletedAgain, "re-deleting a missing user reports false")
        }
      }
    }
  }
