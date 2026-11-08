package com.firemoot.domain

import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema

/**
 * The body shape a webhook endpoint receives. `Stream` is getstream.io's own
 * webhook payload; `Firemoot` is the original `{ type, cid, seq, data }`
 * envelope, deprecated and kept only so existing endpoints can migrate.
 */
enum WebhookFormat(val wire: String):
  case Stream extends WebhookFormat("stream")
  case Firemoot extends WebhookFormat("firemoot")

object WebhookFormat:

  def fromWire(value: String): Either[String, WebhookFormat] =
    values.find(_.wire == value).toRight(
      s"unknown webhook format '$value' (expected ${values.map(_.wire).mkString(" or ")})"
    )

  given Encoder[WebhookFormat] = Encoder.encodeString.contramap(_.wire)
  given Decoder[WebhookFormat] = Decoder.decodeString.emap(fromWire)
  given Schema[WebhookFormat] = Schema.derivedEnumeration[WebhookFormat](encode = Some(_.wire))
