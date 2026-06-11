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

/**
 * Read-only queries (SPEC.md §5, M1.9): channel filtering with cursor
 * pagination, message history, and full-text search. The filter values flow
 * straight into [[QueryRepo]]'s bound parameters; list filters are carried as
 * jsonb arrays so the statement shape never varies with the input.
 */
final class QueryService(pool: Resource[IO, Session[IO]]):

  import QueryService.{DefaultLimit, MaxLimit}

  private def clamp(limit: Option[Int]): Int =
    limit.map(l => math.max(1, math.min(l, MaxLimit))).getOrElse(DefaultLimit)

  def channels(q: ChannelQuery): IO[ChannelPage] =
    val limit = clamp(q.limit)
    val params = (
      q.`type`,
      q.cids.map(_.asJson),
      q.members.map(_.asJson),
      q.custom,
      q.archived,
      q.cursor.map(_.ts),
      q.cursor.map(_.cid),
      limit,
    )
    pool.use(_.runList(QueryRepo.channels, params)).map { rows =>
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
