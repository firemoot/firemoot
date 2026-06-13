# Frented → Firemoot migration plan (v1.0 gate)

| | |
|---|---|
| Date | 12/06/2026 |
| Base | `frentedco/frented` `origin/stag` (265b952b); execute in a **git worktree** |
| Goal | `e2e/messaging-core.spec.ts` passes against a local Firemoot via `@firemoot/test`, **zero spec changes beyond configuration**; `enableStream` specs + skip-gate bypasses removed |
| Inputs | The M4.2 audit (`docs/frented-compat-audit.md`) + the `@firemoot/client`/`core`/`test` surface (M4.1, M4.3-M4.6) |

> Drafted by Opus (the Fable advisor was unreachable). Refine on Fable or
> greenlight to execute. Sequenced so Frented stays shippable at every step,
> behind a `CHAT_BACKEND` flag until the E2E flips green.

## Key insight

`lib/stream.ts` and `lib/stream-server.ts` are flat adapter modules with stable
export signatures (`connectUser`, `sendMessage`, `getUserChannels`, `addReaction`,
`sendFileMessage`, `reportMessage`, `startTyping`, …). The ~31 call sites and the
hand-built UI consume those exports plus `channel.state`. **Reimplement the
adapters over `@firemoot/client`, keep the signatures, and the call sites don't
move.** The bulk of the work is one `channel.state`-shaped facade over the
client's `Channel`/reducer.

## PR sequence

### PR 0 — Worktree + SDK wiring (infra, no behaviour change)
- `git worktree add` off `origin/stag`.
- Decide how Frented consumes the firemoot SDK **before it is published** (§12):
  packed tarballs (`pnpm pack` the three packages) committed under `vendor/`, or a
  `file:`/`link:` to a local firemoot checkout. (Recommend tarballs - reproducible
  in CI; swap to the npm versions at launch.)
- Add a `CHAT_BACKEND=stream|firemoot` env flag so every adapter can branch and
  Frented ships unchanged until the cutover.
- **Accept:** build green, flag defaults to `stream`, no behaviour change.

### PR 1 — Server adapter (`lib/stream-server.ts`)
- Reimplement over `FiremootServer` (`createToken`, `upsertUser`,
  `createChannel(req, members)`, `addMembers`) + an HMAC `coreRestApi` for sends
  and queries. Keep export signatures identical.
- `/api/stream/token` route → `upsertUser` then `createToken(userId)`.
- System/booking/concierge sends → server `sendMessage` (`type:"system"`).
- **Accept:** server-side provisioning + token minting work against a local
  Firemoot; existing unit tests green.

### PR 2 — Webhook route
- Replace Stream's `x-signature` + `verifyWebhook` with
  `X-Firemoot-Signature: sha256=<hmac>` verification; dedupe on
  `X-Firemoot-Delivery`.
- Map consumed events (`message.new`, `message.updated`, membership,
  `user.flagged`). `user.unread_message_reminder` has no equivalent - gate it
  behind a TODO/cron (audit §gaps), not v1-blocking.
- **Accept:** a signed Firemoot delivery is accepted; a forged one is rejected.

### PR 3 — Browser adapter (`lib/stream.ts`) — the big one
- `connectUser`/`disconnectUser` → `FiremootClient.connect()`/`disconnect()` with
  the `tokenProvider` (reuse `fetchUserToken`).
- A `channel.state`-shaped facade over `Channel`: `messages`, `read` (badge),
  `members` + read receipts (`readReceipts`), `typing`. This is the riskiest seam
  - build it first and test in isolation.
- `sendMessage`/`markAsRead`/`addReaction`/`deleteReaction`/`startTyping`/
  `endTyping`/`reportMessage` → `Channel` methods (names already match Stream).
- `sendFileMessage` → `Channel.sendFileMessage` (presign→PUT→attach). Write the
  object URL under `url`; subscribe to `message.updated` for the late `thumbUrl`.
- `getUserChannels` → `client.queryChannels(filter, { watch: true })`.
- Event-name shim: `message.read`→`read.updated`, `connection.changed/.recovered`
  →`status`/`connected`/`reconnecting`.
- Drop the dead `window.StreamChat` hook and `StreamChat.getInstance` plumbing.
- **Accept:** the messaging UI works end-to-end against a local Firemoot in dev.

### PR 4 — Helpers + dependency cleanup
- `stream-eligibility.ts`/`stream-gate*.ts`/`stream-user-id.ts`: with Firemoot
  always-on, collapse the "is Stream enabled" gating (or hard-wire it on under the
  flag). `stream-user-id.ts` likely stays (id mapping).
