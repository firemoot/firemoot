package com.firemoot.ws

import java.time.OffsetDateTime

import com.firemoot.domain.Event
import io.circe.syntax.*
import io.circe.{parser, Json}

/**
 * JSON wire frames for the WebSocket protocol (SPEC.md §5). REST shapes are in
 * the OpenAPI document; WS frames are defined here and mirrored by the client
 * SDK.
 */
object WsFrames:

  // Server -> client
  def hello(connectionId: String, serverTime: OffsetDateTime, me: Json): Json =
    Json.obj(
      "type" -> "hello".asJson,
      "connectionId" -> connectionId.asJson,
      "serverTime" -> serverTime.asJson,
      "me" -> me,
    )

  def event(e: Event): Json =
    Json.obj(
      "type" -> e.`type`.asJson,
      "cid" -> e.cid.asJson,
      "seq" -> e.seq.asJson,
      "data" -> e.data,
    )

  def pong(serverTime: OffsetDateTime): Json =
    Json.obj("type" -> "pong".asJson, "serverTime" -> serverTime.asJson)

  // Client -> server
  enum ClientFrame:
    case Subscribe(channels: Map[String, Long])
    case Ping
    case Unknown

  def parse(text: String): ClientFrame =
    parser
      .parse(text)
      .toOption
      .flatMap { json =>
        val cursor = json.hcursor
        cursor.get[String]("type").toOption.map {
          case "subscribe" =>
            ClientFrame.Subscribe(cursor.get[Map[String, Long]]("channels").getOrElse(Map.empty))
          case "ping" => ClientFrame.Ping
          case _ => ClientFrame.Unknown
        }
      }
      .getOrElse(ClientFrame.Unknown)
