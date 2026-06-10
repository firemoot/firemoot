package com.firemoot.api

import cats.effect.IO
import com.firemoot.service.{ChannelService, MessageService, UserService}
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
        case false => Left(Problem.of(404, "Not Found", Some(s"user '$id' does not exist")))
      }
    }

  private val createChannelServer =
    ApiEndpoints.createChannel.serverLogic { req =>
      channels
        .create(req.`type`, req.id, req.createdBy, req.custom.getOrElse(Json.obj()))
        .map(Right(_))
    }

  private val sendMessageServer =
    ApiEndpoints.sendMessage.serverLogic { case (channelType, id, req) =>
      messages
        .send(
          cid = s"$channelType:$id",
          userId = req.userId,
          text = req.text,
          custom = req.custom.getOrElse(Json.obj()),
          attachments = req.attachments.getOrElse(Json.arr()),
          parentMessageId = req.parentMessageId,
        )
        .map(Right(_))
    }

  val routes: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List(upsertUserServer, deleteUserServer, createChannelServer, sendMessageServer)
    )

  val openApiJson: String = OpenApiDocs.compact
