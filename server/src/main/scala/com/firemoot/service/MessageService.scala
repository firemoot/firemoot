package com.firemoot.service

import cats.effect.{IO, Resource}
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{ChannelRepo, EventRepo, MessageRepo}
import com.firemoot.domain.{Event, Message, UuidV7}
import io.circe.Json
import io.circe.syntax.*
import skunk.Session

import java.util.UUID

/** Why a send was refused. */
enum SendError:
  case ChannelNotFound, ChannelFrozen

final class MessageService(pool: Resource[IO, Session[IO]], backplane: Backplane):

  /**
   * Sends a message: allocates the next per-channel seq (rejecting frozen,
   * deleted or absent channels), inserts the message, and writes the
   * `message.new` event to the replay log - all in one transaction so the three
   * stay consistent (SPEC.md §3). After commit the event is published to the
   * backplane for live WebSocket fan-out.
   */
  def send(
      cid: String,
      userId: Option[String],
      text: Option[String],
      custom: Json,
      attachments: Json,
      parentMessageId: Option[UUID],
  ): IO[Either[SendError, Message]] =
    pool
      .use { session =>
        session.transaction.use { _ =>
          session.runOption(ChannelRepo.bumpSeqForMessage, cid).flatMap {
            case Some(seq) =>
              for
                id <- UuidV7.next
                message <- session.runUnique(
                  MessageRepo.insert,
                  (id, cid, seq, userId, "regular", text, custom, attachments, parentMessageId),
                )
                _ <- session.run(EventRepo.insert, (cid, seq, "message.new", message.asJson))
              yield Right(message)
            case None =>
              session.runOption(ChannelRepo.statusOf, cid).map {
                case Some((frozen, _)) if frozen => Left(SendError.ChannelFrozen)
                case _ => Left(SendError.ChannelNotFound)
              }
          }
        }
      }
      .flatTap {
        case Right(message) =>
          backplane.publish(Event("message.new", cid, message.seq, message.asJson))
        case Left(_) => IO.unit
      }
