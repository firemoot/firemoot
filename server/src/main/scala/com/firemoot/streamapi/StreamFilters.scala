package com.firemoot.streamapi

import cats.syntax.all.*
import com.firemoot.api.ChannelQuery
import io.circe.{Json, JsonObject}

/**
 * Translates getstream.io `filter_conditions` into Firemoot queries. A field
 * takes a bare value, `{$eq: v}` or `{$in: [..]}`, and `$and` merges clauses.
 * Fields with no Firemoot equivalent are rejected with Stream's input error
 * rather than ignored, so a query never silently returns the wrong channels.
 */
object StreamFilters:

  private def values(json: Json): Option[List[Json]] =
    json.asObject match
      case Some(o) if o.contains("$in") => o("$in").flatMap(_.asArray).map(_.toList)
      case Some(o) if o.contains("$eq") => o("$eq").map(v => v.asArray.fold(List(v))(_.toList))
      case Some(_) => None
      case None => Some(json.asArray.fold(List(json))(_.toList))

  private def strings(json: Json): Option[List[String]] =
    values(json).flatMap(_.traverse(_.asString))

  private def flatten(filter: JsonObject): Either[String, List[(String, Json)]] =
    filter.toList.flatTraverse {
      case ("$and", clauses) =>
        clauses.asArray.toRight("$and expects an array").flatMap(
          _.toList.flatTraverse(c =>
            c.asObject.toRight("$and clauses must be objects").flatMap(flatten)
          )
        )
      case (op, _) if op.startsWith("$") => Left(s"operator $op is not supported")
      case kv => Right(List(kv))
    }

  def channelQuery(json: Option[Json], op: String): Either[StreamApiError, ChannelQuery] =
    val filter = json.flatMap(_.asObject).getOrElse(JsonObject.empty)
    flatten(filter).flatMap { clauses =>
      val empty = ChannelQuery(None, None, None, None, None, None, None)
      clauses.foldLeftM((empty, List.empty[String], Option.empty[List[String]])) {
        case ((q, ids, cids), ("type", v)) =>
          strings(v).flatMap(_.headOption).toRight("type must be a string")
            .map(t => (q.copy(`type` = Some(t)), ids, cids))
        case ((q, ids, cids), ("id", v)) =>
          strings(v).toRight("id must be a string or $in list").map(more => (q, ids ++ more, cids))
        case ((q, ids, cids), ("cid", v)) =>
          strings(v).toRight("cid must be a string or $in list").map(more =>
            (q, ids, Some(cids.getOrElse(Nil) ++ more))
          )
        case ((q, ids, cids), ("members", v)) =>
          strings(v).toRight("members must be a $in list").map(ms =>
            (q.copy(members = Some(ms)), ids, cids)
          )
        case ((q, ids, cids), ("archived", v)) =>
          v.asBoolean.toRight("archived must be a boolean").map(a =>
            (q.copy(archived = Some(a)), ids, cids)
          )
        case ((q, ids, cids), (field, v)) =>
          val key = field.stripPrefix("custom.")
          val builtIn = Set(
            "frozen",
            "disabled",
            "hidden",
            "joined",
            "muted",
            "pinned",
            "last_message_at",
            "created_at",
            "updated_at",
            "created_by_id",
            "member_count",
            "team",
            "invite",
            "last_updated",
          )
          if !field.startsWith("custom.") && builtIn(field) then
            Left(s"filtering on $field is not supported")
          else
            val value = v.asObject.flatMap(o => o("$eq")).getOrElse(v)
            if value.isObject then Left(s"only equality filters are supported on custom field $key")
            else
              val custom = q.custom.flatMap(_.asObject).getOrElse(JsonObject.empty).add(key, value)
              Right((q.copy(custom = Some(Json.fromJsonObject(custom))), ids, cids))
      }.map { (q, ids, cids) =>
        val fromIds =
          Option.when(ids.nonEmpty)(ids.map(id => s"${q.`type`.getOrElse("messaging")}:$id"))
        val all = (cids, fromIds) match
          case (Some(a), Some(b)) => Some(a.filter(b.toSet))
          case (a, b) => a.orElse(b)
        q.copy(cids = all)
      }
    }.leftMap(StreamApiError.input(op, _))

  /** `{}` (everyone) or an `id` filter; None means no id restriction. */
  def userIds(json: Option[Json], op: String): Either[StreamApiError, Option[List[String]]] =
    val filter = json.flatMap(_.asObject).getOrElse(JsonObject.empty)
    flatten(filter).flatMap { clauses =>
      clauses.foldLeftM(Option.empty[List[String]]) {
        case (acc, ("id", v)) =>
          strings(v).toRight("id must be a string or $in list").map(ids =>
            Some(acc.fold(ids)(_.filter(ids.toSet)))
          )
        case (_, (field, _)) => Left(s"filtering users on $field is not supported")
      }
    }.leftMap(StreamApiError.input(op, _))
