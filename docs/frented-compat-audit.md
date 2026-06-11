# Frented compatibility audit (M4.2)

| | |
|---|---|
| Date | 11/06/2026 |
| Input | Every `stream-chat` call site in Frented (28 files, ~4.5k lines) + `e2e/messaging-core.spec.ts` |
| Purpose | Scope M4.3 (client-authenticated REST) and M4.4 (SDK gap-close) precisely; seed the Stream→Firemoot migration notes (M4.9) |

## Headline findings

1. **The browser talks to Stream directly.** `lib/stream.ts` is client-side-only;
   the user's JWT (minted by Frented's own `/api/stream/token` route - exactly
   Firemoot's auth model) authorises sends, reads, reactions, queries, uploads,
   flags and search from the browser. Firemoot's REST is HMAC-server-only today,
   so **M4.3 is mandatory** for the v1.0 gate.
2. **`stream-chat-react` is an unused dependency** - zero imports. Frented's
   messaging UI is hand-built on the core data client. No UI library to replace.
3. **The Playwright gate spec never touches the SDK.** `messaging-core.spec.ts`
   (22 tests) drives the UI via `page` + Frented's own `testApi` fixture; the
   `window.StreamChat` hooks in `lib/stream.ts` were for the retired Cypress
   suite. "Zero spec changes beyond configuration" is genuinely achievable.
4. **Channel-state hydration is the biggest non-auth server gap.** Frented leans
   on Stream returning, per channel: members, the caller's read state
   (`unreadCount`), other members' read positions (read receipts), and recent
   messages (conversation previews) - all in the `queryChannels`/`watch`
   response. Firemoot's `ChannelPage`/`GET channel` return bare channel rows.

## M4.3 scope - client-authenticated endpoints (from actual browser call sites)

JWT bearer (`Authorization: Bearer <user JWT>` - the tokens the WS gateway
already verifies). Authz server-side per op; never trust client filters.

| Endpoint | Authz rule | Frented call site |
|---|---|---|
| `POST .../messages` (send) | member; frozen ⇒ 409; `userId` forced to token sub | `useStreamThread.sendMessage` |
| `GET .../messages` (history) | member | `channel.watch()` state load |
| `GET /v1/channels/{type}/{id}` | member; response needs hydration (below) | `channel.watch()` |
| `POST /v1/channels/query` | filter forcibly scoped to `members ∋ caller` | `getUserChannels`, `useStreamChannels` |
| `POST .../read` (markRead) | member; self only | `markAsRead` |
| `POST .../reactions` / `DELETE .../reactions/{type}/{userId}` | member; delete self-only | `addReaction` / `deleteReaction` |
| `POST /v1/search` | scope to caller's member channels server-side | `ConversationList` `client.search(...)` |
| `POST /v1/uploads` | authenticated; per-user rate limit exists (M2.4) | `sendImage`/`sendFile` in `useStreamThread` |
| `POST .../flag` | member; `flaggedBy` forced to token sub | `reportMessage` |
| `PATCH`/`DELETE .../messages/{id}` (edit/delete own) | author-only (moderator/owner override) | *not used by Frented's browser - include anyway: same authz machinery, Stream parity* |

**Hydration (same milestone - same endpoints):** the client-auth variants of
`GET channel` and `channels/query` must return, per channel:
`members` (userId, role, **lastReadSeq** - drives read receipts via
`channel.state.read`), the **caller's** `{lastReadSeq, unreadCount}`
(`state.unreadCount`, badge logic), and the **latest message** (conversation
preview). All of this exists in the schema; it is response-shape work.
Freebie while there: set a `Retry-After` header on 429s (Frented's retry helper
reads it).

## M4.4 scope - SDK gap-close (confirmed against usage)

