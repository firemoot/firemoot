package com.firemoot.streamapi

import java.time.{OffsetDateTime, ZoneOffset}

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.backplane.Backplane
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{ChannelRepo, LookupRepo, ReactionRow, StreamStateRepo}
import com.firemoot.domain.{Channel, Event, Message}
import fs2.Stream
import io.circe.syntax.*
import io.circe.{Decoder, Json}
import org.http4s.websocket.WebSocketFrame
import org.typelevel.log4cats.Logger
import skunk.Session
import skunk.data.Arr

/**
 * Renders Firemoot's backplane events as getstream.io v2 socket events and
 * routes them the way Stream does:
 *
 *  - channel events go to the sockets watching that cid (`message.read` is
 *    Firemoot's `read.updated`);
 *  - a `message.new` also reaches each member's other sockets, which aren't
 *    watching the channel, as `notification.message_new`;
 *  - member changes are followed by `channel.updated` with the new
 *    `member_count`, which the SDK treats as authoritative;
 *  - Firemoot's user-directed events become `notification.mark_read`,
 *    `notification.added_to_channel` / `removed_from_channel` and
 *    `user.presence.changed` on that user's sockets.
 *
 * Channel objects inside events omit `own_capabilities`: the SDK keeps the
 * per-caller capabilities it already holds when the field is absent, and a
 * shared event can't carry each recipient's own.
 */
