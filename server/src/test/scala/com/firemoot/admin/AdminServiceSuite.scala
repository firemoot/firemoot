package com.firemoot.admin

import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.config.DbConfig
import com.firemoot.db.{Database, Migrations}
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

class AdminServiceSuite extends CatsEffectSuite, TestContainerForAll:

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

  test("no default password; set, login, session verify; wrong password rejected") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
        val admin = AdminService(pool, jwtSecret = "admin-signing-secret")
        for
          configuredBefore <- admin.isConfigured
          lockedLogin <- admin.login("anything")
          _ <- admin.setPassword("s3cret-pass")
          configuredAfter <- admin.isConfigured
          good <- admin.login("s3cret-pass")
          bad <- admin.login("wrong")
        yield
          assert(!configuredBefore, "admin starts unconfigured")
          assertEquals(lockedLogin, None, "login is impossible before a password is set")
          assert(configuredAfter, "admin is configured after setPassword")
          assert(good.exists(admin.verifySession), "a session token is minted and verifies")
          assertEquals(bad, None, "the wrong password does not log in")
          assert(!admin.verifySession("garbage.token"), "a junk session is rejected")
      }
    }
  }