| Gap | Frented usage |
|---|---|
| `FiremootClient.queryChannels(filter, {watch})` - `watch: true` subscribes each result over WS (Stream semantics) | both channel-list paths use `watch: true` |
| Expose `removeReaction` on `Channel` (rest method exists) | `deleteReaction` |
| `createToken(userId, expiresAt)` server helper (HS256 mint; pairs with `createHmacAuthorizer`) | `generateUserToken` in `/api/stream/token` |
| `upsertUser` server helper | token route pre-upserts the user |
| Channel create with members (SDK wraps create + addMembers) + `addMembers` batch helper | `ensure-listing-guest-thread-channel`, `syncConciergeChannelMembers` |
| Client reducer: keep **other** members' `lastReadSeq` from `read.updated` (today only self is stored) | `messageReadReceipt.ts` read receipts |
| `Channel.sendFileMessage(file)` convenience (presign → PUT → attach) | `sendFileMessage` |

## Mappings that need no work (adapter-level renames in Frented's migration)

| Stream | Firemoot | Note |
|---|---|---|
| `connectUser(user, tokenProvider)` | `FiremootClient({tokenProvider}).connect()` | identical token-refresh model |
| `channel.watch()` → `state.messages` | `channel.watch()` → `messages` | same name |
| `keystroke()` / `stopTyping()` | `keystroke()` / `stopTyping()` | same names |
| `message.read` event | `read.updated` | rename |
| `notification.added_to_channel` / `removed_from_channel` | same names | identical |
| `connection.changed/.recovered` | `status` / `connected` events | adapter |
| `sendMessage({type:"system", user_id, custom})` (server) | `SendMessageRequest{type:"system", userId, custom}` | identical capability |
| webhook `x-signature` + `verifyWebhook` | `X-Firemoot-Signature: sha256=hmac` | + `X-Firemoot-Delivery` ≙ `x-webhook-id` dedupe |
| `queryChannels` keyset paging on `last_message_at` | `ChannelCursor` keyset | same design |
| caller-supplied channel ids (`gl_<property>_<guest>`) | `type:id` cids | identical |
| snake_case fields (`created_at`, `user_id`, `mime_type`...) | camelCase | mechanical adapter |
| message `user` object | `userId` only | resolve names/avatars from hydrated members |

## Gaps without v1 equivalents (documented, non-gate-blocking)

- **`user.unread_message_reminder` webhook** (Stream's scheduled nudge; Frented
  uses it for WhatsApp reminders). Not in the gate spec. Options: v1.x Firemoot
  feature (worker scanning read state), or Frented-side cron. Decide at
  migration time.
- **`notification.message_new`** (events for *non-watched* member channels).
  With `watch: true` on the 50-channel list page, watched-channel `message.new`
  covers the live UX; residual risk only beyond page 1. Acceptable.
- **`notification.mark_unread`**: Firemoot has no mark-unread. Frented only
  listens (refresh trigger); no-op.
- **Upload thumbnails**: Stream returns `thumb_url` synchronously; Firemoot
  patches `thumbUrl` async via `message.updated`. Frented falls back to the
  full image, but its thread hook must subscribe to `message.updated` (it
  doesn't today) to pick up late thumbs. Also Firemoot's thumbnailer matches
  attachments by a `url` key - Frented writes `image_url`/`asset_url`; the
  migration should write `url` (and read `thumbUrl`) in its attachment shape,
  which is opaque JSONB either way.
- **No server-side unsubscribe** (`stopWatching` is client-local in Firemoot);
  idle watched channels keep streaming until disconnect. Bandwidth, not
  correctness. v1.x candidate.
- **Search fidelity**: `simple`-config FTS (no stemming) vs Stream's search;
  already documented as lower-fidelity (M1.9 decision).

## Frented-side migration shape (informs M4.9 docs, not Firemoot scope)

`lib/stream.ts` / `lib/stream-server.ts` are already the only two touch points
with the SDK proper; the rest of the app consumes their wrappers plus
`channel.state`. The migration is: reimplement those two adapters over
`@firemoot/client` (+ a `channel.state`-shaped view over `ChannelState`), keep
the attachment JSON shape, drop the dead `stream-chat-react` dependency, and
swap the webhook route's signature check. The booking/concierge server libs use
only: create channel + members, send (system) messages as users, query by
member/id, delete channel/user - all existing Firemoot server surface.
