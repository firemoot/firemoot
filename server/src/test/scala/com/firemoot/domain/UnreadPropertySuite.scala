package com.firemoot.domain

import com.firemoot.domain.Unread.Msg
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class UnreadPropertySuite extends ScalaCheckSuite:

  private val viewers = Gen.oneOf("alice", "bob", "carol")

  private val msgGen: Gen[Msg] =
    for
      seq <- Gen.choose(1L, 30L)
      author <- Gen.option(viewers)
      system <- Gen.prob(0.25)
      deleted <- Gen.prob(0.25)
    yield Msg(seq, author, system, deleted)

  private val msgsGen: Gen[List[Msg]] = Gen.listOf(msgGen)

  // Independent reference implementation of the §5 spec.
  private def reference(messages: List[Msg], lastRead: Long, viewer: String): Int =
    messages.foldLeft(0) { (acc, m) =>
      val unread = m.seq > lastRead && m.author != Some(viewer) && !m.system && !m.deleted
      if unread then acc + 1 else acc
    }

  property("count matches an independent reference implementation") {
    forAll(msgsGen, Gen.choose(0L, 30L), viewers) { (messages, lastRead, viewer) =>
      Unread.count(messages, lastRead, viewer) == reference(messages, lastRead, viewer)
    }
  }

  property("a viewer's own messages are never unread") {
    forAll(msgsGen, Gen.choose(0L, 30L), viewers) { (messages, lastRead, viewer) =>
      Unread.count(messages.map(_.copy(author = Some(viewer))), lastRead, viewer) == 0
    }
  }

  property("system and deleted messages are never unread") {
    forAll(msgsGen, Gen.choose(0L, 30L), viewers) { (messages, lastRead, viewer) =>
      Unread.count(messages.map(_.copy(system = true)), lastRead, viewer) == 0 &&
      Unread.count(messages.map(_.copy(deleted = true)), lastRead, viewer) == 0
    }
  }

  property("raising last_read never increases the unread count") {
    forAll(msgsGen, Gen.choose(0L, 15L), Gen.choose(15L, 30L), viewers) {
      (messages, lower, higher, viewer) =>
        Unread.count(messages, higher, viewer) <= Unread.count(messages, lower, viewer)
    }
  }
