# Migrating from Stream

Firemoot deliberately mirrors the Stream Chat model - your backend mints user
tokens, the browser talks directly to the chat backend, and the backend
authorises each operation. Most of a migration is renaming, not rearchitecting.

## Two routes

There are two ways to do this, and it is worth picking deliberately.

**The drop-in adapter.** [`@firemoot/stream-compat`](./stream-compat) exposes the
`stream-chat` API - a class called `StreamChat`, the same methods, the same return
shapes - over Firemoot. Alias the package in your bundler and the only thing that
changes is configuration. It is the fastest route, and the rest of this page still
tells you what differs underneath.

**Porting the seam.** Reimplement your adapter modules over `@firemoot/client`
directly, as described below. More work up front; you end up with a smaller
dependency and the real API. These mix freely - adopt the adapter, then port
screen by screen if and when it is worth it.

## The compatibility philosophy

`@firemoot/client` itself is not a drop-in replacement for the `stream-chat`
package and does not pretend to be - that is what the adapter above is for. Its
compatibility is at the level of the **model**, not the symbols: the same auth
topology, the same channel/member/message/reaction shape, the same `type:id`
channel ids, the same "watch a channel and get an event stream" contract. Where a
Stream name was free to keep - `watch()`, `keystroke()`, `stopTyping()`,
`markRead()`, `notification.added_to_channel` - it was kept. Where keeping it
would have meant carrying Stream's wire conventions into a new API, it was not.

The practical consequence is that migration cost tracks how well your app is
insulated from the SDK. In most codebases the chat client is wrapped in one or
two adapter modules (a browser one and a server one) and the rest of the app
consumes those wrappers plus `channel.state`. Reimplement the two adapters over
`@firemoot/client`, keep their export signatures, and the call sites do not move.

If you use `stream-chat-react`, note that Firemoot ships no UI library. Your
components stay yours - which is either the easy part or the whole project,
depending on how much of the rendering you had delegated.

## What maps 1:1

| Stream | Firemoot |
| --- | --- |
| `connectUser(user, tokenProvider)` | `new FiremootClient({ tokenProvider }).connect()` |
| `channel.watch()` → `channel.state.messages` | `channel.watch()` → `channel.messages` |
| `keystroke()` / `stopTyping()` | `keystroke()` / `stopTyping()` |
| `markRead()` | `markRead()` |
| `sendReaction()` / `deleteReaction()` | `react()` / `removeReaction()` |
| `queryChannels(filter, sort, { watch })` | `queryChannels(filter, { watch: true })` |
| keyset paging on `last_message_at` | `ChannelCursor` keyset paging |
| channel ids (`type:id`) | identical (`type:id` cids) |
| server `sendMessage({ type, user_id, custom })` | `SendMessageRequest` (same capability) |

`queryChannels(filter, { watch: true })` keeps Stream's semantics: each result
comes back hydrated (members, your read state, the latest message) as a `Channel`
handle already subscribed over the socket, and re-subscribed for you across
reconnects.

## Event names

| Stream event | Firemoot |
| --- | --- |
| `message.new` | `message.new` |
| `message.updated` / `message.deleted` | same names |
| `typing.start` / `typing.stop` | same names |
| `notification.added_to_channel` / `removed_from_channel` | same names |
| `message.read` | `read.updated` |
| `notification.mark_read` | `read.updated`, targeted at the reader |
| `connection.changed` / `connection.recovered` | `status` / `connected` / `reconnecting` |
| `notification.message_new` | *(no equivalent - see below)* |
| `notification.mark_unread` | *(no equivalent - there is no mark-unread)* |

`notification.message_new` fired for member channels you were *not* watching.
With `queryChannels({ watch: true })` over a page of channels, the ordinary
`message.new` on watched channels covers the live UX; the gap is only for
channels beyond the page you loaded.

## Field naming

Firemoot uses **camelCase** on the wire (`userId`, `createdAt`, `lastReadSeq`)
where Stream uses snake_case - a mechanical adapter in your wrapper handles it. A
message carries a `userId` rather than an embedded `user` object; resolve names
and avatars from the channel's hydrated `members`.

