package com.firemoot.service

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.db.ChannelRepo
import com.firemoot.domain.Channel
import io.circe.Json
import skunk.Session

final class ChannelService(pool: Resource[IO, Session[IO]]):

  /**
   * Creates a channel (`cid = type:id`) and, when a creator is given, adds them
   * as the owning member - both in one transaction.
   */
  def create(
      channelType: String,
      id: String,
      createdBy: Option[String],
      custom: Json,
  ): IO[Channel] =
    val cid = s"$channelType:$id"
    pool.use { session =>
      session.transaction.use { _ =>
        for
          channel <- session
            .prepare(ChannelRepo.insert)
            .flatMap(_.unique((cid, channelType, id, createdBy, custom)))
          _ <- createdBy.traverse_ { uid =>
            session.prepare(ChannelRepo.addMember).flatMap(_.execute((cid, uid, "owner")))
          }
        yield channel
      }
    }
