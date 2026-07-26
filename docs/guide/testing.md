# Testing

`@firemoot/test` boots a **real** Firemoot for your test suite: the actual Docker
image plus a Postgres, on a private Testcontainers network, healthy in seconds.
No mock server, no fixture drift - if your test passes, it passed against the
thing you deploy.

## Booting an instance

```ts
import { startFiremoot, type FiremootInstance } from "@firemoot/test";

let fm: FiremootInstance;

beforeAll(async () => {
  fm = await startFiremoot();
}, 180_000);

afterAll(async () => {
  await fm.stop();
});
```

`startFiremoot()` returns the mapped `baseUrl` and `wsUrl`, a server-trusted
`server` (a `FiremootServer`) and `rest` client, a `createToken(userId)` helper,
`seed()`, and `stop()`. Ports are randomly mapped, so suites run in parallel
safely, and the containers are reaped on `stop()` - or by Testcontainers' Ryuk if
your process dies first.

It expects `firemoot:latest` to exist locally; build it once with
`sbt "server/Docker/publishLocal"` (or pass `{ image }` to point at a published
tag). The options are `image`, `postgresImage`, `apiKey`, `apiSecret` and
`startupTimeoutMs`.

## Seeding a fixture

`seed()` applies a spec in dependency order - users, then channels with their
members, then messages - over the server credential:

```ts
await fm.seed({
  users: [{ id: "alice" }, { id: "bob" }],
  channels: [
    {
      type: "messaging",
      id: "general",
      createdBy: "alice",
      members: [{ userId: "bob", role: "member" }],
    },
  ],
  messages: [{ type: "messaging", id: "general", userId: "alice", text: "hello" }],
});
```

Then drive it exactly as a browser would:

```ts
const token = await fm.createToken("alice");
const client = new FiremootClient({
  baseUrl: fm.baseUrl,
  wsUrl: fm.wsUrl,
  userId: "alice",
  token,
});

await client.connect();
const channel = client.channel("messaging", "general");
await channel.watch();
await channel.sendMessage({ text: "hello from the test" });
```

## Chaos: severing the connection

`TcpProxy` is a pass-through TCP proxy you can put in front of the instance and
then break on demand. Because it forwards raw bytes it tunnels the WebSocket
upgrade transparently, which makes it a genuine mid-stream network drop rather
than a polite close:

```ts
import { TcpProxy } from "@firemoot/test";

const proxy = new TcpProxy("127.0.0.1", port);
const proxyPort = await proxy.start();

// ...connect the client through ws://127.0.0.1:${proxyPort}/v1/ws, send traffic...

proxy.drop(); // every live socket dies; the listener stays up so reconnects land
```

`drop()` cuts the live connections and lets reconnects straight back through.
`setAccepting(false)` also refuses new ones, which gives you a hard outage window
to test backoff against; `setAccepting(true)` ends it.

This is how the gapless-resume guarantee is tested: send while the socket is
severed, let the client reconnect and re-subscribe from its last seq, and assert
that every message arrives exactly once. See
[the realtime protocol](./protocol) for the mechanism it exercises.

## A note on timers

Do not assert against the wall clock that a timer has or has not yet fired - it
flakes on loaded CI runners. Assert in the generous direction only ("wait, then
check the thing arrived").
