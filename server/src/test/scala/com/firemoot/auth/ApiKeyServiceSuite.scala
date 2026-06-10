package com.firemoot.auth

import ciris.Secret
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import com.firemoot.config.{DbConfig, ServerConfig}
import com.firemoot.db.{Database, Migrations}
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

class ApiKeyServiceSuite extends CatsEffectSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:17"))

  private val serverCfg = ServerConfig("bootstrap-key", Secret("bootstrap-secret"))

  private def dbConfig(pg: PostgreSQLContainer): DbConfig =
    DbConfig(
      host = pg.container.getHost,
      port = pg.container.getMappedPort(5432),
      database = pg.databaseName,
      user = pg.username,
      password = Secret(pg.password),
      maxConnections = 4,
    )

  test("create a key, resolve it for auth, then revoke it (config key always works)") {
    withContainers { pg =>
      val cfg = dbConfig(pg)
      Migrations.run(cfg) >> Database.pool(cfg).use { pool =>
        val keys = ApiKeyService(pool)
        val resolver = ApiKeys.fromConfigAndDb(serverCfg, pool)
        for
          created <- keys.create
          resolved <- resolver.secretFor(created.id)
          bootstrap <- resolver.secretFor("bootstrap-key")
          listedBefore <- keys.list
          revoked <- keys.revoke(created.id)
          afterRevoke <- resolver.secretFor(created.id)
          listedAfter <- keys.list
          revokeMissing <- keys.revoke("does-not-exist")
        yield
          assertEquals(resolved, Some(created.secret), "a fresh DB key resolves for HMAC auth")
          assertEquals(bootstrap, Some("bootstrap-secret"), "the config bootstrap key still works")
          assert(listedBefore.exists(k => k.id == created.id && !k.revoked), "listed, live")
          assert(revoked, "revoke succeeds")
          assertEquals(afterRevoke, None, "a revoked key no longer resolves")
          assert(listedAfter.exists(k => k.id == created.id && k.revoked), "listed, revoked")
          assert(!revokeMissing, "revoking an unknown key is a no-op")
      }
    }
  }
