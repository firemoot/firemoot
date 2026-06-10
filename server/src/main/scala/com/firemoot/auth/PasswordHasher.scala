package com.firemoot.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

import cats.effect.IO
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id password hashing for the admin password (SPEC.md §8, M3.4), using
 * BouncyCastle's pure-JVM implementation (no native libraries). The encoded form
 * is self-describing - `argon2id$<salt>$<hash>` (base64) - so a stored hash can
 * be verified without out-of-band parameters. Verification is constant-time.
 */
object PasswordHasher:

  private val Iterations = 3
  private val MemoryKiB = 64 * 1024 // 64 MiB
  private val Parallelism = 1
  private val HashLength = 32
  private val SaltLength = 16

  def hash(password: String): IO[String] =
    IO.blocking {
      val salt = new Array[Byte](SaltLength)
      SecureRandom().nextBytes(salt)
      s"argon2id$$${encode(salt)}$$${encode(derive(password, salt))}"
    }

  def verify(password: String, encoded: String): IO[Boolean] =
    IO.blocking {
      encoded.split('$') match
        case Array("argon2id", saltB64, hashB64) =>
          MessageDigest.isEqual(derive(password, decode(saltB64)), decode(hashB64))
        case _ => false
    }

  private def derive(password: String, salt: Array[Byte]): Array[Byte] =
    val params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withIterations(Iterations)
      .withMemoryAsKB(MemoryKiB)
      .withParallelism(Parallelism)
      .withSalt(salt)
      .build()
    val generator = new Argon2BytesGenerator()
    generator.init(params)
    val out = new Array[Byte](HashLength)
    generator.generateBytes(password.getBytes(UTF_8), out)
    out

  private def encode(bytes: Array[Byte]): String =
    Base64.getEncoder.withoutPadding.encodeToString(bytes)
  private def decode(value: String): Array[Byte] = Base64.getDecoder.decode(value)
