package com.firemoot.media

import java.util.UUID

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.firemoot.config.MediaConfig
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.UploadRepo
import com.firemoot.service.MessageService
import fs2.Stream
import org.typelevel.log4cats.Logger
import skunk.Session

/**
 * In-process thumbnail worker (SPEC.md §7, M2.3). Each cycle claims `stored`
 * image uploads, downloads the original, downscales it to `maxEdge`px, uploads
 * the thumbnail next to the original, marks the upload `thumbnailed`, and patches
 * `thumbUrl` onto any message attachment that references it (re-emitting
 * `message.updated`). Failures are logged and the upload is left for a later
 * pass rather than crashing the worker.
 */
final class ThumbnailWorker(
    cfg: MediaConfig,
    pool: Resource[IO, Session[IO]],
    store: ObjectStore,
    messages: MessageService,
    logger: Logger[IO],
    pollInterval: FiniteDuration = 5.seconds,
    batchSize: Int = 8,
    maxEdge: Int = 512,
):

  def stream: Stream[IO, Nothing] =
    Stream.awakeEvery[IO](pollInterval).evalMap(_ => runOnce.attempt.void).drain

  /** One claim-and-thumbnail cycle. Exposed so tests can drive it deterministically. */
  def runOnce: IO[Unit] =
    pool.use(_.runList(UploadRepo.claimStoredImages, batchSize)).flatMap(_.traverse_(process))

  private def process(claim: (UUID, String, String)): IO[Unit] =
    val (id, objectKey, _) = claim
    val thumbKey = s"$objectKey.thumb.png"
    val work =
      for
        original <- store.get(objectKey)
        thumbnail <- Thumbnailer.resize(original, maxEdge)
        _ <- store.put(thumbKey, thumbnail, "image/png")
        _ <- pool.use(_.run(UploadRepo.markThumbnailed, (thumbKey, id)))
        _ <- messages.attachThumbnail(cfg.objectUrl(objectKey), cfg.objectUrl(thumbKey))
      yield ()
    work.handleErrorWith(e => logger.warn(e)(s"thumbnailing upload $id ($objectKey) failed"))
