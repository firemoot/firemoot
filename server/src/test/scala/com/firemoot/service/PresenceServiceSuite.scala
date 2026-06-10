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
import com.firemoot.db.{Database, Migrations, UserRepo}
import com.firemoot.domain.Event
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

class PresenceServiceSuite extends CatsEffectSuite, TestContainerForAll:

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

  test("presence.changed reaches co-members only, and offline stamps last_active_at") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      (Backplane.inProcess, Ref[IO].of(Vector.empty[Event])).flatMapN { (backplane, seen) =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val presence = PresenceService(pool, backplane)

          for
            collector <- backplane.subscribe.evalMap(e => seen.update(_ :+ e)).compile.drain.start
            _ <- IO.sleep(250.millis)

            _ <- users.upsert("alice", None, None, "user", Json.obj())
            _ <- users.upsert("bob", None, None, "user", Json.obj())
            _ <- users.upsert("carol", None, None, "user", Json.obj())
            // alice + bob share one channel; carol is off in her own.
            _ <- channels.create("messaging", "general", Some("alice"), Json.obj())
            _ <- channels.addMember("messaging:general", "bob", "member")
            _ <- channels.create("messaging", "other", Some("carol"), Json.obj())

            _ <- presence.online("alice")
            _ <- presence.offline("alice")
            _ <- IO.sleep(300.millis)
            _ <- collector.cancel

            events <- seen.get
            aliceRow <- pool.use(_.runUnique(UserRepo.byId, "alice"))
          yield
            val presenceEvents = events.filter(_.`type` == "presence.changed")

            assert(
              presenceEvents.exists(e =>
                e.target.contains("bob") && status(e).contains("online")
              ),
              "bob (a co-member) is told alice came online",
            )
            assert(
              !presenceEvents.exists(_.target.contains("carol")),
              "carol shares no channel with alice and hears nothing",
            )
            assert(
              !presenceEvents.exists(_.target.contains("alice")),
              "a user is not notified about their own presence",
            )

            val offlineToBob =
              presenceEvents.find(e => e.target.contains("bob") && status(e).contains("offline"))
            assert(offlineToBob.isDefined, "bob is told alice went offline")
            assert(
              offlineToBob.exists(e =>
                e.data.hcursor.get[Option[String]]("lastActiveAt").toOption.flatten.isDefined
              ),
              "the offline event carries the disconnect time",
            )
            assert(aliceRow.lastActiveAt.isDefined, "going offline stamps last_active_at")
        }
      }
    }
  }

  private def status(e: Event): Option[String] =
    e.data.hcursor.get[String]("status").toOption
