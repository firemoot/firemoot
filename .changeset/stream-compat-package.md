---
"@firemoot/stream-compat": minor
---

New package: a drop-in adapter exposing the getstream.io `stream-chat` SDK's
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
