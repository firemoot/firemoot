package com.firemoot.api

/**
 * The authenticated server-SDK caller. M0.4 validates the key id only; HMAC
 * request signing lands in M1.1.
 */
final case class ServerPrincipal(keyId: String)
