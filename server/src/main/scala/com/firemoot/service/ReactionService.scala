package com.firemoot.service

import java.util.UUID

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{MessageRepo, ReactionRepo}
import com.firemoot.domain.Event
import io.circe.Json
import io.circe.syntax.*
import skunk.{Query, Session}

final class ReactionService(pool: Resource[IO, Session[IO]], backplane: Backplane):

  /**
   * Adds a reaction (idempotent), emitting `reaction.new` on an actual add.
   * Returns the message's per-type counts, or none if the message is not found.
   */
  def add(
      cid: String,
      messageId: UUID,
      userId: String,
      reactionType: String,
  ): IO[Option[Map[String, Long]]] =
    mutate(cid, messageId, userId, reactionType, ReactionRepo.add, "reaction.new")

  /** Removes a reaction, emitting `reaction.deleted` when one was actually removed. */
  def remove(
      cid: String,
      messageId: UUID,
      userId: String,
      reactionType: String,
  ): IO[Option[Map[String, Long]]] =
    mutate(cid, messageId, userId, reactionType, ReactionRepo.remove, "reaction.deleted")

  private def mutate(
      cid: String,
      messageId: UUID,
      userId: String,
      reactionType: String,
      op: Query[(UUID, String, String), String],
      eventType: String,
  ): IO[Option[Map[String, Long]]] =
    pool
      .use { session =>
        session.transaction.use { _ =>
          session.runOption(MessageRepo.existsInChannel, (messageId, cid)).flatMap {
            case None => IO.pure((Option.empty[Map[String, Long]], Option.empty[Event]))
            case Some(_) =>
              for
                changed <- session.runOption(op, (messageId, userId, reactionType))
                counts <- session.runList(ReactionRepo.countsByType, messageId).map(_.toMap)
                event <- changed match
                  case None => IO.pure(Option.empty[Event])
                  case Some(_) =>
                    val data = Json.obj(
                      "messageId" -> messageId.asJson,
                      "userId" -> userId.asJson,
                      "type" -> reactionType.asJson,
                      "counts" -> counts.asJson,
                    )
                    ChannelEvents.persist(session, cid, eventType, data).map(Some(_))
              yield (Some(counts), event)
          }
        }
      }
      .flatMap((counts, event) => event.traverse_(backplane.publish).as(counts))
