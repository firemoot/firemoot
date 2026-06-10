# Firemoot

**Self-hosted, open-source chat backend with first-class TypeScript SDKs.**
Stream Chat's developer experience, your infrastructure.

| | |
|---|---|
| Version | 0.1 (founding draft) |
| Date | 10/06/2026 |
| Status | Pre-code. All decisions below are confirmed unless listed under Open Questions. |
| Licence | Apache-2.0 (uniform: server, SDKs, tooling) |
| Name | "Firemoot" - moot (Old English: assembly) + fire; "the gathering around the fire". Only prior use is a fantasy-calendar month meaning "Gathering of Fire" (Gnome Stew, 2013). No commercial or software collisions found as of 10/06/2026. |

## 1. Origin and Differentiator

Firemoot was conceived while fighting flaky E2E tests against Stream Chat's live API in the
Frented project. The chat-API market has no self-hosted option with SaaS-grade developer
experience: XMPP servers (ejabberd, MongooseIM) are protocol stacks without modern SDKs,
OpenIM carries a Kafka+Redis+MongoDB footprint, and the rest are finished apps
(Rocket.Chat, Mattermost), not embeddable backends.

**Headline differentiator: testability as a feature.** Single binary + Postgres means the
entire backend boots in anyone's CI in seconds. The thing a SaaS structurally cannot offer
- "run the whole chat backend inside your test pipeline, no network, no flake, no MAU
metering" - is Firemoot's pitch. Adoption wedge: teams who love Stream's SDK ergonomics but
hate untestable external dependencies, unpredictable MAU pricing, or third-party data
custody (the same regulated-industry concern that drives self-hosted adoption generally).

First consumer: Frented's Playwright suite replaces its live-Stream `enableStream` specs
and skip-gate bypasses with a local Firemoot instance.

## 2. Decisions Log

| Decision | Choice | Why |
|---|---|---|
| Language | Scala 3 (LTS line) | Maintainer fluency and preference; JVM/Netty handles 100k+ idle WS connections; accepted trade-offs: smaller contributor pool, ~300-400MB JVM RSS (GraalVM native-image is a stretch goal) |
| Effect stack | Typelevel: http4s + fs2 + cats-effect | Best WS/streaming ergonomics; tapir compatibility. Reversible until first code; revisit only if maintainer's muscle memory says ZIO |
| API definition | tapir, OpenAPI-first | The OpenAPI spec is the source of truth; TS SDK transport is generated from it. Client SDKs are the moat in this category; codegen keeps them honest |
| Database | PostgreSQL (only required dependency) | Boring, universal, handles messages + search + metrics rollups in v1 |
| Licence | Apache-2.0 | Explicit patent grant (enterprise adoption), trademark carve-out (the MinIO/Pigsty failure mode), Section 5 auto-licenses contributions. AGPL rejected: adoption is the goal; MIT rejected: no patent grant, no trademark language |
| Media store | Any S3-compatible API; reference compose ships `pgsty/minio` | MinIO Inc. archived upstream Feb 2026; the Pigsty fork is actively maintained (4 CVEs patched within days, Apr 2026). Generic-S3-only coding keeps Garage/SeaweedFS/RustFS/Tigris/AWS as zero-cost swaps; known risk: pgsty/minio is bus-factor-one and may rename under trademark pressure |
| Deploy targets v1 | Docker Compose (reference) + Fly.io (first-class) | Tier 1 + Fly only. Kubernetes/Helm explicitly deferred |
| Scale model v1 | Single node | Backplane is an internal interface stub; Postgres LISTEN/NOTIFY is the planned first multi-node step, Redis pub/sub after. Not v1 work |
| Tenancy | Single tenant, API key + secret pairs | Stream-style multi-app tenancy deferred |
| Default port | 6668 | "MOOT" on a phone keypad is 6668; neighbourly nod to IRC's 6667. Avoids overloaded dev defaults |

## 3. Scope

### v1 In

- **Channels**: typed (`messaging`, `livestream`-style types are config), `type:id` cid
  addressing, members with roles (owner/moderator/member), server-created and
  client-created, JSONB `custom` data, frozen/archived flags.
