package com.firemoot.db

import com.firemoot.domain.{Channel, Message, User}
import io.circe.Json
import skunk.*
import skunk.circe.codec.all.jsonb
import skunk.codec.all.*

/**
 * Skunk row codecs for the domain models. Field order must match both the
 * case class and the `*Columns` fragments below.
 */
object Codecs:

  val user: Codec[User] =
    (text *: text.opt *: text.opt *: text *: jsonb[Json] *:
      timestamptz *: timestamptz *: timestamptz.opt *: timestamptz.opt).to[User]

  val channel: Codec[Channel] =
    (text *: text *: text *: text.opt *: jsonb[Json] *: bool *: bool *: int8 *:
      timestamptz.opt *: timestamptz *: timestamptz *: timestamptz.opt).to[Channel]

  val message: Codec[Message] =
    (text *: text *: int8 *: text.opt *: text *: text.opt *: jsonb[Json] *: jsonb[Json] *:
      text.opt *: int4 *: timestamptz *: timestamptz *: timestamptz.opt).to[Message]
