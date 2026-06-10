package com.firemoot.media

import java.net.URI

import cats.effect.{IO, Resource}
import com.firemoot.config.MediaConfig
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

/**
 * The bytes-level S3 access the thumbnail worker needs (M2.3): download an
 * original and upload its thumbnail. Generic-S3 only, path-style, on the
 * lightweight url-connection HTTP client (no Netty).
 */
trait ObjectStore:
  def get(key: String): IO[Array[Byte]]
  def put(key: String, bytes: Array[Byte], contentType: String): IO[Unit]

object ObjectStore:

  def s3(cfg: MediaConfig): Resource[IO, ObjectStore] =
    Resource
      .fromAutoCloseable(IO.blocking {
        S3Client
          .builder()
          .endpointOverride(URI.create(cfg.endpoint))
          .region(Region.of(cfg.region))
          .credentialsProvider(
            StaticCredentialsProvider.create(
              AwsBasicCredentials.create(cfg.accessKey, cfg.secretKey.value)
            )
          )
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
          .httpClient(UrlConnectionHttpClient.create())
          .build()
      })
      .map { client =>
        new ObjectStore:
          def get(key: String): IO[Array[Byte]] =
            IO.blocking {
              client
                .getObjectAsBytes(GetObjectRequest.builder().bucket(cfg.bucket).key(key).build())
                .asByteArray()
            }

          def put(key: String, bytes: Array[Byte], contentType: String): IO[Unit] =
            IO.blocking {
              client.putObject(
                PutObjectRequest.builder().bucket(
                  cfg.bucket
                ).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(bytes),
              )
            }.void
      }
