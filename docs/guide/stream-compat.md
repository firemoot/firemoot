# Drop-in Stream compatibility

`@firemoot/stream-compat` exposes the [`stream-chat`](https://getstream.io/chat/)
JavaScript SDK's surface over Firemoot. It exports a class called `StreamChat`
with the same constructors, the same method names and the same return shapes, so
an app written against Stream can switch chat backends by **changing
configuration only** - the API key, the secret, and a base URL.

This is the fastest path off Stream. It is not the only one: if you would rather
own the seam, [migrating from Stream](./migration) describes porting your adapter
modules onto `@firemoot/client` directly, which is more work up front and leaves
you with a smaller, clearer dependency. Start here, and drop down to
`@firemoot/client` for the parts that matter to you.

## Install

```sh
npm install @firemoot/stream-compat
```

## The two ways to adopt it

### 1. Change the import specifier

```ts
// - import { StreamChat } from "stream-chat";
import { StreamChat } from "@firemoot/stream-compat";
```

Everything after that line stays as it was.

### 2. Alias the package - zero code changes

If you would rather not touch application code at all, point your bundler at the
adapter and leave every `import ... from "stream-chat"` exactly where it is.

::: code-group

```js [vite.config.js]
export default {
  resolve: {
    alias: {
      "stream-chat": "@firemoot/stream-compat",
    },
  },
};
```

```js [next.config.js]
export default {
  webpack: (config) => {
    config.resolve.alias["stream-chat"] = "@firemoot/stream-compat";
    return config;
  },
};
```

```js [webpack.config.js]
export default {
  resolve: {
    alias: {
      "stream-chat": "@firemoot/stream-compat",
    },
  },
};
```

```json [tsconfig.json]
{
  "compilerOptions": {
    "paths": {
      "stream-chat": ["./node_modules/@firemoot/stream-compat"]
    }
  }
}
```

:::

The `tsconfig.json` entry is what makes the *types* resolve to the adapter too;
without it TypeScript keeps checking your code against the real `stream-chat`
types while the bundler substitutes ours at runtime. Set both.

A Node server that is not bundled has no alias step - use the import-specifier
swap there, or an
[import map](https://nodejs.org/api/packages.html#subpath-imports).

## The configuration change

This is the whole migration:

```ts
const client = StreamChat.getInstance("your-firemoot-api-key", {
  baseURL: "https://chat.example.com", // your Firemoot server
});
```

and on the server:

```ts
const server = new StreamChat("your-firemoot-api-key", "your-firemoot-api-secret", {
  baseURL: "https://chat.example.com",
});
```

`baseURL` is required - there is no default to fall back on, and calling anything
without it throws rather than quietly pointing at nothing. `client.setBaseURL(url)`
works too, if that is how your app is wired.

Stream's other constructor options (`timeout`, `warmUp`, `httpsAgent`,
`browser`, …) are accepted and ignored, so an existing options object still
compiles and still runs.

### The two modes

Exactly as in `stream-chat`, one class covers both and the secret is what
distinguishes them:

| Constructor                              | Mode           | What you get                                                                             |
| ---------------------------------------- | -------------- | ---------------------------------------------------------------------------------------- |
| `new StreamChat(key, { baseURL })`       | browser        | `connectUser()`, the WebSocket, watched channels, live `channel.state`, events            |
| `new StreamChat(key, secret, { baseURL })` | server-trusted | HMAC-signed REST, `createToken()`, provisioning, sending as any user, `verifyWebhook()` |

`StreamChat.getInstance()` has the same overloads and the same first-call-wins
singleton semantics as Stream's.

Never construct the secret form in a browser. It holds your API secret, and it
can act as any user.

### Firemoot-specific options

Everything Firemoot needs that Stream has no equivalent for lives under a
`firemoot` key, so it can never collide with a Stream option:

```ts
new StreamChat(key, secret, {
  baseURL: "https://chat.example.com",
  firemoot: {
    webhookSecret: process.env.FIREMOOT_WEBHOOK_SECRET, // see "Webhooks" below
    tokenTtlSeconds: 3600, // default createToken() expiry
  },
});
```

## `createToken` is still synchronous

`stream-chat`'s `createToken(userID, exp?, iat?)` returns a `string`, not a
promise, and plenty of app code depends on that - a token minted inline in a
response body, say. So it is synchronous here too:

```ts
const token = server.createToken("alice"); // string, no await
```

Two honest differences behind that identical signature:

- **The claims are Firemoot's.** The token carries `sub` (the user id) where
  Stream's carries `user_id`, because the Firemoot gateway is what verifies it.
- **Expiry is mandatory.** Stream mints a token that never expires when you omit
  `exp`; Firemoot requires one, so omitting it gives you a **one-hour** token
  rather than an eternal one. Pass `exp` (epoch seconds) or set
  `firemoot.tokenTtlSeconds` to choose. If your app minted eternal tokens and
  never refreshed them, this is the one behaviour change you must plan for -
  wire up a token provider:

  ```ts
  await client.connectUser({ id: "alice" }, async () => {
    const res = await fetch("/api/chat-token");
    return (await res.json()).token;
  });
  ```

  `connectUser` accepts a plain string or a provider, as Stream does, and the
  provider is re-invoked on every reconnect.

`@firemoot/client`'s `FiremootServer.createToken()` remains async (it uses
WebCrypto, so it runs in any runtime). The adapter signs in-process instead,
which is what lets it stay synchronous in both Node and the browser.

