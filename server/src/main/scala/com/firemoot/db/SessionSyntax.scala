package com.firemoot.db

import cats.effect.IO
import skunk.{Command, Query, Session}

/**
 * One-shot helpers for parameterised statements, so call sites read as a single
 * line instead of `prepare(...).flatMap(...)`.
 *
 * Safety is unaffected: the `sql"..."` interpolator binds every `$`-parameter
 * over the extended-query protocol, so these are injection-safe by construction
 * (the only escape hatch is the explicit `#$` raw splice). Re-preparing is cheap
 * - skunk caches prepared statements per session.
 */
object SessionSyntax:

  extension (session: Session[IO])

    def runUnique[A, B](query: Query[A, B], args: A): IO[B] =
      session.prepare(query).flatMap(_.unique(args))

    def runOption[A, B](query: Query[A, B], args: A): IO[Option[B]] =
      session.prepare(query).flatMap(_.option(args))

    def runList[A, B](query: Query[A, B], args: A, chunkSize: Int = 64): IO[List[B]] =
      session.prepare(query).flatMap(_.stream(args, chunkSize).compile.toList)

    def run[A](command: Command[A], args: A): IO[Unit] =
      session.prepare(command).flatMap(_.execute(args)).void
