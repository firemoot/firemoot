# Quickstart

Get a Firemoot talking to a browser in about five minutes: boot the stack, mint a
token from your backend, connect, send.

## 1. Boot the stack

Firemoot is one Docker image plus a Postgres. The reference Compose stack wires
both together:

```sh
# Build the image (until v1.0 publishes it to a registry):
mise exec -- sbt "server/Docker/publishLocal"   # tags firemoot:latest

cd deploy/compose
export FIREMOOT_API_SECRET=$(openssl rand -hex 32)
export FIREMOOT_ADMIN_PASSWORD='choose-something'   # optional, unlocks /admin
docker compose up -d
```

Check it is alive:

```sh
curl localhost:6668/healthz     # liveness
curl localhost:6668/readyz      # liveness + a Postgres ping
```

From v1.0 the image is published as `ghcr.io/firemoot/firemoot`: point the compose
`image:` at it, drop the build step, and nothing else changes.

`FIREMOOT_API_SECRET` is the one credential you must set. It signs your server's
HMAC requests **and** mints the end-user JWTs the browser connects with, so keep
it on your backend and never ship it to a client. Everything else has a usable
default - see the [configuration reference](./configuration).

## 2. Provision from your backend

Your backend talks to Firemoot with the server SDK, authenticated by the API key
and secret. Mint a token for a user and create a channel:

```ts
import { FiremootServer } from "@firemoot/client";

const server = new FiremootServer({
  baseUrl: "http://localhost:6668",
  apiKey: "firemoot", // FIREMOOT_API_KEY_ID
  apiSecret: process.env.FIREMOOT_API_SECRET!,
});

await server.upsertUser({ id: "alice", name: "Alice" });
await server.upsertUser({ id: "bob", name: "Bob" });
await server.createChannel({ type: "messaging", id: "general", createdBy: "alice" }, [
  { userId: "bob", role: "member" },
]);

// Hand this token to the browser (e.g. from your /token route):
const token = await server.createToken("alice"); // HS256, one-hour default expiry
```

## 3. Connect from the browser

The browser uses the user's JWT, never the API secret. The client opens the
WebSocket, watches a channel, and sends optimistically:

```ts
import { FiremootClient } from "@firemoot/client";

const client = new FiremootClient({
  baseUrl: "http://localhost:6668",
  userId: "alice",
  token, // from your backend
});

await client.connect();

const channel = client.channel("messaging", "general");
channel.on("message.new", (event) => console.log(event.data.userId, event.data.text));
await channel.watch();

await channel.sendMessage({ text: "hello, firemoot" });
```

`watch()` loads the channel's recent history and members, then subscribes over
the socket. The send appears immediately (optimistically) and is reconciled when
the server confirms it - whichever of the REST response or the `message.new`
event arrives first, and never duplicated.

For a long-lived session, pass `tokenProvider` instead of `token` so the client
fetches a fresh JWT on every (re)connect rather than dying when the first one
expires:

```ts
const client = new FiremootClient({
  baseUrl: "http://localhost:6668",
  userId: "alice",
  tokenProvider: () => fetch("/api/chat/token").then((r) => r.text()),
});
```

## Next steps

- [Configuration](./configuration) - every `FIREMOOT_*` variable and what it does.
- [Authentication](./auth) - how the two credentials (server HMAC, user JWT) fit together.
- [Realtime protocol](./protocol) - the WebSocket frames and event vocabulary.
- [Testing](./testing) - boot a real Firemoot inside your test suite.
- [Self-hosting](./hosting) - Compose, Caddy, Fly.io, and which platforms cannot host it.
