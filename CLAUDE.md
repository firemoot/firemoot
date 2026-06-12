# Firemoot - working notes for Claude

Self-hosted chat backend. `SPEC.md` is the *what*; `PLAN.md` is the *how/order*
(work task-by-task through its checkboxes, ticking them in the same commit).

## Toolchain (mise)

All tool versions are pinned in `mise.toml` (JDK 25, sbt 1.12.11, node 22, pnpm 11).
The non-interactive shell may not have mise activated, so **prefix tool commands
with `mise exec --`**:

```
mise exec -- sbt -batch test
mise exec -- pnpm -r test
mise exec -- pnpm install
```

After editing `mise.toml`, run `mise trust && mise install`.

## Build / test

- Server: `mise exec -- sbt -batch test` (Scala 3.3.7, package `com.firemoot`, under `server/`).
- SDK: `mise exec -- pnpm -r typecheck && mise exec -- pnpm -r test` (ESM, NodeNext, under `sdk/`).
- **Before pushing, validate as CI does:** `CI=true mise exec -- sbt -batch scalafmtCheckAll scalafmtSbtCheck test`.
  sbt-tpolecat turns warnings into errors when `CI=true`, so a clean local run
  (without `CI`) can still fail CI - always check with `CI=true`.
- Formatting: `mise exec -- sbt scalafmtAll scalafmtSbt` and `mise exec -- pnpm run format`.
- Codegen (OpenAPI -> `@firemoot/core`): `mise exec -- pnpm run codegen` (writes
  `openapi.json` from the tapir endpoints, then regenerates `sdk/ts/core/src/generated`).
  CI fails on drift, so commit the regenerated output.
- Docker image + local stack: `mise exec -- sbt "server/Docker/publishLocal"` then
  `docker compose -f deploy/compose/docker-compose.yml up`.

## Gotchas

- **scalafmt + sbt meta-build:** `.scalafmt.conf` has a `fileOverride` pinning
  `*.sbt` and `project/*.scala` to the `scala213source3` dialect with
  `removeOptionalBraces = no`. The meta-build is compiled as Scala 2.13; the global
  scala3 brace-removal rewrite produces syntax it can't parse and the build won't
  load. Don't drop that override.
- **JDK 25 runtime flags:** run with `-XX:+UseCompactObjectHeaders -XX:+UseG1GC`
  (NOT ZGC - it's incompatible with compact object headers). This is part of the
  RSS/$7-VPS pitch.
- **pnpm via mise** uses the `npm:` backend (`"npm:pnpm"` in `mise.toml`); the aqua
  registry mis-packages pnpm's linux-x64 asset.
- **Auth middleware ordering:** an http4s auth middleware (e.g. `ServerHmacAuth`)
  short-circuits the whole request before the inner route's path is checked, so it
  must be composed **last** in the `<+>` chain - otherwise it 401s sibling routes
  like `/v1/ws` and `/healthz`. Don't run inner routes before auth (side effects).
- **Timer assertions in tests:** never assert against the wall clock that a timer
  *has* or *hasn't yet* fired (it flakes on contended CI runners - bit us in
  `TypingTrackerSuite`). Pure timer logic gets `cats.effect.testkit.TestControl`
  virtual time (`TestControl.executeEmbed { ... }` - deterministic and instant).
  Integration suites may still sleep, but only in the generous direction
  ("wait, then assert something arrived"), never "assert it hasn't happened yet".
- **CI verification:** `gh run watch` exit codes lie when chained with other
  commands; always read per-job conclusions:
  `gh run view <id> --json conclusion,jobs`. The Cold-boot gate and sdk jobs have
  both gone red while the run "looked" green from a wrapper's exit code.
- **SDK connection tests count microtasks.** The `Connection`/`FiremootClient`
  tests flush a fixed number of microtasks (`await Promise.resolve()`) before
  delivering `hello`, so they depend on the exact number of `await` ticks before
  the socket is created in `openSocket`. Adding an `await` to the hot path (e.g.
  `wsUrl()`/token resolution) shifts socket creation a tick later and the tests
  time out. Keep the static-token path await-free (the `tokenProvider ? await … :
  config.token` ternary's else-branch has no await on purpose); don't "DRY" it
  into a `Promise.resolve(...)` wrapper.
- **pnpm 11 + ignored native builds:** testcontainers pulls `ssh2`/`cpu-features`/
  `protobufjs`, whose postinstall builds pnpm refuses by default - and pnpm 11
  *exits non-zero* on that, breaking the deps-status precheck before every `pnpm
  run`. They're declared `false` under `allowBuilds:` in `pnpm-workspace.yaml`
  (we talk to a local Docker socket, so none need native code). Don't flip them to
  `true` (needs a compiler in CI for no gain).

## Conventions

- Domain is **firemoot.com** (canonical) - use it for any email/URL references.
- Commits: imperative, scoped (`server:`, `sdk:`, `admin:`, `deploy:`, `docs:`),
  **no AI attribution**. Committing and pushing in this repo is pre-authorised.
- Record decisions made during build in `SPEC.md` §2; tick `PLAN.md` checkboxes.
- Don't run the app in dev mode - the user does that.
