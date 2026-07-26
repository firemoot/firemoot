import type { RestApi, ServerRestApi, SocketFactory } from "@firemoot/client";
import { afterEach, describe, expect, test } from "vitest";

import { StreamChat, toChannelQuery, type StreamCompatOptions } from "./client.js";
import { FiremootCompatError } from "./errors.js";
import {
  CID,
  channelMeta,
  channelState,
  completeHandshake,
  FakeSocket,
  message,
  rejectingCompatRest,
  rejectingRest,
  rejectingServerRest,
} from "./fakes.js";
import type { CompatRestApi } from "./transport.js";

const BASE_URL = "http://firemoot.test";

function browserClient(
  rest: Partial<RestApi> = {},
  compat: Partial<CompatRestApi> = {},
): {
  client: StreamChat;
  sockets: FakeSocket[];
} {
  const sockets: FakeSocket[] = [];
  const socketFactory: SocketFactory = () => {
    const socket = new FakeSocket();
    sockets.push(socket);
    return socket;
  };
  const client = new StreamChat("api-key", {
    baseURL: BASE_URL,
    firemoot: {
      socketFactory,
      rest: { ...rejectingRest, ...rest },
      compatRest: { ...rejectingCompatRest, ...compat },
    },
  });
  return { client, sockets };
}

/** Connects `alice`, driving the fake socket through its handshake. */
async function connected(
  rest: Partial<RestApi> = {},
  compat: Partial<CompatRestApi> = {},
): Promise<{ client: StreamChat; socket: FakeSocket }> {
  const { client, sockets } = browserClient(rest, compat);
  const connecting = client.connectUser({ id: "alice", name: "Alice" }, "user-token");
  const socket = await completeHandshake(sockets);
  await connecting;
  return { client, socket };
}

function serverClient(
  overrides: {
    rest?: Partial<RestApi>;
    serverRest?: Partial<ServerRestApi>;
    compat?: Partial<CompatRestApi>;
    options?: StreamCompatOptions["firemoot"];
  } = {},
): StreamChat {
  return new StreamChat("api-key", "api-secret", {
    baseURL: BASE_URL,
    firemoot: {
      rest: { ...rejectingRest, ...overrides.rest },
      serverRest: { ...rejectingServerRest, ...overrides.serverRest },
      compatRest: { ...rejectingCompatRest, ...overrides.compat },
      ...overrides.options,
    },
  });
}

afterEach(() => {
  StreamChat._instance = undefined;
});

describe("construction modes", () => {
  test("new StreamChat(key, options) is browser mode", () => {
    const client = new StreamChat("api-key", { baseURL: BASE_URL });
    expect(client.key).toBe("api-key");
    expect(client.getAuthType()).toBe("jwt");
    // No secret, so the server-side surface refuses rather than half-working.
    expect(() => client.createToken("alice")).toThrow(/needs your API secret/);
  });

  test("new StreamChat(key, secret, options) is server-trusted mode", () => {
    const client = new StreamChat("api-key", "api-secret", { baseURL: BASE_URL });
    expect(typeof client.createToken("alice")).toBe("string");
    // Browser-only surface is refused just as clearly the other way round.
    expect(() => client.channel("messaging", "general").state).not.toThrow();
  });

  test("baseURL is required, and setBaseURL is honoured as an alternative", async () => {
    const client = new StreamChat("api-key", {});
    await expect(client.connectUser({ id: "alice" }, "t")).rejects.toThrow(
      /needs your Firemoot server URL/,
    );
    client.setBaseURL(`${BASE_URL}/`);
    // Trailing slashes are trimmed so URL joins stay well-formed.
    expect(() => client.channel("messaging", "general")).toThrow(/connectUser/);
  });

  test("Stream's other transport options are accepted and ignored", () => {
    const client = new StreamChat("api-key", {
      baseURL: BASE_URL,
      timeout: 5000,
      warmUp: true,
      browser: true,
    });
    expect(client.key).toBe("api-key");
  });
});