- **Messages**: send/edit/delete (soft), attachments, reactions, threaded replies
  (parent_message_id + denormalised reply_count), system messages (`type: system`),
  arbitrary JSONB `custom` payloads, server-sent "send as user".
- **Realtime**: WebSocket with heartbeats and seq-based resume (§5); typing
  start/stop; presence (online/offline + last_active_at); read receipts; per-member
  unread counts.
- **Query**: channel queries with a minimal filter DSL (`type`, `cid $in`,
  `members $in`, custom-field equality), sort by `last_message_at`, cursor pagination;
  message history pagination (`before_seq` cursors); message search via Postgres FTS
  (documented as lower-fidelity than dedicated search engines).
- **Media** (§7): presigned direct-to-S3 upload, async in-process thumbnailing,
  MIME/size policy.
- **Server API surface**: user upsert/delete (GDPR hard-delete), token mint, channel
  CRUD + member ops, send-as-user, message flagging/moderation queue.
- **Webhooks** (§9): HMAC-signed, retried, dead-lettered.
- **Dashboard** (§8): bundled admin UI; MAU/CCU/messages-per-day over time. This is a
  first-class requirement, not an afterthought.
- **Observability**: Prometheus `/metrics`, structured JSON logs, health/readiness
  endpoints.

### v1 Out (explicit)

Activity feeds, video/audio calling, AI moderation, polls, campaigns/broadcast,
federation, multi-region, multi-tenancy, horizontal scaling, Kubernetes/Helm charts,
push notifications to mobile (FCM/APNs - v1.x, needed before any mobile SDK), E2EE.

## 4. Architecture

```
                ┌─────────────────────────────────────────────┐
                │              firemoot (one JVM)             │
   wss/https    │  ┌──────────┐ ┌─────────┐ ┌──────────────┐  │
  ────────────► │  │ WS       │ │ REST    │ │ Admin UI     │  │
   :6668        │  │ gateway  │ │ API     │ │ (/admin)     │  │
                │  └────┬─────┘ └────┬────┘ └──────┬───────┘  │
                │       │   in-proc event bus      │          │
                │       │  (Backplane interface;   │          │
                │       │   single-node impl v1)   │          │
                │  ┌────┴──────────────┴───────────┴───────┐  │
                │  │ domain core: channels/messages/users  │  │
                │  └────┬──────────────┬───────────────────┘  │
                │  ┌────┴────┐  ┌──────┴──────────────────┐   │
                │  │ workers │  │ skunk/doobie            │   │
                │  │ thumbs/ │  └──────┬──────────────────┘   │
                │  │ webhook/│         │                      │
                │  │ rollups │         │                      │
                └──┼─────────┼─────────┼──────────────────────┘
                   │         │         │
              ┌────┴───┐     │   ┌─────┴─────┐
              │ S3 API │     │   │ Postgres  │
              │(pgsty/ │     │   │ (the only │
              │ minio …)     │   │  required │
              └────────┘     │   │  dep)     │
                             ▼   └───────────┘
                        webhook targets
```

Principles:

- **One stateful process.** HTTP API, WS gateway, admin UI, and background workers
  (thumbnails, webhook delivery, metric rollups) in a single binary. No sidecar
  services beyond Postgres (+S3 store if media enabled).
- **No sticky sessions ever.** Any future node can accept any connection; all
  cross-connection fan-out goes through the `Backplane` trait. v1 ships the in-process
  implementation only.
- **Plain HTTP upgrade on one port.** No custom TCP, no second port. Every reverse
  proxy and LB that speaks HTTP/1.1 upgrade works.
- **In-memory state is allowed** (this is a long-lived process, not serverless), but
  anything that must survive restart (read state, unread counts, webhook queue, metric
  rollups) lives in Postgres. Connection registry and typing state are ephemeral by
  design.

## 5. Protocol

### Auth

- **Server SDK -> Firemoot**: API key id + HMAC-signed requests (key + secret pair,
  generated at install; rotated via CLI/admin UI).
