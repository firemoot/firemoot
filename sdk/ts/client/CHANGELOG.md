# @firemoot/client

## 1.0.1

### Patch Changes

- 8a045f1: `Channel.watch()` and `resync()` now load the channel's members, read receipts and the caller's read state (via `channels/query` for that one cid) instead of dropping them - previously a watched channel reported `members: []`, `readReceipts: {}` and `read: null`.

  `Channel.hydrate()`, and so `FiremootClient.queryChannels()`, no longer collapses a watched channel's loaded history to its latest message or moves its resume seq past a WS gap. On a watched channel it only adds a latest message the stream hasn't delivered yet and refreshes the caller's unread count, ignoring a snapshot older than a read already applied. A `member.removed` for the connected user now clears that channel's `read`, since a re-add restarts the server's read pointer at 0. A confirmed message arriving through `hydrate()` or a reload also clears its matching optimistic send.

## 1.0.0

### Major Changes

- 329c677: Initial public release of the Firemoot TypeScript SDK: the generated
  `@firemoot/core` transport, types and REST client; the hand-written
  `@firemoot/client` (connection lifecycle, auto-reconnect with seq resume,
  optimistic send, channel-state reducer with read receipts, typed events, and a
  server SDK for token minting + provisioning); and `@firemoot/test`, the
  downstream helper that boots a real Firemoot via Testcontainers and ships a
  `TcpProxy` for reconnect-chaos testing.

### Patch Changes

- Updated dependencies [329c677]
  - @firemoot/core@1.0.0
