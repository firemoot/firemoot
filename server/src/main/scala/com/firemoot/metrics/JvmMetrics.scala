package com.firemoot.metrics

import java.lang.management.{BufferPoolMXBean, ManagementFactory, MemoryType}
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import cats.effect.IO

/**
 * Where the container's memory goes. A memory limit is spent on the heap and on
 * everything else the JVM needs (metaspace, code cache, thread stacks, direct
 * buffers, GC structures); `rss` is what the kernel's OOM killer compares with
 * the limit. Heap in use after the last collection approximates the live set,
 * so it climbs across scrapes only if something is leaking.
 */
final case class JvmMetrics(
    heapUsed: Long,
    heapAfterGc: Long,
    heapCommitted: Long,
    heapMax: Long,
    nonHeapUsed: Long,
    directBuffers: Long,
    rss: Option[Long],
)

object JvmMetrics:

  private val procStatus = Path.of("/proc/self/status")

  def snapshot: IO[JvmMetrics] = IO.blocking {
    val memory = ManagementFactory.getMemoryMXBean
    val heap = memory.getHeapMemoryUsage
    val afterGc = ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(_.getType == MemoryType.HEAP)
      .flatMap(pool => Option(pool.getCollectionUsage))
      .map(_.getUsed)
      .sum
    val direct = ManagementFactory.getPlatformMXBeans(classOf[BufferPoolMXBean]).asScala
      .map(_.getMemoryUsed)
      .filter(_ > 0)
      .sum
    JvmMetrics(
      heapUsed = heap.getUsed,
      heapAfterGc = afterGc,
      heapCommitted = heap.getCommitted,
      heapMax = heap.getMax,
      nonHeapUsed = memory.getNonHeapMemoryUsage.getUsed,
      directBuffers = direct,
      rss = residentBytes,
    )
  }

  /** `VmRSS` from `/proc/self/status` (Linux only; None elsewhere). */
  private def residentBytes: Option[Long] =
    Option.when(Files.isReadable(procStatus))(Files.readAllLines(procStatus).asScala)
      .flatMap(_.collectFirst { case line if line.startsWith("VmRSS:") => line })
      .flatMap(_.split("\\s+").lift(1).flatMap(_.toLongOption))
      .map(_ * 1024)
