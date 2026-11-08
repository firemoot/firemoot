package com.firemoot.webhook

import java.time.{OffsetDateTime, ZoneOffset}

import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.api.MemberState
import com.firemoot.config.UnreadReminderConfig
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.{LookupRepo, MemberRow, ReminderRepo, WebhookRepo}
import com.firemoot.domain.{Channel, Message, User, WebhookFormat}
import fs2.Stream
import io.circe.syntax.*
import io.circe.Json
import org.typelevel.log4cats.Logger
import skunk.Session
import skunk.data.Arr

/** One channel in a reminder: the channel, its members and its newest messages. */
final case class ReminderChannel(
    channel: Channel,
    members: List[MemberRow],
    messages: List[Message],
)

/** One `user.unread_message_reminder`: a user and up to ten of their channels. */
final case class Reminder(user: User, channels: List[ReminderChannel])

/**
 * The `user.unread_message_reminder` webhook event, getstream.io's equivalent.
 * Each carries the user and up to ten channels, each with its members and newest
 * five live messages (newest first), so a handler needs no follow-up API calls.
 */
object UnreadReminder:
  val EventType = "user.unread_message_reminder"
  val MaxChannelsPerEvent = 10
  val MessagesPerChannel = 5

  /** The deprecated Firemoot envelope: user-level, so no `cid` or `seq`. */
  def firemoot(reminder: Reminder): Json =
    Json.obj(
      "type" -> EventType.asJson,
      "data" -> Json.obj(
        "user" -> reminder.user.asJson,
        "channels" -> reminder.channels.map { rc =>
          Json.obj(
            "channel" -> rc.channel.asJson,
            "members" -> rc.members.map(m => MemberState(m.userId, m.role, m.lastReadSeq)).asJson,
            "messages" -> rc.messages.asJson,
          )
        }.asJson,
      ),
    )

  /** Every user a Stream-format body embeds: members and message authors. */
  def embeddedUserIds(reminders: List[Reminder]): List[String] =
    reminders
      .flatMap(_.channels)
      .flatMap(rc => rc.members.map(_.userId) ++ rc.messages.flatMap(_.userId))
      .distinct

/**
 * Sweep tuning. `lookback` bounds how far past due a reminder may still be sent:
 * anything older is skipped rather than sent late, which also keeps enabling
 * reminders from firing for every member's historical unread backlog.
 */
final case class ReminderTuning(
    sweepInterval: FiniteDuration,
    lookback: FiniteDuration,
    batchSize: Int,
)

object ReminderTuning:
  val default: ReminderTuning =
    ReminderTuning(sweepInterval = 15.seconds, lookback = 1.hour, batchSize = 500)

/**
 * Sends `user.unread_message_reminder` when a member leaves a message unread for
 * the configured interval. Each sweep claims due members (see
 * [[com.firemoot.db.ReminderRepo.claimDue]]) and enqueues one event per user
 * per ten channels into the webhook queue, rendered in each endpoint format, in
 * the same transaction, so the dispatcher handles signing, retries and
 * dead-lettering and a crash between claim and enqueue loses nothing.
 */
final class UnreadReminderWorker(
    pool: Resource[IO, Session[IO]],
    cfg: UnreadReminderConfig,
    logger: Logger[IO],
    tuning: ReminderTuning = ReminderTuning.default,
):

  def stream: Stream[IO, Nothing] =
    Stream
      .awakeEvery[IO](tuning.sweepInterval)
      .evalMap(_ => sweep.void.handleErrorWith(logger.error(_)("unread reminder sweep failed")))
      .drain

  def sweep: IO[Int] =
    IO.realTimeInstant.flatMap(now => sweepAt(now.atOffset(ZoneOffset.UTC)))

  /**
   * Claims and enqueues everything due at `now`, one batch per transaction until
   * a short batch; returns the number of events enqueued. Exposed so tests can
   * drive it with a fixed clock.
   */
  def sweepAt(now: OffsetDateTime): IO[Int] =
    val dueBefore = now.minus(cfg.interval.toJava)
    val windowStart = dueBefore.minus(tuning.lookback.toJava)
    val claimArgs = (windowStart, cfg.maxMembers, windowStart, dueBefore, tuning.batchSize)

    val batch: IO[(Int, Int)] =
      pool.use { s =>
        s.transaction.use { _ =>
          for
            formats <- s.execute(WebhookRepo.enabledFormats)
            claimed <- s.runList(ReminderRepo.claimDue, claimArgs)
            reminders <- NonEmptyList
              .fromList(claimed)
              .filter(_ => formats.nonEmpty)
              .fold(IO.pure(List.empty[Reminder]))(reminders(s, _))
            _ <- formats.traverse_(enqueue(s, _, reminders, now))
          yield (claimed.size, reminders.size)
        }
      }

    def loop(total: Int): IO[Int] =
      batch.flatMap { (claimed, sent) =>
        if claimed == tuning.batchSize then loop(total + sent) else IO.pure(total + sent)
      }

    loop(0).flatTap(n => logger.info(s"enqueued $n unread reminder(s)").whenA(n > 0))

  private def enqueue(
      s: Session[IO],
      format: WebhookFormat,
      reminders: List[Reminder],
      now: OffsetDateTime,
  ): IO[Unit] =
    format match
      case WebhookFormat.Firemoot =>
        reminders.traverse_(r => s.run(WebhookRepo.enqueue, (UnreadReminder.firemoot(r), format)))
      case WebhookFormat.Stream =>
        NonEmptyList.fromList(UnreadReminder.embeddedUserIds(reminders)).traverse_ { ids =>
          s.runList(LookupRepo.users, Arr(ids.toList*)).flatMap { users =>
            val byId = users.map(u => u.id -> u).toMap
            reminders.traverse_ { r =>
              val channels = r.channels.map(rc => (rc.channel, rc.members, rc.messages))
              s.run(
                WebhookRepo.enqueue,
                (StreamFormat.reminder(r.user, channels, byId, now), format),
              )
            }
          }
        }

  private def reminders(
      s: Session[IO],
      claimed: NonEmptyList[(String, String)],
  ): IO[List[Reminder]] =
    val userIds = Arr(claimed.toList.map(_._1).distinct*)
    val cids = Arr(claimed.toList.map(_._2).distinct*)
    (
      s.runList(LookupRepo.users, userIds),
      s.runList(LookupRepo.channels, cids),
      s.runList(LookupRepo.members, cids),
      s.runList(LookupRepo.recentMessages, (cids, UnreadReminder.MessagesPerChannel)),
    ).mapN { (users, channels, members, messages) =>
      val userById = users.map(u => u.id -> u).toMap
      val channelByCid = channels.map(c => c.cid -> c).toMap
      val membersByCid = members.groupBy(_.cid)
      val messagesByCid = messages.groupBy(_.cid)
      claimed.toList.groupMap(_._1)(_._2).toList.flatMap { (userId, userCids) =>
        userById.get(userId).toList.flatMap { user =>
          userCids
            .flatMap(cid =>
              channelByCid.get(cid).map { channel =>
                ReminderChannel(
                  channel,
                  membersByCid.getOrElse(cid, Nil),
                  messagesByCid.getOrElse(cid, Nil),
                )
              }
            )
            .grouped(UnreadReminder.MaxChannelsPerEvent)
            .map(Reminder(user, _))
            .toList
        }
      }
    }
