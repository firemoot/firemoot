package com.firemoot.domain

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import cats.effect.IO

/**
 * UUIDv7 generation (RFC 9562): 48-bit millisecond timestamp + version/variant
 * bits + randomness, so message ids are time-ordered. Postgres 17 has no native
 * `uuidv7()`, so ids are minted application-side.
 */
object UuidV7:

  def next: IO[UUID] =
    IO.realTime.map(d => build(d.toMillis, ThreadLocalRandom.current()))

  private[domain] def build(millisEpoch: Long, rnd: ThreadLocalRandom): UUID =
    val randA = rnd.nextInt(0x1000).toLong // 12 bits
    val msb = (millisEpoch << 16) | (0x7L << 12) | randA // ts(48) | ver(4) | rand_a(12)
    val lsb =
      (rnd.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L // variant(10) | rand_b(62)
    new UUID(msb, lsb)
