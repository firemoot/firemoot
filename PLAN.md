# Firemoot Build Plan

| | |
|---|---|
| Status | Living document, derived from SPEC.md v0.1 |
| Date | 10/06/2026 |
| Authority | SPEC.md wins on *what*; this document wins on *how* and *in what order* |

How to use this document: each milestone is broken into ordered, individually
shippable tasks with acceptance criteria. An executing session should work one task
at a time, keep CI green, and tick the checkbox in this file as part of the change
that completes it. Where this plan says "decide here", the decision should be made
during that task and recorded in SPEC.md's Decisions Log.

---

## 1. Toolchain (pinned 10/06/2026 - verify before first use, bump deliberately)

| Component | Pin | Notes |
|---|---|---|
| Toolchain mgr | mise (`mise.toml`) | Single source of truth for JDK/sbt/node/pnpm; CI consumes the same file via `jdx/mise-action`, so local and CI never drift. pnpm uses the `npm:` backend - the aqua registry mis-packages its linux-x64 asset |
| Scala | 3.3.7 (LTS) | Scala 3.9 becomes the next LTS imminently (JDK 17 floor). Start on 3.3.x; schedule a 3.9 upgrade task at M2 once http4s/tapir/skunk publish for it. Do not adopt Next (3.8.x) |
| JDK | Temurin 25 LTS (25.0.3) | Runs Scala 3.3 LTS (3.3.6+) and sbt 1.12 fine. Compact Object Headers (JEP 519) trim ~10-22% heap - directly serving the RSS/$7-VPS pitch - via `-XX:+UseCompactObjectHeaders` with **G1, not ZGC**. 3.3 LTS emits JDK 8 bytecode; we never need 25-targeted output (which 3.3 LTS can't emit anyway) |
| sbt | 1.12.11 | >=1.12.7 fixes CVE-2026-32948. sbt 2.0 only just graduating RC; plugin ecosystem (native-packager et al.) not ready. Revisit post-v1 |
| http4s | 0.23.34 (Ember) | 1.0 milestones still pre-release; 0.23 is the stable line |
| cats-effect / fs2 | 3.7.0 / 3.13.0 (also transitive) | |
| tapir | 1.13.19 | OpenAPI 3.1 docs + http4s server interpreter; openapi-circe (sttp-apispec 0.11.10) serialises the spec |
| skunk | 1.0.0 | Went final (strengthens the spec's "skunk default" - see M0.3 spike) |
| Flyway | 12.8.1 (community) | flyway-core + flyway-database-postgresql; needs the pg JDBC driver *for migrations only*, even though skunk is the app driver |
| munit / scalacheck | munit 1.3.3, scalacheck 1.19.0 | + munit-cats-effect 2.2.0 |
| Testcontainers | testcontainers-scala 0.44.1 | munit + postgresql modules (MinIO via GenericContainer at M2) |
| PostgreSQL | 17 (compose pin `postgres:17`) | Per spec. PG18 exists; no feature need, stay boring |
| Node (SDK dev) | 22.22.3 (22 LTS floor) | Spec said "Node 20+" but Node 20 went EOL Apr 2026. SDK supports Node 22+ (spec amended §10) |
| TS codegen | @hey-api/openapi-ts 0.98.2 (exact) | The 2026 standard for OpenAPI→TS SDKs (fetch client). Pre-1.0: pin exact, snapshot-test the output. Added at M0.8 |
| SDK workspace | pnpm 11 workspaces + TypeScript 6.0.3 + Vitest 4 + Prettier | ESM only (NodeNext resolution); prettier owns code formatting (markdown excluded - hand-maintained) |
| Admin SPA | Vite + TS | Chart lib decided in M3 (uPlot vs Chart.js) |
| Config | ciris 3.15.0 | Typelevel-native, no reflection (GraalVM-friendly) |
| Logging | log4cats-slf4j + logback + logstash-encoder | JSON structured logs (M0.2 decision settled: log4cats over scribe for cats-effect integration) |
| Docker base | eclipse-temurin:25-jre | via sbt-native-packager; runtime flags `-XX:+UseCompactObjectHeaders -XX:+UseG1GC -XX:MaxRAMPercentage=75` |

---

## 2. Phase 0 - repo scaffolding (pre-M0, half a day)

- [x] **0.1 sbt skeleton**: `server` module compiling on Scala 3.3.7 (JDK 25);
      `.scalafmt.conf` (with the meta-build dialect override - see note below),
      `.editorconfig`, `project/Dependencies.scala` with the §1 pins, trivial
      `Main` + passing `SmokeSuite`. Whole M0 dependency graph resolves on JDK 25.
- [x] **0.2 pnpm workspace skeleton**: `sdk/ts/core`, `sdk/ts/client`, `sdk/test`
      placeholder packages (private, `@firemoot/*` names reserved, not published);
      TS 6 / NodeNext, Vitest smoke test per package, typecheck + build green.
- [x] **0.3 CI workflow** (`.github/workflows/ci.yml`): `scala` job (scalafmt check
      + test) and `sdk` job (prettier + typecheck + test + build), both via
      `jdx/mise-action` with Coursier and pnpm-store caches. Runs on PR + main.
- [x] **0.4 Branch protection on `main`**: force pushes and deletion blocked
      (done 10/06/2026); extend with required status checks once the CI workflow
      has reported once on a PR.

Acceptance: a PR with a trivial change runs both jobs green.

> **Gotcha banked (0.1):** scalafmt's `rewrite.scala3.removeOptionalBraces` will
> rewrite `project/*.scala` and `*.sbt` into brace-less Scala 3 syntax, which the
> sbt **meta-build** (compiled as Scala 2.13/source3) cannot parse - the build then
> fails to load. `.scalafmt.conf` pins those paths to the `scala213source3` dialect
> with the rewrite disabled via `fileOverride`. Don't remove that override.

---

## 3. Data model (initial schema, refined during M0/M1)

Single Flyway-managed schema. All ids are caller-supplied text for users (Stream
parity) and `type:id` cids for channels; message ids are UUIDv7 (time-ordered).

```
users             id text PK, name, image, role, custom jsonb, created_at,
                  updated_at, deleted_at, last_active_at
channels          cid text PK ("type:id"), type text, id text, created_by,
                  custom jsonb, frozen bool, archived bool, current_seq bigint
                  NOT NULL DEFAULT 0, last_message_at, created_at, updated_at,
                  deleted_at
channel_members   cid FK, user_id FK, role text CHECK (owner|moderator|member),
                  last_read_seq bigint NOT NULL DEFAULT 0, created_at
                  PK (cid, user_id)
messages          id uuid PK, cid FK, seq bigint, user_id, type (regular|system),
                  text, custom jsonb, attachments jsonb, parent_message_id
                  uuid NULL, reply_count int NOT NULL DEFAULT 0 (denormalised),
                  created_at, updated_at, deleted_at
                  UNIQUE (cid, seq); GIN index on generated tsvector(text) for FTS
reactions         message_id FK, user_id, type text, created_at
                  PK (message_id, user_id, type)
channel_events    cid FK, seq bigint, type text, payload jsonb, created_at
                  PK (cid, seq)        -- the replay log for WS resume
api_keys          id text PK, secret text, created_at, revoked_at
webhook_endpoints id, url, secret, enabled, created_at
webhook_deliveries id, endpoint_id FK, event jsonb, attempts int, status,
                  next_attempt_at, last_error, created_at
                  -- consumed FOR UPDATE SKIP LOCKED; terminal rows = dead letter
uploads           id uuid PK, user_id, object_key, mime, size_bytes, status
                  (pending|stored|thumbnailed), created_at
activity_facts    day date, user_id, kind  -- raw MAU/DAU facts, pruned post-rollup
metrics_hourly    ts, metric, labels jsonb, value
metrics_daily     day, metric, labels jsonb, value
settings          key text PK, value jsonb  -- admin password hash, install metadata
```

Core invariants (property-test these):

- **Seq allocation**: `UPDATE channels SET current_seq = current_seq + 1 WHERE cid=$1
  RETURNING current_seq` in the *same transaction* as the message insert and
  `channel_events` insert. Per-channel seqs are gapless and monotonic under
  concurrency; `UNIQUE (cid, seq)` is the backstop.
- **Replay**: `channel_events` stores the full wire payload so resume is a dumb
  `SELECT ... WHERE cid=$1 AND seq>$2 ORDER BY seq`. Event retention defines the
  resume window (default 7 days; older resume points get a `resync_required` response
  telling the client to re-query the channel).
- **Unread**: count of messages with `seq > last_read_seq AND user_id <> me AND
  type <> 'system' AND deleted_at IS NULL`.

---

## 4. M0 - walking skeleton (riskiest seams first)

Goal (from SPEC §14): compose boots app+Postgres; WS `hello`; REST `sendMessage` →
`message.new` in two browser tabs; tapir spec generating `@firemoot/core`; DB library
settled.

- [x] **M0.1 HTTP server**: Ember on `:6668`, `/healthz` (liveness) and `/readyz`
      (Postgres ping via skunk `select 1`), ciris env-first config, 5s graceful
      shutdown, logback+logstash JSON logs. Tested unit + Testcontainers.
- [x] **M0.2 DB decision - skunk** (ADR 0001). The decisive factor is native
      `LISTEN`/`NOTIFY` as fs2 streams for the future backplane; doobie/JDBC has no
      async notifications. Building the slice twice was judged wasteful given how
      one-sided that is - M0.1 already validates skunk end to end. Recorded in
      SPEC.md §15 and `docs/decisions/0001-database-library.md`.
- [ ] **M0.3 Flyway on boot** + `V001__initial.sql` covering §3 (users, channels,
      channel_members, messages, channel_events, api_keys minimum).
- [x] **M0.4 tapir foundation**: `ApiEndpoints` module; RFC 9457 `Problem` model
      (status derived from body; media type tightened in M1.1); `/v1/openapi.json`
      served; `POST /v1/users`, `POST /v1/channels`, `POST /v1/channels/{type}/{id}/messages`.
      Domain models + skunk codecs/repos; seq-allocating message transaction
      (seq + message + `channel_events` in one commit). Server-key auth via
      `X-Firemoot-Key` header (stub: key-id equality; HMAC in M1.1). Testcontainers
      suite covers the happy path, seq increment and 401.
- [x] **M0.5 WS gateway**: `GET /v1/ws?token=` upgrade (with `?user=` dev fallback
      until token minting in M1.1); JWT `sub` parsed without verification (M1.1);
      `hello` frame with connection id, server time, user; protocol ping every 25s
      and a watchdog that reaps after ~2 missed pongs; JSON ping→pong; in-memory
      `ConnectionRegistry`.
- [x] **M0.6 Backplane trait + in-proc impl**: `Backplane` trait
      (`publish`/`subscribe`); in-process `Topic[IO, Event]` impl. Per-connection
      filtering by subscribed cid happens in the WS handler (M0.5/M0.7). Tested:
      published events reach an active subscriber in order.
- [x] **M0.7 The golden path**: `sendMessage` REST → txn (message + seq + event) →
      `backplane.publish` after commit → subscribed WS connections receive
      `message.new`. `subscribe` frame with `{cid: last_seen_seq}`; replays
      `channel_events` then live-streams (full resume hardened in M1.8). Proven by
      an end-to-end Testcontainers suite: real Ember server + JDK WS client, REST
      send arrives as a `message.new` frame.
- [x] **M0.8 TS codegen pipeline**: `OpenApiExport` writes `openapi.json` from the
      tapir endpoints (pure, no server boot); `@firemoot/core` generated from it via
      @hey-api/openapi-ts (fetch client + types + sdk), committed under
      `src/generated`. `pnpm codegen` regenerates both; the CI `codegen` job
      regenerates and fails on any staged drift (SPEC §10). `exactOptionalPropertyTypes`
      is relaxed for the generated package only.
- [x] **M0.9 Compose + boot-speed gate**: Docker image via sbt-native-packager
      (`eclipse-temurin:25-jre`, JDK 25 RSS flags baked in); `deploy/compose/docker-compose.yml`
      (firemoot + postgres:17, postgres healthcheck gating the app). CI `boot` job
      builds the image, `docker compose up`, polls `/healthz`, and fails if cold
      start to healthy is >=15s. Locally observed healthy in ~4s.
- [x] **M0.10 Two-tab demo**: static `demo.html` served at `/demo`, gated by
      `FIREMOOT_DEV_DEMO` (off by default; overridable in compose). Seeds user +
      channel, connects WS, subscribes, sends via REST, renders `message.new`.
      Verified in-container: 200 with the flag on, 404 off.

Exit criteria (all met 10/06/2026): every task green in CI; the two-tab demo
serves and streams; skunk decision logged (ADR 0001). **M0 walking skeleton
complete** - tapir->codegen and fs2 WS fan-out, the two riskiest seams, both proven.

---

## 5. M1 - chat core (the long pole)

Ordered so each task ships behind a passing protocol/integration suite.

- [x] **M1.1 Auth, properly**: `JwtAuth` HS256 verification (jwt-scala's
      constant-time compare, required `exp`, ±60s leeway), `sub`/`role` claims -
      used by the WS gateway (`?user=` now dev-only). `HmacSigner` + `ServerHmacAuth`
      middleware sign+verify server requests over a canonical
      `method\npath\ntimestamp\nsha256(body)` (constant-time, ±300s window), keyed
      by `ApiKeys` (config bootstrap key; DB keys in M3). Errors are
      `application/problem+json` (RFC 9457). Membership/role authz isn't needed yet
      (current surface is all server-trusted); it lands with client-authenticated
      endpoints. Unit + integration tested; tapir `securityIn` removed (SDK regenerated).
- [x] **M1.2 Users**: `DELETE /v1/users/{id}` GDPR hard-delete (one txn: scrub
      authored messages' text/custom + tombstone them, then delete the user -
      cascading memberships and reactions, nulling `user_id` on the messages).
      **Survives:** message rows (seq, reply_count, created_at, thread structure)
      with content erased. **Removed:** user row, memberships, reactions. 204 on
      success, 404 if absent. `last_active_at` via `LastActiveTracker` (in-memory
      per-user debounce, touched on WS connect). Unit + Testcontainers tested.
- [x] **M1.3 Channels**: GET/PATCH/DELETE channel + POST/DELETE members
      (owner/moderator/member fixed set, validated -> 400; SPEC open question 2
      resolved "simple"); frozen rejects sends (409), archived flag stored (queries
      respect it in M1.9). All ops emit seq'd channel events via `ChannelEvents`
      (`channel.updated`/`channel.deleted`, `member.added`/`member.removed`) reusing
      cid-filtered WS delivery + replay. `notification.added_to_channel`/
      `removed_from_channel` are **user-targeted** (new `Event.target`; WS live filter
      delivers to the target user or to cid subscribers) - live-only, not persisted.
      Service + endpoint + targeted-delivery tests. 26 green.
- [x] **M1.4 Messages, full lifecycle**: `PATCH`/`DELETE`
      `/v1/channels/{type}/{id}/messages/{messageId}` -> seq'd `message.updated` /
      `message.deleted` (soft delete scrubs text). Threads:
      `reply_count` incremented on reply send and decremented on reply delete, in
      the send/delete transaction. System messages via `type` (validated
      regular/system -> 400). Send-as-user (`userId`) and opaque JSONB attachments
      already plumbed. Service + endpoint tests (incl. reply_count, events). 28 green.
- [x] **M1.5 Reactions**: `POST`/`DELETE`
      `/v1/channels/{type}/{id}/messages/{messageId}/reactions[/{type}/{userId}]`
      (idempotent add, no-op remove). Each real change emits a seq'd
      `reaction.new`/`reaction.deleted` carrying the per-type counts, and the
      endpoints return the updated `ReactionSummary` (per-type counts). 404 if the
      message is absent. The remove user is a path segment (so the HMAC signature,
      which covers the path not the query, binds it). Service + endpoint tests. 30 green.
- [x] **M1.6 Read state**: `POST /v1/channels/{type}/{id}/read` advances
      `last_read_seq` (greatest, never rewinds; defaults to current seq).
      `read.updated` emitted both as a channel broadcast (receipt) and targeted to
      the reader (badge sync to all their devices); carries per-channel
      `unreadCount` + `totalUnread`. `hello` now carries `totalUnread`. Unread SQL
      excludes own/system/deleted (`is distinct from cm.user_id`). Unread arithmetic
      is a pure `domain.Unread` spec, **scalacheck property-tested** and confirmed
      against the SQL in an integration test. 404 for missing channel/membership.
      36 green.
- [x] **M1.7 Typing + presence**: `typing.start`/`typing.stop` WS frames are
      ephemeral - published straight to the backplane (seq 0, never persisted to
      `channel_events`, never replayed) and delivered to the channel's current
      subscribers. A per-connection `TypingTracker` throttles re-broadcasts and
      auto-expires a stuck indicator via generation-tagged timers (a superseded
      timer no-ops, so no fiber cancellation is needed); a connection may only
      signal typing for a channel it is watching. Presence: `ConnectionRegistry`
      now counts connections per user, so the gateway detects online (first
      connection) / offline (last connection) transitions; `PresenceService` fans
      `presence.changed` out to the user's co-members (everyone sharing a channel)
      as user-directed events, stamping `last_active_at` on disconnect. Unit
      (registry transitions, typing throttle/expiry/refresh) + Testcontainers
      (co-member fan-out) + end-to-end two-socket WS suite. 44 green.
- [x] **M1.8 Resume, hardened**: a re-subscribe enters a per-channel *replaying*
      phase (`ResumeBuffer`) **before** the gap is read, so live persisted events
      that land during the replay query are buffered rather than delivered - they
      can't race ahead and either suppress an earlier replay event (loss) or
      duplicate one. The gap is replayed, then the buffer is flushed in seq order
      and deduped by a high-water seq; the flush re-checks the buffer between
      drains so events arriving mid-flush are caught before the live phase begins,
      making the handoff atomic (no reordering). Ephemeral events (seq 0, typing)
      bypass the buffer. A resume point older than the retained events (`min(seq)`
      gap) yields `resync_required`. Tested: deterministic race/dedupe/ordering +
      a 60-event concurrent flush-vs-live property in `ResumeBufferSuite`, and an
      end-to-end suite firing writes during re-subscribe (gapless, unique,
      ordered) plus the resync path. 51 green.
- [x] **M1.9 Queries**: `POST /v1/channels/query` filters on `type`, `cids $in`,
      `members $in`, `custom` (jsonb `@>` containment) and `archived`, sorts by
      most-recent activity (`coalesce(last_message_at, created_at)`) and pages by a
      keyset cursor. **Injection-safe by construction**: every value is a bound
      parameter and the statement shape is fixed (list filters ride in one jsonb
      array param expanded with `jsonb_array_elements_text`; a NULL param means "no
      constraint"), so there is no dynamic SQL assembly - covered by an adversarial
      literal-matching test. `GET /v1/channels/{type}/{id}/messages?before_seq=&limit=`
      is keyset message history (newest first, excludes deleted). `POST /v1/search`
      is ranked FTS via `websearch_to_tsquery('simple', ...)` against the stored
      `text_search` vector, with an optional cid filter, documented as
      lower-fidelity (no stemming). New tapir endpoints regenerated into
      `@firemoot/core`. 57 green.
- [x] **M1.10 Webhooks**: endpoint registry (`POST`/`GET`/`DELETE /v1/webhooks`,
      signing secret returned once on create). A backplane subscriber fans each
      *persisted* channel event (`target` empty, `seq > 0`) into one
      `webhook_deliveries` row per enabled endpoint (V002 migration). The
      `WebhookDispatcher` worker reaps abandoned claims, claims a batch
      `for update skip locked` (flipping to `processing` with a visibility
      deadline), POSTs each with `X-Firemoot-Signature: sha256=HMAC(secret, body)`
      (+`X-Firemoot-Event`/`-Delivery`) under a 5s timeout; 2xx -> delivered,
      anything else schedules the next retry on the 1m/5m/30m/2h backoff and then
      dead-letters. Tested end-to-end against loopback endpoints: signed fan-out
      delivery, and retry-then-dead-letter on a failing endpoint. `user.flagged`
      enqueues for free once moderation publishes it (M1.11). New endpoints
      regenerated into `@firemoot/core`. 60 green.
- [x] **M1.11 Moderation**: `POST /v1/channels/{type}/{id}/messages/{messageId}/flag`
      records a flag in the `message_flags` queue (V003 migration), capturing the
      message's author, and enqueues a `user.flagged` webhook event directly (so
      external moderation tooling is notified) - deliberately **not** broadcast
      over WebSockets, keeping moderation off the member-facing timeline.
      `GET /v1/moderation/flags?status=` lists the queue (admin UI surfaces it in
      M3). 404 for an absent message. Service + HTTP wiring tests; new endpoints
      regenerated into `@firemoot/core`. 61 green.
- [ ] **M1.12 Rate limiting**: token-bucket per API key and per user behind a
      `RateLimiter` trait (in-memory impl); applied to connects, sends, uploads,
      search.
- [ ] **M1.13 Protocol test suite**: raw-frame WS tests - handshake, heartbeat
      reaping, resume-after-gap, multi-device read sync, subscribe/replay ordering.
      This suite is the protocol's executable spec; grow it with every event type.

Exit criteria: every SPEC §3 "v1 In" line except media/dashboard demonstrably
covered by an integration or protocol test.

---

## 6. M2 - media

- [ ] **M2.1 S3 presigner**: AWS SDK v2 S3 presigner (generic endpoint + path-style
      config; *no vendor admin APIs* - SPEC §7 hard rule). `POST /v1/uploads`
      validates MIME allowlist + size policy (10MB images / 50MB files defaults) →
      presigned PUT + final object URL; `uploads` row tracks lifecycle.
- [ ] **M2.2 Media-disabled mode**: no S3 config → uploads return 501 with a clear
      problem+json body; zero S3 code paths touched at boot.
- [ ] **M2.3 Thumbnailing worker**: in-process queue off `uploads`; longest-edge
      512px (imageio + TwelveMonkeys for format coverage); write-back to store;
      patch `thumb_url` onto the attachment; re-emit `message.updated`.
- [ ] **M2.4 Per-user upload rate limit** (uses M1.12 interface).
- [ ] **M2.5 Compose adds `pgsty/minio`**; Testcontainers S3 integration suite runs
      the full presign→PUT→thumbnail→message.updated loop.
- [ ] **M2.6 Tigris verification on Fly** (manual checklist + docs note) - proves the
      generic-S3 claim against a second implementation.
- [ ] **M2.7 Scala 3.9 LTS upgrade check** (scheduled here deliberately): if
      http4s/tapir/skunk publish for 3.9, take the upgrade; else re-check at M4.

---

## 7. M3 - dashboard and metrics

- [ ] **M3.1 Fact capture**: connection/API activity → `activity_facts`; CCU gauge
      sampled every 60s.
- [ ] **M3.2 Rollup worker**: hourly rollups → `metrics_hourly`; compaction to
      `metrics_daily` after 7 days; raw fact pruning after rollup. MAU = distinct
      users active in trailing 30 days, computed daily; DAU/WAU alongside; messages/
      day with per-channel-type breakdown; storage gauges (media bytes, DB size).
- [ ] **M3.3 Prometheus `/metrics`**: live gauges/counters (prometheus4cats or
      http4s-metrics - decide here). Dashboard must not depend on it.
- [ ] **M3.4 Admin auth**: password set at install (env or CLI), argon2 hash in
      `settings`, session cookie, CSRF token, no default password (SPEC open
      question 3 resolved: local password only in v1; OIDC is v1.x).
- [ ] **M3.5 Admin SPA**: Vite + TS, baked into binary resources at `/admin`;
      charts (90-day default window): MAU/DAU/WAU, CCU p95/max + live now,
      messages/day stacked by type, storage; webhook dead-letter list with replay
      button (closes the M1.10 loop); API key rotation UI.
- [ ] **M3.6 Soak baseline**: k6 WS scenario (N idle + M msg/s) running nightly;
      record memory and p99 delivery latency thresholds as CI regression gates
      (SPEC §12).

---

## 8. M4 - SDK polish, test helper, docs, Fly

- [ ] **M4.1 `@firemoot/client` state layer**: `FiremootClient` (connect lifecycle,
      auto-reconnect with seq resume, token-refresh hook), `Channel` handle (state
      cache, optimistic send with rollback, watch/subscribe, typing throttle,
      read-state tracking), typed event emitter for the §5 event list.
- [ ] **M4.2 Reconnect chaos tests**: TCP proxy (toxiproxy or hand-rolled) dropping/
      delaying mid-stream; assert zero message loss/duplication across reconnects -
      this is the SDK's headline credibility test.
- [ ] **M4.3 `@firemoot/test`**: starts Firemoot (Docker or binary), waits healthy,
      seeds users/channels/messages via server API, returns URLs + tokens; consumed
      first by Firemoot's own SDK test suite (dogfood gate 1).
- [ ] **M4.4 npm publishing**: changesets-based release flow for the three packages;
      `@firemoot/core` regeneration remains a CI drift gate.
- [ ] **M4.5 Fly.io first-class**: `deploy/fly/fly.toml`
      (`min_machines_running = 1`, auto-stop disabled), Fly Postgres/Neon + Tigris
      walkthrough, the idle-stop gotcha documented prominently.
- [ ] **M4.6 Docs site**: docs/ as the source; static site (decide generator here -
      lean default: VitePress); covers quickstart (compose in 5 minutes), auth model,
      protocol reference (generated event list), Stream→Firemoot migration notes,
      sizing guidance, unsupported-hosts framing (serverless apps are *clients*).
- [ ] **M4.7 Caddy reference config** in compose docs (one-stanza TLS + WS upgrade).

---

## 9. v1.0 gate (definition of done)

- [ ] Frented's Playwright messaging E2E suite passes against a local Firemoot via
      `@firemoot/test` **with zero spec changes beyond configuration** (SPEC §14).
- [ ] `enableStream` specs and skip-gate bypasses removed from Frented.
- [ ] Cold-start-to-healthy <15s CI assertion still green (never regressed).
- [ ] SECURITY.md disclosure address is real and monitored.
- [ ] Assets registered (§12 below); repo flipped public; v1.0.0 tagged.

---

## 10. Cross-cutting workstreams (apply throughout)

**Testing pyramid** (SPEC §12): pure domain core → munit+scalacheck property tests
(seq allocation, unread arithmetic, filter DSL, resume splice); Testcontainers
integration per endpoint/event; raw-WS protocol suite (M1.13); k6 soak (M3.6);
SDK chaos (M4.2); downstream dogfood (M4.3 → v1 gate).

**Security posture** (SPEC §13): every milestone PR review checks - authz on every
channel op, constant-time comparisons, generic prod errors behind dev flag, rate
limits on new surfaces. wss/HSTS guidance in docs from M4.

**Observability**: JSON logs from M0.1; request/WS connection metrics from M0;
Prometheus formalised in M3.3.

**Spec hygiene**: decisions made during build (DB lib, chart lib, metrics lib,
docs generator, Node floor) get recorded in SPEC.md §2 in the same PR.

---

## 11. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Resume-splice race (replay vs live publish) is subtle | Treated as a named M1.8 task with dedicated concurrency tests, not an implementation detail |
| Scala 3.9 LTS transition mid-build | Start on 3.3.7; scheduled upgrade checkpoints (M2.7, M4); libraries publish for both lines during overlap |
| @hey-api/openapi-ts pre-1.0 churn | Pin exact version; generated output is committed + snapshot-diffed in CI |
| sbt 2.0 ecosystem churn | Stay on 1.12.x for v1 |
| pgsty/minio bus-factor / rename | Generic-S3-only code (M2.1 hard rule); Tigris verification (M2.6) proves swappability; compose pin by digest |
| JVM RSS vs "$7 VPS" pitch | `-XX:MaxRAMPercentage` defaults in compose; soak job tracks RSS from M3.6; GraalVM native-image remains stretch |
| FTS fidelity expectations | Documented as lower-fidelity from day one (docs task M4.6) |
| Single-maintainer scope creep | SPEC §3 "v1 Out" list is contractual; new ideas go to a v1.x backlog section in SPEC, not the milestones |
| Name squatting before launch | §12 asset registration happens *before* the repo goes public |

---

## 12. Operational checklist (user tasks - deferred until the product is built
and tested locally, but completed before the public flip)

- [ ] npm org `@firemoot`
- [ ] Docker Hub org `firemoot`
- [ ] Domains: **firemoot.com is canonical** (docs, security contact, everything);
      .io / .dev / .chat as defensive registrations
- [ ] Real mailbox behind security@firemoot.com (SECURITY.md address)
- [ ] Branch protection on `firemoot/firemoot` main (Phase 0.4)
- [ ] Trademark decision (SPEC open question 5 - £170 UK filing, user's call)

---

## 13. Working agreements

- Plan/spec evolution on Fable; execution sessions on Opus, working task-by-task
  through this file.
- Commits: imperative mood, scoped (`server:`, `sdk:`, `admin:`, `deploy:`, `docs:`).
  No AI attribution, ever.
- CI is always green on main; the <15s boot gate is never allowed to rot.
- Checkboxes in this file are updated in the same PR that completes the work.