## The `channel.state` projection

The seam most likely to bite is `channel.state`, which Stream apps read from
everywhere. `@firemoot/client` ships a projection for exactly this:

```ts
import { streamChannelState } from "@firemoot/client";

const state = streamChannelState(channel);
// state.messages          - confirmed by ascending seq, then optimistic sends
// state.members           - keyed by userId
// state.read              - read receipts keyed by userId
// state.unreadCount       - the calling user's badge
// state.last_message_at   - ISO timestamp, or null
```

It is a pure projection over the client's reducer, not a second copy of the
state, so recompute it whenever the channel emits `change` rather than holding
onto the object. Wrapping this is considerably less work than rebuilding a
reducer, and it is unit-tested against hydration plus `read.updated`,
`member.*` and `message.new`.

## Client-supplied message ids

`sendMessage` accepts an optional `id`. Omit it and the server mints a UUIDv7;
supply one and it is used verbatim, which is how you keep your own ids stable
across a migration. Ids are strings (max 255 characters, no whitespace or control
characters) and **globally unique**, not per-channel.

One important difference from an idempotency key: a duplicate id is a **`409`**,
not a replay. Firemoot will not return the original message, so you cannot use it
to make a retry safe - only to assert uniqueness. Handle the `409` explicitly.

## Webhooks

Every delivery carries Stream's header names as well as Firemoot's, so a handler
ported from Stream keeps reading the headers it already reads:

| | Stream | Firemoot sends |
| --- | --- | --- |
| Signature header | `x-signature` | `X-Signature` (bare hex) **and** `X-Firemoot-Signature: sha256=<hex>` |
| Dedupe header | `x-webhook-id` | `X-Webhook-Id` **and** `X-Firemoot-Delivery` |
| Attempt counter | `x-webhook-attempt` | `X-Webhook-Attempt` (counts from 1) |
| Event type header | - | `X-Firemoot-Event` |
| Verification helper | `verifyWebhook()` | `verifyWebhook()` via [`@firemoot/stream-compat`](./stream-compat), or a direct HMAC comparison |

Firemoot signs the raw body with HMAC-SHA256 keyed by the *endpoint's* secret -
not the API secret, which is what Stream uses for this. That is the one thing you
must reconfigure. `X-Signature` carries the bare hex digest Stream's helper
expects; `X-Firemoot-Signature` is the same digest with a `sha256=` prefix. The
[webhooks guide](./webhooks) has the hand-rolled verification code.

The body is Firemoot's envelope (`{ type, cid, seq, data }`, camelCase) rather
than Stream's; the adapter ships `normalizeWebhookEvent()` if your handler reads
Stream's shape.

Note also that there is no per-endpoint event subscription in v1 - every enabled
endpoint gets every deliverable event and filters on `type` at your end.

## Differences to plan for

- **Search** is Postgres full-text with the `simple` configuration: fast and
  injection-safe, but no stemming or typo tolerance. Treat it as lower fidelity
  than a dedicated search engine.
- **Thumbnails** are generated asynchronously. An image upload returns the full
  object URL immediately and the server patches a `thumbUrl` onto the attachment
  a moment later via `message.updated`. Subscribe to `message.updated` to pick it
  up, and fall back to the full image until then. Write the object URL under a
  `url` key in your attachment JSON - that is what the thumbnailer matches.
- **Scheduled "unread reminder" webhooks** have no v1 equivalent. If you relied on
  Stream's `user.unread_message_reminder`, run it as a cron in your own app
  against the read state.
- **`stopWatching` is client-local.** There is no server-side unsubscribe in v1;
  idle watched channels keep streaming until the socket closes. That is bandwidth,
  not correctness.

## Server provisioning

Creating a channel with members, sending system messages as a user, querying by
member, deleting a channel or user - all map directly onto the `FiremootServer`
helpers and the server REST surface. See [authentication](./auth) for how the
server credential differs from the browser's.
