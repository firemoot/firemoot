package com.firemoot.service

import java.time.OffsetDateTime

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{PresenceRepo, UserRepo}
import com.firemoot.domain.Event
import io.circe.Json
import io.circe.syntax.*
import skunk.Session

/**
 * Presence fan-out (SPEC.md §5, M1.7). A user's online/offline transitions -
 * detected by the connection registry (first connection up, last connection
 * down) - are pushed to everyone who shares a channel with them as user-directed
 * `presence.changed` events: no cid, not persisted, delivered to each peer's
 * live connections regardless of what they're subscribed to.
 */
final class PresenceService(pool: Resource[IO, Session[IO]], backplane: Backplane):

  def online(userId: String): IO[Unit] =
    fanOut(userId, status = "online", lastActiveAt = None)

  /** Records the disconnect time, then announces the user as offline. */
  def offline(userId: String): IO[Unit] =
    pool
      .use(_.runOption(UserRepo.touchLastActiveReturning, userId))
      .flatMap(lastActive => fanOut(userId, "offline", lastActive))

  private def fanOut(
      userId: String,
      status: String,
      lastActiveAt: Option[OffsetDateTime],
  ): IO[Unit] =
    pool.use(_.runList(PresenceRepo.coMembers, userId)).flatMap { peers =>
      val data = Json.obj(
        "userId" -> userId.asJson,
        "status" -> status.asJson,
        "lastActiveAt" -> lastActiveAt.asJson,
      )
      peers.traverse_(peer =>
        backplane.publish(Event.notification("presence.changed", "", peer, data))
      )
    }
