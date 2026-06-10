package com.firemoot.domain

import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Json}

/**
 * A channel event (SPEC.md §5) carried over the backplane. `data` holds the
 * type-specific payload (for `message.new`, the serialised message).
 *
 * `target = None`: channel-broadcast, persisted in `channel_events`, delivered to
 * cid subscribers and replayed on resume. `target = Some(userId)`: a live
 * user-directed notification, delivered only to that user's connections and not
 * persisted.
 */
final case class Event(
    `type`: String,
    cid: String,
    seq: Long,
    data: Json,
    target: Option[String] = None,
)

object Event:
  given Codec[Event] = deriveCodec

  /** A live, user-directed notification (not persisted, no seq). */
  def notification(eventType: String, cid: String, userId: String, data: Json): Event =
    Event(eventType, cid, seq = 0L, data = data, target = Some(userId))
