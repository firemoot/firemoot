package com.firemoot

import java.nio.file.{Files, Paths}

import cats.effect.{ExitCode, IO, IOApp}
import com.firemoot.api.OpenApiDocs

/**
 * Writes the OpenAPI document to a file (default `openapi.json`) so CI and
 * developers can regenerate the `@firemoot/core` SDK without running the server.
 * Run with `sbt "server/runMain com.firemoot.OpenApiExport <path>"`.
 */
object OpenApiExport extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val target = Paths.get(args.headOption.getOrElse("openapi.json"))
    IO.blocking(Files.writeString(target, OpenApiDocs.pretty + "\n"))
      .as(ExitCode.Success)
