# Webhooks

Firemoot pushes every persisted channel event to your HTTP endpoints, as
[getstream.io](https://getstream.io)'s own webhook payloads - a handler written
against Stream reads them unchanged. The queue is a Postgres table, not a broker:
deliveries are retried on a fixed backoff ladder and, when they run out of
attempts, dead-lettered rather than dropped so you can requeue them from the
[admin dashboard](./admin).

## Registering an endpoint

Registration is a server-key operation, so it goes through your backend, not the
browser:

```sh
curl -X POST http://localhost:6668/v1/webhooks \
  -H 'content-type: application/json' \
  -d '{"url": "https://example.com/hooks/firemoot"}'
  # ...plus the three HMAC headers; the server SDK signs these for you
```

```json
{ "id": "0197…", "url": "https://example.com/hooks/firemoot", "secret": "…", "enabled": true, "format": "stream" }
```

`secret` is generated for you if you do not supply one, and this `201` response
is the **only** time it is returned - store it now. `GET /v1/webhooks` lists
endpoints without secrets, and `DELETE /v1/webhooks/{id}` removes one (which
cascades away its pending deliveries).

There is no per-endpoint event filter in v1: every enabled endpoint receives
every deliverable event, and you filter on `type` at your end.

### Body format

Each endpoint has a `format`:

| Format | Body | Status |
| --- | --- | --- |
| `stream` | Stream's webhook payload (`message.new` with `message.user`, `channel_id`, …) | The default |
| `firemoot` | The `{ type, cid, seq, data }` envelope the WebSocket carries | **Deprecated** - will be removed |

`firemoot` exists only so endpoints registered before `stream` arrived can
migrate. To move one, deploy a handler that accepts Stream's payload (or run the
body through [`normalizeWebhookEvent`](./stream-compat#webhooks), which passes
Stream payloads through untouched), then flip the endpoint - its secret is kept:

```sh
curl -X PATCH http://localhost:6668/v1/webhooks/0197… \
  -H 'content-type: application/json' \
  -d '{"format": "stream"}'
```

`PATCH` also takes `enabled`. Deliveries already queued keep the body they were
rendered with.

## The delivery

```
POST <your url>
Content-Type: application/json
X-Firemoot-Signature: sha256=<hex>
X-Firemoot-Delivery: <uuid>
X-Firemoot-Event: message.new
X-Signature: <hex>
X-Webhook-Id: <uuid>
X-Webhook-Attempt: 1
X-Api-Key: <your server api key id>
```

The second block is [getstream.io](https://getstream.io)'s header naming for the
same delivery, so a handler written against Stream keeps working unchanged - see
[migrating from Stream](./migration). Prefer the `X-Firemoot-*` headers in new
code; the aliases exist for compatibility and carry no extra information.

The body is Stream's payload for that event:

```json
{
  "type": "message.new",
  "cid": "messaging:general",
  "channel_id": "general",
  "channel_type": "messaging",
  "message": { "id": "…", "text": "hi", "type": "regular", "user": { "id": "alice", … }, … },
  "user": { "id": "alice", … },
  "members": [{ "user_id": "alice", "user": { … }, "role": "owner", … }, …],
  "created_at": "2026-11-08T10:00:00Z"
}
```

Delivery is **at-least-once and unordered** - rows are claimed with
`for update skip locked` and processed eight at a time, so a retry can land after
a newer event. `X-Firemoot-Delivery` (and its alias `X-Webhook-Id`) is stable
across retries of the same event; dedupe on it. `X-Webhook-Attempt` counts from
`1`, so a value above `1` tells you this event has been tried before. Order by
the payload's timestamps (`message.created_at`, `updated_at`) where it matters.

### Verifying the signature

The signature is `sha256=` followed by the lowercase hex HMAC-SHA256 of the **raw
request body**, keyed by that endpoint's secret. Verify before parsing, against
the bytes you received rather than a re-serialised object:

```ts
import { createHmac, timingSafeEqual } from "node:crypto";

function verify(rawBody: Buffer, header: string, secret: string): boolean {
  const expected = `sha256=${createHmac("sha256", secret).update(rawBody).digest("hex")}`;
  const a = Buffer.from(expected);
  const b = Buffer.from(header);
  return a.length === b.length && timingSafeEqual(a, b);
}
```

This is a different scheme from the one your *outbound* server requests use, even
though both land in a header called `X-Firemoot-Signature`. See
[authentication](./auth).

`X-Signature` carries the same digest **without** the `sha256=` prefix, which is
the exact shape Stream's `verifyWebhook(rawBody, signature)` expects - so a
handler ported from Stream verifies against it with no code change:

```ts
client.verifyWebhook(rawBody, req.headers["x-signature"]);
```

`X-Api-Key` is your server API key id (`FIREMOOT_API_KEY_ID`), sent because
Stream sends one. Treat it as routing metadata only: Firemoot signs with the
**endpoint's** secret, not the API key's secret, so it is not the key to verify
with.

## Which events are delivered

Everything persisted and broadcast to a whole channel, under Stream's names:

`message.new`, `message.updated`, `message.deleted`, `message.read`,
`reaction.new`, `reaction.deleted`, `channel.updated`, `channel.deleted`,
`member.added`, `member.removed` - plus `message.flagged` from moderation and
`user.unread_message_reminder` (below).

Payloads follow Stream's documented shapes, with what Firemoot can truthfully
fill in:

- Users carry `id`, `name`, `image`, `role`, timestamps, `last_active` and their
  custom fields spread at the top level, as Stream does. `banned` is always
  `false` (Firemoot has no bans) and there is no `online`.
- Messages carry `reaction_counts`, the ten `latest_reactions`, `reply_count`,
  `parent_id` and custom fields spread at the top level. There is no `html` and
  `mentioned_users` is always empty.
- `message.new` lists the channel's `members` only for channels of up to 100
  members.
- `message.deleted` has `deleted_at` but no `text`: Firemoot's soft delete
  scrubs it.
- `message.updated` has no `message_update.old_text`, channels have no
  `config`, and no event carries `request_info`.

A `firemoot`-format endpoint gets the same events under their envelope names
(`read.updated`, `user.flagged`) with camelCase `data`.

Ephemeral and user-directed events are **not** delivered: `typing.start`,
`typing.stop`, `presence.changed`, and the per-user unread notifications. The
one user-level event that *is* delivered is the unread reminder below.

## Unread reminders

`user.unread_message_reminder` tells you a user has left a message unread for a
while, so you can email them, push, or text. It is Stream's event of the same
name, and it is **off** until you set an interval:

```sh
FIREMOOT_UNREAD_REMINDER_INTERVAL_SECONDS=300   # 60 to 86400
```

A member is reminded when their oldest unread message - not their own, not a
system message, not deleted - has waited that long. Each unread burst is
reminded **once**; the next message after a reminder starts a fresh clock, and
reading the channel cancels a pending one. Up to ten of a user's channels are
batched into one event (more spill into a second), and channels with more than
`FIREMOOT_UNREAD_REMINDER_MAX_MEMBERS` (default 10) members are skipped, so a
busy group channel cannot turn into a reminder per member. A sweep runs every
15 seconds, so a reminder lands up to that long after it falls due.

The body is Stream's: `channels` keyed by cid, members nested in each `channel`:

```json
{
  "type": "user.unread_message_reminder",
  "created_at": "2026-11-08T10:05:00Z",
  "user": { "id": "bob", "name": "Bob", … },
  "channels": {
    "messaging:dm": {
      "channel": {
        "cid": "messaging:dm", "type": "messaging", "id": "dm", "member_count": 2,
        "members": [{ "user_id": "alice", "user": { … }, "role": "owner" }, …],
        …
      },
      "messages": [{ "id": "…", "text": "…", "type": "regular", "user": { "id": "alice", … }, … }]
    }
  }
}
```

`messages` is the channel's newest five live messages, newest first - including
the recipient's own and system messages, so you can see the conversation as it
stands. Check `messages[0].user.id` if you only want to notify when the other
side spoke last.

Reminders only look back an hour past due. Turning the feature on does not
remind everyone about their old unread history, and after an outage longer than
that, reminders that fell due while the server was down are skipped rather than
sent late.

## Retries and dead-letters

| | |
| --- | --- |
| Request timeout | 5s |
| Backoff ladder | 1 minute, 5 minutes, 30 minutes, 2 hours |
| Total attempts | 5 (the first, plus four retries) |
| Counts as failure | any non-2xx, a connection error, a timeout, or the endpoint being deleted/disabled mid-flight |

Anything still failing after the ladder is marked `dead` with its last error
recorded. Dead letters are listed in the admin dashboard, and replaying one
resets it to pending with `attempts = 0` - so a replay gets the whole ladder
again, and a permanently broken consumer will re-dead-letter rather than spin.

Successful deliveries stay in the table as `delivered`. There is no pruning
worker yet, so on a busy instance that table is worth an occasional manual
cleanup.
