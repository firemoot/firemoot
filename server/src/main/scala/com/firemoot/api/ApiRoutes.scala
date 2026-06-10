package com.firemoot.api

import cats.effect.IO
import com.firemoot.service.{ChannelService, MessageService, SendError, UserService}
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
):

  private def cid(channelType: String, id: String): String = s"$channelType:$id"

  private def notFound(what: String): Problem =
    Problem.of(404, "Not Found", Some(s"$what does not exist"))

  private val validRoles = Set("owner", "moderator", "member")

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
      messages
        .send(
          cid = cid(channelType, id),
          userId = req.userId,
          text = req.text,
          custom = req.custom.getOrElse(Json.obj()),
          attachments = req.attachments.getOrElse(Json.arr()),
          parentMessageId = req.parentMessageId,
        )
        .map {
          case Right(message) => Right(message)
          case Left(SendError.ChannelNotFound) =>
            Left(notFound(s"channel '${cid(channelType, id)}'"))
          case Left(SendError.ChannelFrozen) =>
            Left(Problem.of(409, "Conflict", Some(s"channel '${cid(channelType, id)}' is frozen")))
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
      )
    )

  val openApiJson: String = OpenApiDocs.compact
