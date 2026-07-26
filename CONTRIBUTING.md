# Contributing to Firemoot

Thanks for looking. Issues, questions and pull requests are all welcome. This page
covers the toolchain and the checks CI runs, so your PR does not fail on something
mechanical.

By contributing you agree that your contribution is licensed under
[Apache-2.0](LICENSE), per section 5 of that licence.

## Toolchain

Every tool version (JDK 25, sbt, Node, pnpm) is pinned in `mise.toml`, so
[mise](https://mise.jdx.dev) is the only thing you install by hand:

```sh
mise trust && mise install
```

If your shell does not have mise activated, prefix commands with `mise exec --`.
The examples below do that throughout; drop it if `sbt` and `pnpm` are already on
your `PATH` at the pinned versions.

You will also want Docker running: the SDK integration tests and the cold-boot
check start real containers.

## Repository layout

```
server/          # Scala 3 service (http4s + fs2 + cats-effect + tapir + skunk)
sdk/ts/core/     # @firemoot/core - OpenAPI-generated transport, types, REST client
sdk/ts/client/   # @firemoot/client - connection lifecycle, channel state, server SDK
sdk/test/        # @firemoot/test - boots and seeds a real server via Testcontainers
admin/           # dashboard SPA source (Vite + TS), built into the server image
deploy/          # docker-compose reference stack, Fly.io config, Caddy, soak harness
docs/            # documentation site (VitePress)
```

## Build and test

```sh
mise exec -- sbt -batch test                   # server (Scala 3, under server/)
mise exec -- pnpm install
mise exec -- pnpm -r typecheck                 # SDKs (under sdk/)
mise exec -- pnpm -r test
```

Formatting:

```sh
mise exec -- sbt scalafmtAll scalafmtSbt
mise exec -- pnpm run format
```

### Validate the way CI does

**This is the one that catches people out.** sbt-tpolecat promotes warnings to
errors when `CI=true`, so a clean local `sbt test` can still fail CI. Always run
the full gate before you push:

```sh
CI=true mise exec -- sbt -batch scalafmtCheckAll scalafmtSbtCheck test
```

### Codegen drift

The tapir endpoint definitions are the source of truth for the HTTP API. The
OpenAPI document and the `@firemoot/core` transport layer are generated from them,
and CI fails if the committed output does not match a fresh run. If you touched an
endpoint, regenerate and commit the result:

```sh
mise exec -- pnpm run codegen    # writes openapi.json, regenerates sdk/ts/core/src/generated
```

The admin dashboard build under `admin/` is drift-gated the same way: its output is
committed into the server's resources so the JVM build stays node-free.

## Running a server locally

```sh
mise exec -- sbt "server/Docker/publishLocal"   # tags firemoot:latest
docker compose -f deploy/compose/docker-compose.yml up
```

See [`deploy/compose/README.md`](deploy/compose/README.md) for configuration and
[the quickstart](docs/guide/quickstart.md) for connecting a client.

## Pull requests

- **Open an issue first for anything large.** A design discussion is cheaper than a
  rewritten branch. Small fixes can go straight to a PR.
- **Keep the diff to one thing.** Unrelated formatting churn makes review slower
  and blame less useful.
- **Tests come with the change.** Server logic gets a munit suite; SDK changes get
  vitest coverage. Do not skip a failing test to make the suite green.
- **Never assert against the wall clock** that a timer has or has not yet fired -
  it flakes on contended runners. Pure timer logic uses
  `cats.effect.testkit.TestControl` virtual time; integration suites may sleep, but
  only in the "wait, then assert something arrived" direction.
- **Document decisions.** If you make a non-obvious architectural call, add a row
  to the decisions log in [SPEC.md](SPEC.md) section 2 in the same commit.
- **Public API changes need a changeset** (`mise exec -- pnpm changeset`) so the
  SDK packages version correctly.

## Commit messages

Imperative mood, scoped with the area you touched:

```
server: paginate message history by before_id
sdk: reconnect with per-channel resume seqs
admin: chart storage growth over 90 days
deploy: pin the Postgres image by digest
docs: record the string message-id decision in SPEC section 2
```

Scopes in use: `server`, `sdk`, `admin`, `deploy`, `docs`. Wrap the body at about
72 columns. Please do not add AI tool attribution or co-author trailers.

## Reporting security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md) - reports go to
security@firemoot.com or through GitHub's private vulnerability reporting.
