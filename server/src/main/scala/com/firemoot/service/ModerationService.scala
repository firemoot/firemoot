package com.firemoot.service

import cats.effect.{IO, Resource}
import com.firemoot.api.Flag
import com.firemoot.db.ModerationRepo
import com.firemoot.db.SessionSyntax.*
import com.firemoot.domain.Event
import io.circe.Json
import io.circe.syntax.*
import skunk.Session

/**
 * Message moderation (SPEC.md §3, M1.11). Flagging records a row in the
 * moderation queue (surfaced in the admin dashboard, M3) and enqueues a
 * `user.flagged` webhook event so external tooling is notified. Flags are
 * deliberately *not* broadcast over WebSockets - moderation stays off the
 * member-facing channel timeline.
 */
final class ModerationService(pool: Resource[IO, Session[IO]], webhooks: WebhookService):

  def flag(
      cid: String,
      messageId: String,
      flaggedBy: String,
      reason: Option[String],
  ): IO[Option[Flag]] =
    pool.use(_.runOption(ModerationRepo.flaggableAuthor, (messageId, cid))).flatMap {
      case None => IO.pure(None)
      case Some(flaggedUser) =>
        for
          row <- pool.use(_.runUnique(ModerationRepo.insert, (messageId, cid, flaggedBy, reason)))
          (id, status, createdAt) = row
          data = Json.obj(
            "messageId" -> messageId.asJson,
            "flaggedUser" -> flaggedUser.asJson,
            "flaggedBy" -> flaggedBy.asJson,
            "reason" -> reason.asJson,
          )
          _ <- webhooks.enqueue(Event("user.flagged", cid, seq = 0L, data = data))
        yield Some(Flag(id, messageId, cid, flaggedUser, flaggedBy, reason, status, createdAt))
    }

  def listFlags(status: String): IO[List[Flag]] =
    pool.use(_.runList(ModerationRepo.listByStatus, status)).map(_.map {
      (id, messageId, cid, flaggedUser, flaggedBy, reason, st, createdAt) =>
        Flag(id, messageId, cid, flaggedUser, flaggedBy, reason, st, createdAt)
    })
