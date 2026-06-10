package com.firemoot.db

import skunk.*
import skunk.codec.all.*
import skunk.implicits.*

/** Presence fan-out targets (SPEC.md §5, M1.7). */
object PresenceRepo:

  /**
   * The distinct other users who share at least one channel with `userId` - the
   * audience for that user's `presence.changed` events.
   */
  val coMembers: Query[String, String] =
    sql"""
      select distinct peer.user_id
      from channel_members mine
      join channel_members peer on peer.cid = mine.cid
      where mine.user_id = $text and peer.user_id <> mine.user_id
    """.query(text)
