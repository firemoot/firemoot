# Authentication

Firemoot has exactly two callers, and a distinct credential for each.

| Caller | Credential | Trust | Used for |
| --- | --- | --- | --- |
| Your backend | API key + secret (HMAC) | Full - may act as any user | Provisioning, server-side sends, admin |
| The browser | End-user JWT (HS256 bearer) | Scoped - one user, authorised per op | Sending, reading, reacting, querying, uploading |

Both verify against the same `FIREMOOT_API_SECRET`. Keep it on your server. The
browser never sees it.

## Server requests - HMAC

Server SDK calls are signed over a canonical string and sent as three headers.
The signature covers the method, path, a timestamp and a hash of the body, so it
cannot be replayed against a different request:

```
canonical = FIREMOOT-HMAC-SHA256\n<METHOD>\n<path>\n<unixSeconds>\n<sha256Hex(body)>
```

```
X-Firemoot-Key:       <api key id>
X-Firemoot-Timestamp: <unix seconds>
X-Firemoot-Signature: <hmac-sha256(secret, canonical)>
```

The signature is lowercase hex, the comparison is constant-time, and the
timestamp must be within ±300s of the server's clock. The server SDK builds all
three headers for you (`createHmacAuthorizer`); you only hold the key and secret.
A server caller is fully trusted - it may send as any user and reach the
admin-only endpoints (user and channel provisioning, webhook registration).

::: warning One header name, two schemes
`X-Firemoot-Signature` is also the header on **outbound** webhook deliveries, and
the two are not the same thing. Inbound (here) it is a bare hex HMAC over the
canonical string above. Outbound it is `sha256=<hex>` over the raw request body,
keyed by the *webhook endpoint's* secret rather than the API secret. See
[webhooks](./webhooks).
:::

## Browser requests - end-user JWT

Mint a short-lived HS256 token for a user on your backend and hand it to the
browser:

```ts
const token = await server.createToken("alice"); // sub: "alice", default 1h expiry
```

The browser presents it two ways, both verified against the API secret:

- **WebSocket:** `wss://<host>/v1/ws?token=<jwt>`
- **REST:** `Authorization: Bearer <jwt>` (the client SDK adds this automatically)

Every channel operation is then authorised **server-side** against the caller's
membership and role - the client cannot lie:

- Must be a member of the channel (non-members get `403`).
- Identity fields are forced to the token subject - you cannot send, react or
  flag as someone else.
- A reaction can only be removed by its owner.
- Edit/delete is author-only (a channel moderator or owner may override).
- `queryChannels` and search are forcibly scoped to the caller's channels.
- The admin endpoints (user/channel provisioning, webhooks) reject a user token.

This is the same model a hosted vendor uses: your backend mints tokens, the
browser talks directly to the chat backend, and the backend enforces who can do
what.

## Rotating keys

Server API keys live in config (the bootstrap key) **and** the database. Create
or revoke DB-backed keys from the [admin dashboard](./admin) and they take effect
immediately - no restart. The bootstrap key from `FIREMOOT_API_KEY_ID` /
`FIREMOOT_API_SECRET` is resolved before the database, so it keeps working on an
empty schema, but for the same reason it cannot be revoked from the UI.

The end-user JWT signing key *is* the API secret, so rotating it deliberately
invalidates every live token as well as your server keys. Cutting over to a new
DB-backed API key is the low-blast-radius move.
