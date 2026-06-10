# Firemoot

**Self-hosted, open-source chat backend with first-class TypeScript SDKs.**
Stream Chat's developer experience, your infrastructure.

> Status: pre-code. Private until launch assets are registered (see SPEC.md §16).

One JVM binary + PostgreSQL. The whole backend boots inside your CI pipeline in
seconds - no network, no flake, no MAU metering. Default port `6668` ("MOOT" on a
phone keypad).

| Document | Purpose |
|---|---|
| [SPEC.md](SPEC.md) | Founding specification: scope, architecture, protocol, decisions log |
| [PLAN.md](PLAN.md) | Comprehensive build plan: milestones M0-M4, task breakdown, schema |
| [SECURITY.md](SECURITY.md) | Vulnerability disclosure policy |

## Repository layout (target)

```
server/          # Scala 3 service (http4s + fs2 + cats-effect + tapir + skunk)
sdk/ts/          # @firemoot/client (state layer) + @firemoot/core (generated transport)
sdk/test/        # @firemoot/test - boots/seeds a Firemoot for downstream CI
admin/           # dashboard SPA source (Vite + TS)
deploy/compose/  # reference docker-compose.yml
deploy/fly/      # fly.toml + docs
docs/
```

## Licence

[Apache-2.0](LICENSE)
