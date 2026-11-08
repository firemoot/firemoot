package com.firemoot.webhook

import java.time.OffsetDateTime

import cats.effect.IO
import cats.syntax.all.*
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{LookupRepo, MemberRow, ReactionRow}
import com.firemoot.domain.{Channel, Event, Message, User}
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import skunk.Session
import skunk.data.Arr

/**
 * Renders webhook bodies in getstream.io's own payload shape (the `stream`
 * endpoint format), so a handler written against Stream reads them unchanged.
 *
 * Only what Firemoot can truthfully compute is emitted: there is no `html`,
 * `online`, channel `config`, `request_info` or `message_update.old_text`, and
 * `banned` is always false because Firemoot has no bans. Firemoot's soft delete
 * scrubs message text, so `message.deleted` carries `deleted_at` and no `text`.
 */
object StreamFormat:

  /** `message.new` lists the channel's members only up to this many. */
  val MaxMembersOnMessageNew = 100

  val LatestReactions = 10

  /** Firemoot event types whose Stream name differs; the rest keep their name. */
  def streamType(firemootType: String): String = firemootType match
    case "read.updated" => "message.read"
    case "user.flagged" => "message.flagged"
    case other => other

  /** Builds a JSON object, dropping absent (null) fields, with `custom` spread underneath. */
  private def withCustom(custom: Json, fields: (String, Json)*): Json =
    val base = Json.obj(fields*).dropNullValues
    custom.asObject.fold(base)(obj => Json.fromJsonObject(obj).deepMerge(base))

  def user(u: User): Json =
    withCustom(
      u.custom,
      "id" -> u.id.asJson,
      "name" -> u.name.asJson,
      "image" -> u.image.asJson,
      "role" -> u.role.asJson,
      "created_at" -> u.createdAt.asJson,
      "updated_at" -> u.updatedAt.asJson,
      "last_active" -> u.lastActiveAt.asJson,
      "banned" -> false.asJson,
    )

  private def userRef(id: String, users: Map[String, User]): Json =
    users.get(id).fold(Json.obj("id" -> id.asJson))(user)

  def reaction(r: ReactionRow, users: Map[String, User]): Json =
    Json.obj(
      "message_id" -> r.messageId.asJson,
      "user_id" -> r.userId.asJson,
      "user" -> userRef(r.userId, users),
      "type" -> r.`type`.asJson,
      "score" -> 1.asJson,
      "created_at" -> r.createdAt.asJson,
      "updated_at" -> r.createdAt.asJson,
    )

  def message(
      m: Message,
      users: Map[String, User],
      counts: Map[String, Long],
      latest: List[ReactionRow],
  ): Json =
    withCustom(
      m.custom,
      "id" -> m.id.asJson,
      "cid" -> m.cid.asJson,
      "text" -> m.text.asJson,
      "type" -> m.`type`.asJson,
      "user" -> m.userId.map(userRef(_, users)).asJson,
      "attachments" -> m.attachments,
      "latest_reactions" -> latest.map(reaction(_, users)).asJson,
      "own_reactions" -> Json.arr(),
      "reaction_counts" -> counts.asJson,
      "reaction_scores" -> counts.asJson,
      "reply_count" -> m.replyCount.asJson,
      "parent_id" -> m.parentMessageId.asJson,
      "mentioned_users" -> Json.arr(),
      "created_at" -> m.createdAt.asJson,
      "updated_at" -> m.updatedAt.asJson,
      "deleted_at" -> m.deletedAt.asJson,
    )

  def member(m: MemberRow, users: Map[String, User]): Json =
    Json.obj(
      "user_id" -> m.userId.asJson,
      "user" -> userRef(m.userId, users),
      "role" -> m.role.asJson,
      "channel_role" ->
        (if m.role == "moderator" then "channel_moderator" else "channel_member").asJson,
      "is_moderator" -> (m.role == "moderator").asJson,
      "created_at" -> m.createdAt.asJson,
      "updated_at" -> m.createdAt.asJson,
    )

  /** `members` is included when given; `member_count` comes from it or `memberCount`. */
  def channel(
      c: Channel,
      users: Map[String, User],
      memberCount: Long,
      members: Option[List[MemberRow]],
  ): Json =
    withCustom(
      c.custom,
      "cid" -> c.cid.asJson,
      "id" -> c.id.asJson,
      "type" -> c.`type`.asJson,
      "created_by" -> c.createdBy.map(userRef(_, users)).asJson,
      "frozen" -> c.frozen.asJson,
      "member_count" -> memberCount.asJson,
      "members" -> members.map(_.map(member(_, users))).asJson,
      "last_message_at" -> c.lastMessageAt.asJson,
      "created_at" -> c.createdAt.asJson,
      "updated_at" -> c.updatedAt.asJson,
      "deleted_at" -> c.deletedAt.asJson,
    )

  /**
   * `user.unread_message_reminder` in Stream's shape: `channels` keyed by cid,
   * each with the channel (members nested inside it) and its newest messages.
   * `users` must cover the recipient's channels' members and message authors.
   */
  def reminder(
      recipient: User,
      channels: List[(Channel, List[MemberRow], List[Message])],
      users: Map[String, User],
      now: OffsetDateTime,
  ): Json =
    Json.obj(
      "type" -> UnreadReminder.EventType.asJson,
      "created_at" -> now.asJson,
      "user" -> user(recipient),
      "channels" -> Json.fromFields(channels.map { (c, members, messages) =>
        c.cid -> Json.obj(
          "channel" -> channel(c, users, members.size.toLong, Some(members)),
          "messages" -> messages.map(message(_, users, Map.empty, Nil)).asJson,
        )
      }),
    )

  private def lookupUsers(s: Session[IO], ids: Iterable[String]): IO[Map[String, User]] =
    val distinct = ids.toList.distinct
    if distinct.isEmpty then IO.pure(Map.empty)
    else s.runList(LookupRepo.users, Arr(distinct*)).map(_.map(u => u.id -> u).toMap)

  private def lookupChannel(s: Session[IO], cid: String): IO[Option[Channel]] =
    s.runList(LookupRepo.channels, Arr(cid)).map(_.headOption)

  final private case class Reactions(counts: Map[String, Long], latest: List[ReactionRow])

  private def reactionsOf(s: Session[IO], messageId: String): IO[Reactions] =
    (
      s.runList(LookupRepo.reactionCounts, messageId).map(_.toMap),
      s.runList(LookupRepo.latestReactions, (messageId, LatestReactions)),
    ).mapN(Reactions.apply)

  private def field[A: Decoder](data: Json, name: String): Option[A] =
    data.hcursor.get[A](name).toOption

  /**
   * Stream's body for a Firemoot channel event (or `user.flagged`), loading the
   * users, message and channel rows Stream embeds. None when the event has no
   * Stream counterpart or the rows it describes are gone.
   */
  def render(s: Session[IO], event: Event, now: OffsetDateTime): IO[Option[Json]] =
    val (channelType, channelId) = event.cid.split(":", 2) match
      case Array(t, id) => (t, id)
      case _ => ("", event.cid)
    def body(fields: (String, Json)*): Json =
      Json.obj(
        (List(
          "type" -> streamType(event.`type`).asJson,
          "cid" -> event.cid.asJson,
          "channel_id" -> channelId.asJson,
          "channel_type" -> channelType.asJson,
          "created_at" -> now.asJson,
        ) ++ fields)*
      )

    event.`type` match
      case "message.new" =>
        event.data.as[Message].toOption.traverse { m =>
          for
            count <- s.runUnique(LookupRepo.memberCount, event.cid)
            listed <-
              if count <= MaxMembersOnMessageNew then
                s.runList(LookupRepo.members, Arr(event.cid)).map(Some(_))
              else IO.pure(None)
            users <- lookupUsers(s, m.userId.toList ++ listed.toList.flatten.map(_.userId))
          yield body(
            "message" -> message(m, users, Map.empty, Nil),
            "user" -> m.userId.map(userRef(_, users)).asJson,
            "members" -> listed.map(_.map(member(_, users))).asJson,
          ).dropNullValues
        }

      case "message.updated" =>
        event.data.as[Message].toOption.traverse { m =>
          for
            reactions <- reactionsOf(s, m.id)
            users <- lookupUsers(s, m.userId.toList ++ reactions.latest.map(_.userId))
          yield body(
            "message" -> message(m, users, reactions.counts, reactions.latest),
            "user" -> m.userId.map(userRef(_, users)).asJson,
          ).dropNullValues
        }

      case "message.deleted" =>
        field[String](event.data, "id").flatTraverse { id =>
          s.runOption(LookupRepo.message, id).flatMap(_.traverse { m =>
            for
              reactions <- reactionsOf(s, m.id)
              users <- lookupUsers(s, m.userId.toList ++ reactions.latest.map(_.userId))
            yield body(
              "message" -> message(m, users, reactions.counts, reactions.latest),
              "hard_delete" -> false.asJson,
            )
          })
        }

      case "reaction.new" | "reaction.deleted" =>
        (
          field[String](event.data, "messageId"),
          field[String](event.data, "userId"),
          field[String](event.data, "type"),
        ).tupled.flatTraverse { (messageId, reactorId, reactionType) =>
          s.runOption(LookupRepo.message, messageId).flatMap(_.traverse { m =>
            for
              reactions <- reactionsOf(s, m.id)
              users <-
                lookupUsers(s, reactorId :: m.userId.toList ++ reactions.latest.map(_.userId))
              row = reactions.latest
                .find(r => r.userId == reactorId && r.`type` == reactionType)
                .getOrElse(ReactionRow(messageId, reactorId, reactionType, now))
            yield body(
              "message" -> message(m, users, reactions.counts, reactions.latest),
              "reaction" -> reaction(row, users),
              "user" -> userRef(reactorId, users),
            )
          })
        }

      case "read.updated" =>
        field[String](event.data, "userId").traverse { readerId =>
          lookupUsers(s, List(readerId)).map { users =>
            val unread = field[Long](event.data, "unreadCount").getOrElse(0L)
            val total = field[Long](event.data, "totalUnread").getOrElse(0L)
            val reader = userRef(readerId, users).deepMerge(
              Json.obj(
                "channel_unread_count" -> unread.asJson,
                "channel_last_read_at" -> now.asJson,
                "total_unread_count" -> total.asJson,
                "unread_count" -> total.asJson,
              )
            )
            body("user" -> reader)
          }
        }

      case "channel.updated" | "channel.deleted" =>
        lookupChannel(s, event.cid).flatMap(_.traverse { c =>
          for
            count <- s.runUnique(LookupRepo.memberCount, c.cid)
            users <- lookupUsers(s, c.createdBy.toList)
          yield body("channel" -> channel(c, users, count, None))
        })

      case "member.added" =>
        field[String](event.data, "userId").flatTraverse { memberId =>
          s.runOption(LookupRepo.member, (event.cid, memberId)).flatMap(_.traverse { row =>
            lookupUsers(s, List(memberId)).map { users =>
              body("member" -> member(row, users), "user" -> userRef(memberId, users))
            }
          })
        }

      case "member.removed" =>
        field[String](event.data, "userId").traverse { memberId =>
          lookupUsers(s, List(memberId)).map { users =>
            body(
              "member" -> Json.obj(
                "user_id" -> memberId.asJson,
                "user" -> userRef(memberId, users),
              ),
              "user" -> userRef(memberId, users),
            )
          }
        }

      case "user.flagged" =>
        (field[String](event.data, "messageId"), field[String](event.data, "flaggedBy")).tupled
          .flatTraverse { (messageId, flaggedBy) =>
            s.runOption(LookupRepo.message, messageId).flatMap(_.traverse { m =>
              for
                reactions <- reactionsOf(s, m.id)
                total <- s.runUnique(LookupRepo.flagCount, m.id)
                users <-
                  lookupUsers(s, flaggedBy :: m.userId.toList ++ reactions.latest.map(_.userId))
              yield body(
                "user" -> userRef(flaggedBy, users),
                "message" -> message(m, users, reactions.counts, reactions.latest),
                "reason" -> field[String](event.data, "reason").asJson,
                "total_flags" -> total.asJson,
              ).dropNullValues
            })
          }

      case _ => IO.pure(None)
