package com.firemoot.api

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import sttp.tapir.Schema

/**
 * RFC 9457 problem detail. (Media type is tightened to `application/problem+json`
 * in M1.1 when error paths become first-class; the shape is RFC-correct now.)
 */
final case class Problem(`type`: String, title: String, status: Int, detail: Option[String])

object Problem:
  given Codec[Problem] = deriveCodec
  given Schema[Problem] = Schema.derived

  def of(status: Int, title: String, detail: Option[String] = None): Problem =
    Problem("about:blank", title, status, detail)