- **End user -> Firemoot**: JWT (HS256, signed with the API secret), minted by the
  customer's backend via the server SDK. Claims: `sub` (user id), `exp`, optional
  `role`. Same integration shape as Stream's `createToken`, deliberately, so migration
  is one mental model.

### REST

OpenAPI 3.1, defined in tapir, served at `/v1/*` with the spec at `/v1/openapi.json`.
Errors: RFC 9457 problem+json. Cursor pagination throughout. Rate limiting: token
bucket per API key and per user (in-memory v1; the limiter sits behind an interface for
the multi-node future).

### WebSocket

Single endpoint `GET /v1/ws?token=<jwt>`.

- **Handshake**: first server frame is `hello` carrying `connection_id`, server time,
  and the authenticated user object. The client SDK resolves `connect()` on `hello`.
- **Heartbeat**: server pings every 25s (under every default LB idle timeout);
  connection reaped after 2 missed pongs. Clients may send `ping` frames and get
  `pong` with server time (clock-skew measurement).
- **Sequencing**: every channel event carries a per-channel monotonic `seq` (bigint,
  allocated transactionally with the write). The channel's current `seq` is returned
  on every channel query.
- **Subscription**: clients explicitly `subscribe` with
  `{ cid: last_seen_seq }` pairs (0 = just stream from now). The server replays
  persisted events with `seq > last_seen_seq` in order, then streams live. This is the
  whole reconnect story: disconnect, reconnect, re-subscribe with last seen seqs,
  miss nothing. Disconnects are the normal case, not the error case.
- **Event types** (v1): `hello`, `message.new`, `message.updated`, `message.deleted`,
  `reaction.new`, `reaction.deleted`, `typing.start`, `typing.stop`, `member.added`,
  `member.removed`, `channel.updated`, `channel.deleted`, `read.updated` (read
  receipts + unread counts), `presence.changed`, `notification.added_to_channel`,
  `notification.removed_from_channel`.
- Ephemeral events (`typing.*`, `presence.changed`) carry no seq and are never
  replayed.

### Unread counts and read state

Per (channel, member): `last_read_seq`. Unread = count of non-own, non-system messages
with `seq > last_read_seq`. `markRead` advances the pointer and emits `read.updated`
to the channel (receipt) and to the reader's other devices (badge sync). Total unread
badge = server-computed sum, included in `hello` and on `read.updated`.

## 6. Tech Stack

| Layer | Choice |
|---|---|
| Language | Scala 3 LTS |
| HTTP/WS | http4s (Ember/Netty backend) + fs2 |
| Effects | cats-effect 3 |
| API definition | tapir -> OpenAPI 3.1 |
| DB access | skunk (or doobie; settle during M0 spike) |
| Migrations | Flyway, run on boot (single-node v1 makes this safe) |
| Build | sbt + sbt-native-packager Docker image (JVM, eclipse-temurin) |
| Stretch | GraalVM native-image build for a small static image |
| Tests | munit + scalacheck; Testcontainers (Postgres, S3) for integration |
| Admin UI | Static SPA (Vite + TS) baked into the binary's resources, served at `/admin` |

Repository layout:

```
firemoot/
  server/          # Scala service
  sdk/ts/          # @firemoot/client (state layer) + @firemoot/core (generated transport)
  sdk/test/        # @firemoot/test - boots/seeds a Firemoot for downstream CI
  admin/           # dashboard SPA source
  deploy/compose/  # reference docker-compose.yml
  deploy/fly/      # fly.toml + docs
  docs/
  SPEC.md
```

## 7. Media

- Flow: client asks `POST /v1/uploads` -> server validates MIME/size policy against
  config -> returns presigned PUT URL + final object URL -> client uploads directly to
  the S3 store -> client attaches `{ asset_url | image_url, mime, size, title }` to the
  message.
- Thumbnailing: async in-process worker generates image thumbnails (longest edge
  512px) written back to the store; `thumb_url` patched onto the attachment and
  re-emitted via `message.updated`.
