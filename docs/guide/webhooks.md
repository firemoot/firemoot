# Webhooks

Firemoot pushes every persisted channel event to your HTTP endpoints. The queue
is a Postgres table, not a broker: deliveries are retried on a fixed backoff
ladder and, when they run out of attempts, dead-lettered rather than dropped so
you can requeue them from the [admin dashboard](./admin).

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
{ "id": "0197…", "url": "https://example.com/hooks/firemoot", "secret": "…", "enabled": true }
```

`secret` is generated for you if you do not supply one, and this `201` response
is the **only** time it is returned - store it now. `GET /v1/webhooks` lists
endpoints without secrets, and `DELETE /v1/webhooks/{id}` removes one (which
cascades away its pending deliveries).

There is no per-endpoint event filter in v1: every enabled endpoint receives
every deliverable event, and you filter on `type` at your end.

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

The body is the same envelope the WebSocket carries:

```json
{ "type": "message.new", "cid": "messaging:general", "seq": 42, "data": { … } }
```

Delivery is **at-least-once and unordered** - rows are claimed with
`for update skip locked` and processed eight at a time, so a retry can land after
a newer event. `X-Firemoot-Delivery` (and its alias `X-Webhook-Id`) is stable
across retries of the same event; dedupe on it. `X-Webhook-Attempt` counts from
`1`, so a value above `1` tells you this event has been tried before. Within a
channel, `seq` gives you the true ordering.

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

Everything persisted and broadcast to a whole channel:

`message.new`, `message.updated`, `message.deleted`, `reaction.new`,
`reaction.deleted`, `channel.updated`, `channel.deleted`, `member.added`,
`member.removed`, `read.updated` - plus `user.flagged`, which is enqueued
directly by moderation with `data` of `{ messageId, flaggedUser, flaggedBy,
reason }`.

Ephemeral and user-directed events are **not** delivered: `typing.start`,
`typing.stop`, `presence.changed`, and the per-user unread notifications.

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
