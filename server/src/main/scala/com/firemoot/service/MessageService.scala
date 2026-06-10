package com.firemoot.service

import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{ChannelRepo, EventRepo, MessageRepo}
import com.firemoot.domain.{Event, Message, UuidV7}
import io.circe.Json
import io.circe.syntax.*
import skunk.Session

/** Why a send was refused. */
enum SendError:
  case ChannelNotFound, ChannelFrozen

final class MessageService(pool: Resource[IO, Session[IO]], backplane: Backplane):

  /**
   * Sends a message: allocates the next per-channel seq (rejecting frozen,
   * deleted or absent channels), inserts the message, increments the parent's
   * `reply_count` when it is a thread reply, and writes the `message.new` event -
   * all in one transaction (SPEC.md §3). Published to the backplane after commit.
   */
  def send(
      cid: String,
      userId: Option[String],
      text: Option[String],
      custom: Json,
      attachments: Json,
      parentMessageId: Option[UUID],
      messageType: String = "regular",
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
                  (id, cid, seq, userId, messageType, text, custom, attachments, parentMessageId),
                )
                _ <- session.run(EventRepo.insert, (cid, seq, "message.new", message.asJson))
                _ <- parentMessageId.traverse_(pid =>
                  session.run(MessageRepo.incrementReplyCount, pid)
                )
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

  /**
   * Edits a message's text/custom and emits `message.updated`. None if the
   * message is missing, in another channel, or already deleted.
   */
  def edit(
      cid: String,
      messageId: UUID,
      text: Option[String],
      custom: Option[Json],
  ): IO[Option[Message]] =
    pool
      .use { session =>
        session.transaction.use { _ =>
          session.runOption(MessageRepo.update, (text, custom, messageId, cid)).flatMap {
            case None => IO.pure((Option.empty[Message], Option.empty[Event]))
            case Some(message) =>
              ChannelEvents
                .persist(session, cid, "message.updated", message.asJson)
                .map(event => (Some(message), Some(event)))
          }
        }
      }
      .flatMap((message, event) => event.traverse_(backplane.publish).as(message))

  /**
   * Patches the `thumbUrl` onto every attachment referencing `originalUrl` and
   * re-emits `message.updated` for each affected message (M2.3 thumbnailing).
   */
  def attachThumbnail(originalUrl: String, thumbUrl: String): IO[Unit] =
    val matchFragment = Json.arr(Json.obj("url" -> originalUrl.asJson))
    pool.use(_.runList(MessageRepo.withAttachment, matchFragment)).flatMap { rows =>
      rows.traverse_ { (id, cid, attachments) =>
        val patched = patchAttachments(attachments, originalUrl, thumbUrl)
        pool
          .use { session =>
            session.transaction.use { _ =>
              session
                .runUnique(MessageRepo.setAttachments, (patched, id))
                .flatMap(message =>
                  ChannelEvents.persist(session, cid, "message.updated", message.asJson)
                )
            }
          }
          .flatMap(backplane.publish)
      }
    }

  private def patchAttachments(attachments: Json, originalUrl: String, thumbUrl: String): Json =
    attachments.asArray match
      case None => attachments
      case Some(items) =>
        Json.fromValues(items.map { item =>
          if item.hcursor.get[String]("url").toOption.contains(originalUrl) then
            item.deepMerge(Json.obj("thumbUrl" -> thumbUrl.asJson))
          else item
        })

  /**
   * Soft-deletes a message (scrubbing its text), decrements the parent thread's
   * `reply_count`, and emits `message.deleted`. False if nothing was deleted.
   */
  def delete(cid: String, messageId: UUID): IO[Boolean] =
    pool
      .use { session =>
        session.transaction.use { _ =>
          session.runOption(MessageRepo.softDelete, (messageId, cid)).flatMap {
            case None => IO.pure((false, Option.empty[Event]))
            case Some(parent) =>
              for
                _ <- parent.traverse_(pid => session.run(MessageRepo.decrementReplyCount, pid))
                event <- ChannelEvents.persist(
                  session,
                  cid,
                  "message.deleted",
                  Json.obj("id" -> messageId.asJson, "cid" -> cid.asJson),
                )
              yield (true, Some(event))
          }
        }
      }
      .flatMap((deleted, event) => event.traverse_(backplane.publish).as(deleted))