describe("getInstance singleton", () => {
  test("returns the same instance and ignores later arguments, as Stream does", () => {
    const first = StreamChat.getInstance("api-key", { baseURL: BASE_URL });
    const second = StreamChat.getInstance("other-key", { baseURL: "http://elsewhere.test" });
    expect(second).toBe(first);
    expect(second.key).toBe("api-key");
  });

  test("the secret overload constructs a server-trusted singleton", () => {
    const client = StreamChat.getInstance("api-key", "api-secret", { baseURL: BASE_URL });
    expect(typeof client.createToken("alice")).toBe("string");
    expect(StreamChat.getInstance("api-key")).toBe(client);
  });
});

describe("connectUser", () => {
  test("opens the socket and exposes the connected user", async () => {
    const { client, socket } = await connected();
    expect(client.userID).toBe("alice");
    expect(client.user).toMatchObject({ id: "alice", name: "Alice" });
    expect(socket.closed).toBe(false);
  });

  test("a repeat connect for the same user is a no-op", async () => {
    const { client, sockets } = browserClient();
    const first = client.connectUser({ id: "alice" }, "token");
    await completeHandshake(sockets);
    await first;

    await client.connectUser({ id: "alice" }, "token");

    expect(sockets).toHaveLength(1);
  });

  test("disconnectUser clears the user and the memoised channels", async () => {
    const { client } = await connected();
    await client.disconnectUser();
    expect(client.userID).toBeUndefined();
    expect(client.user).toBeNull();
    expect(() => client.channel("messaging", "general")).toThrow(/connectUser/);
  });

  test("a token provider is resolved rather than a static string", async () => {
    const { client, sockets } = browserClient();
    let calls = 0;
    const connecting = client.connectUser({ id: "alice" }, () => {
      calls += 1;
      return "fresh-token";
    });
    await completeHandshake(sockets);
    await connecting;
    expect(calls).toBeGreaterThan(0);
  });
});

describe("channel()", () => {
  test("memoises the facade per cid", async () => {
    const { client } = await connected();
    expect(client.channel("messaging", "general")).toBe(client.channel("messaging", "general"));
  });

  test("a later call carrying data does not reuse the memoised facade", () => {
    const client = serverClient();
    const first = client.channel("messaging", "general");
    const withData = client.channel("messaging", "general", { members: ["alice"] });
    expect(withData).not.toBe(first);
  });

  test("a channel with no id is refused - Firemoot has no distinct channels", () => {
    const client = serverClient();
    expect(() => client.channel("messaging")).toThrow(/requires a channel id/);
  });
});

describe("queryChannels", () => {
  test("translates Stream's filter shapes onto a Firemoot ChannelQuery", () => {
    expect(toChannelQuery({ type: "messaging", members: { $in: ["alice"] } })).toEqual({
      type: "messaging",
      members: ["alice"],
    });
    expect(toChannelQuery({ type: "messaging", id: "general" })).toEqual({
      type: "messaging",
      cids: [CID],
    });
    expect(toChannelQuery({ type: "messaging", id: { $in: ["general", "support"] } })).toEqual({
      type: "messaging",
      cids: ["messaging:general", "messaging:support"],
    });
    expect(toChannelQuery({ cid: { $in: [CID] } })).toEqual({ cids: [CID] });
  });

  test("a page limit is clamped to the server's maximum", () => {
    expect(toChannelQuery({ type: "messaging" }, 500)).toEqual({ type: "messaging", limit: 100 });
  });

  test("filtering by id without a type is refused rather than guessed", () => {
    expect(() => toChannelQuery({ id: "general" })).toThrow(/also needs a `type`/);
  });

  test("sorts newest-first by last_message_at and subscribes when watching", async () => {
    const { client, sockets } = browserClient({
      queryChannels: () =>
        Promise.resolve({
          channels: [
            channelState("quiet", {
              channel: channelMeta("quiet", { createdAt: "2026-07-20T09:00:00Z" }),
            }),
            channelState("busy", {
              channel: channelMeta("busy", { currentSeq: 9 }),
              latestMessage: message("m9", 9, { createdAt: "2026-07-26T12:00:00Z" }),
            }),
          ],
        }),
    });
    const connecting = client.connectUser({ id: "alice" }, "token");
    const socket = await completeHandshake(sockets);
    await connecting;

    const channels = await client.queryChannels(
      { type: "messaging", members: { $in: ["alice"] } },
      { last_message_at: -1 },
      { watch: true },
    );

    expect(channels.map((c) => c.id)).toEqual(["busy", "quiet"]);
    const subscribes = socket
      .frames()
      .filter((f) => f["type"] === "subscribe")
      .map((f) => f["channels"]);
    expect(subscribes).toContainEqual({ "messaging:busy": 9 });
  });

  test("ascending sort reverses the order", async () => {
    const { client, sockets } = browserClient({
      queryChannels: () =>
        Promise.resolve({
          channels: [
            channelState("busy", {
              latestMessage: message("m9", 9, { createdAt: "2026-07-26T12:00:00Z" }),
            }),
            channelState("quiet", {
              channel: channelMeta("quiet", { createdAt: "2026-07-20T09:00:00Z" }),
            }),
          ],
        }),
    });
    const connecting = client.connectUser({ id: "alice" }, "token");
    await completeHandshake(sockets);
    await connecting;

    const channels = await client.queryChannels({ type: "messaging" }, { last_message_at: 1 });

    expect(channels.map((c) => c.id)).toEqual(["quiet", "busy"]);
  });

  test("server mode returns hydrated facades without a socket", async () => {
    const client = serverClient({
      rest: {
        queryChannels: () =>
          Promise.resolve({
            channels: [
              channelState("general", {
                channel: channelMeta("general", { custom: { topic: "support" } }),
                latestMessage: message("m1", 1, { text: "hello" }),
              }),
            ],
          }),
      },
    });

    const channels = await client.queryChannels({ type: "messaging" });

    expect(channels).toHaveLength(1);
    expect(channels[0]?.data).toMatchObject({ topic: "support" });
    expect(channels[0]?.state.messages.map((m) => m.text)).toEqual(["hello"]);
  });
});

