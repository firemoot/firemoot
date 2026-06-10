package com.firemoot.ws

import cats.effect.{IO, Resource}
import com.firemoot.db.EventRepo
import com.firemoot.domain.Event
import skunk.Session

/**
 * Reads persisted `channel_events` for resume: everything with `seq > lastSeen`
 * for a channel, in order (SPEC.md §5).
 */
final class EventReplay(pool: Resource[IO, Session[IO]]):

  def since(cid: String, lastSeenSeq: Long): IO[List[Event]] =
    pool.use { session =>
      session.prepare(EventRepo.since).flatMap { pq =>
        pq.stream((cid, lastSeenSeq), 64)
          .map((seq, eventType, payload) => Event(eventType, cid, seq, payload))
          .compile
          .toList
      }
    }
