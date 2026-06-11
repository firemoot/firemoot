package com.firemoot.metrics

import java.time.temporal.ChronoUnit
import java.time.{LocalDate, OffsetDateTime, ZoneOffset}

import cats.effect.IO
import cats.syntax.all.*
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.backplane.Backplane
import com.firemoot.config.DbConfig
import com.firemoot.db.MetricsRepo
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{Database, Migrations}
import com.firemoot.service.{ChannelService, MessageService, UserService}
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import skunk.codec.all.*
import skunk.implicits.*

class MetricsServiceSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val hourlyValue =
    sql"select value from metrics_hourly where metric = $text and ts = $timestamptz".query(float8)

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  test("captures activity, computes windowed actives, snapshots, rolls up CCU and prunes") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Backplane.inProcess.flatMap { backplane =>
        Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
          val metrics = MetricsService(pool)
          val users = UserService(pool)
          val channels = ChannelService(pool, backplane)
          val messages = MessageService(pool, backplane)
          val today = LocalDate.now(ZoneOffset.UTC)
          val hourStart = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)

          def send(cid: String) =
            messages.send(cid, Some("alice"), Some("hi"), Json.obj(), Json.arr(), None, "regular")

          for
            // Activity: alice/bob today, carol 10 days ago.
            _ <- metrics.record("alice")
            _ <- metrics.record("bob")
            _ <- pool.use(_.run(MetricsRepo.recordActivityOn, (today.minusDays(10), "carol")))

            // Messages across two channel types.
            _ <- users.upsert("alice", None, None, "user", Json.obj())
            _ <- channels.create("messaging", "general", Some("alice"), Json.obj())
            _ <- channels.create("livestream", "event", Some("alice"), Json.obj())
            _ <- send("messaging:general")
            _ <- send("messaging:general")
            _ <- send("livestream:event")

            live <- metrics.live(ccuNow = 4)

            // CCU samples within the hour, then roll up.
            _ <- metrics.sampleCcu(hourStart, 2)
            _ <- metrics.sampleCcu(hourStart.plusMinutes(10), 5)
            _ <- metrics.sampleCcu(hourStart.plusMinutes(20), 3)
            _ <- metrics.rollupHour(hourStart, hourStart.plusHours(1))
            ccuMax <- pool.use(_.runUnique(hourlyValue, ("ccu_max", hourStart)))
            ccuMaxSeries <- metrics.hourlySeries("ccu_max", hourStart.minusHours(1))

            _ <- metrics.snapshotDay(today)
            mauSeries <- metrics.dailySeries("mau", today)
            messageSeries <- metrics.dailySeries("messages", today)

            _ <- metrics.prune(
              factsBefore = today,
              ccuBefore = hourStart.minusDays(1),
              hourlyBefore = hourStart.minusDays(1),
            )
            mauAfterPrune <- pool.use(_.runUnique(MetricsRepo.activeUsers, 30))
          yield
            assertEquals(live.dau, 2L, "alice and bob today")
            assertEquals(live.wau, 2L, "carol is outside the 7-day window")
            assertEquals(live.mau, 3L, "carol is inside the 30-day window")
            assertEquals(live.messagesByType, Map("messaging" -> 2L, "livestream" -> 1L))
            assertEquals(live.mediaBytes, 0L)
            assert(live.dbSizeBytes > 0L, "database size is reported")
            assertEquals(live.ccuNow, 4)

            assertEquals(ccuMax, 5.0, "hourly CCU max")
            assertEquals(ccuMaxSeries.map(_._3), List(5.0), "hourly CCU max reads back as a series")

            assertEquals(mauSeries.map(_._3), List(3.0), "the day's MAU is snapshotted")
            val messagesByType = messageSeries.map { (_, labels, value) =>
              labels.hcursor.get[String]("channelType").toOption.get -> value
            }.toMap
            assertEquals(messagesByType, Map("messaging" -> 2.0, "livestream" -> 1.0))

            assertEquals(mauAfterPrune, 2L, "pruning removed carol's stale fact")
        }
      }
    }
  }
