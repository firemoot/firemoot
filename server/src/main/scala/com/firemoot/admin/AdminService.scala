package com.firemoot.admin

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import com.firemoot.auth.{JwtAuth, PasswordHasher}
import com.firemoot.db.SettingsRepo
import com.firemoot.db.SessionSyntax.*
import io.circe.Json
import skunk.Session

/**
 * Admin authentication (SPEC.md §8, M3.4). The password is set at install (env
 * or CLI), stored as an Argon2id hash in `settings`; there is no default - until
 * a password is set, login is impossible. A successful login mints a short-lived
 * signed session token (an admin-scoped JWT) carried in an httpOnly cookie.
 */
final class AdminService(
    pool: Resource[IO, Session[IO]],
    jwtSecret: String,
    sessionTtl: FiniteDuration = 12.hours,
):

  private val PasswordKey = "admin_password_hash"

  def setPassword(plain: String): IO[Unit] =
    PasswordHasher.hash(plain).flatMap { hashed =>
      pool.use(_.run(SettingsRepo.upsert, (PasswordKey, Json.fromString(hashed))))
    }

  def isConfigured: IO[Boolean] = storedHash.map(_.isDefined)

  /** Verifies the password and, on success, returns a session token. */
  def login(plain: String): IO[Option[String]] =
    storedHash.flatMap {
      case None => IO.pure(None)
      case Some(hash) =>
        PasswordHasher.verify(plain, hash).flatMap {
          case false => IO.pure(None)
          case true =>
            IO.realTimeInstant.map { now =>
              Some(JwtAuth.sign(
                jwtSecret,
                "admin",
                Some("admin"),
                now.plusSeconds(sessionTtl.toSeconds),
              ))
            }
        }
    }

  def verifySession(token: String): Boolean =
    JwtAuth.verify(jwtSecret, token).toOption.exists(_.sub == "admin")

  private def storedHash: IO[Option[String]] =
    pool.use(_.runOption(SettingsRepo.get, PasswordKey)).map(_.flatMap(_.asString))
