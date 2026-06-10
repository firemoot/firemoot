# ADR 0001: Database library - skunk

- Status: Accepted
- Date: 2026-06-10
- Milestone: M0.2 (resolves SPEC.md §15 open question 1)

## Context

Firemoot needs Postgres access from a cats-effect 3 / fs2 / http4s stack. The two
realistic candidates were **skunk** (Typelevel, native protocol) and **doobie**
(JDBC over a blocking pool). SPEC.md named skunk as the default and scheduled a
confirming spike in M0.

## Decision

Use **skunk 1.0.0** as the sole database driver.

## Rationale

- **Native `LISTEN`/`NOTIFY`.** skunk exposes Postgres channels as fs2 `Stream`s
  directly. SPEC.md §4 names Postgres `LISTEN`/`NOTIFY` as the first multi-node
  `Backplane` step; with skunk that is a first-class streaming API, whereas JDBC
  (doobie) has no async notification support and would force a blocking poll loop.
- **Non-blocking, session-based.** skunk speaks the Postgres wire protocol on the
  fs2/cats-effect runtime - no JDBC thread pool to size against a high
  WebSocket-connection-count server. This matches the "one stateful process,
  100k idle connections" model.
- **Stack coherence.** Pure Typelevel (cats-effect, fs2, scodec) like http4s and
  tapir; otel4s tracing slots into the same observability story.
- **Compile-checked SQL** with explicit codecs - we *want* hand-written SQL here,
  not an ORM/DSL.
- **1.0.0 is final**, which retires the "is it ready?" risk the spike existed to test.

## Trade-offs accepted

- No higher-level query DSL; every statement is raw SQL with an explicit codec.
- Smaller ecosystem and contributor pool than doobie.
- A JDBC driver still ships, but *only* for Flyway migrations on boot (see the
  forthcoming M0.3 work) - it is never on the application's hot path.

## Validation

M0.1 was built on skunk end to end: `Session.Builder` pool, a `select 1` readiness
query, and a Testcontainers `postgres:17` integration test - all green. Building the
slice a second time on doobie was judged wasteful given the decisive `LISTEN`/`NOTIFY`
argument; that fit is the reason skunk wins, and it is not close.
