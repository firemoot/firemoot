package com.firemoot.service

import java.util.UUID

import cats.effect.{IO, Resource}
import com.firemoot.backplane.Backplane
import com.firemoot.db.{ChannelRepo, EventRepo, MessageRepo}
import com.firemoot.domain.{Event, Message, UuidV7}
import io.circe.Json
import io.circe.syntax.*
import skunk.Session

final class MessageService(pool: Resource[IO, Session[IO]], backplane: Backplane):

  /**
   * Sends a message: allocates the next per-channel seq, inserts the message,
   * and writes the `message.new` event to the replay log - all in one
   * transaction so the three stay consistent (SPEC.md §3). After commit the
   * event is published to the backplane for live WebSocket fan-out (M0.7).
   */
  def send(
      cid: String,
      userId: Option[String],
      text: Option[String],
      custom: Json,
      attachments: Json,
      parentMessageId: Option[UUID],
  ): IO[Message] =
    pool.use { session =>
      session.transaction.use { _ =>
        for
          seq <- session.prepare(ChannelRepo.bumpSeq).flatMap(_.unique(cid))
          id <- UuidV7.next
          message <- session
            .prepare(MessageRepo.insert)
            .flatMap(
              _.unique((
                id,
                cid,
                seq,
                userId,
                "regular",
                text,
                custom,
                attachments,
                parentMessageId,
              ))
            )
          _ <- session
            .prepare(EventRepo.insert)
            .flatMap(_.execute((cid, seq, "message.new", message.asJson)))
        yield message
      }
    }.flatTap(message => backplane.publish(Event("message.new", cid, message.seq, message.asJson)))
