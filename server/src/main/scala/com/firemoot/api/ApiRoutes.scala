package com.firemoot.api

import cats.effect.IO
import com.firemoot.media.{MediaService, UploadError}
import com.firemoot.ratelimit.{RateGuard, RateLimitDecision}
import com.firemoot.service.{
  ChannelService,
  MessageService,
  ModerationService,
  QueryService,
  ReactionService,
  ReadService,
  SendError,
  UserService,
  WebhookService,
}
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.tapir.server.http4s.Http4sServerInterpreter

/**
 * The unauthenticated REST routes; the [[com.firemoot.auth.ServerHmacAuth]]
 * middleware wraps them in [[com.firemoot.Application]].
 */
final class ApiRoutes(
    users: UserService,
    channels: ChannelService,
    messages: MessageService,
    reactions: ReactionService,
    reads: ReadService,
    queries: QueryService,
    webhooks: WebhookService,
    moderation: ModerationService,
    rate: RateGuard = RateGuard.unlimited,
    media: Option[MediaService] = None,
):

  private def cid(channelType: String, id: String): String = s"$channelType:$id"

  private def notFound(what: String): Problem =
    Problem.of(404, "Not Found", Some(s"$what does not exist"))

  private def tooManyRequests(retryAfterSeconds: Long): Problem =
    Problem.of(
      429,
      "Too Many Requests",
      Some(s"rate limit exceeded; retry after ${retryAfterSeconds}s"),
    )

  private def limited(decision: RateLimitDecision): Option[Problem] =
    decision match
      case RateLimitDecision.Allowed => None
      case RateLimitDecision.Retry(after) => Some(tooManyRequests(after.toSeconds))

  /** Per-user send budget. None = allowed; Some(problem) = 429. */
  private def sendAllowed(userId: Option[String]): IO[Option[Problem]] =
    userId match
      case None => IO.pure(None)
      case Some(user) => rate.send(user).map(limited)

  private def uploadAllowed(userId: Option[String]): IO[Option[Problem]] =
    userId match
      case None => IO.pure(None)
      case Some(user) => rate.upload(user).map(limited)

  private val validRoles = Set("owner", "moderator", "member")
  private val validMessageTypes = Set("regular", "system")

  private val upsertUserServer =
    ApiEndpoints.upsertUser.serverLogic { req =>
      users
        .upsert(
          req.id,
          req.name,
          req.image,
          req.role.getOrElse("user"),
          req.custom.getOrElse(Json.obj()),
        )
        .map(Right(_))
    }

  private val deleteUserServer =
    ApiEndpoints.deleteUser.serverLogic { id =>
      users.delete(id).map {
        case true => Right(())
        case false => Left(notFound(s"user '$id'"))
      }
    }

  private val createChannelServer =
    ApiEndpoints.createChannel.serverLogic { req =>
      channels
        .create(req.`type`, req.id, req.createdBy, req.custom.getOrElse(Json.obj()))
        .map(Right(_))
    }

  private val getChannelServer =
    ApiEndpoints.getChannel.serverLogic { case (channelType, id) =>
      channels.get(cid(
        channelType,
        id,
      )).map(_.toRight(notFound(s"channel '${cid(channelType, id)}'")))
    }

  private val updateChannelServer =
    ApiEndpoints.updateChannel.serverLogic { case (channelType, id, req) =>
      channels
        .update(cid(channelType, id), req.custom, req.frozen, req.archived)
        .map(_.toRight(notFound(s"channel '${cid(channelType, id)}'")))
    }

  private val deleteChannelServer =
    ApiEndpoints.deleteChannel.serverLogic { case (channelType, id) =>
      channels.softDelete(cid(channelType, id)).map {
        case true => Right(())
        case false => Left(notFound(s"channel '${cid(channelType, id)}'"))
      }
    }

  private val addMemberServer =
    ApiEndpoints.addMember.serverLogic { case (channelType, id, req) =>
      val role = req.role.getOrElse("member")
      if !validRoles(role) then
        IO.pure(Left(Problem.of(400, "Bad Request", Some(s"invalid role '$role'"))))
      else
        channels.addMember(cid(channelType, id), req.userId, role).map {
          case true => Right(())
          case false => Left(notFound(s"channel '${cid(channelType, id)}'"))
        }
    }

  private val removeMemberServer =
    ApiEndpoints.removeMember.serverLogic { case (channelType, id, userId) =>
      channels.removeMember(cid(channelType, id), userId).map {
        case true => Right(())
        case false => Left(notFound(s"member '$userId' of channel '${cid(channelType, id)}'"))
      }
    }

  private val sendMessageServer =
    ApiEndpoints.sendMessage.serverLogic { case (channelType, id, req) =>
      val messageType = req.`type`.getOrElse("regular")
      if !validMessageTypes(messageType) then
        IO.pure(Left(Problem.of(400, "Bad Request", Some(s"invalid message type '$messageType'"))))
      else
        sendAllowed(req.userId).flatMap {
          case Some(limited) => IO.pure(Left(limited))
          case None =>
            messages
              .send(
                cid = cid(channelType, id),
                userId = req.userId,
                text = req.text,
                custom = req.custom.getOrElse(Json.obj()),
                attachments = req.attachments.getOrElse(Json.arr()),
                parentMessageId = req.parentMessageId,
                messageType = messageType,
              )
              .map {
                case Right(message) => Right(message)
                case Left(SendError.ChannelNotFound) =>
                  Left(notFound(s"channel '${cid(channelType, id)}'"))
                case Left(SendError.ChannelFrozen) =>
                  Left(Problem.of(
                    409,
                    "Conflict",
                    Some(s"channel '${cid(channelType, id)}' is frozen"),
                  ))
              }
        }
    }

  private val editMessageServer =
    ApiEndpoints.editMessage.serverLogic { case (channelType, id, messageId, req) =>
      messages
        .edit(cid(channelType, id), messageId, req.text, req.custom)
        .map(_.toRight(notFound(s"message '$messageId'")))
    }

  private val deleteMessageServer =
    ApiEndpoints.deleteMessage.serverLogic { case (channelType, id, messageId) =>
      messages.delete(cid(channelType, id), messageId).map {
        case true => Right(())
        case false => Left(notFound(s"message '$messageId'"))
      }
    }

  private val addReactionServer =
    ApiEndpoints.addReaction.serverLogic { case (channelType, id, messageId, req) =>
      reactions.add(cid(channelType, id), messageId, req.userId, req.`type`).map {
        case Some(counts) => Right(ReactionSummary(messageId, counts))
        case None => Left(notFound(s"message '$messageId'"))
      }
    }

  private val removeReactionServer =
    ApiEndpoints.removeReaction.serverLogic {
      case (channelType, id, messageId, reactionType, user) =>
        reactions.remove(cid(channelType, id), messageId, user, reactionType).map {
          case Some(counts) => Right(ReactionSummary(messageId, counts))
          case None => Left(notFound(s"message '$messageId'"))
        }
    }

  private val markReadServer =
    ApiEndpoints.markRead.serverLogic { case (channelType, id, req) =>
      reads.markRead(cid(channelType, id), req.userId, req.seq).map {
        case Some(state) =>
          Right(ReadStateResponse(state.lastReadSeq, state.unreadCount, state.totalUnread))
        case None => Left(notFound(s"channel '${cid(channelType, id)}' or membership"))
      }
    }

  private val queryChannelsServer =
    ApiEndpoints.queryChannels.serverLogic(query => queries.channels(query).map(Right(_)))

  private val listMessagesServer =
    ApiEndpoints.listMessages.serverLogic { case (channelType, id, beforeSeq, limit) =>
      queries.messageHistory(cid(channelType, id), beforeSeq, limit).map(Right(_))
    }

  private val searchMessagesServer =
    ApiEndpoints.searchMessages.serverLogic(req => queries.search(req).map(Right(_)))

  private val createWebhookServer =
    ApiEndpoints.createWebhook.serverLogic(req => webhooks.register(req).map(Right(_)))

  private val listWebhooksServer =
    ApiEndpoints.listWebhooks.serverLogic(_ => webhooks.list.map(Right(_)))

  private val deleteWebhookServer =
    ApiEndpoints.deleteWebhook.serverLogic { id =>
      webhooks.delete(id).map {
        case true => Right(())
        case false => Left(notFound(s"webhook '$id'"))
      }
    }

  private val flagMessageServer =
    ApiEndpoints.flagMessage.serverLogic { case (channelType, id, messageId, req) =>
      moderation.flag(cid(channelType, id), messageId, req.userId, req.reason).map {
        case Some(flag) => Right(flag)
        case None => Left(notFound(s"message '$messageId'"))
      }
    }

  private val listFlagsServer =
    ApiEndpoints.listFlags.serverLogic { status =>
      moderation.listFlags(status.getOrElse("open")).map(Right(_))
    }

  private val createUploadServer =
    ApiEndpoints.createUpload.serverLogic { req =>
      media match
        case None =>
          IO.pure(Left(Problem.of(
            501,
            "Not Implemented",
            Some("media uploads are not configured"),
          )))
        case Some(svc) =>
          uploadAllowed(req.userId).flatMap {
            case Some(problem) => IO.pure(Left(problem))
            case None =>
              svc.presignUpload(req).map {
                case Right(ticket) => Right(ticket)
                case Left(UploadError.UnsupportedType(mime)) =>
                  Left(Problem.of(400, "Bad Request", Some(s"unsupported media type '$mime'")))
                case Left(UploadError.TooLarge(maxBytes)) =>
                  Left(Problem.of(
                    413,
                    "Payload Too Large",
                    Some(s"exceeds the $maxBytes byte limit"),
                  ))
              }
          }
    }

  val routes: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List(
        upsertUserServer,
        deleteUserServer,
        createChannelServer,
        getChannelServer,
        updateChannelServer,
        deleteChannelServer,
        addMemberServer,
        removeMemberServer,
        sendMessageServer,
        editMessageServer,
        deleteMessageServer,
        addReactionServer,
        removeReactionServer,
        markReadServer,
        queryChannelsServer,
        listMessagesServer,
        searchMessagesServer,
        createWebhookServer,
        listWebhooksServer,
        deleteWebhookServer,
        flagMessageServer,
        listFlagsServer,
        createUploadServer,
      )
    )

  val openApiJson: String = OpenApiDocs.compact