final class StreamEvents(
    pool: Resource[IO, Session[IO]],
    backplane: Backplane,
    connections: StreamConnections,
    state: StreamStateService,
    logger: Logger[IO],
):

  // Rendering reads a few rows, and a slow subscriber backpressures every
  // publisher on the topic, so dispatch concurrently (Stream delivery is
  // unordered across channels anyway) and never let one failure end the stream.
  def stream: Stream[IO, Nothing] =
    backplane.subscribe
      .parEvalMapUnordered(4)(event =>
        dispatch(event).handleErrorWith(e =>
          logger.error(e)(s"stream event dispatch failed for ${event.`type`} on ${event.cid}")
        )
      )
      .drain

  private def now: IO[OffsetDateTime] = IO.realTimeInstant.map(_.atOffset(ZoneOffset.UTC))

  private def send(targets: List[StreamConnection], json: Json): IO[Unit] =
    val frame = WebSocketFrame.Text(json.noSpaces)
    targets.traverse_(_.outbound.offer(Some(frame)))

  private def field[A: Decoder](data: Json, name: String): Option[A] =
    data.hcursor.get[A](name).toOption

  private def eventChannel(c: Channel, us: V2Json.Users, memberCount: Long): Json =
    V2Json.channel(c, us, memberCount, None, None).mapObject(_.remove("own_capabilities"))

  def dispatch(event: Event): IO[Unit] =
    event.target match
      case Some(userId) => notify(event, userId)
      case None =>
        connections.all.flatMap { all =>
          val watchers = all.filter(_.watched(event.cid))
          val needsRender = watchers.nonEmpty || (event.`type` == "message.new" && all.nonEmpty)
          IO.whenA(needsRender) {
            now.flatMap(at =>
              pool.use(s =>
                render(s, event, at).flatMap {
                  case None => IO.unit
                  case Some(json) =>
                    send(watchers, json) >>
                      followUp(s, event, json, all, watchers, at)
                }
              )
            )
          }
        }

  private def followUp(
      s: Session[IO],
      event: Event,
      rendered: Json,
      all: List[StreamConnection],
      watchers: List[StreamConnection],
      at: OffsetDateTime,
  ): IO[Unit] =
    event.`type` match
      case "message.new" =>
        s.runList(LookupRepo.members, Arr(event.cid)).flatMap { members =>
          val memberIds = members.map(_.userId).toSet
          val unwatched = all.filter(c => memberIds(c.userId) && !c.watched(event.cid))
          IO.whenA(unwatched.nonEmpty) {
            channelSnapshot(s, event.cid).flatMap(_.traverse_ { channelJson =>
              val note = StreamEvents.base("notification.message_new", event.cid, at).deepMerge(
                Json.obj(
                  "channel" -> channelJson,
                  "message" -> rendered.hcursor.downField("message").focus.getOrElse(Json.Null),
                  "message_id" ->
                    rendered.hcursor.downField("message_id").focus.getOrElse(Json.Null),
                )
              )
              send(unwatched, note)
            })
          }
        }
      case "member.added" | "member.removed" =>
        IO.whenA(watchers.nonEmpty) {
          channelSnapshot(s, event.cid).flatMap(_.traverse_ { channelJson =>
            send(
              watchers,
              StreamEvents.base("channel.updated", event.cid, at).deepMerge(Json.obj("channel" ->
                channelJson)),
            )
          })
        }
      case _ => IO.unit

  private def channelSnapshot(s: Session[IO], cid: String): IO[Option[Json]] =
    s.runOption(ChannelRepo.anyByCid, cid).flatMap(_.traverse { c =>
      (state.users(s, c.createdBy.toList), s.runUnique(LookupRepo.memberCount, cid))
        .mapN((us, count) => eventChannel(c, us, count))
    })

  private def messageWithReactions(s: Session[IO], m: Message): IO[(Json, V2Json.Users)] =
    state.reactions(s, List(m.id), None).flatMap { reacts =>
      val r = reacts.getOrElse(m.id, MessageReactions.empty)
      state.users(s, m.userId.toList ++ r.latest.map(_.userId)).map(us =>
        (V2Json.message(m, us, r), us)
      )
    }

  /** The v2 event for a channel-broadcast Firemoot event, or None when Stream has no equivalent. */
  def render(s: Session[IO], event: Event, at: OffsetDateTime): IO[Option[Json]] =
    val cid = event.cid
    def base(t: String) = StreamEvents.base(t, cid, at)
    def withUser(t: String, userId: String, extra: (String, Json)*) =
      state.users(s, List(userId)).map(us =>
        base(t).deepMerge(Json.obj(("user" -> us(userId)) +: extra*))
      )
    event.`type` match
      case "message.new" | "message.updated" =>
        event.data.as[Message].toOption.traverse { m =>
          messageWithReactions(s, m).flatMap { (json, us) =>
            connections.watcherCount(cid).map { watchers =>
              base(event.`type`).deepMerge(Json.obj(
                "message" -> json,
                "message_id" -> m.id.asJson,
                "user" -> m.userId.fold(V2Json.missingUser(""))(us(_)),
                "watcher_count" -> watchers.asJson,
                "created_at" ->
                  V2Json.ns(if event.`type` == "message.new" then m.createdAt else m.updatedAt),
              ))
            }
          }
        }
      case "message.deleted" =>
        field[String](event.data, "id").flatTraverse(id =>
          s.runOption(LookupRepo.message, id).flatMap(_.traverse { m =>
            messageWithReactions(s, m).map { (json, us) =>
              base("message.deleted").deepMerge(Json.obj(
                "message" -> json,
                "message_id" -> m.id.asJson,
                "hard_delete" -> false.asJson,
                "user" -> m.userId.fold(V2Json.missingUser(""))(us(_)),
              ))
            }
          })
        )
      case "reaction.new" | "reaction.deleted" =>
        (
          field[String](event.data, "messageId"),
          field[String](event.data, "userId"),
          field[String](event.data, "type"),
        )
          .tupled.flatTraverse { (messageId, reactorId, reactionType) =>
            s.runOption(LookupRepo.message, messageId).flatMap(_.traverse { m =>
              messageWithReactions(s, m).flatMap { (json, _) =>
                state.users(s, List(reactorId)).map { us =>
                  val reaction = ReactionRow(messageId, reactorId, reactionType, at)
                  base(event.`type`).deepMerge(Json.obj(
                    "message" -> json,
                    "message_id" -> messageId.asJson,
                    "reaction" -> V2Json.reaction(reaction, us),
                    "user" -> us(reactorId),
                  ))
                }
              }
            })
          }
      case "read.updated" =>
        field[String](event.data, "userId").traverse { reader =>
          lastReadMessageId(s, cid, field[Long](event.data, "lastReadSeq")).flatMap { lastRead =>
            withUser("message.read", reader, "last_read_message_id" -> lastRead.asJson)
          }.map(_.dropNullValues)
        }
      case "member.added" =>
        field[String](event.data, "userId").flatTraverse(userId =>
          s.runOption(LookupRepo.member, (cid, userId)).flatMap(_.traverse { row =>
            state.users(s, List(userId)).map(us =>
              base("member.added").deepMerge(Json.obj(
                "member" -> V2Json.member(row, us),
                "user" -> us(userId),
              ))
            )
          })
        )
      case "member.removed" =>
        field[String](event.data, "userId").traverse(userId =>
          state.users(s, List(userId)).map(us =>
            base("member.removed").deepMerge(Json.obj(
              "member" -> Json.obj("user_id" -> userId.asJson, "user" -> us(userId)),
              "user" -> us(userId),
            ))
          )
        )
      case "channel.updated" | "channel.deleted" =>
        channelSnapshot(s, cid).map(_.map(c =>
          base(event.`type`).deepMerge(Json.obj("channel" -> c))
        ))
      case "typing.start" | "typing.stop" =>
        field[String](event.data, "userId").traverse(userId =>
          withUser(
            event.`type`,
            userId,
            "parent_id" -> field[String](event.data, "parentId").asJson,
          )
            .map(_.dropNullValues)
        )
      case other if StreamEvents.isCustom(other) =>
        field[String](event.data, "userId").traverse(userId =>
          withUser(
            other,
            userId,
            "custom" -> event.data.hcursor.downField("custom").focus.getOrElse(Json.obj()),
          )
        )
      case _ => IO.pure(None)

  private def lastReadMessageId(
      s: Session[IO],
      cid: String,
      seq: Option[Long],
  ): IO[Option[String]] =
    seq.filter(_ > 0).flatTraverse { upTo =>
      s.runList(StreamStateRepo.before, (cid, upTo + 1, 1)).map(_.headOption.map(_.id))
    }

  /** Firemoot's user-directed events, as Stream's per-user notifications. */
  private def notify(event: Event, userId: String): IO[Unit] =
    connections.ofUser(userId).flatMap { targets =>
      IO.whenA(targets.nonEmpty) {
        now.flatMap(at =>
          pool.use { s =>
            val json: IO[Option[Json]] = event.`type` match
              case "read.updated" =>
                lastReadMessageId(s, event.cid, field[Long](event.data, "lastReadSeq")).flatMap {
                  lastRead =>
                    (
                      state.users(s, List(userId)),
                      s.runUnique(StreamStateRepo.unreadChannels, userId),
                    ).mapN { (us, unreadChannels) =>
                      val total = field[Long](event.data, "totalUnread").getOrElse(0L)
                      Some(StreamEvents.base(
                        "notification.mark_read",
                        event.cid,
                        at,
                      ).deepMerge(Json.obj(
                        "user" -> us(userId),
                        "last_read_message_id" -> lastRead.asJson,
                        "total_unread_count" -> total.asJson,
                        "unread_count" -> total.asJson,
                        "unread_channels" -> unreadChannels.asJson,
                        "unread_messages" -> 0.asJson,
                        "unread_threads" -> 0.asJson,
                      )).dropNullValues)
                    }
                }
              case "notification.added_to_channel" | "notification.removed_from_channel" =>
                channelSnapshot(s, event.cid).flatMap(_.traverse { c =>
                  s.runOption(LookupRepo.member, (event.cid, userId)).flatMap { row =>
                    state.users(s, List(userId)).map { us =>
                      StreamEvents.base(event.`type`, event.cid, at).deepMerge(Json.obj(
                        "channel" -> c,
                        "member" -> row.fold(Json.obj(
                          "user_id" -> userId.asJson,
                          "user" -> us(userId),
                        ))(V2Json.member(_, us)),
                      ))
                    }
                  }
                })
              case "presence.changed" =>
                field[String](event.data, "userId").traverse { peer =>
                  state.users(s, List(peer)).map(us =>
                    Json.obj(
                      "type" -> "user.presence.changed".asJson,
                      "user" -> us(peer),
                      "created_at" -> V2Json.ns(at),
                      "custom" -> Json.obj(),
                    )
                  )
                }
              case _ => IO.pure(None)
            json.flatMap(_.traverse_(send(targets, _)))
          }
        )
      }
    }

