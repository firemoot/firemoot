package com.firemoot.ws

import pdi.jwt.{JwtCirce, JwtOptions}

/**
 * Extracts the `sub` (user id) from a connection JWT. M0 decodes without
 * verifying the signature; constant-time HMAC verification and `exp` checks
 * land in M1.1.
 */
object TokenAuth:

  def subject(token: String): Option[String] =
    JwtCirce
      .decodeJson(token, JwtOptions(signature = false, expiration = false))
      .toOption
      .flatMap(_.hcursor.get[String]("sub").toOption)
