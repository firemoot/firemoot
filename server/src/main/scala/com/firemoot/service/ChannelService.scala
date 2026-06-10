package com.firemoot.service

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.backplane.Backplane
import com.firemoot.db.ChannelRepo
import com.firemoot.db.SessionSyntax.*
import com.firemoot.domain.{Channel, Event}
import io.circe.Json
import io.circe.syntax.*
import skunk.Session

final class ChannelService(pool: Resource[IO, Session[IO]], backplane: Backplane):

  /**
   * Creates a channel (`cid = type:id`) and, when a creator is given, adds them
   * as the owning member - both in one transaction.
   */
  def create(
      channelType: String,
      id: String,
      createdBy: Option[String],
      custom: Json,
  ): IO[Channel] =
    val cid = s"$channelType:$id"
    pool.use { session =>
      session.transaction.use { _ =>
        for
          channel <-
            session.runUnique(ChannelRepo.insert, (cid, channelType, id, createdBy, custom))
          _ <- createdBy.traverse_(uid =>
            session.runOption(ChannelRepo.addMember, (cid, uid, "owner"))
          )
        yield channel
      }
    }

  def get(cid: String): IO[Option[Channel]] =
    pool.use(_.runOption(ChannelRepo.byCid, cid))

  def update(
      cid: String,
      custom: Option[Json],
      frozen: Option[Boolean],
      archived: Option[Boolean],
  ): IO[Option[Channel]] =
    pool
      .use { session =>
        session.transaction.use { _ =>
          session.runOption(ChannelRepo.update, (custom, frozen, archived, cid)).flatMap {
            case None => IO.pure((Option.empty[Channel], Option.empty[Event]))
            case Some(channel) =>
              ChannelEvents
                .persist(session, cid, "channel.updated", channel.asJson)
                .map(event => (Some(channel), Some(event)))
          }
        }
      }
      .flatMap((channel, event) => event.traverse_(backplane.publish).as(channel))

  def softDelete(cid: String): IO[Boolean] =
    pool
      .use { session =>
        session.transaction.use { _ =>
          session.runOption(ChannelRepo.softDelete, cid).flatMap {
            case None => IO.pure((false, Option.empty[Event]))
            case Some(_) =>
              ChannelEvents
                .persist(session, cid, "channel.deleted", Json.obj("cid" -> cid.asJson))
                .map(event => (true, Some(event)))
          }
        }
      }
      .flatMap((deleted, event) => event.traverse_(backplane.publish).as(deleted))

  /**
   * Adds a member (idempotent). Returns false if the channel does not exist.
   * Emits `member.added` to the channel and `notification.added_to_channel` to
   * the added user, but only on an actual (non-repeat) add.
   */
  def addMember(cid: String, userId: String, role: String): IO[Boolean] =
    get(cid).flatMap {
      case None => IO.pure(false)
      case Some(_) =>
        pool
          .use { session =>
            session.transaction.use { _ =>
              session.runOption(ChannelRepo.addMember, (cid, userId, role)).flatMap {
                case None => IO.pure(List.empty[Event])
                case Some(_) =>
                  val data =
                    Json.obj("cid" -> cid.asJson, "userId" -> userId.asJson, "role" -> role.asJson)
                  ChannelEvents
                    .persist(session, cid, "member.added", data)
                    .map(memberAdded =>
                      List(
                        memberAdded,
                        Event.notification(
                          "notification.added_to_channel",
                          cid,
                          userId,
                          Json.obj("cid" -> cid.asJson),
                        ),
                      )
                    )
              }
            }
          }
          .flatMap(events => events.traverse_(backplane.publish))
          .as(true)
    }

  /**
   * Removes a member. Returns false if they were not a member. Emits
   * `member.removed` to the channel and `notification.removed_from_channel` to
   * the user.
   */
  def removeMember(cid: String, userId: String): IO[Boolean] =
    pool
      .use { session =>
        session.transaction.use { _ =>
          session.runOption(ChannelRepo.removeMember, (cid, userId)).flatMap {
            case None => IO.pure(List.empty[Event])
            case Some(_) =>
              val data = Json.obj("cid" -> cid.asJson, "userId" -> userId.asJson)
              ChannelEvents
                .persist(session, cid, "member.removed", data)
                .map(memberRemoved =>
                  List(
                    memberRemoved,
                    Event.notification(
                      "notification.removed_from_channel",
                      cid,
                      userId,
                      Json.obj("cid" -> cid.asJson),
                    ),
                  )
                )
          }
        }
      }
      .flatMap(events => events.traverse_(backplane.publish).as(events.nonEmpty))
