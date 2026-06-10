package com.firemoot.auth

import cats.effect.IO
import com.firemoot.config.ServerConfig

/**
 * Resolves the shared secret for a server API key id. v1 is backed by the
 * install-time config key; DB-backed keys with rotation arrive with the admin UI
 * (M3.5).
 */
trait ApiKeys:
  def secretFor(keyId: String): IO[Option[String]]

object ApiKeys:

  def fromConfig(cfg: ServerConfig): ApiKeys =
    (keyId: String) => IO.pure(Option.when(keyId == cfg.apiKeyId)(cfg.apiSecret.value))
