package com.firemoot.domain

/**
 * Unread arithmetic (SPEC.md §5), as a pure spec: a message is unread for a
 * viewer when its seq is past the viewer's `last_read_seq`, it is not the
 * viewer's own, not a system message, and not deleted. Property-tested here and
 * mirrored by the SQL in `ReadRepo`.
 */
object Unread:

  final case class Msg(seq: Long, author: Option[String], system: Boolean, deleted: Boolean)

  def count(messages: List[Msg], lastReadSeq: Long, viewer: String): Int =
    messages.count(m =>
      m.seq > lastReadSeq && !m.author.contains(viewer) && !m.system && !m.deleted
    )
