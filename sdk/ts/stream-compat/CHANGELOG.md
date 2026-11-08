# @firemoot/stream-compat

## 1.1.0

### Minor Changes

- 44d4f01: Webhook endpoints now have a `format`. `stream` (the default for new endpoints) delivers getstream.io's own webhook payloads, so a handler written against Stream needs no translation; `firemoot` is the old `{ type, cid, seq, data }` envelope and is deprecated. `@firemoot/core` gains `format` on the webhook types and `updateWebhook` (`PATCH /v1/webhooks/{id}`) to flip an existing endpoint without changing its secret. `normalizeWebhookEvent()` now returns a Stream payload unchanged and is deprecated.
- fe6aba1: `normalizeWebhookEvent()` now rebuilds the server's new `user.unread_message_reminder` webhook in Stream's shape: `user`, plus `channels` keyed by cid, each with the member list nested in `channel` and its newest messages carrying `user.id`. A reminder handler written against Stream reads it unchanged.

### Patch Changes

- Updated dependencies [44d4f01]
  - @firemoot/core@1.1.0
  - @firemoot/client@1.0.3

## 1.0.2

### Patch Changes

- Updated dependencies [7409dbb]
  - @firemoot/client@1.0.2

## 1.0.1

### Patch Changes

- Updated dependencies [8a045f1]
  - @firemoot/client@1.0.1

## 1.0.0

### Major Changes

- 5584589: New package: a drop-in adapter exposing the getstream.io `stream-chat` SDK's
  surface over `@firemoot/client`. It exports a `StreamChat` class fusing both of
  Stream's modes behind the same constructors - `new StreamChat(key, options)` for
  the browser (connect, watch, live channel state, events) and
  `new StreamChat(key, secret, options)` for server-trusted work (HMAC-signed REST,
  token minting, provisioning, webhook verification). An app written against Stream
  switches backends by changing configuration only, either by swapping the import
  specifier or by aliasing `stream-chat` in its bundler.

  `createToken` keeps Stream's synchronous signature; uncovered `stream-chat`
  methods exist but throw a named `FiremootCompatError` rather than silently doing
  nothing, with three documented exceptions that are genuine no-ops.

### Patch Changes

- Updated dependencies [329c677]
  - @firemoot/core@1.0.0
  - @firemoot/client@1.0.0