- Remove `stream-chat` and the **unused** `stream-chat-react` from `package.json`.
- Confirm `concierge-stream-client.tsx` rides the new browser adapter.
- **Accept:** no `stream-chat` imports remain; typecheck/lint green.

### PR 5 — E2E gate (the definition of done)
- Boot a local Firemoot in the Playwright run via `@firemoot/test`
  (`startFiremoot` + `seed`) in global setup (or a compose service), and point the
  app's chat env (`CHAT_BACKEND=firemoot` + base URLs/secret) at it.
- Seed the fixtures `messaging-core.spec.ts` expects (users, channels, members).
- Make the spec pass with **zero changes beyond configuration**; delete the
  `enableStream` specs and any skip-gate bypasses.
- **Accept:** `pnpm test:e2e:file e2e/messaging-core.spec.ts` green against
  Firemoot; flip `CHAT_BACKEND` default to `firemoot`.

## PR 3 surface + event map (derived 13/06/2026 - turnkey for the build)

PR 3 is **not** a flat function swap: the app uses a StreamChat **client** facade
*and* a **channel** facade. Used surface (counted across `app`/`components`/`hooks`/`lib`):

- **client**: `on` (×15), `channel` (×9), `queryChannels` (×6), `userID` (×6), `user`,
  `connectUser`/`disconnectUser`, `flagMessage`.
- **channel**: `id` (×13), `sendMessage` (×11), `state` (×7, via `streamChannelState`),
  `data` (×5), `watch`, `sendImage`/`sendFile`, plus `on`/`keystroke`/`stopTyping`/
  `markRead`/`sendReaction`/`deleteReaction` (from the adapter).

**Event translation** (`client.on`/`channel.on` subscriptions found in the app →
the facade must synthesise a Stream-shaped event for each; **verify each handler's
read payload against the running E2E - this is the irreducible runtime risk**):

| Stream event (subscribed) | Firemoot source | Shim |
|---|---|---|
| `message.new` | channel `message.new` | direct; handler reads `event.message` |
| `message.read` | channel `read.updated` | rename; reader = `data.userId` |
| `typing.start` / `typing.stop` | channel `typing.start`/`typing.stop` | direct |
| `notification.added_to_channel` / `removed_from_channel` | client same-named (user-targeted) | direct - names already match |
| `notification.message_new` | (none) | covered by watched-channel `message.new` under `watch:true`; residual risk only beyond page 1 (audit: acceptable) |
| `notification.mark_read` | client `read.updated` (targeted to reader) | shim |
| `notification.mark_unread` | (none) | Frented only listens to trigger a refresh - safe no-op |
| `connection.changed` / `connection.recovered` | client `status`/`connected`/`reconnecting` | shim |

Build order: `FiremootChannelFacade` (`.state`=`streamChannelState`, action methods,
translating `.on`) **+ unit tests in isolation** first (verifiable, no app); then the
client facade (`channel`/`queryChannels({watch})`/`on`/`userID`); then redefine
`StreamChannelType` to the used-subset interface so both Stream's `Channel` and the
facade satisfy it (call sites don't move); then branch `connectUser`/`getUserChannels`
on the flag.

## Riskiest seams (verify first)
1. The `channel.state` facade fidelity - read receipts (other members'
   `lastReadSeq`), unread badge, typing, optimistic order. Unit-test it against
   `@firemoot/client` before wiring the UI. **De-risked (13/06/2026):**
   `@firemoot/client` ships `streamChannelState(channel)` - a
   Stream-`channel.state`-shaped projection (`messages`, `members`, per-user
   `read` receipts, `unreadCount`, `last_message_at`) over the M4.4 reducer, with
   unit tests driving hydration + `read.updated`/`member.*`/`message.new` (the
   five `state.*` fields Frented actually reads are all covered). The browser
   adapter (PR 3) wraps this rather than rebuilding the reducer.
2. Pre-publish SDK consumption (tarball vs link) - get it reproducible in CI early.
3. Async thumbnails - the thread view must subscribe to `message.updated`
   (Stream returned `thumb_url` synchronously; Firemoot patches it later).
4. `queryChannels` hydration vs Stream's `channel.state` (members + caller read +
   latest message are covered by M4.3; confirm the shapes the UI reads).
5. Token-route auth parity (HS256 `sub`/`exp`, role claims if the UI uses them).
