package com.firemoot.service

import cats.effect.{IO, Resource}
import com.firemoot.api.{
  ChannelCursor,
  ChannelPage,
  ChannelQuery,
  MessagePage,
  SearchHit,
  SearchPage,
  SearchRequest,
}
import com.firemoot.db.QueryRepo
import com.firemoot.db.SessionSyntax.*
import io.circe.syntax.*
import skunk.Session
import skunk.data.Arr

/**
 * Read-only queries (SPEC.md §5, M1.9): channel filtering with cursor
 * pagination, message history, and full-text search. The filter values flow
 * straight into [[QueryRepo]]'s bound parameters; list filters are carried as
 * jsonb arrays so the statement shape never varies with the input.
 */
final class QueryService(
    pool: Resource[IO, Session[IO]],
    heavyMemberThreshold: Int = QueryService.HeavyMemberThreshold,
):

  import QueryService.{DefaultLimit, MaxLimit}

  private def clamp(limit: Option[Int]): Int =
    limit.map(l => math.max(1, math.min(l, MaxLimit))).getOrElse(DefaultLimit)

  /**
   * Routes to the statement whose plan is driven by the most selective filter
   * present. The all-optional [[QueryRepo.channels]] statement can only scan
   * every channel (each filter sits behind an `is null` OR), so it is reserved
   * for the cases where that is genuinely the cheapest shape: no cid/member
   * filter (an activity-index walk), or a member of more channels than
   * [[heavyMemberThreshold]] (where the index walk stops after `limit` hits but
   * a membership-driven join would touch every one of their channels).
   */
  def channels(q: ChannelQuery): IO[ChannelPage] =
    val limit = clamp(q.limit)
    val genericParams = (
      q.`type`,
      q.cids.map(_.asJson),
      q.members.map(_.asJson),
      q.custom,
      q.archived,
      q.cursor.map(_.ts),
      q.cursor.map(_.cid),
      limit,
    )
    pool
      .use { s =>
        (q.cids, q.members) match
          case (Some(cids), _) =>
            s.runList(
              QueryRepo.channelsByCids,
              (
                cids.asJson,
                q.`type`,
                q.members.map(_.asJson),
                q.custom,
                q.archived,
                q.cursor.map(_.ts),
                q.cursor.map(_.cid),
                limit,
              ),
            )
          case (None, Some(members)) =>
            val memberArr = Arr(members*)
            s.runUnique(QueryRepo.memberChannelCount, (memberArr, heavyMemberThreshold))
              .flatMap { memberships =>
                if memberships >= heavyMemberThreshold then
                  s.runList(QueryRepo.channels, genericParams)
                else
                  s.runList(
                    QueryRepo.channelsByMembers,
                    (
                      memberArr,
                      q.`type`,
                      q.custom,
                      q.archived,
                      q.cursor.map(_.ts),
                      q.cursor.map(_.cid),
                      limit,
                    ),
                  )
              }
          case (None, None) => s.runList(QueryRepo.channels, genericParams)
      }
      .map { rows =>
        val next = Option.when(rows.sizeIs == limit) {
          val last = rows.last
          ChannelCursor(last.lastMessageAt.getOrElse(last.createdAt), last.cid)
        }
        ChannelPage(rows, next)
      }

  def messageHistory(cid: String, beforeSeq: Option[Long], limit: Option[Int]): IO[MessagePage] =
    val lim = clamp(limit)
    pool.use(_.runList(QueryRepo.messageHistory, (cid, beforeSeq, lim))).map { rows =>
      MessagePage(rows, Option.when(rows.sizeIs == lim)(rows.last.seq))
    }

  /** The seq of `messageId` within `cid`, for resolving a `before_id` cursor (None if absent). */
  def messageSeq(cid: String, messageId: String): IO[Option[Long]] =
    pool.use(_.runOption(QueryRepo.messageSeqById, (cid, messageId)))

  def search(req: SearchRequest): IO[SearchPage] =
    val lim = clamp(req.limit)
    pool.use(_.runList(QueryRepo.search, (req.query, req.cid, lim))).map { rows =>
      SearchPage(rows.map((message, score) => SearchHit(message, score.toDouble)))
    }

  /** As [[search]], restricted to channels `userId` is a member of (client auth). */
  def searchAsMember(req: SearchRequest, userId: String): IO[SearchPage] =
    val lim = clamp(req.limit)
    pool.use(_.runList(QueryRepo.searchAsMember, (req.query, req.cid, userId, lim))).map { rows =>
      SearchPage(rows.map((message, score) => SearchHit(message, score.toDouble)))
    }

object QueryService:
  private val DefaultLimit = 25
  private val MaxLimit = 100

  /**
   * Memberships at which a members query stops being driven from the member's
   * own channel list and falls back to the activity-index walk. Both shapes
   * cost single-digit milliseconds at the boundary, so the exact value is not
   * sensitive.
   */
  private val HeavyMemberThreshold = 1000
