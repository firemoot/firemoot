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
  def hello(connectionId: String, serverTime: OffsetDateTime, me: Json, totalUnread: Long): Json =
    Json.obj(
      "type" -> "hello".asJson,
      "connectionId" -> connectionId.asJson,
      "serverTime" -> serverTime.asJson,
      "me" -> me,
      "totalUnread" -> totalUnread.asJson,
    )

  def event(e: Event): Json = e.wire

  def pong(serverTime: OffsetDateTime): Json =
    Json.obj("type" -> "pong".asJson, "serverTime" -> serverTime.asJson)

  /**
   * The resume point predates retained events: the gap can't be replayed, so the
   * client must re-query the channel and re-subscribe from its current state.
   */
  def resyncRequired(cid: String): Json =
    Json.obj("type" -> "resync_required".asJson, "cid" -> cid.asJson)

  // Client -> server
  enum ClientFrame:
    case Subscribe(channels: Map[String, Long])
    case TypingStart(cid: String)
    case TypingStop(cid: String)
    case Ping
    case Unknown

  def parse(text: String): ClientFrame =
    parser
      .parse(text)
      .toOption
      .flatMap { json =>
        val cursor = json.hcursor
        def withCid(make: String => ClientFrame): ClientFrame =
          cursor.get[String]("cid").toOption.fold(ClientFrame.Unknown)(make)
        cursor.get[String]("type").toOption.map {
          case "subscribe" =>
            ClientFrame.Subscribe(cursor.get[Map[String, Long]]("channels").getOrElse(Map.empty))
          case "typing.start" => withCid(ClientFrame.TypingStart.apply)
          case "typing.stop" => withCid(ClientFrame.TypingStop.apply)
          case "ping" => ClientFrame.Ping
          case _ => ClientFrame.Unknown
        }
      }
      .getOrElse(ClientFrame.Unknown)
