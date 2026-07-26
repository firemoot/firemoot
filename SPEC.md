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

Firemoot was conceived while fighting flaky E2E tests against Stream Chat's live API in a
production marketplace application. The chat-API market has no self-hosted option with
SaaS-grade developer experience: XMPP servers (ejabberd, MongooseIM) are protocol stacks
without modern SDKs, OpenIM carries a Kafka+Redis+MongoDB footprint, and the rest are
finished apps (Rocket.Chat, Mattermost), not embeddable backends.

**Headline differentiator: testability as a feature.** Single binary + Postgres means the
entire backend boots in anyone's CI in seconds. The thing a SaaS structurally cannot offer
- "run the whole chat backend inside your test pipeline, no network, no flake, no MAU
metering" - is Firemoot's pitch. Adoption wedge: teams who love Stream's SDK ergonomics but
hate untestable external dependencies, unpredictable MAU pricing, or third-party data
custody (the same regulated-industry concern that drives self-hosted adoption generally).

First consumer: the downstream production app's Playwright suite replaces its live-Stream
`enableStream` specs and skip-gate bypasses with a local Firemoot instance.

## 2. Decisions Log

| Decision | Choice | Why |
|---|---|---|
| Language | Scala 3 (LTS line) | Maintainer fluency and preference; JVM/Netty handles 100k+ idle WS connections; accepted trade-offs: smaller contributor pool, ~300-400MB JVM RSS (GraalVM native-image is a stretch goal) |
| JDK | Temurin 25 LTS (added 10/06/2026) | Runs Scala 3.3 LTS fine; Compact Object Headers (JEP 519) cut ~10-22% heap, partly mitigating the RSS trade-off above, via `-XX:+UseCompactObjectHeaders` on G1 (not ZGC). 21 was the alternative; 24 is dead (non-LTS, EOL) |
| Toolchain | mise (`mise.toml`); pinned JDK/sbt/node/pnpm (added 10/06/2026) | One file drives local + CI (via `jdx/mise-action`), eliminating version drift |
| Effect stack | Typelevel: http4s + fs2 + cats-effect | Best WS/streaming ergonomics; tapir compatibility. Reversible until first code; revisit only if maintainer's muscle memory says ZIO |
| API definition | tapir, OpenAPI-first | The OpenAPI spec is the source of truth; TS SDK transport is generated from it. Client SDKs are the moat in this category; codegen keeps them honest |
| Database | PostgreSQL (only required dependency) | Boring, universal, handles messages + search + metrics rollups in v1 |
| Licence | Apache-2.0 | Explicit patent grant (enterprise adoption), trademark carve-out (the MinIO/Pigsty failure mode), Section 5 auto-licenses contributions. AGPL rejected: adoption is the goal; MIT rejected: no patent grant, no trademark language |
| Media store | Any S3-compatible API; reference compose ships `pgsty/minio` | MinIO Inc. archived upstream Feb 2026; the Pigsty fork is actively maintained (4 CVEs patched within days, Apr 2026). Generic-S3-only coding keeps Garage/SeaweedFS/RustFS/Tigris/AWS as zero-cost swaps; known risk: pgsty/minio is bus-factor-one and may rename under trademark pressure |
| Deploy targets v1 | Docker Compose (reference) + Fly.io (first-class) | Tier 1 + Fly only. Kubernetes/Helm explicitly deferred |
| Scale model v1 | Single node | Backplane is an internal interface stub; Postgres LISTEN/NOTIFY is the planned first multi-node step, Redis pub/sub after. Not v1 work |
| Tenancy | Single tenant, API key + secret pairs | Stream-style multi-app tenancy deferred |
| Default port | 6668 | "MOOT" on a phone keypad is 6668; neighbourly nod to IRC's 6667. Avoids overloaded dev defaults |
| Query filter DSL (added 10/06/2026, M1.9) | Fixed-shape parameterised SQL, not dynamic assembly | The channel filter (`type`/`cids`/`members`/`custom`/`archived`) compiles to a single prepared statement: list filters ride in one jsonb array param (`jsonb_array_elements_text`), custom uses jsonb containment (`@>`), a NULL param means "no constraint". No identifier or value is ever interpolated, so injection-safety is structural, not validated. Channel paging is a keyset cursor on `coalesce(last_message_at, created_at), cid` |
| Full-text search (added 10/06/2026, M1.9) | Postgres FTS, `simple` config, websearch syntax | `websearch_to_tsquery('simple', ...)` over a stored `text_search` GIN vector, `ts_rank`ed. Deliberately lower-fidelity (no stemming/language awareness/typo tolerance) and documented as such; a dedicated search engine is out of scope for v1 |
| Media uploads (added 10/06/2026, M2) | Presigned direct-to-S3 PUT; AWS SDK v2 presigner, path-style, generic-S3 only | The app only ever presigns / gets / puts - never vendor admin APIs - so MinIO/Tigris/Garage/AWS swap unchanged (proven end-to-end against MinIO). Media is disabled unless `FIREMOOT_S3_ENDPOINT` is set (uploads 501). Thumbnails are 512px PNGs generated by an in-process worker (ImageIO + TwelveMonkeys) and patched onto message attachments via a re-emitted `message.updated` |
| Scala 3.9 LTS upgrade (checked 10/06/2026, M2.7) | Stay on 3.3.7 for v1; re-check at M4 | 3.9 LTS (Q2 2026) is feature-frozen against 3.8 and brings no feature we need; 3.3.7 LTS is supported to ~Q2 2027. A days-old compiler bump risks the CI quality gate for no gain. Scala 3 forward binary-compat means the 3.3-built http4s/tapir/skunk keep working when the upgrade is taken later |
| Admin dashboard chart library (added 11/06/2026, M3.5) | uPlot, not Chart.js | uPlot (~45KB, zero-dep, MIT) over Chart.js (~5x larger, canvas/animation-oriented) keeps the minimal-footprint/$7-VPS pitch honest for a self-hosted admin panel. Renders the §8 charts (DAU/WAU/MAU, hourly CCU p95/max, messages/day stacked by channel type, storage) |
| Admin SPA build integration (added 11/06/2026, M3.5) | Vite + TS app at `admin/`, built into `server/src/main/resources/admin`, committed and CI drift-gated | The build emits stable, unminified filenames the server serves from the classpath via `AdminSpaRoutes` (mirrors `demo.html`). The output is committed and diff-checked in CI exactly like the generated SDK, so the JVM/Docker build stays node-free while CI guarantees freshness. CSRF is double-submit: the SPA reads the `firemoot_csrf` cookie back into an `X-CSRF-Token` header on mutations |
| Soak harness (added 11/06/2026, M3.6) | k6 (v2.0, stable `k6/websockets`); nightly, memory-capped | `deploy/soak/ws-soak.js` mints HS256 JWTs + HMAC request signatures in-script so it drives the real auth paths; latency is measured end-to-end via `custom.sentAtMs`. Each run uses a fresh channel so seq-0 subscribe replays nothing (else stale-timestamp replays pollute the metric). The nightly workflow caps firemoot at 1g (the "$7 VPS" envelope, making the RSS gate meaningful against the MaxRAMPercentage heap sizing) and gates on p99 latency + peak RSS + no-OOM |
| Client-authenticated REST (added 11/06/2026, M4 plan review) | v1 ships JWT-bearer client endpoints with per-op membership/role authz (M4.3) | The downstream audit showed that app's browser sends messages **directly** with the user's JWT (Stream's model); HMAC must stay server-only, so without client auth the v1.0 gate fails or forces proxy routes into the customer app. This closes the M1.1 authz deferral and makes the §13 "authorise every channel op server-side" posture enforceable. The end-user JWT already described in §5 simply gains a REST bearer surface alongside the WS one |
| Docs generator (added 12/06/2026, M4.9) | VitePress, `docs/` as source, a workspace package | Markdown-first, near-zero config, fast static output; the docs build is gated by the existing sdk job's `pnpm -r build` so pages can't rot. Internal records (the decision log) stay in `docs/` but are `srcExclude`d from the published site. Astro/Docusaurus were heavier for no v1 gain |
| Message ids are caller-suppliable text (added 20/07/2026) | `messages.id` (and its FK columns) migrated uuid -> text (V006); `SendMessageRequest.id` optional, duplicate -> 409 via the PK unique violation (race-safe); global `DELETE /v1/messages/{id}` added | Stream parity: Stream message ids are arbitrary caller strings, and idempotent re-sends (`<bookingId>_first` seeds) + delete-by-id-alone are load-bearing in the downstream migration. Server-minted ids remain UUIDv7 strings, so absent-callers see no change |
| Downstream test helper (added 12/06/2026, M4.5) | `@firemoot/test` boots Firemoot via **testcontainers-node**, not the reference compose | A programmatic helper's contract is "boot, wait healthy, hand back URLs + tokens"; testcontainers gives random mapped host ports (parallel-safe, no fixed-6668 collisions), automatic container reaping (Ryuk) even on a crashed run, and a clean async API returning the mapped `baseUrl`. The reference `docker-compose.yml` stays the *deployment* artifact; the *test* lifecycle is a separate concern. The dogfood gate (Firemoot's own SDK suite driving a real server) is the first consumer and caught the missing client REST bearer auth on day one |
| Channel-state hydration (added 12/06/2026, M4.3) | `get channel` / `channels/query` return a hydrated `ChannelState` (the channel + `members` carrying `lastReadSeq`, the caller's `read` state, and the `latestMessage`) | The downstream audit named this the biggest non-auth gap - Stream returns members, read receipts, the caller's unread count and a conversation preview inline, and the browser UI leans on all four. Hydration is batched over the page's whole cid set (≤3 queries regardless of page size; the caller-unread query runs only for an end-user caller), so it never degrades to N+1. It is one tapir output type for both caller kinds; `read` is absent for a server-key caller (which has no single subject). `@firemoot/client` keeps returning a bare `Channel` from `getChannel` for now - consuming the richer state (other members' read receipts, badge counts) is M4.4 |
| Client-supplied message ids + global delete (added 20/07/2026, downstream migration) | `messages.id` is caller-supplied **text**, not a server-only UUID; `POST …/messages` accepts an optional `id`; `DELETE /v1/messages/{id}` deletes by id alone | Closes the last Stream-parity gap the downstream audit flagged: Stream lets the caller set a message id on send (dedupes a re-send as "already exists") and delete a message by id without channel context, which the downstream app's own thread-policy layer was compensating for. The V006 migration retypes `messages.id` and every referencing column (`parent_message_id`, `reactions.message_id`, `message_flags.message_id`) uuid→text in one step (drop FKs, `uuid::text` cast, re-add FKs), preserving existing rows; server-minted ids stay UUIDv7 strings. Client ids are validated (non-empty, ≤255 chars, no whitespace/control chars → 400). Dedupe is race-safe by construction: the id stays the primary key, so a duplicate surfaces as the unique-violation mapped to a **409** whose detail contains "already exists" and the id (consumers pattern-match that phrase). The global delete resolves the channel server-side and mirrors the channel-scoped delete's authorisation exactly (server key, or a member who is the author/moderator); the channel-scoped endpoint stays. Message DTOs already carried `id` as a JSON string, so WS events and the SDK outbox (nonce-based, no id parsing) are unaffected |
| Stream-alias webhook headers (added 26/07/2026, downstream migration) | Each delivery sends getstream.io's header names *in addition to* the Firemoot ones: `X-Signature`, `X-Webhook-Id`, `X-Webhook-Attempt`, `X-Api-Key` | Stream's `verifyWebhook(rawBody, xSignature)` compares against `hex(HMAC-SHA256(secret, rawBody))` with **no** scheme prefix (verified against the stream-chat SDK's `verifySignature`), so `X-Signature` deliberately omits the `sha256=` that `X-Firemoot-Signature` carries - the two headers are the same digest in two encodings. A migrating app therefore keeps its existing handler and changes only config. The Firemoot headers are untouched, so existing consumers are unaffected. `X-Webhook-Id` is the delivery id (stable across retries, as Stream's is) and `X-Webhook-Attempt` counts from 1. `X-Api-Key` is the server API key id (`FIREMOOT_API_KEY_ID`) and is informational: Firemoot signs with the *endpoint's* secret, not the API key's, so it must not be used to select the verification key |

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
- The same delivery also carries getstream.io's header names - `X-Signature` (the
  bare hex digest, no `sha256=` prefix), `X-Webhook-Id`, `X-Webhook-Attempt`,
  `X-Api-Key` - so a handler written against Stream ports by config alone.
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
  tokens. The dogfood target: the downstream app's Playwright suite.
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
- Dogfood: `@firemoot/test` used by Firemoot's own SDK tests, then by the downstream
  app.
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
- **v1.0 gate.** The downstream app's messaging E2E suite passes against Firemoot in CI
  with zero spec changes beyond configuration. That is the definition of done.

## 15. Open Questions

1. ~~skunk vs doobie (settle in M0 spike; skunk default).~~ **Resolved 10/06/2026:
   skunk** (ADR 0001) - native `LISTEN`/`NOTIFY` for the backplane is decisive.
2. ~~Channel-type permission model: how much of Stream's role/permission matrix to
   replicate vs a simpler owner/moderator/member fixed set (v1 leans simple).~~
   **Resolved 10/06/2026 (M1.3): the simple fixed set** - owner/moderator/member,
   validated server-side; client-auth REST (M4.3) enforces it per operation.
3. ~~Admin UI auth: local password only, or optional OIDC from day one?~~
   **Resolved 10/06/2026 (M3.4): local password only in v1** (Argon2id, no
   default - locked until set); OIDC is v1.x.
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
