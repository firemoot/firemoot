package com.firemoot.service

import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

import cats.effect.{IO, Resource}
import com.firemoot.api.{CreateWebhookRequest, WebhookCreated, WebhookEndpoint}
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.WebhookRepo
import com.firemoot.domain.{Event, UuidV7}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import skunk.Session

/** A dead-lettered webhook delivery, surfaced in the admin dashboard (M3.5). */
final case class DeadLetter(
    id: UUID,
    endpointId: String,
    eventType: String,
    attempts: Int,
    lastError: String,
    createdAt: OffsetDateTime,
)

object DeadLetter:
  given Codec[DeadLetter] = deriveCodec

/**
 * Webhook endpoint registry and event enqueueing (SPEC.md §3, M1.10). Persisted
 * channel events are fanned out (one delivery row per enabled endpoint) into the
 * queue that [[com.firemoot.webhook.WebhookDispatcher]] drains. The signing
 * secret is generated on registration and returned only once.
 */
final class WebhookService(pool: Resource[IO, Session[IO]]):

  def register(req: CreateWebhookRequest): IO[WebhookCreated] =
    for
      id <- UuidV7.next.map(_.toString)
      secret <- req.secret.fold(WebhookService.randomSecret)(IO.pure)
      enabled = req.enabled.getOrElse(true)
      _ <- pool.use(_.run(WebhookRepo.insertEndpoint, (id, req.url, secret, enabled)))
    yield WebhookCreated(id, req.url, secret, enabled)

  def list: IO[List[WebhookEndpoint]] =
    pool.use(_.execute(WebhookRepo.listEndpoints)).map(_.map { (id, url, enabled, createdAt) =>
      WebhookEndpoint(id, url, enabled, createdAt)
    })

  def delete(id: String): IO[Boolean] =
    pool.use(_.runOption(WebhookRepo.deleteEndpoint, id)).map(_.isDefined)

  /** Fans the event out to a pending delivery per enabled endpoint. */
  def enqueue(event: Event): IO[Unit] =
    pool.use(_.run(WebhookRepo.enqueue, event.wire))

  def deadLetters: IO[List[DeadLetter]] =
    pool.use(_.execute(WebhookRepo.deadLetters)).map(_.map {
      (id, endpointId, eventType, attempts, lastError, createdAt) =>
        DeadLetter(id, endpointId, eventType, attempts, lastError, createdAt)
    })

  /** Requeues a dead delivery; false if it was not a dead letter. */
  def replay(id: UUID): IO[Boolean] =
    pool.use(_.runOption(WebhookRepo.replayDead, id)).map(_.isDefined)

object WebhookService:

  /** Only persisted channel-broadcast events (not ephemeral or user-directed). */
  def isDeliverable(event: Event): Boolean = event.target.isEmpty && event.seq > 0L

  private def randomSecret: IO[String] =
    IO.delay {
      val bytes = new Array[Byte](32)
      SecureRandom().nextBytes(bytes)
      bytes.map("%02x".format(_)).mkString
    }
