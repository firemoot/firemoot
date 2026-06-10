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
| Scala | 3.3.7 (LTS) | Scala 3.9 becomes the next LTS imminently (JDK 17 floor). Start on 3.3.x; schedule a 3.9 upgrade task at M2 once http4s/tapir/skunk publish for it. Do not adopt Next (3.8.x) |
| JDK | Temurin 21 LTS | Build and runtime baseline. Temurin 25 LTS revisited with the GraalVM stretch goal |
| sbt | 1.12.x | sbt 2.0 is only just graduating RC; plugin ecosystem (native-packager et al.) not ready. Revisit post-v1 |
| http4s | 0.23.34 (Ember) | 1.0 milestones still pre-release; 0.23 is the stable line |
| cats-effect / fs2 | latest 3.x (transitive via http4s/skunk) | |
| tapir | 1.13.x | OpenAPI 3.1 docs + http4s server interpreter |
| skunk | 1.0.0 | Went final (strengthens the spec's "skunk default" - see M0.3 spike) |
| Flyway | latest community (11.x) | JDBC-based: needs the pg JDBC driver *for migrations only*, even if skunk wins |
| munit / scalacheck | munit 1.x, scalacheck 1.18.x | + munit-cats-effect |
| Testcontainers | testcontainers-scala latest | Postgres + MinIO modules |
| PostgreSQL | 17 (compose pin `postgres:17`) | Per spec. PG18 exists; no feature need, stay boring |
| Node (SDK dev) | 22 LTS floor | Spec said "Node 20+" but Node 20 went EOL Apr 2026. SDK supports Node 22+; document this as a spec amendment |
| TS codegen | @hey-api/openapi-ts (pin exact version) | The 2026 standard for OpenAPI→TS SDKs (fetch client). Pre-1.0: pin exact, snapshot-test the output |
| SDK workspace | pnpm workspaces + TypeScript 5.x + Vitest | ESM only |
| Admin SPA | Vite + TS | Chart lib decided in M3 (uPlot vs Chart.js) |
| Config | ciris | Typelevel-native, no reflection (GraalVM-friendly) |
| Logging | structured JSON via logback + logstash-encoder (or scribe) | decide M0.2 |
| Docker base | eclipse-temurin:21-jre | via sbt-native-packager |

---

## 2. Phase 0 - repo scaffolding (pre-M0, half a day)

- [ ] **0.1 sbt skeleton**: single `server` module compiling on Scala 3.3.7; scalafmt
      (`.scalafmt.conf`) + scalafix basics; `.editorconfig`; `project/Dependencies.scala`
      with the §1 pins.
- [ ] **0.2 pnpm workspace skeleton**: `sdk/ts/core`, `sdk/ts/client`, `sdk/test`
      placeholder packages (private, `@firemoot/*` names reserved in package.json but
      not published).
- [ ] **0.3 CI workflow** (`.github/workflows/ci.yml`): jobs for `scala` (compile +
      test), `sdk` (typecheck + test), both cached. Runs on PR + main.
- [ ] **0.4 Branch protection on `main`** - *user task, needs admin*: require CI green,
      no force push.

Acceptance: a PR with a trivial change runs both jobs green.

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

- [ ] **M0.1 HTTP server**: Ember on `:6668`, `/healthz` (liveness) and `/readyz`
      (Postgres ping), ciris config (env-first), graceful shutdown, JSON logs.
- [ ] **M0.2 DB spike - skunk vs doobie** (timebox: 1 day). Build the same vertical
      slice twice: channel insert + seq bump + LISTEN/NOTIFY round-trip + jsonb codec.
      Criteria: LISTEN/NOTIFY ergonomics (skunk is native fs2 - matters for the
      Postgres backplane step later), jsonb codec friction, pool behaviour under
      churn, error diagnostics. skunk 1.0.0 is the default winner unless the spike
      surfaces a blocker. Record the decision in SPEC.md §2.
- [ ] **M0.3 Flyway on boot** + `V001__initial.sql` covering §3 (users, channels,
      channel_members, messages, channel_events, api_keys minimum).
- [ ] **M0.4 tapir foundation**: endpoint definitions module; RFC 9457 problem+json
      error model; `/v1/openapi.json`; first endpoints: `POST /v1/users` (upsert),
      `POST /v1/channels`, `POST /v1/channels/{type}/{id}/messages`. Server-key HMAC
      auth middleware (stub validation acceptable until M1.1).
- [ ] **M0.5 WS gateway**: `GET /v1/ws?token=` upgrade; JWT parse (full verification
      lands M1.1); `hello` frame with `connection_id`, server time, user; server ping
      every 25s, reap after 2 missed pongs; connection registry (in-memory).
- [ ] **M0.6 Backplane trait + in-proc impl**: start with a single global
      `fs2.concurrent.Topic[F, Event]` with per-connection subscription filtering
      (simplest correct thing at single-node scale); per-channel topics are a
      measured optimisation later, not a default.
- [ ] **M0.7 The golden path**: `sendMessage` REST → txn (message + seq + event) →
      backplane publish → subscribed WS connections receive `message.new`.
      `subscribe` client frame accepted with `{cid: last_seen_seq}`; replay from
      `channel_events` then live-stream (full resume semantics hardened in M1.8).
- [ ] **M0.8 TS codegen pipeline**: `@firemoot/core` generated from
      `/v1/openapi.json` via @hey-api/openapi-ts (fetch client); committed to the
      repo; CI job regenerates and fails on `git diff --exit-code` (the drift gate
      from SPEC §10).
- [ ] **M0.9 Compose + boot-speed gate**: `deploy/compose/docker-compose.yml`
      (firemoot + postgres:17); CI job: `docker compose up -d`, assert `/healthz`
      OK in **<15s** from cold (SPEC §12's standing pitch-assertion).
- [ ] **M0.10 Two-tab demo**: minimal static page (server resource, dev-flag-gated)
      that connects, subscribes, sends via REST, renders `message.new`. This is the
      M0 demo artefact, not a product surface.

Exit criteria: all of the above green in CI; demo recorded; skunk/doobie decision
logged in SPEC.md.

---

## 5. M1 - chat core (the long pole)

Ordered so each task ships behind a passing protocol/integration suite.

- [ ] **M1.1 Auth, properly**: HS256 JWT verification (constant-time HMAC, required
      `exp`, ±60s skew), `sub`/`role` claims; server API HMAC request signing
      (key id + signature header, canonical request); per-endpoint authz middleware
      (membership/role checks server-side, no client-asserted identity).
- [ ] **M1.2 Users**: upsert/delete (GDPR hard-delete: erase user row + authored
      message text/custom → tombstone, cascade reactions/memberships; document
      exactly what survives); `last_active_at` debounced writes.
- [ ] **M1.3 Channels**: full CRUD; member add/remove with roles
      (owner/moderator/member fixed set - SPEC open question 2 resolved as "simple");
      frozen (reject sends) and archived (hide from default queries) semantics;
      `channel.updated`/`channel.deleted`, `member.added`/`member.removed`,
      `notification.added_to_channel`/`notification.removed_from_channel` events.
- [ ] **M1.4 Messages, full lifecycle**: edit + soft delete (`message.updated`/
      `message.deleted`); threads (`parent_message_id`, transactional `reply_count`
      denorm); system messages; server "send as user"; attachments as opaque JSONB
      payload (media upload flow is M2).
- [ ] **M1.5 Reactions**: add/remove, `reaction.new`/`reaction.deleted`, per-type
      counts on the message view.
- [ ] **M1.6 Read state**: `markRead` endpoint advancing `last_read_seq`;
      `read.updated` to channel (receipt) + reader's other devices (badge sync);
      total-unread sum in `hello` and on `read.updated`; unread arithmetic
      property-tested per §3 invariant.
- [ ] **M1.7 Typing + presence**: `typing.start`/`typing.stop` WS frames with
      server-side throttle and auto-expiry (no seq, never replayed); presence
      online/offline from connection registry + `last_active_at`,
      `presence.changed` fan-out.
- [ ] **M1.8 Resume, hardened**: re-subscribe with last-seen seqs replays exactly the
      gap then splices into live without loss or duplication (the race between
      replay-read and live-publish is *the* correctness problem here - solve with
      buffer-then-dedupe-by-seq, test with concurrent writes during resume);
      `resync_required` path when the resume point predates event retention.
- [ ] **M1.9 Queries**: filter DSL (`type`, `cid $in`, `members $in`, custom-field
      equality) parsed into parameterised SQL - property-test against injection;
      sort by `last_message_at`; cursor pagination; message history `before_seq`
      pagination; FTS search endpoint (`websearch_to_tsquery`, ranked, documented as
      lower-fidelity).
- [ ] **M1.10 Webhooks**: endpoint registry; enqueue persisted events (+`user.flagged`)
      into `webhook_deliveries`; SKIP LOCKED worker pool; `X-Firemoot-Signature:
      sha256=HMAC(secret, body)`; 5s timeout; retries 1m/5m/30m/2h → dead-letter
      status (admin UI surfacing lands in M3).
- [ ] **M1.11 Moderation**: message flagging endpoint + queue table, `user.flagged`
      webhook event.
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

## 12. Operational checklist (user tasks, before public launch)

- [ ] npm org `@firemoot`
- [ ] Docker Hub org `firemoot`
- [ ] Domains: firemoot.com / .io / .dev / .chat (at minimum .dev for docs +
      security contact)
- [ ] Real disclosure mailbox behind SECURITY.md address
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
