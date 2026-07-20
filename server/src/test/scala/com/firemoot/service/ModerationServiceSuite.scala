package com.firemoot.service

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.api.CreateWebhookRequest
import com.firemoot.backplane.Backplane
import com.firemoot.config.DbConfig
import com.firemoot.db.{Database, Migrations}
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import skunk.circe.codec.all.jsonb
import skunk.implicits.*

class ModerationServiceSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val cid = "messaging:general"

  private val deliveredEvents = sql"select event from webhook_deliveries".query(jsonb[Json])

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  test("flagging queues a flag, captures the author, and enqueues user.flagged") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val messages = MessageService(pool, backplane)
          val webhooks = WebhookService(pool)
          val moderation = ModerationService(pool, webhooks)

          for
            _ <- users.upsert("alice", None, None, "user", Json.obj())
            _ <- users.upsert("bob", None, None, "user", Json.obj())
            _ <- channels.create("messaging", "general", Some("alice"), Json.obj())
            msg <- messages
              .send(cid, Some("alice"), Some("rude words"), Json.obj(), Json.arr(), None, "regular")
              .map(_.toOption.get)
            _ <-
              webhooks.register(CreateWebhookRequest("http://example.test/hook", None, Some(true)))

            flagged <- moderation.flag(cid, msg.id, "bob", Some("spam"))
            missing <- moderation.flag(cid, "no-such-message", "bob", None)
            open <- moderation.listFlags("open")
            events <- pool.use(_.execute(deliveredEvents))
          yield
            assert(flagged.isDefined, "the flag is created")
            val flag = flagged.get
            assertEquals(flag.flaggedUser, Some("alice"), "the message author is captured")
            assertEquals(flag.flaggedBy, "bob")
            assertEquals(flag.reason, Some("spam"))
            assertEquals(flag.status, "open")
            assertEquals(missing, None, "flagging an absent message is a 404")
            assertEquals(open.map(_.id), List(flag.id), "the open queue lists the flag")

            val userFlagged =
              events.filter(e => e.hcursor.get[String]("type").contains("user.flagged"))
            assertEquals(userFlagged.size, 1, "one user.flagged delivery was enqueued")
            val data = userFlagged.head.hcursor.downField("data")
            assertEquals(data.get[String]("flaggedUser").toOption, Some("alice"))
            assertEquals(data.get[String]("flaggedBy").toOption, Some("bob"))
        }
      }
    }
  }