describe("client events", () => {
  test("on(type, handler) translates Firemoot events into Stream-shaped ones", async () => {
    const { client, socket } = await connected();
    const seen: Array<Record<string, unknown>> = [];
    client.on("message.new", (event) => seen.push(event as unknown as Record<string, unknown>));

    socket.deliver({
      type: "message.new",
      cid: CID,
      seq: 1,
      data: message("m1", 1, { text: "hi", userId: "bob" }),
    });

    expect(seen).toHaveLength(1);
    expect(seen[0]).toMatchObject({ type: "message.new", cid: CID, user: { id: "bob" } });
  });

  test("the two no-equivalent events subscribe harmlessly and never fire", async () => {
    const { client, socket } = await connected();
    let fired = 0;
    const a = client.on("notification.message_new", () => (fired += 1));
    const b = client.on("notification.mark_unread", () => (fired += 1));

    socket.deliver({ type: "message.new", cid: CID, seq: 1, data: message("m1", 1) });

    expect(fired).toBe(0);
    expect(() => {
      a.unsubscribe();
      b.unsubscribe();
    }).not.toThrow();
  });

  test("unsubscribe detaches the handler", async () => {
    const { client, socket } = await connected();
    let fired = 0;
    const subscription = client.on("message.new", () => (fired += 1));
    socket.deliver({ type: "message.new", cid: CID, seq: 1, data: message("m1", 1) });
    subscription.unsubscribe();
    socket.deliver({ type: "message.new", cid: CID, seq: 2, data: message("m2", 2) });
    expect(fired).toBe(1);
  });

  test("the all-events form receives every event under its Stream name", async () => {
    const { client, socket } = await connected();
    const types: string[] = [];
    client.on((event) => types.push(event.type));

    socket.deliver({ type: "message.new", cid: CID, seq: 1, data: message("m1", 1) });
    socket.deliver({
      type: "presence.changed",
      cid: "",
      seq: 0,
      data: { userId: "bob", status: "online" },
    });

    expect(types).toEqual(["message.new", "user.presence.changed"]);
  });

  test("an unknown Stream event name is refused rather than silently ignored", async () => {
    const { client } = await connected();
    expect(() => client.on("channel.hidden" as never, () => {})).toThrow(FiremootCompatError);
  });
});

