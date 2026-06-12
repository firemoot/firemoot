package com.firemoot.service

import cats.effect.{IO, Resource}
import com.firemoot.api.{CallerReadState, ChannelState, MemberState}
import com.firemoot.db.HydrationRepo
import com.firemoot.db.SessionSyntax.*
import com.firemoot.domain.Channel
import io.circe.syntax.*
import skunk.Session

/**
 * Hydrates the client-authenticated channel responses (M4.3): given a page of
 * channel rows, attaches each channel's members, latest message and - when a
 * caller is known - that caller's read state. The lookups are batched over the
 * whole cid set (see [[HydrationRepo]]), so hydrating a page costs at most three
 * queries regardless of its size.
 */
final class HydrationService(pool: Resource[IO, Session[IO]]):

  def hydrate(channels: List[Channel], caller: Option[String]): IO[List[ChannelState]] =
    if channels.isEmpty then IO.pure(Nil)
    else
      val cids = channels.map(_.cid).asJson
      pool.use { session =>
        for
          memberRows <- session.runList(HydrationRepo.members, cids)
          latest <- session.runList(HydrationRepo.latestMessages, cids)
          unread <- caller.fold(IO.pure(List.empty[(String, Long)]))(uid =>
            session.runList(HydrationRepo.callerUnread, (uid, cids))
          )
        yield
          val membersByCid = memberRows
            .groupMap { case (cid, _, _, _) => cid } { case (_, userId, role, lastReadSeq) =>
              MemberState(userId, role, lastReadSeq)
            }
          val latestByCid = latest.map(m => m.cid -> m).toMap
          val unreadByCid = unread.toMap
          channels.map { channel =>
            val members = membersByCid.getOrElse(channel.cid, Nil)
            val read = caller.map { uid =>
              val lastReadSeq = members.find(_.userId == uid).map(_.lastReadSeq).getOrElse(0L)
              CallerReadState(lastReadSeq, unreadByCid.getOrElse(channel.cid, 0L))
            }
            ChannelState(channel, members, read, latestByCid.get(channel.cid))
          }
      }
