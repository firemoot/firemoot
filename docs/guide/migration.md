# Migrating from Stream

Firemoot deliberately mirrors the Stream Chat model - your backend mints user
tokens, the browser talks directly to the chat backend, and the backend
authorises each operation. Most of a migration is renaming, not rearchitecting.

## The shape of the move

Stream's client surface is wrapped in one or two adapter modules in most apps
(`lib/stream.ts` and a server counterpart). Reimplement those two adapters over
`@firemoot/client` and `@firemoot/server`, keep your UI, and you are most of the
way there. If you used `stream-chat-react`, note Firemoot ships no UI library -
your components stay yours.

## Direct mappings

| Stream | Firemoot |
| --- | --- |
| `connectUser(user, tokenProvider)` | `new FiremootClient({ tokenProvider }).connect()` |
| `channel.watch()` → `channel.state.messages` | `channel.watch()` → `channel.messages` |
| `keystroke()` / `stopTyping()` | `keystroke()` / `stopTyping()` |
| `markRead()` | `markRead()` |
| `message.read` event | `read.updated` event |
| `notification.added_to_channel` / `removed_from_channel` | same names |
| `connection.changed` / `.recovered` | `status` / `connected` / `reconnecting` events |
| `queryChannels(filter, sort, { watch })` | `queryChannels(filter, { watch: true })` |
| keyset paging on `last_message_at` | `ChannelCursor` keyset paging |
| channel ids (`type:id`) | identical (`type:id` cids) |
| server `sendMessage({ type, user_id, custom })` | `SendMessageRequest` (same capability) |
| webhook `x-signature` + `verifyWebhook` | `X-Firemoot-Signature: sha256=<hmac>` |

## Field naming

Firemoot uses **camelCase** on the wire (`userId`, `createdAt`, `lastReadSeq`)
where Stream uses snake_case. A mechanical adapter in your wrapper handles it. A
message carries a `userId` rather than an embedded `user` object - resolve names
and avatars from the channel's hydrated `members`.

## Differences to plan for

- **Search** is Postgres full-text (`simple` config): fast and injection-safe, but
  no stemming or typo tolerance. Treat it as lower fidelity than a dedicated
  search engine.
- **Thumbnails** are generated asynchronously. An image upload returns the full
  object URL immediately; the server patches a `thumbUrl` onto the attachment a
  moment later via a `message.updated` event. Subscribe to `message.updated` to
  pick it up, and fall back to the full image until then. Write the object URL
  under a `url` key in your attachment JSON (that is what the thumbnailer matches).
- **Scheduled "unread reminder" webhooks** have no v1 equivalent. If you relied on
  Stream's `user.unread_message_reminder`, run it as a cron in your own app
  against the read state.
- **`stopWatching` is client-local.** There is no server unsubscribe in v1; idle
  watched channels keep streaming until the socket closes.

## Server provisioning

The booking/admin paths - create a channel with members, send system messages as
a user, query by member, delete a channel or user - all map directly onto the
`@firemoot/server` helpers and the server REST surface. See the
[auth model](./auth) for how the server credential differs from the browser's.