describe("flagMessage", () => {
  test("resolves the owning channel from the watched state", async () => {
    const flags: Array<{ type: string; id: string; messageId: string; userId: string }> = [];
    const { client, socket } = await connected(
      {
        getChannel: () => Promise.resolve(channelMeta("general")),
        getMessages: () => Promise.resolve({ messages: [message("m1", 1)] }),
      },
      {
        flagMessage: (type, id, messageId, body) => {
          flags.push({ type, id, messageId, userId: body.userId });
          return Promise.resolve();
        },
      },
    );
    void socket;

    await client.channel("messaging", "general").watch();
    await client.flagMessage("m1", { reason: "spam" });

    expect(flags).toEqual([{ type: "messaging", id: "general", messageId: "m1", userId: "alice" }]);
  });

  test("a message in no watched channel fails loudly", async () => {
    const { client } = await connected();
    await expect(client.flagMessage("nope")).rejects.toThrow(/not in any watched channel/);
  });

  test("a server client explains why it cannot flag", async () => {
    const client = serverClient();
    await expect(client.flagMessage("m1")).rejects.toThrow(/channel-scoped/);
  });
});

describe("createToken", () => {
  test("is synchronous and returns a token, matching stream-chat's signature", () => {
    const client = serverClient();
    const token: string = client.createToken("alice");
    expect(token.split(".")).toHaveLength(3);
  });

  test("defaults to a one-hour expiry because Firemoot requires one", () => {
    const client = serverClient({ options: { now: () => 1_700_000_000_000 } });
    const payload = JSON.parse(
      Buffer.from(client.createToken("alice").split(".")[1] as string, "base64url").toString(),
    ) as { sub: string; exp: number };
    expect(payload).toEqual({ sub: "alice", exp: 1_700_000_000 + 3600 });
  });

  test("an explicit exp and iat are passed through as epoch seconds", () => {
    const client = serverClient();
    const payload = JSON.parse(
      Buffer.from(
        client.createToken("bob", 1_900_000_000, 1_800_000_000).split(".")[1] as string,
        "base64url",
      ).toString(),
    ) as Record<string, unknown>;
    expect(payload).toEqual({ sub: "bob", exp: 1_900_000_000, iat: 1_800_000_000 });
  });
});

describe("user administration", () => {
  test("upsertUser folds Stream's top-level custom fields into Firemoot's custom", async () => {
    let body: Record<string, unknown> | undefined;
    const client = serverClient({
      serverRest: {
        upsertUser: (sent) => {
          body = sent as unknown as Record<string, unknown>;
          return Promise.resolve({
            id: "alice",
            role: "user",
            custom: {},
            createdAt: "t",
            updatedAt: "t",
          });
        },
      },
    });

    await client.upsertUser({ id: "alice", name: "Alice", role: "admin", team: "support" });

    expect(body).toEqual({
      id: "alice",
      name: "Alice",
      role: "admin",
      custom: { team: "support" },
    });
  });

  test("deleteUser and deleteMessage reach the right transports", async () => {
    const deleted: string[] = [];
    const client = serverClient({
      compat: {
        deleteUser: (userId) => {
          deleted.push(`user:${userId}`);
          return Promise.resolve();
        },
      },
      serverRest: {
        deleteMessage: (messageId) => {
          deleted.push(`message:${messageId}`);
          return Promise.resolve();
        },
      },
    });

    await client.deleteUser("bob", { hard_delete: true });
    await client.deleteMessage("m1", true);

    expect(deleted).toEqual(["user:bob", "message:m1"]);
  });
});

describe("loud failures", () => {
  test("an uncovered Stream method throws a FiremootCompatError naming it", () => {
    const client = serverClient();
    expect(() => client.queryUsers()).toThrow(FiremootCompatError);
    expect(() => client.queryUsers()).toThrow(/client\.queryUsers\(\) is not supported/);
    expect(() => client.devToken("alice")).toThrow(/compatibility table/);
  });

  test("the error names the package and points at the guide", () => {
    const client = serverClient();
    expect(() => client.muteUser()).toThrow(
      /@firemoot\/stream-compat \(v0\.0\.0\).*firemoot\.com\/guide\/stream-compat/s,
    );
  });

  test("browser-only calls before connectUser say so", () => {
    const client = new StreamChat("api-key", { baseURL: BASE_URL });
    expect(() => client.on("message.new", () => {})).toThrow(/call connectUser\(\) first/);
  });
});
