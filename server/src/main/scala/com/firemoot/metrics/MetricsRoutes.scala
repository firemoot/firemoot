package com.firemoot.metrics

import cats.effect.IO
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.{HttpRoutes, MediaType}

/**
 * Prometheus scrape endpoint (SPEC.md §8, M3.3). The exposition text is rendered
 * by hand - no metrics library - keeping the dependency/RSS footprint minimal.
 * Live gauges only; the dashboard reads the rollup tables, never this endpoint.
 */
final class MetricsRoutes(metrics: MetricsService, ccuNow: IO[Int]):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "metrics" =>
    ccuNow.flatMap(metrics.live).flatMap { live =>
      Ok(render(live)).map(_.withContentType(`Content-Type`(MediaType.text.plain)))
    }
  }

  private def gauge(name: String, help: String, value: Double): String =
    s"# HELP firemoot_$name $help\n# TYPE firemoot_$name gauge\nfiremoot_$name $value\n"

  private def render(m: LiveMetrics): String =
    val messages =
      if m.messagesByType.isEmpty then ""
      else
        val header = "# HELP firemoot_messages Messages in the trailing day by channel type\n" +
          "# TYPE firemoot_messages gauge\n"
        header + m.messagesByType.toList.sortBy(_._1).map { (channelType, count) =>
          s"""firemoot_messages{channel_type="$channelType"} ${count.toDouble}\n"""
        }.mkString

    gauge("ccu", "Live concurrent WebSocket connections", m.ccuNow.toDouble) +
      gauge("dau", "Daily active users", m.dau.toDouble) +
      gauge("wau", "Weekly active users", m.wau.toDouble) +
      gauge("mau", "Monthly active users", m.mau.toDouble) +
      gauge("media_bytes", "Total stored upload bytes", m.mediaBytes.toDouble) +
      gauge("db_size_bytes", "Database size in bytes", m.dbSizeBytes.toDouble) +
      messages
