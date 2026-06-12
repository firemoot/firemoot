# Protocol reference

The realtime protocol is a JSON message stream over a single WebSocket. The
`@firemoot/client` SDK implements all of this; the reference here is for building
another client or understanding the wire.

## Connecting

```
GET /v1/ws?token=<end-user JWT>
```

On a successful upgrade the server sends a `hello` frame:

```json
{ "type": "hello", "connectionId": "…", "serverTime": "…", "me": { … }, "totalUnread": 0 }
```

`totalUnread` is the user's global unread badge across every channel.

## Heartbeat

The server pings on its own schedule and reaps a connection after a couple of
missed pongs. A client may also keep the link warm:

```json
// client -> server
{ "type": "ping" }
// server -> client
{ "type": "pong" }
```

## Subscribing and resuming

Subscribe to one or more channels, each with the last seq you have already seen
(`0` for a fresh subscription):

```json
{ "type": "subscribe", "channels": { "messaging:general": 0 } }
```

The server replays every persisted event after that seq in order, then switches
to live delivery. The handoff is atomic: events that arrive during the replay are
buffered and spliced in by seq, so there is no gap and no duplicate.

If your resume seq is older than the retained event window, the server replies:

```json
{ "type": "resync_required", "cid": "messaging:general" }
```

Re-query the channel state and re-subscribe from the latest seq (the SDK does
this for you).

## Typing

Typing indicators are ephemeral - never persisted, never replayed:

```json
{ "type": "typing.start", "cid": "messaging:general" }
{ "type": "typing.stop",  "cid": "messaging:general" }
```

## Events

Every server event is a frame of the shape:

```json
{ "type": "<event>", "cid": "<channel>", "seq": <number>, "data": { … } }
```

`seq` is the per-channel sequence (gapless, monotonic) for persisted events, and
`0` for ephemeral ones (typing, presence). The event types:

| Event | `data` carries | Notes |
| --- | --- | --- |
| `message.new` | the message | A new message in a watched channel. |
| `message.updated` | the message | Edit, or a late thumbnail patched onto an attachment. |
| `message.deleted` | the message | Soft delete; text is scrubbed. |
| `reaction.new` | `messageId`, `userId`, `type`, `counts` | Per-type counts after the change. |
| `reaction.deleted` | `messageId`, `userId`, `type`, `counts` | |
| `read.updated` | `cid`, `userId`, `lastReadSeq`, `unreadCount`, `totalUnread` | Receipt for a member; your own moves your badge. |
| `typing.start` / `typing.stop` | `userId` | Ephemeral (`seq` 0). |
| `presence.changed` | `userId`, `status`, `lastActiveAt` | Online/offline among co-members. |
| `channel.updated` | the channel | Custom data, frozen or archived changed. |
| `channel.deleted` | `cid` | Soft delete. |
| `member.added` / `member.removed` | `cid`, `userId`, `role?` | Membership change. |
| `notification.added_to_channel` / `notification.removed_from_channel` | `cid` | User-targeted; delivered to the affected user. |

This table mirrors `@firemoot/client`'s typed `events.ts` - the executable spec
the protocol test suite is written against. New event types are added there with
their exact `data` shape.
