# @firemoot/core

## 1.1.1

### Patch Changes

- e3fff80: `Problem` gains an optional `retryAfter` (whole seconds), set on `429` responses alongside the new `Retry-After` header.

## 1.1.0

### Minor Changes

- 44d4f01: Webhook endpoints now have a `format`. `stream` (the default for new endpoints) delivers getstream.io's own webhook payloads, so a handler written against Stream needs no translation; `firemoot` is the old `{ type, cid, seq, data }` envelope and is deprecated. `@firemoot/core` gains `format` on the webhook types and `updateWebhook` (`PATCH /v1/webhooks/{id}`) to flip an existing endpoint without changing its secret. `normalizeWebhookEvent()` now returns a Stream payload unchanged and is deprecated.

## 1.0.0

### Major Changes

- 329c677: Initial public release of the Firemoot TypeScript SDK: the generated
  `@firemoot/core` transport, types and REST client; the hand-written
  `@firemoot/client` (connection lifecycle, auto-reconnect with seq resume,
  optimistic send, channel-state reducer with read receipts, typed events, and a
  server SDK for token minting + provisioning); and `@firemoot/test`, the
  downstream helper that boots a real Firemoot via Testcontainers and ships a
  `TcpProxy` for reconnect-chaos testing.
