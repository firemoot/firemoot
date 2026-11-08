package com.firemoot.service

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.api.CreateWebhookRequest
import com.firemoot.domain.WebhookFormat
import com.firemoot.backplane.Backplane
import com.firemoot.config.DbConfig
import com.firemoot.db.{Database, Migrations}
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*
import skunk.implicits.*

class ModerationServiceSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val cid = "messaging:general"

  private val deliveredEvents =
    sql"""select e.format, d.event from webhook_deliveries d
          join webhook_endpoints e on e.id = d.endpoint_id""".query(text *: jsonb[Json])

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
            _ <- webhooks.register(CreateWebhookRequest("http://s.test/hook", None, Some(true)))
            _ <- webhooks.register(
              CreateWebhookRequest(
                "http://f.test/hook",
                None,
                Some(true),
                Some(WebhookFormat.Firemoot),
              )
            )

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

            def ofType(format: String, eventType: String) =
              events.collect {
                case (f, e) if f == format && e.hcursor.get[String]("type").contains(eventType) => e
              }
            val userFlagged = ofType("firemoot", "user.flagged")
            assertEquals(userFlagged.size, 1, "one user.flagged delivery was enqueued")
            val data = userFlagged.head.hcursor.downField("data")
            assertEquals(data.get[String]("flaggedUser").toOption, Some("alice"))
            assertEquals(data.get[String]("flaggedBy").toOption, Some("bob"))
            val messageFlagged = ofType("stream", "message.flagged")
            assertEquals(
              messageFlagged.size,
              1,
              "the stream endpoint gets Stream's message.flagged",
            )
            val flaggedEvent = messageFlagged.head.hcursor
            assertEquals(flaggedEvent.downField("user").get[String]("id").toOption, Some("bob"))
            assertEquals(
              flaggedEvent.downField("message").downField("user").get[String]("id").toOption,
              Some("alice"),
            )
        }
      }
    }
  }
