package com.firemoot.media

import cats.effect.{IO, Resource}
import com.firemoot.config.MediaConfig
import com.firemoot.db.SessionSyntax.*
import com.firemoot.db.UploadRepo
import com.firemoot.domain.UuidV7
import skunk.Session

/**
 * Uploads whose bytes come through the server rather than a presigned PUT: what
 * getstream.io's API takes (multipart `POST .../image` and `.../file`). The same
 * MIME allowlist and size caps apply, and a stored image is picked up by the
 * thumbnail worker exactly like a presigned one.
 */
final class DirectUploads(cfg: MediaConfig, pool: Resource[IO, Session[IO]], objects: ObjectStore):

  /** `(allowed MIME types, image byte cap, file byte cap)`, for Stream's app settings. */
  def limits: (Set[String], Long, Long) = (cfg.allowedMime, cfg.maxImageBytes, cfg.maxFileBytes)

  /** The object's public URL. */
  def store(userId: Option[String], filename: String, mime: String, bytes: Array[Byte])
      : IO[Either[UploadError, String]] =
    val limit = if mime.startsWith("image/") then cfg.maxImageBytes else cfg.maxFileBytes
    if !cfg.allowedMime.contains(mime) then IO.pure(Left(UploadError.UnsupportedType(mime)))
    else if bytes.length.toLong > limit then IO.pure(Left(UploadError.TooLarge(limit)))
    else
      for
        id <- UuidV7.next
        safe = filename.replaceAll("[^A-Za-z0-9._-]", "_")
        key = s"uploads/$id/${if safe.isEmpty then "file" else safe}"
        _ <- objects.put(key, bytes, mime)
        _ <- pool.use(_.run(UploadRepo.insert, (id, userId, key, mime, bytes.length.toLong)))
        _ <- pool.use(_.runOption(UploadRepo.markStored, id))
      yield Right(cfg.objectUrl(key))
