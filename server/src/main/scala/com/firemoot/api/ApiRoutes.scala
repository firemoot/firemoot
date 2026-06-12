package com.firemoot.api

import java.util.UUID

import cats.effect.IO
import com.firemoot.auth.Principal
import com.firemoot.domain.Message
import com.firemoot.media.{MediaService, UploadError}
import com.firemoot.ratelimit.{RateGuard, RateLimitDecision}
import com.firemoot.service.{
  ChannelService,
  HydrationService,
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
import org.http4s.{HttpRoutes, Request}
import sttp.tapir.{extractFromRequest, EndpointInput}
import sttp.tapir.server.http4s.Http4sServerInterpreter

/**
 * The REST routes. Authentication is the [[com.firemoot.auth.ApiAuth]] middleware
 * (composed in [[com.firemoot.Application]]), which attaches a [[Principal]] read
 * here via `principalInput`. Server-key callers keep full trust (may act as any
 * user); end-user callers are authorised per operation against channel
 * membership and role, with identity fields forced to the token subject.
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
    hydration: HydrationService,
    rate: RateGuard = RateGuard.unlimited,
    media: Option[MediaService] = None,
):

  private def cid(channelType: String, id: String): String = s"$channelType:$id"

  private def notFound(what: String): Problem =
    Problem.of(404, "Not Found", Some(s"$what does not exist"))

  private def forbidden(detail: String): Problem = Problem.of(403, "Forbidden", Some(detail))

  private val unauthenticated: Problem =
    Problem.of(401, "Unauthorized", Some("authentication required"))

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

  /** The verified caller, read off the request attribute the auth middleware set. */
  private val principalInput: EndpointInput[Principal] =
    extractFromRequest(req =>
      req.underlying
        .asInstanceOf[Request[IO]]
        .attributes
        .lookup(Principal.attribute)
        .getOrElse(Principal.Denied)
    )

  /**
   * Runs `server` for a server-key caller, or - for an end user - checks channel
   * membership first and runs `member(userId, role)`. A non-member is 403.
   */
  private def authed[A](principal: Principal, c: String)(
      server: => IO[Either[Problem, A]],
      member: (String, String) => IO[Either[Problem, A]],
  ): IO[Either[Problem, A]] =
    principal match
      case Principal.Server(_) => server
      case Principal.User(uid, _) =>
        channels.memberRole(c, uid).flatMap {
          case Some(role) => member(uid, role)
          case None => IO.pure(Left(forbidden(s"not a member of '$c'")))
        }
      case Principal.Denied => IO.pure(Left(unauthenticated))

  /** Allows server-key callers only; end users get 403. */
  private def serverOnly[A](principal: Principal)(body: => IO[Either[Problem, A]])
      : IO[Either[Problem, A]] =
    principal match
      case Principal.Server(_) => body
      case _ => IO.pure(Left(forbidden("this operation requires a server API key")))

  // --- operation bodies (shared by the server-key and end-user paths) ---

  private def doSend(c: String, req: SendMessageRequest): IO[Either[Problem, Message]] =
    val messageType = req.`type`.getOrElse("regular")
    if !validMessageTypes(messageType) then
      IO.pure(Left(Problem.of(400, "Bad Request", Some(s"invalid message type '$messageType'"))))
    else
      sendAllowed(req.userId).flatMap {
        case Some(problem) => IO.pure(Left(problem))
        case None =>
          messages
            .send(
              cid = c,
              userId = req.userId,
              text = req.text,
              custom = req.custom.getOrElse(Json.obj()),
              attachments = req.attachments.getOrElse(Json.arr()),
              parentMessageId = req.parentMessageId,
              messageType = messageType,
            )
            .map {
              case Right(message) => Right(message)
              case Left(SendError.ChannelNotFound) => Left(notFound(s"channel '$c'"))
              case Left(SendError.ChannelFrozen) =>
                Left(Problem.of(409, "Conflict", Some(s"channel '$c' is frozen")))
            }
      }

  private def doEdit(
      c: String,
      messageId: UUID,
      req: EditMessageRequest,
  ): IO[Either[Problem, Message]] =
    messages.edit(
      c,
      messageId,
      req.text,
      req.custom,
    ).map(_.toRight(notFound(s"message '$messageId'")))

  private def doDelete(c: String, messageId: UUID): IO[Either[Problem, Unit]] =
    messages.delete(c, messageId).map {
      case true => Right(())
      case false => Left(notFound(s"message '$messageId'"))
    }

  private def doAddReaction(
      c: String,
      messageId: UUID,
      req: AddReactionRequest,
  ): IO[Either[Problem, ReactionSummary]] =
    reactions.add(c, messageId, req.userId, req.`type`).map {
      case Some(counts) => Right(ReactionSummary(messageId, counts))
      case None => Left(notFound(s"message '$messageId'"))
    }

  private def doRemoveReaction(
      c: String,
      messageId: UUID,
      user: String,
      reactionType: String,
  ): IO[Either[Problem, ReactionSummary]] =
    reactions.remove(c, messageId, user, reactionType).map {
      case Some(counts) => Right(ReactionSummary(messageId, counts))
      case None => Left(notFound(s"message '$messageId'"))
    }

  private def doMarkRead(c: String, req: MarkReadRequest): IO[Either[Problem, ReadStateResponse]] =
    reads.markRead(c, req.userId, req.seq).map {
      case Some(state) =>
        Right(ReadStateResponse(state.lastReadSeq, state.unreadCount, state.totalUnread))
      case None => Left(notFound(s"channel '$c' or membership"))
    }

  private def doFlag(
      c: String,
      messageId: UUID,
      req: FlagMessageRequest,
  ): IO[Either[Problem, Flag]] =
    moderation.flag(c, messageId, req.userId, req.reason).map {
      case Some(flag) => Right(flag)
      case None => Left(notFound(s"message '$messageId'"))
    }

  /** A single channel hydrated (members, latest message, caller read state). */
  private def doGetChannelState(
      c: String,
      caller: Option[String],
  ): IO[Either[Problem, ChannelState]] =
    channels.get(c).flatMap {
      case None => IO.pure(Left(notFound(s"channel '$c'")))
      case Some(channel) =>
        hydration.hydrate(List(channel), caller).map(states => Right(states.head))
    }

  private def doUpload(req: CreateUploadRequest): IO[Either[Problem, UploadTicket]] =
    media match
      case None =>
        IO.pure(Left(Problem.of(501, "Not Implemented", Some("media uploads are not configured"))))
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

  /** Author or a channel moderator/owner may edit/delete a message. */
  private def asAuthorOrModerator[A](c: String, messageId: UUID, uid: String, role: String)(
      body: => IO[Either[Problem, A]]
  ): IO[Either[Problem, A]] =
    messages.authorInChannel(c, messageId).flatMap {
      case None => IO.pure(Left(notFound(s"message '$messageId'")))
      case Some(author) =>
        if author.contains(uid) || role == "owner" || role == "moderator" then body
        else IO.pure(Left(forbidden("you can only modify your own messages")))
    }

  // --- server-key-only endpoints ---

  private val upsertUserServer =
    ApiEndpoints.upsertUser.in(principalInput).serverLogic { case (req, principal) =>
      serverOnly(principal) {
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
    }

  private val deleteUserServer =
    ApiEndpoints.deleteUser.in(principalInput).serverLogic { case (id, principal) =>
      serverOnly(principal) {
        users.delete(id).map {
          case true => Right(())
          case false => Left(notFound(s"user '$id'"))
        }
      }
    }

  private val createChannelServer =
    ApiEndpoints.createChannel.in(principalInput).serverLogic { case (req, principal) =>
      serverOnly(principal) {
        channels
          .create(req.`type`, req.id, req.createdBy, req.custom.getOrElse(Json.obj()))
          .map(Right(_))
      }
    }

  private val updateChannelServer =
    ApiEndpoints.updateChannel.in(principalInput).serverLogic {
      case (channelType, id, req, principal) =>
        serverOnly(principal) {
          channels
            .update(cid(channelType, id), req.custom, req.frozen, req.archived)
            .map(_.toRight(notFound(s"channel '${cid(channelType, id)}'")))
        }
    }

  private val deleteChannelServer =
    ApiEndpoints.deleteChannel.in(principalInput).serverLogic { case (channelType, id, principal) =>
      serverOnly(principal) {
        channels.softDelete(cid(channelType, id)).map {
          case true => Right(())
          case false => Left(notFound(s"channel '${cid(channelType, id)}'"))
        }
      }
    }

  private val addMemberServer =
    ApiEndpoints.addMember.in(principalInput).serverLogic {
      case (channelType, id, req, principal) =>
        serverOnly(principal) {
          val role = req.role.getOrElse("member")
          if !validRoles(role) then
            IO.pure(Left(Problem.of(400, "Bad Request", Some(s"invalid role '$role'"))))
          else
            channels.addMember(cid(channelType, id), req.userId, role).map {
              case true => Right(())
              case false => Left(notFound(s"channel '${cid(channelType, id)}'"))
            }
        }
    }

  private val removeMemberServer =
    ApiEndpoints.removeMember.in(principalInput).serverLogic {
      case (channelType, id, userId, principal) =>
        serverOnly(principal) {
          channels.removeMember(cid(channelType, id), userId).map {
            case true => Right(())
            case false => Left(notFound(s"member '$userId' of channel '${cid(channelType, id)}'"))
          }
        }
    }

  private val createWebhookServer =
    ApiEndpoints.createWebhook.in(principalInput).serverLogic { case (req, principal) =>
      serverOnly(principal)(webhooks.register(req).map(Right(_)))
    }

  private val listWebhooksServer =
    ApiEndpoints.listWebhooks.in(principalInput).serverLogic { principal =>
      serverOnly(principal)(webhooks.list.map(Right(_)))
    }

  private val deleteWebhookServer =
    ApiEndpoints.deleteWebhook.in(principalInput).serverLogic { case (id, principal) =>
      serverOnly(principal) {
        webhooks.delete(id).map {
          case true => Right(())
          case false => Left(notFound(s"webhook '$id'"))
        }
      }
    }

  private val listFlagsServer =
    ApiEndpoints.listFlags.in(principalInput).serverLogic { case (status, principal) =>
      serverOnly(principal)(moderation.listFlags(status.getOrElse("open")).map(Right(_)))
    }

  // --- dual (server-key or authorised end-user) endpoints ---

  private val getChannelServer =
    ApiEndpoints.getChannel.in(principalInput).serverLogic { case (channelType, id, principal) =>
      val c = cid(channelType, id)
      authed(principal, c)(
        doGetChannelState(c, None),
        (uid, _) => doGetChannelState(c, Some(uid)),
      )
    }

  private val sendMessageServer =
    ApiEndpoints.sendMessage.in(principalInput).serverLogic {
      case (channelType, id, req, principal) =>
        val c = cid(channelType, id)
        authed(principal, c)(doSend(c, req), (uid, _) => doSend(c, req.copy(userId = Some(uid))))
    }

  private val editMessageServer =
    ApiEndpoints.editMessage.in(principalInput).serverLogic {
      case (channelType, id, messageId, req, principal) =>
        val c = cid(channelType, id)
        authed(principal, c)(
          doEdit(c, messageId, req),
          (uid, role) => asAuthorOrModerator(c, messageId, uid, role)(doEdit(c, messageId, req)),
        )
    }

  private val deleteMessageServer =
    ApiEndpoints.deleteMessage.in(principalInput).serverLogic {
      case (channelType, id, messageId, principal) =>
        val c = cid(channelType, id)
        authed(principal, c)(
          doDelete(c, messageId),
          (uid, role) => asAuthorOrModerator(c, messageId, uid, role)(doDelete(c, messageId)),
        )
    }

  private val addReactionServer =
    ApiEndpoints.addReaction.in(principalInput).serverLogic {
      case (channelType, id, messageId, req, principal) =>
        val c = cid(channelType, id)
        authed(principal, c)(
          doAddReaction(c, messageId, req),
          (uid, _) => doAddReaction(c, messageId, req.copy(userId = uid)),
        )
    }

  private val removeReactionServer =
    ApiEndpoints.removeReaction.in(principalInput).serverLogic {
      case (channelType, id, messageId, reactionType, user, principal) =>
        val c = cid(channelType, id)
        authed(principal, c)(
          doRemoveReaction(c, messageId, user, reactionType),
          (uid, _) =>
            if user == uid then doRemoveReaction(c, messageId, user, reactionType)
            else IO.pure(Left(forbidden("you can only remove your own reactions"))),
        )
    }

  private val markReadServer =
    ApiEndpoints.markRead.in(principalInput).serverLogic { case (channelType, id, req, principal) =>
      val c = cid(channelType, id)
      authed(principal, c)(doMarkRead(c, req), (uid, _) => doMarkRead(c, req.copy(userId = uid)))
    }

  private val flagMessageServer =
    ApiEndpoints.flagMessage.in(principalInput).serverLogic {
      case (channelType, id, messageId, req, principal) =>
        val c = cid(channelType, id)
        authed(principal, c)(
          doFlag(c, messageId, req),
          (uid, _) => doFlag(c, messageId, req.copy(userId = uid)),
        )
    }

  private val listMessagesServer =
    ApiEndpoints.listMessages.in(principalInput).serverLogic {
      case (channelType, id, beforeSeq, limit, principal) =>
        val c = cid(channelType, id)
        def history = queries.messageHistory(c, beforeSeq, limit).map(Right(_))
        authed(principal, c)(history, (_, _) => history)
    }

  private val queryChannelsServer =
    ApiEndpoints.queryChannels.in(principalInput).serverLogic { case (query, principal) =>
      def page(q: ChannelQuery, caller: Option[String]): IO[Either[Problem, ChannelStatePage]] =
        queries.channels(q).flatMap { p =>
          hydration.hydrate(p.channels, caller).map(states =>
            Right(ChannelStatePage(states, p.nextCursor))
          )
        }
      principal match
        case Principal.Server(_) => page(query, None)
        case Principal.User(uid, _) => page(query.copy(members = Some(List(uid))), Some(uid))
        case Principal.Denied => IO.pure(Left(unauthenticated))
    }

  private val searchMessagesServer =
    ApiEndpoints.searchMessages.in(principalInput).serverLogic { case (req, principal) =>
      principal match
        case Principal.Server(_) => queries.search(req).map(Right(_))
        case Principal.User(uid, _) => queries.searchAsMember(req, uid).map(Right(_))
        case Principal.Denied => IO.pure(Left(unauthenticated))
    }

  private val createUploadServer =
    ApiEndpoints.createUpload.in(principalInput).serverLogic { case (req, principal) =>
      principal match
        case Principal.Server(_) => doUpload(req)
        case Principal.User(uid, _) => doUpload(req.copy(userId = Some(uid)))
        case Principal.Denied => IO.pure(Left(unauthenticated))
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
