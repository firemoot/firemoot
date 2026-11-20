# Stream's own SDKs, unmodified

Firemoot speaks getstream.io's v2 REST API and its `/api/v2/connect` socket, the
protocol [`stream-chat`](https://getstream.io/chat/) v10 and
[`@stream-io/node-sdk`](https://getstream.io/chat/docs/node/) use. Point them at
Firemoot and they run unchanged: no adapter and no wrapper, and nothing in your
app's chat code changes. It's built for non-production environments of apps
whose production chat runs on Stream: previews, CI and local development get a
chat backend that behaves like Stream without a Stream account.

For `stream-chat` v9, see [drop-in Stream compatibility](./stream-compat).

## Point the SDKs at Firemoot

Use a Firemoot API key and its secret where your app uses Stream's.

```ts
// Browser
import { StreamChat } from "stream-chat";

const client = new StreamChat(FIREMOOT_API_KEY, {
  client: { baseURL: "https://chat.example.com" },
});
await client.connectUser({ id: "alice", name: "Alice" }, tokenProvider);
```

```ts
// Server
import { StreamClient } from "@stream-io/node-sdk";

const server = new StreamClient(FIREMOOT_API_KEY, FIREMOOT_API_SECRET, {
  basePath: "https://chat.example.com",
});
const token = server.generateUserToken({ user_id: "alice" });
```

`stream-chat` derives its socket URL from `baseURL` (`https` becomes `wss`), so
Firemoot's REST API and socket share one origin, which they do by default. Don't
serve Firemoot on port `3030`: the SDK rewrites that port to `8800` for the
socket.

Tokens are Stream's: HS256 JWTs signed with the API secret, `{ user_id }` for a
user and `{ server: true }` for the backend. `exp` is optional, as on Stream.
Firemoot's own `/v1` API and its tokens are separate and unaffected.

## What it covers

Everything below runs against the real SDKs in Firemoot's CI
(`sdk/stream-contract`).

| Area | Endpoints |
| --- | --- |
| Connecting | `/api/v2/connect` (auth in the first frame, `connection.ok`, health checks) |
| Channels | query (and `queryChannelsAndHydrate`), get-or-create, watch, query, distinct channels, update, partial update, add and remove members, delete, stop watching |
| Messages | send, get, get many, update, partial update, delete, thread replies, paging (`id_lt`, `id_gt`, `id_around`, `created_at_around`) |
| Reads and events | mark read, typing, custom events |
| Reactions | send (with `enforce_unique`), delete, list |
| Search | `query` or `message_filter_conditions.text`, scoped by channel |
| Uploads | multipart image and file uploads, channel-scoped and client-level |
| Users | upsert, query, batch delete |
| Moderation | flag a message |
| Server ops | batch channel delete, tasks, app settings |

Events reach sockets the way Stream routes them. Channel events go to sockets
watching that channel. A new message also reaches each member's other sockets as
`notification.message_new`. Member changes are followed by `channel.updated`.
Read, membership and presence changes arrive as `notification.mark_read`,
`notification.added_to_channel`, `notification.removed_from_channel` and
`user.presence.changed`.

Any other `/api/v2` endpoint answers with a Stream-shaped error (code `4`, status
`501`) naming the endpoint, so a gap shows up the first time your app hits it
rather than as a silent no-op.

## Where it differs from Stream

- **One channel type's rules.** Every channel behaves like Stream's default
  `messaging` type: members can read, send, react, upload and mark read; owners
  and moderators can also update, delete and manage members. There's no
  channel-type or permission configuration.
- **Filters.** `queryChannels` takes `type`, `id`, `cid`, `members` and
  equality on custom fields, with `$eq`, `$in` and `$and`. Anything else is
  rejected with code `4`, never ignored. Results order by recent activity.
- **No delivery receipts.** Channel config sends `delivery_events: false`, so the
  SDK doesn't post delivery reports.
- **Unread totals on events.** `connection.ok` carries the user's totals. Message
  events don't carry per-recipient `total_unread_count` or `unread_channels`.
- **Deletes are soft.** A hard message delete is stored as a soft delete, so the
  message comes back with `type: "deleted"`. Batch deletes run synchronously and
  report an already-completed task.
- **Uploads need media.** Image and file uploads need
  [media configured](./configuration#media-s3).
- **No mutes, bans, pins, polls, drafts, reminders or watcher lists.**

## Webhooks

`@stream-io/node-sdk` verifies webhooks with the API secret, while Firemoot signs
each delivery with its endpoint's secret. To keep `verifyWebhook` working, register
the endpoint with the API secret as its secret, in the default `stream` body
format:

```sh
curl -X POST https://chat.example.com/v1/webhooks \
  -H 'content-type: application/json' \
  -d '{"url": "https://app.example.com/hooks/chat", "secret": "<your api secret>"}'
  # ...plus the HMAC headers for Firemoot's own API
```

See [webhooks](./webhooks) for delivery, retries and the payloads.
