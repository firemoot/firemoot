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

## Conventions

- Domain is **firemoot.com** (canonical) - use it for any email/URL references.
- Commits: imperative, scoped (`server:`, `sdk:`, `admin:`, `deploy:`, `docs:`),
  **no AI attribution**. Committing and pushing in this repo is pre-authorised.
- Record decisions made during build in `SPEC.md` §2; tick `PLAN.md` checkboxes.
- Don't run the app in dev mode - the user does that.
