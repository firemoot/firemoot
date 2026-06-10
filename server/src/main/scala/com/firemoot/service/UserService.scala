package com.firemoot.service

import cats.effect.{IO, Resource}
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.UserRepo
import com.firemoot.domain.User
import io.circe.Json
import skunk.Session

final class UserService(pool: Resource[IO, Session[IO]]):

  def upsert(
      id: String,
      name: Option[String],
      image: Option[String],
      role: String,
      custom: Json,
  ): IO[User] =
    pool.use(_.runUnique(UserRepo.upsert, (id, name, image, role, custom)))

  /**
   * GDPR hard-delete (SPEC.md §3). In one transaction: scrub the content of the
   * user's authored messages (rows survive as tombstones), then delete the user -
   * which cascades their memberships and reactions and nulls `user_id` on those
   * messages. Returns false if the user did not exist.
   */
  def delete(id: String): IO[Boolean] =
    pool.use { session =>
      session.transaction.use { _ =>
        session.run(UserRepo.scrubAuthoredMessages, id) >>
          session.runOption(UserRepo.deleteReturningId, id).map(_.isDefined)
      }
    }
