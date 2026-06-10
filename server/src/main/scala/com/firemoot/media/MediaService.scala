package com.firemoot.media

import java.net.URI
import java.time.Duration

import cats.effect.{IO, Resource}
import com.firemoot.api.{CreateUploadRequest, UploadTicket}
import com.firemoot.config.MediaConfig
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.UploadRepo
import com.firemoot.domain.UuidV7
import skunk.Session
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

enum UploadError:
  case UnsupportedType(mime: String)
  case TooLarge(maxBytes: Long)

/**
 * Presigns direct-to-S3 uploads (SPEC.md §7, M2.1). The MIME allowlist and
 * size policy are enforced here; a row tracks the upload's lifecycle. The
 * presigner only does local signing - no network, no vendor admin APIs - so any
 * S3-compatible store works via the path-style endpoint.
 */
final class MediaService(
    cfg: MediaConfig,
    pool: Resource[IO, Session[IO]],
    presigner: S3Presigner,
):

  def presignUpload(req: CreateUploadRequest): IO[Either[UploadError, UploadTicket]] =
    validate(req) match
      case Left(error) => IO.pure(Left(error))
      case Right(()) =>
        for
          id <- UuidV7.next
          key = objectKey(id, req.filename)
          _ <- pool.use(_.run(UploadRepo.insert, (id, req.userId, key, req.mime, req.sizeBytes)))
          url <- presignPut(key, req.mime)
        yield Right(UploadTicket(id, url, objectUrl(key), cfg.presignExpiry.toSeconds))

  private def validate(req: CreateUploadRequest): Either[UploadError, Unit] =
    if !cfg.allowedMime.contains(req.mime) then Left(UploadError.UnsupportedType(req.mime))
    else
      val limit = if req.mime.startsWith("image/") then cfg.maxImageBytes else cfg.maxFileBytes
      if req.sizeBytes > limit then Left(UploadError.TooLarge(limit)) else Right(())

  private def objectKey(id: java.util.UUID, filename: String): String =
    val safe = filename.replaceAll("[^A-Za-z0-9._-]", "_")
    s"uploads/$id/${if safe.isEmpty then "file" else safe}"

  private def objectUrl(key: String): String =
    cfg.publicBaseUrl match
      case Some(base) => s"${base.stripSuffix("/")}/$key"
      case None => s"${cfg.endpoint.stripSuffix("/")}/${cfg.bucket}/$key"

  private def presignPut(key: String, mime: String): IO[String] =
    IO.blocking {
      val put = PutObjectRequest.builder().bucket(cfg.bucket).key(key).contentType(mime).build()
      val request = PutObjectPresignRequest
        .builder()
        .signatureDuration(Duration.ofSeconds(cfg.presignExpiry.toSeconds))
        .putObjectRequest(put)
        .build()
      presigner.presignPutObject(request).url().toString
    }

object MediaService:

  /** Builds a path-style S3 presigner for any S3-compatible endpoint. */
  def presigner(cfg: MediaConfig): Resource[IO, S3Presigner] =
    Resource.fromAutoCloseable(IO.blocking {
      S3Presigner
        .builder()
        .endpointOverride(URI.create(cfg.endpoint))
        .region(Region.of(cfg.region))
        .credentialsProvider(
          StaticCredentialsProvider.create(
            AwsBasicCredentials.create(cfg.accessKey, cfg.secretKey.value)
          )
        )
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()
    })

  def resource(
      cfg: MediaConfig,
      pool: Resource[IO, Session[IO]],
  ): Resource[IO, MediaService] =
    presigner(cfg).map(new MediaService(cfg, pool, _))
