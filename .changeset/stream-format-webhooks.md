---
"@firemoot/core": minor
"@firemoot/stream-compat": minor
---

Webhook endpoints now have a `format`. `stream` (the default for new endpoints) delivers getstream.io's own webhook payloads, so a handler written against Stream needs no translation; `firemoot` is the old `{ type, cid, seq, data }` envelope and is deprecated. `@firemoot/core` gains `format` on the webhook types and `updateWebhook` (`PATCH /v1/webhooks/{id}`) to flip an existing endpoint without changing its secret. `normalizeWebhookEvent()` now returns a Stream payload unchanged and is deprecated.