- **Generic S3 API only.** No vendor admin APIs anywhere in the codebase. Reference
  compose ships `pgsty/minio`; documented drop-ins: Garage, SeaweedFS, RustFS, Tigris
  (Fly), AWS S3/R2.
- Media disabled = no S3 dependency at all; uploads return 501 with a clear error.
- Policy defaults: 10MB images, 50MB files, MIME allowlist, per-user upload rate limit.

## 8. Dashboard and Metrics (first-class requirement)

Bundled admin UI at `/admin` (auth: admin password set at install, session cookie).

Charts (time-series, day granularity, 90-day default window):

- **MAU**: distinct user ids with any connection or API-attributed action in the
  trailing 30 days, computed daily. (The metric Stream bills on; self-hosters watching
  it is the point.)
- **DAU** and **WAU** alongside.
- **Concurrent connections**: gauge sampled every 60s; chart p95/max per day, with a
  live "right now" number.
- **Messages per day**, with per-channel-type breakdown.
- **Storage**: media bytes stored, DB size.

Implementation: `metrics_hourly` rollup rows written by the in-process worker,
compacted to `metrics_daily` after 7 days; raw activity facts pruned after rollup. All
in Postgres - no extra dependency. Prometheus `/metrics` exposes the live gauges and
counters for people with existing observability; the dashboard does not depend on
Prometheus.

## 9. Webhooks

- Events: the persisted event list from §5 (no ephemeral events), plus
  `user.flagged`.
- Delivery: POST JSON, `X-Firemoot-Signature: sha256=<HMAC(secret, body)>`, 5s
  timeout, retries at 1m/5m/30m/2h, then dead-letter table visible (and replayable) in
  the admin UI.
- Webhook queue lives in Postgres (`FOR UPDATE SKIP LOCKED` consumer) - survives
  restarts, no extra dependency.

## 10. TypeScript SDK

Two packages, npm scope `@firemoot`:

- **`@firemoot/core`** - generated from the OpenAPI spec (transport, types, REST
  client). Regenerated in CI; drift between server and SDK is a build failure.
- **`@firemoot/client`** - the hand-written value layer: `FiremootClient` (connection
  lifecycle, auto-reconnect with seq resume, token refresh hook), `Channel` handle
  (state cache, optimistic send with rollback, watch/subscribe, typing throttle,
  read-state tracking), event emitter typed per §5 event list.
- **`@firemoot/test`** - downstream-CI helper: starts Firemoot (Docker or binary),
  waits healthy, seeds users/channels/messages via server API, hands back URLs +
  tokens. The dogfood target: Frented's Playwright suite.
- Server-side usage (token mint, server API) works in any Node runtime including
  serverless (it is plain HTTPS + JWT signing); only the realtime client needs a
  socket.
- Browser + Node 22+ (amended 10/06/2026: Node 20 reached EOL Apr 2026); ESM; zero
  runtime deps beyond a WS shim for Node.

