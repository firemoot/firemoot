package com.firemoot.api

import cats.effect.IO
import com.firemoot.config.ServerConfig
import com.firemoot.service.{ChannelService, MessageService, UserService}
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import sttp.apispec.openapi.circe.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.http4s.Http4sServerInterpreter

final class ApiRoutes(
    cfg: ServerConfig,
    users: UserService,
    channels: ChannelService,
    messages: MessageService,
):

  private def authenticate(key: String): IO[Either[Problem, ServerPrincipal]] =
    IO.pure(
      if key == cfg.apiKeyId then Right(ServerPrincipal(key))
      else Left(Problem.of(401, "Unauthorized", Some("Invalid or missing API key")))
    )

  private val upsertUserServer =
    ApiEndpoints.upsertUser.serverSecurityLogic(authenticate).serverLogic { _ => req =>
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

  private val createChannelServer =
    ApiEndpoints.createChannel.serverSecurityLogic(authenticate).serverLogic { _ => req =>
      channels
        .create(req.`type`, req.id, req.createdBy, req.custom.getOrElse(Json.obj()))
        .map(Right(_))
    }

  private val sendMessageServer =
    ApiEndpoints.sendMessage.serverSecurityLogic(authenticate).serverLogic { _ =>
      { case (channelType, id, req) =>
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
    }

  val routes: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List(upsertUserServer, createChannelServer, sendMessageServer)
    )

  val openApiJson: String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(ApiEndpoints.all, "Firemoot", "0.1.0")
      .asJson
      .deepDropNullValues
      .noSpaces
