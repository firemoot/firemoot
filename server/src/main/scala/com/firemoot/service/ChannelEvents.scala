package com.firemoot.service

import cats.effect.IO
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{ChannelRepo, EventRepo}
import com.firemoot.domain.Event
import io.circe.Json
import skunk.Session

/**
 * Emits a persisted, seq'd channel event: allocate the next per-channel seq and
 * write the `channel_events` row, atomically within the caller's transaction.
 * Publishing to the backplane happens after commit, in the service.
 */
object ChannelEvents:

  def persist(session: Session[IO], cid: String, eventType: String, data: Json): IO[Event] =
    for
      seq <- session.runUnique(ChannelRepo.bumpSeq, cid)
      _ <- session.run(EventRepo.insert, (cid, seq, eventType, data))
    yield Event(eventType, cid, seq, data)
