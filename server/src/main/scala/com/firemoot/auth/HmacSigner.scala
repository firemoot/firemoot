package com.firemoot.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 request signing for the server SDK -> Firemoot call path
 * (SPEC.md §5). The signature binds the method, path, a timestamp and a hash of
 * the body, so neither replay (bounded by the timestamp window) nor body
 * tampering is possible. The SDK reproduces `canonicalString` exactly.
 *
 * Canonical string (newline-separated):
 * {{{ FIREMOOT-HMAC-SHA256\n<METHOD>\n<path>\n<unixSeconds>\n<sha256Hex(body)> }}}
 */
object HmacSigner:

  private val scheme = "FIREMOOT-HMAC-SHA256"

  def canonicalString(method: String, path: String, timestamp: Long, body: Array[Byte]): String =
    s"$scheme\n${method.toUpperCase}\n$path\n$timestamp\n${hex(sha256(body))}"

  def sign(secret: String, canonical: String): String =
    hex(hmacSha256(secret, canonical.getBytes(UTF_8)))

  /** Constant-time signature check (`MessageDigest.isEqual`). */
  def verify(secret: String, canonical: String, signatureHex: String): Boolean =
    MessageDigest.isEqual(sign(secret, canonical).getBytes(UTF_8), signatureHex.getBytes(UTF_8))

  private def sha256(data: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(data)

  private def hmacSha256(secret: String, data: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(data)

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString
