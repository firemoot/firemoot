package com.firemoot.service

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{ChannelRepo, EventRepo, MessageRepo}
import com.firemoot.domain.{Event, Message, UuidV7}
import io.circe.Json
import io.circe.syntax.*
import skunk.{Session, SqlState}

/** Why a send was refused. */
enum SendError:
  case ChannelNotFound, ChannelFrozen
  case DuplicateId(id: String)

final class MessageService(pool: Resource[IO, Session[IO]], backplane: Backplane):

  /**
   * Sends a message: allocates the next per-channel seq (rejecting frozen,
   * deleted or absent channels), inserts the message, increments the parent's
   * `reply_count` when it is a thread reply, and writes the `message.new` event -
   * all in one transaction (SPEC.md §3). Published to the backplane after commit.
   *
   * A caller may supply `id` (Stream parity); absent, the server mints a UUIDv7
   * string. Ids are globally unique (the primary key), so a re-send of an
   * existing id surfaces as [[SendError.DuplicateId]] by catching the unique
   * violation - race-safe without a check-then-insert.
   */
  def send(
      cid: String,
      userId: Option[String],
      text: Option[String],
      custom: Json,
      attachments: Json,
      parentMessageId: Option[String],
      messageType: String = "regular",
      id: Option[String] = None,
  ): IO[Either[SendError, Message]] =
    id.fold(UuidV7.next.map(_.toString))(IO.pure).flatMap { messageId =>
      pool
        .use { session =>
          session.transaction.use { _ =>
            session.runOption(ChannelRepo.bumpSeqForMessage, cid).flatMap {
              case Some(seq) =>
                for
                  message <- session.runUnique(
                    MessageRepo.insert,
                    (
                      messageId,
                      cid,
                      seq,
                      userId,
                      messageType,
                      text,
                      custom,
                      attachments,
                      parentMessageId,
                    ),
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
        .recover { case SqlState.UniqueViolation(_) => Left(SendError.DuplicateId(messageId)) }
        .flatTap {
          case Right(message) =>
            backplane.publish(Event("message.new", cid, message.seq, message.asJson))
          case Left(_) => IO.unit
        }
    }

  /**
   * Edits a message's text/custom and emits `message.updated`. None if the
   * message is missing, in another channel, or already deleted.
   */
  def edit(
      cid: String,
      messageId: String,
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
   * The author of a live message in the channel, for edit/delete authorisation:
   * outer none = no such message, inner none = author scrubbed.
   */
  def authorInChannel(cid: String, messageId: String): IO[Option[Option[String]]] =
    pool.use(_.runOption(MessageRepo.authorInChannel, (messageId, cid)))

  /** The channel a live message belongs to, for the channel-less global delete. */
  def channelOf(messageId: String): IO[Option[String]] =
    pool.use(_.runOption(MessageRepo.channelOf, messageId))

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
  def delete(cid: String, messageId: String): IO[Boolean] =
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