## Compatibility

### Covered

| Area           | Methods                                                                                                                          |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Client setup   | `new StreamChat()`, `getInstance()`, `setBaseURL()`, `getAuthType()`                                                              |
| Connection     | `connectUser()`, `setUser()`, `disconnectUser()`, `closeConnection()`, `openConnection()`, `userID`, `user`                        |
| Channels       | `channel()`, `queryChannels()`                                                                                                    |
| Events         | `client.on(type, handler)`, `client.on(handler)`, `client.off()`, `channel.on()`, `channel.off()`                                  |
| Channel state  | `channel.state` (`messages`, `members`, `read`, `unreadCount`, `last_message_at`), `channel.data`, `lastMessage()`, `countUnread()` |
| Messaging      | `sendMessage()`, `markRead()`, `keystroke()`, `stopTyping()`, `sendReaction()`, `deleteReaction()`                                 |
| Attachments    | `sendImage(file, name, contentType)`, `sendFile(file, name, contentType)`                                                          |
| Watching       | `channel.watch()`, `channel.stopWatching()`, `channel.query()`                                                                    |
| Moderation     | `client.flagMessage()`                                                                                                            |
| Server: users  | `createToken()`, `upsertUser()`, `upsertUsers()`, `deleteUser()`                                                                   |
| Server: admin  | `channel.create()`, `channel.query()`, `channel.updatePartial()`, `channel.delete()`, `channel.addMembers()`, `deleteMessage()`    |
| Webhooks       | `verifyWebhook()`                                                                                                                 |

Behaviours that are preserved because apps depend on them:

- `queryChannels(filter, sort, { watch: true })` subscribes every result over the
  socket and re-subscribes across reconnects, as Stream does. `{ type, id, cid,
members: { $in } }` filters are translated; sorting is by `last_message_at`
  (both directions), and any other sort field **throws** rather than silently
  returning mis-ordered channels.
- `channel.create()` on a channel that already exists tops up its membership and
  then throws a duplicate-shaped error carrying `code: 4`, exactly as Stream's
  does, so a "create or join" call site needs no change.
- A `sendMessage` with a caller-supplied `id` that collides comes back as an
  `Error` carrying `status: 409`, and unknown top-level message keys are folded
  into custom data - both matching Stream.

### Deliberate no-ops

Three calls succeed and do nothing. Each exists because real apps make it
harmlessly, and failing would be the wrong answer:

| Call                                    | Why                                                                                                                 |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `client.on("notification.message_new")`  | Subscribes fine, never fires. Watched channels' own `message.new` already covers the live UX under `{ watch: true }`. |
| `client.on("notification.mark_unread")`  | Subscribes fine, never fires. Firemoot has no mark-unread.                                                          |
| `channel.sendEvent()`                    | Firemoot's typing events are client-only (a WebSocket frame), so there is no server-side inject to perform.          |

