# @firemoot/core

## 1.0.0

### Major Changes

- 329c677: Initial public release of the Firemoot TypeScript SDK: the generated
  `@firemoot/core` transport, types and REST client; the hand-written
  `@firemoot/client` (connection lifecycle, auto-reconnect with seq resume,
  optimistic send, channel-state reducer with read receipts, typed events, and a
  server SDK for token minting + provisioning); and `@firemoot/test`, the
  downstream helper that boots a real Firemoot via Testcontainers and ships a
  `TcpProxy` for reconnect-chaos testing.
