# Firemoot

**Self-hosted, open-source chat backend with first-class TypeScript SDKs.**
Stream Chat's developer experience, your infrastructure.

> Status: feature-complete through milestone M4 (server, SDKs, dashboard, deploy,
> docs). The v1.0 gate is the downstream E2E run and launch-asset registration
> (see [SPEC.md §16](SPEC.md)); the repo is private until those land.

One JVM binary + PostgreSQL. The whole backend boots inside your CI pipeline in
seconds - no network, no flake, no MAU metering. Realtime over WebSockets with
resumable, gapless delivery. Default port `6668` ("MOOT" on a phone keypad).

## Quickstart

```sh
sbt "server/Docker/publishLocal"      # build the image
cd deploy/compose && docker compose up -d
curl localhost:6668/healthz
```

Then mint a token and connect a client - see the
[quickstart guide](docs/guide/quickstart.md).

| Document | Purpose |
|---|---|
| [SPEC.md](SPEC.md) | Founding specification: scope, architecture, protocol, decisions log |
| [PLAN.md](PLAN.md) | Build plan: milestones M0-M4, task breakdown, schema |
| [docs/](docs/) | Documentation site (VitePress): quickstart, auth, protocol, migration, hosting |
| [SECURITY.md](SECURITY.md) | Vulnerability disclosure policy |

## Repository layout

```
server/          # Scala 3 service (http4s + fs2 + cats-effect + tapir + skunk)
sdk/ts/core/     # @firemoot/core - generated OpenAPI transport, types, REST client
sdk/ts/client/   # @firemoot/client - connection lifecycle, channel state, server SDK
sdk/test/        # @firemoot/test - boots/seeds a real Firemoot + chaos TcpProxy
admin/           # dashboard SPA source (Vite + TS, built into the server image)
deploy/compose/  # reference docker-compose.yml + Caddy + quickstart
deploy/fly/      # fly.toml + Fly.io walkthrough
deploy/caddy/    # one-stanza TLS reverse proxy
docs/            # VitePress documentation site
```

## Licence

[Apache-2.0](LICENSE)
