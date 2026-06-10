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