`channel.watch()` on a *server-trusted* client is also a no-op: there is no
server-side watch, and `channel.query()` serves the same purpose.

### Everything else throws

Any other `stream-chat` method exists on the object and throws a
`FiremootCompatError` naming itself:

```
channel.mute() is not supported by @firemoot/stream-compat (v0.0.0);
see the compatibility table at https://firemoot.com/guide/stream-compat
```

This is deliberate. A silent no-op would let a migration look finished while
mutes, bans or polls quietly did nothing; a throw surfaces every gap on the first
test run. Broadly, the unsupported surface is: mutes and bans, channel types and
commands, permissions and roles, polls, drafts, reminders, threads, campaigns and
segments, imports and exports, push providers and devices, blocklists, user
search and `queryUsers`, message pinning, and translation.

Calling a method from the wrong mode throws the same way, and says which mode it
needs - `channel.create()` on a browser client, or `channel.on()` on a
server-trusted one.

## Webhooks

Firemoot sends Stream's header names alongside its own on every delivery, so a
handler ported from Stream keeps working:

| Purpose    | Stream            | Firemoot sends both                                |
| ---------- | ----------------- | -------------------------------------------------- |
| Signature  | `X-Signature`     | `X-Signature` **and** `X-Firemoot-Signature`       |
| Dedupe id  | `X-Webhook-Id`    | `X-Webhook-Id` **and** `X-Firemoot-Delivery`       |
| Attempt    | `X-Webhook-Attempt` | `X-Webhook-Attempt` (counts from 1)              |
| Event type | -                 | `X-Firemoot-Event`                                 |

`X-Signature` is the bare lowercase hex HMAC-SHA256 of the raw body - the exact
shape Stream's `verifyWebhook` compares against. `X-Firemoot-Signature` is the
same digest with a `sha256=` prefix. `verifyWebhook()` accepts either:

```ts
if (!server.verifyWebhook(rawBody, req.headers["x-signature"])) {
  return res.status(401).end();
}
```

**One thing you must configure.** Stream signs webhooks with your API secret;
Firemoot signs each delivery with that **endpoint's own secret**. Set it:

```ts
new StreamChat(key, secret, {
  baseURL,
  firemoot: { webhookSecret: process.env.FIREMOOT_WEBHOOK_SECRET },
});
```

Without it, `verifyWebhook` falls back to the API secret, which only works if you
deliberately configured the endpoint with that same value. If every delivery
suddenly fails verification, this is why.

The event body is Firemoot's envelope (`{ type, cid, seq, data }`, camelCase)
rather than Stream's. If your handler reads `event.message.user.id`, normalise it
first:

```ts
import { normalizeWebhookEvent } from "@firemoot/stream-compat";

const event = normalizeWebhookEvent(JSON.parse(rawBody));
// { type: "message.new", cid, message: { id, text, user: { id } } }
```

`message.new` stays `message.new` and `read.updated` becomes Stream's
`message.read`; other events pass through under their own type. There is also a
standalone `verifyWebhookSignature(secret, rawBody, header)` if you would rather
not construct a client just to check a signature.

See the [webhooks guide](./webhooks) for delivery semantics, retries and
dead-lettering.

## What this does not change

The adapter is a translation layer, not an emulator. It does not give Firemoot
Stream's features - the [migration guide](./migration) is still the honest list
of what differs underneath, and all of it applies here:

- Search is Postgres full-text: no stemming, no typo tolerance.
- Thumbnails arrive asynchronously via a later `message.updated`.
- `stopWatching` is client-local; there is no server-side unsubscribe in v1.
- A duplicate message id is a `409`, not an idempotent replay.

## Dropping down to `@firemoot/client`

The adapter is built on `@firemoot/client` and hides none of it. When you want
the real API - typed events, the channel reducer, the optimistic outbox - import
it directly and run both side by side. Migrating a screen at a time is a
perfectly good plan, and the compatibility layer is a reasonable thing to keep
around indefinitely for the parts of your app that are not worth revisiting.
