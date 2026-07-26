<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/public/logo-wordmark-dark.svg">
  <img src="docs/public/logo-wordmark.svg" alt="Firemoot" width="300">
</picture>

**Stream Chat's developer experience, your infrastructure.**

[![CI](https://github.com/firemoot/firemoot/actions/workflows/ci.yml/badge.svg)](https://github.com/firemoot/firemoot/actions/workflows/ci.yml)
[![Soak](https://github.com/firemoot/firemoot/actions/workflows/soak.yml/badge.svg)](https://github.com/firemoot/firemoot/actions/workflows/soak.yml)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE)
[![Scala](https://img.shields.io/badge/Scala-3.3-DC322F?logo=scala&logoColor=white)](build.sbt)
[![JDK](https://img.shields.io/badge/JDK-25-437291?logo=openjdk&logoColor=white)](mise.toml)
[![TypeScript SDK](https://img.shields.io/badge/SDK-TypeScript-3178C6?logo=typescript&logoColor=white)](sdk/ts)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-EA580C)](CONTRIBUTING.md)

</div>

Firemoot is a self-hosted, open-source chat backend with a Stream-compatible API
surface and first-class TypeScript SDKs. It is **one JVM binary plus PostgreSQL** -
no Kafka, no Redis, no MongoDB, no sidecars. A *moot* is a gathering; a firemoot is
the gathering round the fire, which is what a chat channel has always been. It
listens on port `6668` ("MOOT" on a phone keypad, a neighbourly nod to IRC's 6667).

The headline feature is **testability**. Because the whole backend is a container
and a database, it boots inside your CI pipeline in seconds: no network, no flake,
no MAU metering, no shared sandbox that another branch just corrupted. Our CI has a
cold-boot gate that fails the build if the server does not answer `/healthz` within
15 seconds of `docker run` (it typically manages it in about 9). That is the thing a
SaaS structurally cannot sell you.

It is also meant to be genuinely cheap to run. The nightly soak drives real
WebSocket traffic through the real auth paths against a container capped at 1GB of
memory and gates the build on p99 delivery latency and peak resident memory. What
it reports: **p95 delivery around 61ms at a peak RSS of about 260MiB**. A $7 VPS is
a serious deployment target here, not a joke.

The compatibility story is not theoretical either. A production marketplace
application's full Playwright end-to-end suite - booking threads, a concierge
inbox, attachments, unread badges, typing indicators, read receipts, webhooks -
passes against Firemoot with **zero spec changes**, driven through a
Stream-compatible facade.

## Use it as

**A chat backend for CI and preview environments.** `@firemoot/test` boots a real
server (not a mock) via Testcontainers on random ports, seeds it, and hands back
URLs and tokens:

```ts
import { startFiremoot } from "@firemoot/test";

const fm = await startFiremoot(); // firemoot:latest + postgres:17, healthy in seconds
await fm.seed({
  users: [{ id: "alice" }, { id: "bob" }],
  channels: [
    { type: "messaging", id: "general", createdBy: "alice", members: [{ userId: "bob", role: "member" }] },
  ],
});

const token = await fm.createToken("alice");
// point your app at fm.baseUrl / fm.wsUrl, run your suite, then:
await fm.stop();
```

**A self-hosted production chat backend.** One `docker compose up` for the
reference stack, or the Fly.io config in [`deploy/fly/`](deploy/fly/). Your
messages stay in your Postgres, and your bill does not track your user count.

**A local development backend.** The same image your CI runs, on your laptop, with
the admin dashboard at `/admin` to see what is actually happening.

## Quickstart

Firemoot has not been published to Docker Hub or npm yet, so build the image
locally first:

```sh
sbt "server/Docker/publishLocal"          # tags firemoot:latest

cd deploy/compose
export FIREMOOT_API_SECRET=$(openssl rand -hex 32)
docker compose up -d

curl localhost:6668/healthz               # liveness
curl localhost:6668/readyz                # liveness + a Postgres ping
```

`FIREMOOT_API_SECRET` is the one credential you must set: it signs your backend's
HMAC requests **and** mints the end-user JWTs the browser connects with. Set
`FIREMOOT_ADMIN_PASSWORD` too if you want the dashboard.

Your backend provisions users and channels and mints tokens, exactly as it would
against a hosted vendor:

```ts
import { FiremootServer } from "@firemoot/client";

const server = new FiremootServer({
  baseUrl: "http://localhost:6668",
  apiKey: "firemoot", // FIREMOOT_API_KEY_ID
  apiSecret: process.env.FIREMOOT_API_SECRET!,
});

await server.upsertUser({ id: "alice", name: "Alice" });
await server.createChannel({ type: "messaging", id: "general", createdBy: "alice" }, [
  { userId: "bob", role: "member" },
]);

const token = await server.createToken("alice"); // hand this to the browser
```

The browser connects with that JWT and never sees the secret:

```ts
import { FiremootClient } from "@firemoot/client";

const client = new FiremootClient({ baseUrl: "http://localhost:6668", userId: "alice", token });
await client.connect();

const channel = client.channel("messaging", "general");
channel.on("message.new", (e) => console.log(e.data.userId, e.data.text));
await channel.watch();

await channel.sendMessage({ text: "hello, firemoot" });
```

Sends are optimistic: the message appears immediately and is reconciled the moment
the server confirms it, whichever of the REST response or the `message.new` event
arrives first, and never duplicated. Full walkthrough in the
[quickstart guide](docs/guide/quickstart.md).

## Stream compatibility

Firemoot deliberately mirrors Stream Chat's model rather than inventing a new one:
your backend mints user tokens, the browser talks to the chat backend directly, and
the backend authorises every operation. The covered surface is the part most apps
actually use - `type:id` channels with typed members and roles, messages with
client-supplied ids, reactions, threads, attachments, read state and unread counts,
typing, presence, channel queries with keyset pagination, full-text search, flags
and moderation, uploads, and HMAC-signed webhooks. `@firemoot/client` also ships
`streamChannelState(channel)`, a projection of a channel into a Stream
`channel.state`-shaped read model, so a migrated UI can keep reading
`{ messages, members, read, unreadCount, last_message_at }`.

This is not 100% of Stream's API and does not try to be: activity feeds, calling,
AI moderation, polls, campaigns and push notifications are out of scope, and
Firemoot uses camelCase on the wire where Stream uses snake_case. The
[migration guide](docs/guide/migration.md) has the mapping table and the honest
list of differences to plan for.

## Architecture

```
                  ┌────────────────────────────────────────┐
   wss / https    │           firemoot (one JVM)           │
  ──────────────► │   WS gateway │ REST API │ admin UI     │
       :6668      │   ───────── in-process bus ─────────   │
                  │   domain core │ workers: thumbnails,   │
                  │               │ webhooks, rollups      │
                  └──────┬────────────────────┬────────────┘
                  ┌──────┴─────┐       ┌──────┴───────────┐
                  │ S3 (media, │       │ Postgres (the    │
                  │  optional) │       │ only required    │
                  └────────────┘       │ dependency)      │
                                       └──────────────────┘
```

Everything that must survive a restart - messages, read state, unread counts, the
webhook queue, metric rollups - lives in Postgres; the connection registry and
typing state are deliberately ephemeral. The WebSocket protocol is seq-based and
resumable: clients re-subscribe with their last seen sequence per channel and the
server replays what they missed, so a disconnect is the normal case rather than an
error case.

v1 is **single node**. Fan-out goes through a `Backplane` interface with an
in-process implementation; a Postgres `LISTEN`/`NOTIFY` backplane is the planned
first step to multi-node, and is not shipped yet.

## Deploy

**Docker Compose** is the reference deployment: `firemoot` + `postgres:17`, plus an
optional S3-compatible store for media. Put [Caddy](deploy/caddy/Caddyfile) in
front for automatic TLS - the whole config is a `reverse_proxy localhost:6668`
stanza, and WebSocket upgrades pass through without a special directive. See
[`deploy/compose/`](deploy/compose/).

**Fly.io** is a first-class target, because a stateful WebSocket backend is exactly
what Fly machines are good at. Three things matter:

```sh
fly apps create firemoot
fly deploy --ha=false     # one machine; --ha would create a second that shares no state
```

- `fly.toml` sets `auto_stop_machines = "off"` and `min_machines_running = 1`.
  Fly's default scale-to-zero is designed for stateless HTTP; on a chat node it
  silently drops every connected client. Leave those settings alone.
- Pass `--ha=false`. Fly deploys two machines by default, and with a single-node
  backplane the second one is a coin flip over which node your users land on.
- On Fly's unmanaged Postgres over `flycast`, set `FIREMOOT_DB_SSLMODE=disable`.
  The proxy accepts the SSL request and then breaks the handshake rather than
  refusing it outright, so pgjdbc's default `prefer` never falls back to plaintext
  and boot dies in Flyway.

Full walkthrough in [`deploy/fly/README.md`](deploy/fly/README.md). Serverless
platforms (Vercel, Netlify, Lambda) cannot host Firemoot - they cannot hold the
sockets - but your serverless app makes a perfectly good *client* of it.

## Admin dashboard

Firemoot bundles a dashboard at `/admin`, built into the server image and unlocked
by `FIREMOOT_ADMIN_PASSWORD`. It charts DAU/WAU/MAU (the metric SaaS chat bills you
on, which is rather the point of watching it yourself), concurrent connections as a
daily p95/max plus a live number, messages per day by channel type, and storage
used. It doubles as the operational console: inspect and replay dead-lettered
webhook deliveries, rotate API keys. All of it comes from Postgres rollups with no
extra dependency, and Prometheus `/metrics` is there separately for people who
already have observability.

## Development

Tool versions (JDK 25, sbt, Node, pnpm) are pinned in `mise.toml`:

```sh
mise trust && mise install

mise exec -- sbt -batch test                                  # server
mise exec -- pnpm install && mise exec -- pnpm -r test        # SDKs
CI=true mise exec -- sbt -batch scalafmtCheckAll scalafmtSbtCheck test   # what CI runs
```

The OpenAPI spec is the source of truth for the SDK transport, and CI fails on
drift, so run `mise exec -- pnpm run codegen` and commit the result whenever you
change a tapir endpoint. [CONTRIBUTING.md](CONTRIBUTING.md) has the details.

## Documentation

| Document | Purpose |
|---|---|
| [Quickstart](docs/guide/quickstart.md) | Boot a server and connect a browser in about five minutes |
| [Auth model](docs/guide/auth.md) | Server HMAC vs end-user JWT, and where each belongs |
| [Protocol reference](docs/guide/protocol.md) | WebSocket frames, event vocabulary, resume semantics |
| [Migrating from Stream](docs/guide/migration.md) | Mapping table and the differences to plan for |
| [Sizing and performance](docs/guide/sizing.md) | What one node holds, and what it costs |
| [Hosting](docs/guide/hosting.md) | Compose, Fly.io, Caddy, and what cannot host it |
| [SPEC.md](SPEC.md) | Founding specification: scope, architecture, decisions log |

## Contributing

Issues and pull requests are welcome - see [CONTRIBUTING.md](CONTRIBUTING.md) for
the toolchain, the repository layout and the checks CI runs, and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for how we expect people to behave.
Security reports go to security@firemoot.com privately, per
[SECURITY.md](SECURITY.md).

## Licence

[Apache-2.0](LICENSE). Explicit patent grant, and a trademark carve-out: the code
is yours to use, the name is ours to keep meaning one thing.
