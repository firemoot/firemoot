package com.firemoot.auth

import cats.effect.SyncIO
import org.typelevel.vault.Key

/**
 * The authenticated caller of a REST request (SPEC.md §5). A `Server` principal
 * holds a verified API key (the customer backend - full trust, may act as any
 * user). A `User` principal holds a verified end-user JWT subject and is
 * authorised per operation against channel membership and role. `Denied` is the
 * defensive fallback when no principal was attached (an invariant violation - the
 * auth middleware always sets one or 401s first).
 */
enum Principal:
  case Server(keyId: String)
  case User(userId: String, role: Option[String])
  case Denied

object Principal:

  /**
   * The request attribute the auth middleware writes and the routes read. A
   * single shared `vault.Key` (created once) bridges the http4s middleware and
   * tapir's `extractFromRequest`, which reads it off the underlying request.
   */
  val attribute: Key[Principal] = Key.newKey[SyncIO, Principal].unsafeRunSync()
