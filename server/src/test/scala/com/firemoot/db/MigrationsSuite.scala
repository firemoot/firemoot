package com.firemoot.db

import cats.effect.IO
import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.config.DbConfig
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import skunk.codec.all.int8
import skunk.implicits.*

class MigrationsSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private def configFor(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 2,
    )

  test("migrations create the core chat schema and are idempotent") {
    withContainers { pg =>
      val cfg = configFor(pg)
      for
        applied <- Migrations.run(cfg)
        _ = assert(applied >= 1, s"expected at least one migration, got $applied")
        secondRun <- Migrations.run(cfg)
        _ = assertEquals(secondRun, 0, "re-running migrations should apply nothing")
        _ <- Database.pool(cfg).use {
          _.use { s =>
            for
              channels <- s.unique(sql"select count(*) from channels".query(int8))
              members <- s.unique(sql"select count(*) from channel_members".query(int8))
              messages <- s.unique(sql"select count(*) from messages".query(int8))
              events <- s.unique(sql"select count(*) from channel_events".query(int8))
              reactions <- s.unique(sql"select count(*) from reactions".query(int8))
              users <- s.unique(sql"select count(*) from users".query(int8))
              apiKeys <- s.unique(sql"select count(*) from api_keys".query(int8))
            yield
              assertEquals(channels, 0L)
              assertEquals(members, 0L)
              assertEquals(messages, 0L)
              assertEquals(events, 0L)
              assertEquals(reactions, 0L)
              assertEquals(users, 0L)
              assertEquals(apiKeys, 0L)
          }
        }
      yield ()
    }
  }
