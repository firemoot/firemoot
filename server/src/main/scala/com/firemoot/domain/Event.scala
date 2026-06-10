package com.firemoot.domain

import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Json}

/**
 * A persisted channel event (SPEC.md §5): the wire shape carried over the
 * backplane, stored in `channel_events`, and replayed on resume. `data` holds
 * the type-specific payload (for `message.new`, the serialised message).
 */
final case class Event(`type`: String, cid: String, seq: Long, data: Json)

object Event:
  given Codec[Event] = deriveCodec