object StreamEvents:

  /** Firemoot's own event names; anything else on a channel is an app's custom event. */
  private val firemootTypes = Set(
    "message.new",
    "message.updated",
    "message.deleted",
    "reaction.new",
    "reaction.deleted",
    "read.updated",
    "member.added",
    "member.removed",
    "channel.updated",
    "channel.deleted",
    "typing.start",
    "typing.stop",
    "presence.changed",
    "notification.added_to_channel",
    "notification.removed_from_channel",
  )

  def isCustom(eventType: String): Boolean = !firemootTypes(eventType)

  /** The envelope every channel event shares. */
  def base(eventType: String, cid: String, at: OffsetDateTime): Json =
    val (channelType, channelId) = cid.split(":", 2) match
      case Array(t, id) => (t, id)
      case _ => ("", cid)
    Json.obj(
      "type" -> eventType.asJson,
      "cid" -> cid.asJson,
      "channel_id" -> channelId.asJson,
      "channel_type" -> channelType.asJson,
      "created_at" -> V2Json.ns(at),
      "custom" -> Json.obj(),
    )

/** `user.watching.start` / `stop`, sent to a channel's watchers with the new `watcher_count`. */
object StreamWatching:

  def announce(
      pool: Resource[IO, Session[IO]],
      connections: StreamConnections,
      cid: String,
      userId: String,
      start: Boolean,
  ): IO[Unit] =
    for
      user <- pool.use(_.runOption(com.firemoot.db.UserRepo.byId, userId))
      watchers <- connections.watching(cid)
      at <- IO.realTimeInstant.map(_.atOffset(ZoneOffset.UTC))
      json = StreamEvents
        .base(if start then "user.watching.start" else "user.watching.stop", cid, at)
        .deepMerge(Json.obj(
          "user" -> user.fold(V2Json.missingUser(userId))(V2Json.user(_, online = true)),
          "watcher_count" -> watchers.map(_.userId).distinct.size.asJson,
        ))
      frame = WebSocketFrame.Text(json.noSpaces)
      _ <- watchers.traverse_(_.outbound.offer(Some(frame)))
    yield ()
