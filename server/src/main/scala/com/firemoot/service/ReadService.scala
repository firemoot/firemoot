package com.firemoot.service

import cats.effect.{IO, Resource}
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{ChannelRepo, ReadRepo}
import com.firemoot.domain.Event
import io.circe.Json
import io.circe.syntax.*
import skunk.Session

final case class ReadState(lastReadSeq: Long, unreadCount: Long, totalUnread: Long)

final class ReadService(pool: Resource[IO, Session[IO]], backplane: Backplane):

  /**
   * Advances the reader's pointer (to `upTo`, or the channel's current seq) and
   * recomputes their per-channel and total unread. Emits `read.updated` as a
   * channel broadcast (the receipt) and as a user-directed event to the reader
   * (badge sync to all their devices). None if the channel or membership is
   * absent.
   */
  def markRead(cid: String, userId: String, upTo: Option[Long]): IO[Option[ReadState]] =
    pool
      .use { session =>
        session.transaction.use { _ =>
          targetSeq(session, cid, upTo).flatMap {
            case None => IO.pure((Option.empty[ReadState], Option.empty[Event]))
            case Some(seq) =>
              session.runOption(ReadRepo.markRead, (seq, cid, userId)).flatMap {
                case None => IO.pure((Option.empty[ReadState], Option.empty[Event]))
                case Some(lastRead) =>
                  for
                    unread <- session.runUnique(ReadRepo.channelUnread, (userId, cid))
                    total <- session.runUnique(ReadRepo.totalUnread, userId)
                    data = readData(cid, userId, lastRead, unread, total)
                    event <- ChannelEvents.persist(session, cid, "read.updated", data)
                  yield (Some(ReadState(lastRead, unread, total)), Some(event))
              }
          }
        }
      }
      .flatMap {
        case (Some(state), Some(receipt)) =>
          val badge = Event.notification("read.updated", cid, userId, receipt.data)
          (backplane.publish(receipt) >> backplane.publish(badge)).as(Some(state))
        case _ => IO.pure(None)
      }

  private def targetSeq(session: Session[IO], cid: String, upTo: Option[Long]): IO[Option[Long]] =
    upTo match
      case Some(seq) => IO.pure(Some(seq))
      case None => session.runOption(ChannelRepo.currentSeq, cid)

  private def readData(
      cid: String,
      userId: String,
      lastRead: Long,
      unread: Long,
      total: Long,
  ): Json =
    Json.obj(
      "cid" -> cid.asJson,
      "userId" -> userId.asJson,
      "lastReadSeq" -> lastRead.asJson,
      "unreadCount" -> unread.asJson,
      "totalUnread" -> total.asJson,
    )
