package com.firemoot.auth

import java.security.SecureRandom
import java.time.OffsetDateTime

import cats.effect.{IO, Resource}
import com.firemoot.config.ServerConfig
import com.firemoot.db.ApiKeyRepo
import com.firemoot.db.SessionSyntax.*
import com.firemoot.domain.UuidV7
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import skunk.Session

/**
 * Resolves the shared secret for a server API key id. The install-time config key
 * always works (bootstrap); additional keys live in the database and can be
 * rotated from the admin dashboard (M3.5) without a restart.
 */
trait ApiKeys:
  def secretFor(keyId: String): IO[Option[String]]

object ApiKeys:

  def fromConfig(cfg: ServerConfig): ApiKeys =
    (keyId: String) => IO.pure(Option.when(keyId == cfg.apiKeyId)(cfg.apiSecret.value))

  /** The config bootstrap key plus any live DB keys. */
  def fromConfigAndDb(cfg: ServerConfig, pool: Resource[IO, Session[IO]]): ApiKeys =
    (keyId: String) =>
      if keyId == cfg.apiKeyId then IO.pure(Some(cfg.apiSecret.value))
      else pool.use(_.runOption(ApiKeyRepo.secretFor, keyId))

/** A key's metadata (never its secret). */
final case class ApiKeyInfo(id: String, createdAt: OffsetDateTime, revoked: Boolean)

object ApiKeyInfo:
  given Codec[ApiKeyInfo] = deriveCodec

/** A freshly-created key - the secret is returned only here. */
final case class ApiKeyCreated(id: String, secret: String)

object ApiKeyCreated:
  given Codec[ApiKeyCreated] = deriveCodec

/** Lists, creates and revokes DB-backed API keys for the admin dashboard. */
final class ApiKeyService(pool: Resource[IO, Session[IO]]):

  def list: IO[List[ApiKeyInfo]] =
    pool.use(_.execute(ApiKeyRepo.list)).map(_.map { (id, createdAt, revokedAt) =>
      ApiKeyInfo(id, createdAt, revokedAt.isDefined)
    })

  def create: IO[ApiKeyCreated] =
    for
      id <- UuidV7.next.map(_.toString)
      secret <- randomSecret
      _ <- pool.use(_.run(ApiKeyRepo.insert, (id, secret)))
    yield ApiKeyCreated(id, secret)

  def revoke(id: String): IO[Boolean] =
    pool.use(_.runOption(ApiKeyRepo.revoke, id)).map(_.isDefined)

  private def randomSecret: IO[String] =
    IO.delay {
      val bytes = new Array[Byte](32)
      SecureRandom().nextBytes(bytes)
      bytes.map("%02x".format(_)).mkString
    }
