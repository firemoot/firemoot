package com.firemoot.webhook

import java.time.ZoneOffset
import java.util.UUID

import scala.concurrent.duration.*

import cats.effect.syntax.all.*
import cats.effect.{IO, Resource}
import com.firemoot.auth.HmacSigner
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.WebhookRepo
import fs2.Stream
import io.circe.Json
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{MediaType, Method, Request, Uri}
import skunk.Session

/**
 * The webhook delivery worker (SPEC.md §3, M1.10). Each cycle reaps abandoned
 * claims, claims a batch of due deliveries `for update skip locked`, and POSTs
 * each to its endpoint with `X-Firemoot-Signature: sha256=HMAC(secret, body)`.
 * A 2xx marks the delivery delivered; anything else (including a timeout)
 * schedules the next retry on the backoff, and once the retries are exhausted
 * the delivery becomes a dead letter.
 *
 * Every delivery also carries getstream.io's header names for the same values
 * (`X-Signature`, `X-Webhook-Id`, `X-Webhook-Attempt`, `X-Api-Key`) so a handler
 * written against Stream keeps working unchanged. `X-Signature` is the bare
 * lowercase hex digest Stream sends - no `sha256=` prefix.
 */
final class WebhookDispatcher(
    pool: Resource[IO, Session[IO]],
    client: Client[IO],
    cfg: WebhookConfig,
    apiKeyId: String,
):

  def stream: Stream[IO, Nothing] =
    Stream.awakeEvery[IO](cfg.pollInterval).evalMap(_ => pollOnce.attempt.void).drain

  /** One reap-claim-deliver cycle. Exposed so tests can drive it deterministically. */
  def pollOnce: IO[Unit] =
    for
      deadline <- IO.realTimeInstant.map(
        _.plusMillis(cfg.visibilityTimeout.toMillis).atOffset(ZoneOffset.UTC)
      )
      _ <- pool.use(_.execute(WebhookRepo.reapStuck))
      claimed <- pool.use(_.runList(WebhookRepo.claimBatch, (deadline, cfg.batchSize)))
      _ <- claimed.parTraverseN(cfg.concurrency)(process).void
    yield ()

  private def process(claim: (UUID, String, Json, Int)): IO[Unit] =
    val (id, endpointId, event, attempts) = claim
    pool.use(_.runOption(WebhookRepo.endpointById, endpointId)).flatMap {
      case Some((url, secret, enabled)) if enabled => deliver(id, url, secret, event, attempts)
      case _ => fail(id, attempts, "endpoint removed or disabled")
    }

  private def deliver(
      id: UUID,
      url: String,
      secret: String,
      event: Json,
      attempts: Int,
  ): IO[Unit] =
    Uri.fromString(url) match
      case Left(_) => fail(id, attempts, s"invalid endpoint url: $url")
      case Right(uri) =>
        val body = event.noSpaces
        val digest = HmacSigner.sign(secret, body)
        val eventType = event.hcursor.get[String]("type").getOrElse("")
        val request = Request[IO](Method.POST, uri)
          .withEntity(body)
          .withContentType(`Content-Type`(MediaType.application.json))
          .putHeaders(
            "X-Firemoot-Signature" -> s"sha256=$digest",
            "X-Firemoot-Delivery" -> id.toString,
            "X-Firemoot-Event" -> eventType,
            "X-Signature" -> digest,
            "X-Webhook-Id" -> id.toString,
            "X-Webhook-Attempt" -> attempts.toString,
            "X-Api-Key" -> apiKeyId,
          )
        client
          .status(request)
          .timeout(cfg.requestTimeout)
          .attempt
          .flatMap {
            case Right(status) if status.isSuccess => pool.use(_.run(WebhookRepo.markDelivered, id))
            case Right(status) => fail(id, attempts, s"endpoint returned ${status.code}")
            case Left(err) => fail(id, attempts, Option(err.getMessage).getOrElse(err.toString))
          }

  private def fail(id: UUID, attempts: Int, error: String): IO[Unit] =
    if attempts > cfg.backoff.length then pool.use(_.run(WebhookRepo.markDead, (error, id)))
    else
      IO.realTimeInstant.flatMap { now =>
        val nextAt = now.plusMillis(cfg.backoff(attempts - 1).toMillis).atOffset(ZoneOffset.UTC)
        pool.use(_.run(WebhookRepo.reschedule, (nextAt, error, id)))
      }

/**
 * Worker tuning. `backoff` is the delay before each retry; once `attempts`
 * exceeds its length the delivery is dead-lettered (so v1 makes 1 + backoff.size
 * attempts in total).
 */
final case class WebhookConfig(
    pollInterval: FiniteDuration,
    visibilityTimeout: FiniteDuration,
    batchSize: Int,
    concurrency: Int,
    requestTimeout: FiniteDuration,
    backoff: List[FiniteDuration],
)

object WebhookConfig:
  val default: WebhookConfig = WebhookConfig(
    pollInterval = 1.second,
    visibilityTimeout = 30.seconds,
    batchSize = 32,
    concurrency = 8,
    requestTimeout = 5.seconds,
    backoff = List(1.minute, 5.minutes, 30.minutes, 2.hours),
  )
