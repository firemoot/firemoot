package com.firemoot.streamapi

import java.time.OffsetDateTime

import cats.effect.IO
import cats.syntax.all.*
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{LookupRepo, MemberRow, StreamStateRepo}
import com.firemoot.domain.{Channel, Message}
import com.firemoot.ws.ConnectionRegistry
import io.circe.Json
import io.circe.syntax.*
import skunk.Session
import skunk.data.Arr

/** Which messages a channel query returns (Stream's `messages` pagination params). */
enum MessagePaging:
  case Newest(limit: Int)
  case Before(messageId: String, limit: Int)
  case After(messageId: String, limit: Int)
  case Around(messageId: String, limit: Int)
  case AroundTime(at: OffsetDateTime, limit: Int)

object MessagePaging:
  val MaxLimit = 300

  /** Reads `{limit, id_lt, id_gt, id_around, created_at_around}`; `default` when absent. */
  def from(json: Option[Json], default: Int): MessagePaging =
    val c = json.map(_.hcursor)
    def str(k: String) = c.flatMap(_.get[String](k).toOption)
    val limit =
      c.flatMap(_.get[Int]("limit").toOption).getOrElse(default).max(0).min(MaxLimit)
    str("id_lt").map(Before(_, limit))
      .orElse(str("id_gt").map(After(_, limit)))
      .orElse(str("id_around").map(Around(_, limit)))
      .orElse(
        str("created_at_around")
          .flatMap(s => scala.util.Try(OffsetDateTime.parse(s)).toOption)
          .map(AroundTime(_, limit))
      )
      .getOrElse(Newest(limit))

/**
 * Builds getstream.io channel-state responses (`ChannelStateResponseFields`),
 * batched over a page of channels. Every response carries the real `members` and
 * every member's `read` row, including the caller's, because the SDK applies both
 * unconditionally: an empty member list wipes its state and a missing own read row
 * resets the caller's unread count.
 */
