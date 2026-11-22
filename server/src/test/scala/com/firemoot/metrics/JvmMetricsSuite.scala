package com.firemoot.metrics

import java.nio.file.{Files, Path}

import munit.CatsEffectSuite

class JvmMetricsSuite extends CatsEffectSuite:

  test("a snapshot reports a bounded heap, non-heap use and (on Linux) RSS") {
    JvmMetrics.snapshot.map { j =>
      assert(j.heapMax > 0, j)
      assert(j.heapUsed > 0 && j.heapUsed <= j.heapCommitted, j)
      assert(j.heapAfterGc >= 0, j)
      assert(j.nonHeapUsed > 0, j)
      assertEquals(j.rss.isDefined, Files.isReadable(Path.of("/proc/self/status")))
      assert(j.rss.forall(_ > j.nonHeapUsed), j)
    }
  }
