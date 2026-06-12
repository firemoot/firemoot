# Quickstart

Get a Firemoot talking to a browser in about five minutes.

## 1. Boot the stack

Firemoot ships as a Docker image plus a Postgres. Using the reference compose
stack:

```sh
# Build the image (until it is published to Docker Hub):
sbt "server/Docker/publishLocal"          # tags firemoot:latest

cd deploy/compose
export FIREMOOT_API_SECRET=$(openssl rand -hex 32)
docker compose up -d
```

Check it is alive:

```sh
curl localhost:6668/healthz     # liveness
curl localhost:6668/readyz      # liveness + a Postgres ping
```

`FIREMOOT_API_SECRET` is the one credential you must set: it signs your server's
HMAC requests **and** mints the end-user JWTs the browser connects with. Set
`FIREMOOT_ADMIN_PASSWORD` too if you want the admin dashboard at `/admin`.

## 2. Provision from your backend

Your backend talks to Firemoot with the server SDK, authenticated by the API key
+ secret (never expose the secret to the browser). Mint a token for a user, and
create a channel:

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
const token = await server.createToken("alice");
```

## 3. Connect from the browser

The browser uses the user's JWT - never the API secret. The client connects the
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
channel.on("message.new", (e) => console.log(e.data.userId, e.data.text));
await channel.watch();

await channel.sendMessage({ text: "hello, firemoot" });
```

The send appears immediately (optimistically) and is reconciled when the server
confirms it - whichever of the REST response or the `message.new` event arrives
first, and never duplicated.

## Next steps

- [Auth model](./auth) - how the two surfaces (server HMAC, client JWT) fit together.
- [Protocol reference](./protocol) - the WebSocket frames and event vocabulary.
- [Hosting](./hosting) - Compose, Fly.io, Caddy, and which platforms can't host it.