final class StreamStateService(
    connections: StreamConnections,
    registry: ConnectionRegistry,
):

  def online: IO[Set[String]] = (connections.onlineUsers, registry.onlineUsers).mapN(_ ++ _)

  def users(s: Session[IO], ids: Iterable[String]): IO[V2Json.Users] =
    val distinct = ids.toList.distinct
    (
      if distinct.isEmpty then IO.pure(Nil) else s.runList(LookupRepo.users, Arr(distinct*)),
      online,
    ).mapN((us, on) => V2Json.Users(us.map(u => u.id -> u).toMap, on))

  /** The page `paging` selects in one channel, oldest first. */
  def page(s: Session[IO], cid: String, paging: MessagePaging): IO[List[Message]] =
    def around(seq: Long, limit: Int): IO[List[Message]] =
      (
        s.runList(StreamStateRepo.before, (cid, seq + 1, limit)),
        s.runList(StreamStateRepo.after, (cid, seq, limit)),
      ).mapN { (older, newer) =>
        val wantNewer = math.min(newer.size, limit / 2)
        val takeOlder = math.min(older.size, limit - wantNewer)
        val takeNewer = math.min(newer.size, limit - takeOlder)
        older.take(takeOlder).reverse ++ newer.take(takeNewer)
      }
    def seqOf(id: String) = s.runOption(StreamStateRepo.seqOf, (cid, id))
    paging match
      case MessagePaging.Newest(limit) =>
        s.runList(StreamStateRepo.newestPerChannel, (Arr(cid), limit)).map(_.reverse)
      case MessagePaging.Before(id, limit) =>
        seqOf(id).flatMap(_.fold(IO.pure(Nil)) { seq =>
          s.runList(StreamStateRepo.before, (cid, seq, limit)).map(_.reverse)
        })
      case MessagePaging.After(id, limit) =>
        seqOf(id).flatMap(_.fold(IO.pure(Nil))(seq =>
          s.runList(StreamStateRepo.after, (cid, seq, limit))
        ))
      case MessagePaging.Around(id, limit) =>
        seqOf(id).flatMap(_.fold(IO.pure(Nil))(around(_, limit)))
      case MessagePaging.AroundTime(at, limit) =>
        s.runUnique(StreamStateRepo.seqAtOrBefore, (cid, at)).flatMap { seq =>
          if seq == 0 then s.runList(StreamStateRepo.after, (cid, 0L, limit))
          else around(seq, limit)
        }

  /** The newest `limit` messages of each channel, oldest first per channel. */
  def newestPages(s: Session[IO], cids: List[String], limit: Int): IO[Map[String, List[Message]]] =
    if cids.isEmpty || limit == 0 then IO.pure(Map.empty)
    else
      s.runList(StreamStateRepo.newestPerChannel, (Arr(cids*), limit))
        .map(_.groupBy(_.cid).view.mapValues(_.reverse).toMap)

  /** Reactions for the given messages, with `caller`'s own. */
  def reactions(
      s: Session[IO],
      messageIds: List[String],
      caller: Option[String],
  ): IO[Map[String, MessageReactions]] =
    if messageIds.isEmpty then IO.pure(Map.empty)
    else
      val ids = Arr(messageIds.distinct*)
      (
        s.runList(StreamStateRepo.reactionCounts, ids),
        s.runList(StreamStateRepo.latestReactions, (ids, 10)),
        caller.fold(IO.pure(List.empty))(u => s.runList(StreamStateRepo.ownReactions, (ids, u))),
      ).mapN { (counts, latest, own) =>
        val countsBy = counts.groupMap(_._1)(r => r._2 -> r._3).view.mapValues(_.toMap).toMap
        val latestBy = latest.groupBy(_.messageId)
        val ownBy = own.groupBy(_.messageId)
        messageIds.map { id =>
          id -> MessageReactions(
            countsBy.getOrElse(id, Map.empty),
            latestBy.getOrElse(id, Nil),
            ownBy.getOrElse(id, Nil),
          )
        }.toMap
      }

  def readRows(s: Session[IO], cids: List[String]): IO[List[ReadRow]] =
    if cids.isEmpty then IO.pure(Nil)
    else
      s.runList(StreamStateRepo.readRows, Arr(cids*)).map(_.map {
        (cid, userId, lastReadId, lastReadMessageAt, joinedAt, unread) =>
          ReadRow(cid, userId, lastReadMessageAt.getOrElse(joinedAt), lastReadId, unread)
      })

  /** `ChannelStateResponseFields` for each channel, in order. */
  def states(
      s: Session[IO],
      channels: List[Channel],
      caller: Option[String],
      pages: Map[String, List[Message]],
  ): IO[List[Json]] =
    val cids = channels.map(_.cid)
    if cids.isEmpty then IO.pure(Nil)
    else
      for
        members <- s.runList(LookupRepo.members, Arr(cids*))
        counts <- s.runList(StreamStateRepo.memberCounts, Arr(cids*)).map(_.toMap)
        reads <- readRows(s, cids)
        messages = pages.values.flatten.toList
        reacts <- reactions(s, messages.map(_.id), caller)
        userIds = members.map(_.userId) ++ channels.flatMap(_.createdBy) ++
          messages.flatMap(_.userId) ++
          reacts.values.flatMap(r => (r.latest ++ r.own).map(_.userId))
        us <- users(s, userIds)
        watchers <- cids.traverse(cid => connections.watcherCount(cid).map(cid -> _)).map(_.toMap)
      yield
        val membersBy = members.groupBy(_.cid)
        val readsBy = reads.groupBy(_.cid)
        channels.map { c =>
          val ms = membersBy.getOrElse(c.cid, Nil)
          val callerMember = caller.flatMap(u => ms.find(_.userId == u))
          channelState(
            c,
            us,
            ms,
            counts.getOrElse(c.cid, 0L),
            callerMember,
            pages.getOrElse(c.cid, Nil),
            reacts,
            readsBy.getOrElse(c.cid, Nil),
            watchers.getOrElse(c.cid, 0),
          )
        }

  private def channelState(
      c: Channel,
      us: V2Json.Users,
      members: List[MemberRow],
      memberCount: Long,
      callerMember: Option[MemberRow],
      messages: List[Message],
      reacts: Map[String, MessageReactions],
      reads: List[ReadRow],
      watcherCount: Int,
  ): Json =
    Json.obj(
      "channel" -> V2Json.channel(c, us, memberCount, None, callerMember.map(_.role)),
      "members" -> members.map(V2Json.member(_, us)).asJson,
      "membership" -> callerMember.map(V2Json.member(_, us)).asJson,
      "messages" -> messages.map(m =>
        V2Json.message(m, us, reacts.getOrElse(m.id, MessageReactions.empty))
      ).asJson,
      "pinned_messages" -> Json.arr(),
      "threads" -> Json.arr(),
      "read" -> reads.map(V2Json.read(_, us)).asJson,
      "watcher_count" -> watcherCount.asJson,
      "watchers" -> Json.arr(),
      "hidden" -> false.asJson,
    ).dropNullValues
