package com.firemoot.streamapi

import java.time.OffsetDateTime

import com.firemoot.db.{MemberRow, ReactionRow}
import com.firemoot.domain.{Channel, Message, User}
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/** A member's read position as Stream reports it. */
final case class ReadRow(
    cid: String,
    userId: String,
    lastReadAt: OffsetDateTime,
    lastReadMessageId: Option[String],
    unreadMessages: Long,
)

/** A message's reactions: per-type counts, the newest few, and the caller's own. */
final case class MessageReactions(
    counts: Map[String, Long],
    latest: List[ReactionRow],
    own: List[ReactionRow],
)

object MessageReactions:
  val empty: MessageReactions = MessageReactions(Map.empty, Nil, Nil)

/**
 * getstream.io's v2 wire shapes (what `stream-chat` v10 and `@stream-io/node-sdk`
 * read): every server timestamp is a JSON number of unix nanoseconds, and custom
 * data sits under `custom` on every entity. Fields the v2 schema marks required
 * are always present; Firemoot has no bans, mutes, teams, polls or pins, so those
 * carry their empty values rather than being omitted.
 */
object V2Json:

  /**
   * Unix nanoseconds, truncated to microseconds: JS doubles quantise ~1.7e18 to
   * about 256ns, so microsecond values compare equal wherever the SDK checks
   * equality (send reconciliation, read positions).
   */
  def ns(t: OffsetDateTime): Json =
    val micros = t.toEpochSecond * 1_000_000L + t.getNano / 1_000L
    Json.fromLong(micros * 1_000L)

  def nsOpt(t: Option[OffsetDateTime]): Json = t.fold(Json.Null)(ns)

  private def obj(fields: (String, Json)*): Json = Json.obj(fields*).dropNullValues

  private def customOf(json: Json): Json =
    json.asObject.fold(Json.obj())(Json.fromJsonObject)

  /** `UserResponse`. `online` is whether the user has a live connection. */
  def user(u: User, online: Boolean): Json =
    obj(
      "id" -> u.id.asJson,
      "name" -> u.name.asJson,
      "image" -> u.image.asJson,
      "role" -> u.role.asJson,
      "language" -> "".asJson,
      "teams" -> Json.arr(),
      "blocked_user_ids" -> Json.arr(),
      "banned" -> false.asJson,
      "online" -> online.asJson,
      "custom" -> customOf(u.custom),
      "created_at" -> ns(u.createdAt),
      "updated_at" -> ns(u.updatedAt),
      "last_active" -> nsOpt(u.lastActiveAt),
      "deleted_at" -> nsOpt(u.deletedAt),
    )

  /** A user that no longer exists (hard-deleted): Stream renders a bare id. */
  def missingUser(id: String): Json =
    obj(
      "id" -> id.asJson,
      "role" -> "user".asJson,
      "language" -> "".asJson,
      "teams" -> Json.arr(),
      "blocked_user_ids" -> Json.arr(),
      "banned" -> false.asJson,
      "online" -> false.asJson,
      "custom" -> Json.obj(),
      "created_at" -> Json.fromLong(0L),
      "updated_at" -> Json.fromLong(0L),
    )

  /** `OwnUserResponse`: the connected user, with their unread totals. */
  def ownUser(u: User, totalUnread: Long, unreadChannels: Long): Json =
    user(u, online = true).deepMerge(
      Json.obj(
        "invisible" -> false.asJson,
        "total_unread_count" -> totalUnread.asJson,
        "unread_count" -> totalUnread.asJson,
        "unread_channels" -> unreadChannels.asJson,
        "unread_threads" -> 0.asJson,
        "channel_mutes" -> Json.arr(),
        "mutes" -> Json.arr(),
        "devices" -> Json.arr(),
      )
    )

  final case class Users(byId: Map[String, User], online: Set[String]):
    def apply(id: String): Json = byId.get(id).fold(missingUser(id))(user(_, online(id)))

  /**
   * Stream's `messaging` channel-type defaults: what a member may do, plus the
   * moderation capabilities owners and moderators hold.
   */
  def ownCapabilities(role: Option[String]): List[String] =
    val member = List(
      "connect-events",
      "delete-own-message",
      "delete-own-reaction",
      "flag-message",
      "leave-channel",
      "mute-channel",
      "quote-message",
      "read-events",
      "search-messages",
      "send-custom-events",
      "send-links",
      "send-message",
      "send-reaction",
      "send-reply",
      "send-typing-events",
      "typing-events",
      "update-own-message",
      "upload-file",
    )
    val moderator = List(
      "ban-channel-members",
      "delete-any-message",
      "delete-channel",
      "freeze-channel",
      "pin-message",
      "update-any-message",
      "update-channel",
      "update-channel-members",
    )
    role match
      case Some("owner") | Some("moderator") => (member ++ moderator).sorted
      case Some(_) => member
      case None => Nil

  /** Channel config: the `messaging` type's feature flags. */
  val messagingConfig: Json =
    Json.obj(
      "name" -> "messaging".asJson,
      "typing_events" -> true.asJson,
      "read_events" -> true.asJson,
      "delivery_events" -> false.asJson,
      "connect_events" -> true.asJson,
      "search" -> true.asJson,
      "reactions" -> true.asJson,
      "replies" -> true.asJson,
      "quotes" -> true.asJson,
      "mutes" -> true.asJson,
      "uploads" -> true.asJson,
      "url_enrichment" -> false.asJson,
      "custom_events" -> true.asJson,
      "push_notifications" -> false.asJson,
      "reminders" -> false.asJson,
      "user_message_reminders" -> false.asJson,
      "mark_messages_pending" -> false.asJson,
      "polls" -> false.asJson,
      "shared_locations" -> false.asJson,
      "skip_last_msg_update_for_system_msgs" -> false.asJson,
      "count_messages" -> false.asJson,
      "message_retention" -> "infinite".asJson,
      "max_message_length" -> 5000.asJson,
      "automod" -> "disabled".asJson,
      "automod_behavior" -> "flag".asJson,
      "commands" -> Json.arr(),
      "created_at" -> Json.fromLong(0L),
      "updated_at" -> Json.fromLong(0L),
    )

  /** `ChannelMemberResponse`. */
  def member(m: MemberRow, users: Users): Json =
    obj(
      "user_id" -> m.userId.asJson,
      "user" -> users(m.userId),
      "role" -> m.role.asJson,
      "channel_role" -> channelRole(m.role).asJson,
      "is_moderator" -> (m.role == "moderator").asJson,
      "banned" -> false.asJson,
      "shadow_banned" -> false.asJson,
      "notifications_muted" -> false.asJson,
      "custom" -> Json.obj(),
      "created_at" -> ns(m.createdAt),
      "updated_at" -> ns(m.createdAt),
    )

  def channelRole(role: String): String = role match
    case "owner" => "channel_owner"
    case "moderator" => "channel_moderator"
    case _ => "channel_member"

  /**
   * `ChannelResponse`. `callerRole` is the caller's membership role (None for a
   * server-side caller or a non-member), which decides `own_capabilities`.
   */
  def channel(
      c: Channel,
      users: Users,
      memberCount: Long,
      members: Option[List[MemberRow]],
      callerRole: Option[String],
  ): Json =
    obj(
      "cid" -> c.cid.asJson,
      "id" -> c.id.asJson,
      "type" -> c.`type`.asJson,
      "custom" -> customOf(c.custom),
      "created_by" -> c.createdBy.map(users(_)).asJson,
      "frozen" -> c.frozen.asJson,
      "disabled" -> false.asJson,
      "hidden" -> false.asJson,
      "member_count" -> memberCount.asJson,
      "members" -> members.map(_.map(member(_, users))).asJson,
      "config" -> messagingConfig,
      "own_capabilities" -> ownCapabilities(callerRole).asJson,
      "cooldown" -> 0.asJson,
      "last_message_at" -> nsOpt(c.lastMessageAt),
      "created_at" -> ns(c.createdAt),
      "updated_at" -> ns(c.updatedAt),
      "deleted_at" -> nsOpt(c.deletedAt),
    )

  def reaction(r: ReactionRow, users: Users): Json =
    Json.obj(
      "message_id" -> r.messageId.asJson,
      "user_id" -> r.userId.asJson,
      "user" -> users(r.userId),
      "type" -> r.`type`.asJson,
      "score" -> 1.asJson,
      "custom" -> Json.obj(),
      "created_at" -> ns(r.createdAt),
      "updated_at" -> ns(r.createdAt),
    )

  /** Every attachment carries a `custom` object, as v2 requires. */
  private def attachments(json: Json): Json =
    json.asArray.fold(Json.arr())(items =>
      Json.fromValues(items.map(a =>
        a.asObject.fold(a)(o =>
          if o.contains("custom") then a else Json.fromJsonObject(o.add("custom", Json.obj()))
        )
      ))
    )

  /** Plain text as the HTML Stream's markdown renderer produces for it. */
  def html(text: Option[String]): String =
    text.filter(_.nonEmpty).fold("") { t =>
      val escaped = t
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&#34;")
        .replace("'", "&#39;")
      s"<p>$escaped</p>\n"
    }

  /** `MessageResponse`. */
  def message(m: Message, users: Users, reactions: MessageReactions): Json =
    val messageType =
      if m.deletedAt.isDefined then "deleted"
      else if m.parentMessageId.isDefined && m.`type` == "regular" then "reply"
      else m.`type`
    val groups = JsonObject.fromIterable(reactions.counts.toList.map { (t, n) =>
      val ofType = reactions.latest.filter(_.`type` == t).map(_.createdAt)
      t -> Json.obj(
        "count" -> n.asJson,
        "sum_scores" -> n.asJson,
        "first_reaction_at" -> ofType.minOption.fold(ns(m.createdAt))(ns),
        "last_reaction_at" -> ofType.maxOption.fold(ns(m.updatedAt))(ns),
      )
    })
    obj(
      "id" -> m.id.asJson,
      "cid" -> m.cid.asJson,
      "type" -> messageType.asJson,
      "text" -> m.text.getOrElse("").asJson,
      "html" -> html(m.text).asJson,
      "user" -> m.userId.fold(missingUser(""))(users(_)),
      "custom" -> customOf(m.custom),
      "attachments" -> attachments(m.attachments),
      "latest_reactions" -> reactions.latest.map(reaction(_, users)).asJson,
      "own_reactions" -> reactions.own.map(reaction(_, users)).asJson,
      "reaction_counts" -> reactions.counts.asJson,
      "reaction_scores" -> reactions.counts.asJson,
      "reaction_groups" -> Json.fromJsonObject(groups),
      "reply_count" -> m.replyCount.asJson,
      "deleted_reply_count" -> 0.asJson,
      "parent_id" -> m.parentMessageId.asJson,
      "show_in_channel" -> m.parentMessageId.map(_ => false).asJson,
      "mentioned_users" -> Json.arr(),
      "mentioned_channel" -> false.asJson,
      "mentioned_here" -> false.asJson,
      "restricted_visibility" -> Json.arr(),
      "pinned" -> false.asJson,
      "shadowed" -> false.asJson,
      "silent" -> false.asJson,
      "created_at" -> ns(m.createdAt),
      "updated_at" -> ns(m.updatedAt),
      "deleted_at" -> nsOpt(m.deletedAt),
    )

  /** `ReadStateResponse`. */
  def read(r: ReadRow, users: Users): Json =
    obj(
      "user" -> users(r.userId),
      "last_read" -> ns(r.lastReadAt),
      "last_read_message_id" -> r.lastReadMessageId.asJson,
      "unread_messages" -> r.unreadMessages.asJson,
    )
