<!-- Keep it short. The diff says what changed; this says why. -->

## What and why

## Tests

<!-- Which suites you ran, and what new coverage this adds. -->

## Checklist

- [ ] `CI=true mise exec -- sbt -batch scalafmtCheckAll scalafmtSbtCheck test` passes
- [ ] `mise exec -- pnpm -r typecheck && mise exec -- pnpm -r test` passes
- [ ] Endpoint changes: ran `mise exec -- pnpm run codegen` and committed the regenerated output
- [ ] Public SDK API changes: added a changeset (`mise exec -- pnpm changeset`)
- [ ] Non-obvious decisions recorded in SPEC.md section 2