Scala server SDK comes free (the domain client is the server's own); other languages
are post-v1, community-driven via the OpenAPI spec.

## 11. Deployment

### Reference: Docker Compose (Tier 1)

`deploy/compose/docker-compose.yml`: `firemoot` + `postgres:17` + `pgsty/minio`.
Suitable for a $7 Hetzner-class VPS behind Caddy (one-stanza reverse proxy with
automatic TLS; WS upgrade works out of the box). This compose file is also exactly
what downstream CI runs. Sizing guidance in docs: 2GB RAM comfortable, JVM capped
with `-XX:MaxRAMPercentage`.

### First-class: Fly.io

`deploy/fly/fly.toml`: `min_machines_running = 1`, auto-stop disabled (stateful WS),
single region to start, Fly Postgres or Neon, Tigris for media (S3-compatible, zero
config change). Docs include the one Fly gotcha that matters: do not let the proxy
idle-stop a machine holding live sockets.

### Explicitly unsupported as hosts

Vercel, Netlify, AWS Lambda, AWS App Runner - no long-lived connections. Documented
clearly with the framing: your serverless app is a *client* of Firemoot (server SDK
over HTTPS), exactly as it would be a client of Stream.

## 12. Testing Strategy

- Unit: domain core pure where possible; munit + scalacheck (property tests for seq
  allocation, unread arithmetic, filter DSL).
- Integration: Testcontainers Postgres + S3; every REST endpoint and WS event path.
- Protocol: a dedicated suite driving raw WS frames (handshake, resume-after-gap,
  heartbeat reaping, multi-device read sync).
- Soak: k6 WS scenario (N idle connections + M msg/s) run nightly in CI; regression
  thresholds on memory and p99 delivery latency.
- Dogfood: `@firemoot/test` used by Firemoot's own SDK tests, then by Frented.
- CI: GitHub Actions; every PR runs the full stack in compose. The project must always
  pass its own "boots fast in CI" pitch - a CI job asserts cold start to healthy
  < 15s.

## 13. Security Posture

- wss/https only in production docs; HSTS notes for proxies.
- JWTs verified with constant-time HMAC; `exp` required; clock skew ±60s.
- All channel operations authorise against membership/role server-side; no
  client-asserted identity anywhere.
- Rate limits on connection attempts, message send, uploads, search.
- Webhook payloads signed; admin UI session-cookied, CSRF-protected, no default
  password.
- Generic errors in production mode; detailed errors behind a dev flag.
- `SECURITY.md` with a disclosure address from day one. (The RustFS CVE stream is the
  cautionary tale: object-storage-adjacent projects get probed early.)

## 14. Milestones

- **M0 - walking skeleton.** Compose boots app+Postgres; WS handshake (`hello`),
  `sendMessage` REST -> `message.new` over WS between two browser tabs; tapir spec
  generating `@firemoot/core`; skunk-vs-doobie spike settled. *Proves the riskiest
  seams: tapir->codegen and fs2 WS fan-out.*
- **M1 - chat core.** Full §3 channel/message/reaction/thread/typing/read surface;
  seq resume; queries + pagination; FTS search; webhooks.
- **M2 - media.** Presigned uploads, thumbnails, policy; pgsty/minio in compose;
  Tigris on Fly verified.
- **M3 - dashboard.** Rollups, admin UI charts, Prometheus endpoint.
- **M4 - SDK polish + test helper.** `@firemoot/client` state layer hardened
  (reconnect chaos tests), `@firemoot/test` shipped, docs site, Fly deploy guide.
- **v1.0 gate.** Frented's messaging E2E suite passes against Firemoot in CI with
  zero spec changes beyond configuration. That is the definition of done.

## 15. Open Questions

1. skunk vs doobie (settle in M0 spike; skunk default).
2. Channel-type permission model: how much of Stream's role/permission matrix to
   replicate vs a simpler owner/moderator/member fixed set (v1 leans simple).
3. Admin UI auth: local password only, or optional OIDC from day one?
4. Message retention/pruning policy knobs - v1 or v1.x?
5. Trademark registration for "Firemoot" - worth £170 (UK) early, or wait for traction?

## 16. Assets Checklist (all verified free 10/06/2026 - register before public code)

- [ ] firemoot.com (canonical - used throughout for docs and security contact) /
      firemoot.io / firemoot.dev / firemoot.chat (RDAP-clear; .com via Verisign
      authoritative)
- [ ] GitHub org `firemoot`
- [ ] npm org `@firemoot`
- [ ] Docker Hub org `firemoot`
- [ ] (defer) crates.io / PyPI names - both currently free

## 17. Reference Points

- Stream Chat surface (the parity benchmark): channels/cid model, JWT token mint,
  event names, unread semantics. Firemoot deliberately rhymes with Stream's concepts
  for migration familiarity but does not copy its API verbatim.
- pgsty/minio: https://github.com/pgsty/minio (community MinIO continuation; Apr 2026
  CVE responsiveness).
- WS scaling/practice: websocket.org guides (heartbeats, reconnect-as-normal-case,
  backplane pattern).
- Cautionary tales informing the licence and storage choices: MinIO AIStor rug-pull
  (repo archived Feb 2026), OpenMaxIO's dormant fork, RustFS's early CVE stream.
