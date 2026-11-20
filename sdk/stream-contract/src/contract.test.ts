import { type FiremootInstance, TcpProxy, startFiremoot } from "@firemoot/test";
import { StreamClient } from "@stream-io/node-sdk";
import { type Event, type EventType, StreamChat } from "stream-chat";
import { afterAll, beforeAll, describe, expect, test } from "vitest";

declare module "stream-chat" {
  interface CustomChannelData {
    name?: string;
    topic?: string;
  }
}

// Opt-in: needs Docker and a built `firemoot:latest` image, like the dogfood suite.
const RUN = process.env["FIREMOOT_DOGFOOD"] === "1";

describe.runIf(RUN)("contract: getstream.io's own SDKs against Firemoot's v2 API", () => {
  let fm: FiremootInstance;
  let server: StreamClient;
  const clients: StreamChat[] = [];

  beforeAll(async () => {
    fm = await startFiremoot();
    server = new StreamClient(fm.apiKey, fm.apiSecret, { basePath: fm.baseUrl });
    await server.upsertUsers([
      { id: "alice", name: "Alice" },
      { id: "bob", name: "Bob" },
      { id: "carol", name: "Carol" },
      { id: "mallory", name: "Mallory" },
    ]);
    await server.chat.channel("messaging", "general").getOrCreate({
      data: {
        created_by_id: "alice",
        members: [{ user_id: "alice" }, { user_id: "bob" }, { user_id: "carol" }],
        custom: { name: "General" },
      },
    });
  }, 180_000);

  afterAll(async () => {
    await Promise.all(clients.map((c) => c.disconnectUser()));
    await fm?.stop();
  });

  async function connect(id: string, pingIntervalMs?: number): Promise<StreamChat> {
    const client = new StreamChat(fm.apiKey, {
      client: {
        baseURL: fm.baseUrl,
        ...(pingIntervalMs ? { wsConnection: { pingIntervalMs } } : {}),
      },
    });
    const token = server.generateUserToken({ user_id: id });
    await client.connectUser({ id }, () => Promise.resolve(token));
    clients.push(client);
    return client;
  }

  const inGeneral = (e: Event): boolean => "cid" in e && e.cid === "messaging:general";

  function next(client: StreamChat, type: EventType, matches: (e: Event) => boolean = () => true) {
    return new Promise<Event>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no ${type} within 10s`)), 10_000);
      const sub = client.on(type, (event) => {
        if (!matches(event)) return;
        clearTimeout(timer);
        sub.unsubscribe();
        resolve(event);
      });
    });
  }

  test("connectUser resolves with connection.ok carrying the full own user", async () => {
    const alice = await connect("alice");
    expect(alice.userID).toBe("alice");
    expect(alice.user?.name).toBe("Alice");
  });

  test("a server-created channel hydrates through queryChannelsAndHydrate", async () => {
    const bob = await connect("bob");
    const channels = await bob.queryChannelsAndHydrate({
      filter_conditions: { type: "messaging", members: { $in: ["bob"] } },
      sort: [{ field: "last_message_at", direction: -1 }],
    });
    expect(channels.map((c) => c.cid)).toContain("messaging:general");
    const general = channels.find((c) => c.cid === "messaging:general");
    expect(general?.data?.custom?.name).toBe("General");
  });

  test("a message reaches watchers as message.new and unwatched members as notification.message_new", async () => {
    const sender = await connect("alice");
    const watcher = await connect("bob");
    const elsewhere = await connect("carol");
    await sender.channel("messaging", "general").watch();
    await watcher.channel("messaging", "general").watch();

    const received = next(watcher, "message.new", inGeneral);
    const notified = next(elsewhere, "notification.message_new");
    const sent = await sender.channel("messaging", "general").sendMessage({
      message: { text: "hello from alice" },
    });

    const event = await received;
    expect(event.type).toBe("message.new");
    if (event.type !== "message.new") return;
    expect(event.message.id).toBe(sent.message.id);
    expect(event.message.text).toBe("hello from alice");
    expect(event.user?.id).toBe("alice");
    expect(inGeneral(await notified)).toBe(true);
  });

  test("markRead tells the other members with message.read", async () => {
    const alice = await connect("alice");
    const bob = await connect("bob");
    const aliceChannel = alice.channel("messaging", "general");
    const bobChannel = bob.channel("messaging", "general");
    await aliceChannel.watch();
    await bobChannel.watch();
    await aliceChannel.sendMessage({ message: { text: "read me" } });

    const read = next(alice, "message.read", inGeneral);
    await bobChannel.markRead();
    const event = await read;
    if (event.type !== "message.read") throw new Error(`unexpected ${event.type}`);
    expect(event.user?.id).toBe("bob");
  });

  test("keystroke delivers typing.start to the other watchers", async () => {
    const alice = await connect("alice");
    const bob = await connect("bob");
    await alice.channel("messaging", "general").watch();
    await bob.channel("messaging", "general").watch();

    const typing = next(alice, "typing.start", inGeneral);
    await bob.channel("messaging", "general").keystroke();
    const event = await typing;
    if (event.type !== "typing.start") throw new Error(`unexpected ${event.type}`);
    expect(event.user?.id).toBe("bob");
  });

  test("a partial update reaches watchers as channel.updated", async () => {
    const alice = await connect("alice");
    const bob = await connect("bob");
    const aliceChannel = alice.channel("messaging", "general");
    await aliceChannel.watch();
    await bob.channel("messaging", "general").watch();

    const updated = next(bob, "channel.updated", inGeneral);
    const response = await aliceChannel.updatePartial({ set: { topic: "launch" } });
    expect(response.channel?.custom?.topic).toBe("launch");
    const event = await updated;
    if (event.type !== "channel.updated") throw new Error(`unexpected ${event.type}`);
    expect(event.channel?.custom?.topic).toBe("launch");
    expect(event.channel?.custom?.name).toBe("General");
  });

  test("the server SDK sends as a user and the message arrives over the socket", async () => {
    const bob = await connect("bob");
    await bob.channel("messaging", "general").watch();
    const received = next(bob, "message.new", inGeneral);
    await server.chat.channel("messaging", "general").sendMessage({
      message: { text: "from the backend", user_id: "alice" },
    });
    const event = await received;
    if (event.type !== "message.new") throw new Error(`unexpected ${event.type}`);
    expect(event.message.text).toBe("from the backend");
    expect(event.message.user?.id).toBe("alice");
  });

  test("reactions round-trip as reaction.new and reaction.deleted with counts", async () => {
    const alice = await connect("alice");
    const bob = await connect("bob");
    const aliceChannel = alice.channel("messaging", "general");
    await aliceChannel.watch();
    await bob.channel("messaging", "general").watch();
    const { message } = await aliceChannel.sendMessage({ message: { text: "react to me" } });

    const added = next(bob, "reaction.new", inGeneral);
    const response = await alice.sendReaction({ id: message.id }, { reaction: { type: "like" } });
    expect(response.reaction.type).toBe("like");
    expect(response.message.own_reactions.map((r) => r.type)).toEqual(["like"]);
    const event = await added;
    if (event.type !== "reaction.new") throw new Error(`unexpected ${event.type}`);
    expect(event.reaction?.user?.id).toBe("alice");
    expect(event.message?.reaction_counts?.["like"]).toBe(1);

    const removed = next(bob, "reaction.deleted", inGeneral);
    await alice.deleteReaction({ id: message.id, type: "like" });
    const gone = await removed;
    if (gone.type !== "reaction.deleted") throw new Error(`unexpected ${gone.type}`);
    expect(gone.message?.reaction_counts?.["like"] ?? 0).toBe(0);
  });

  test("edits and deletes reach watchers as message.updated and message.deleted", async () => {
    const alice = await connect("alice");
    const bob = await connect("bob");
    const aliceChannel = alice.channel("messaging", "general");
    await aliceChannel.watch();
    await bob.channel("messaging", "general").watch();
    const { message } = await aliceChannel.sendMessage({ message: { text: "first draft" } });

    const updated = next(bob, "message.updated", inGeneral);
    await alice.updateMessage({ id: message.id }, { message: { text: "final draft" } });
    const edit = await updated;
    if (edit.type !== "message.updated") throw new Error(`unexpected ${edit.type}`);
    expect(edit.message.text).toBe("final draft");

    const deleted = next(bob, "message.deleted", inGeneral);
    await alice.deleteMessage({ id: message.id });
    const del = await deleted;
    if (del.type !== "message.deleted") throw new Error(`unexpected ${del.type}`);
    expect(del.message.id).toBe(message.id);
    expect(del.message.type).toBe("deleted");
  });

  test("thread replies are listed by getReplies and search finds message text", async () => {
    const alice = await connect("alice");
    const channel = alice.channel("messaging", "general");
    await channel.watch();
    const { message: parent } = await channel.sendMessage({
      message: { text: "parent of a thread" },
    });
    await channel.sendMessage({ message: { text: "a reply with a needle", parent_id: parent.id } });

    const replies = await alice.getReplies({ parent_id: parent.id });
    expect(replies.messages.map((m) => m.text)).toContain("a reply with a needle");

    const found = await alice.search({
      payload: { filter_conditions: { cid: "messaging:general" }, query: "needle" },
    });
    expect(found.results.map((r) => r.message?.text)).toContain("a reply with a needle");
  });

  test("the server SDK flags a message", async () => {
    const alice = await connect("alice");
    const { message } = await alice
      .channel("messaging", "general")
      .sendMessage({ message: { text: "flag me" } });
    const flagged = await server.moderation.flag({
      entity_type: "stream:chat:v1:message",
      entity_id: message.id,
      user_id: "bob",
      reason: "spam",
    });
    expect(flagged.item_id).toBeTruthy();
  });

  test("the server SDK batch-deletes channels and users as completed tasks", async () => {
    await server.upsertUsers([{ id: "temp-user" }]);
    await server.chat.channel("messaging", "temp").getOrCreate({
      data: { created_by_id: "alice", members: [{ user_id: "alice" }] },
    });

    const channels = await server.chat.deleteChannels({ cids: ["messaging:temp"] });
    expect(channels.result?.["messaging:temp"]?.status).toBe("ok");
    const users = await server.deleteUsers({ user_ids: ["temp-user"] });
    const task = await server.getTask({ id: users.task_id });
    expect(task.status).toBe("completed");

    const remaining = await server.queryUsers({
      payload: { filter_conditions: { id: { $in: ["temp-user"] } } },
    });
    expect(remaining.users).toEqual([]);
  });

  test("a non-member is refused with Stream's not-allowed error", async () => {
    const mallory = await connect("mallory");
    await expect(mallory.channel("messaging", "general").watch()).rejects.toMatchObject({
      code: 17,
    });
  });

  test("a token signed with the wrong secret fails connectUser", async () => {
    const forger = new StreamClient(fm.apiKey, "not-the-secret", { basePath: fm.baseUrl });
    const client = new StreamChat(fm.apiKey, { client: { baseURL: fm.baseUrl } });
    const token = forger.generateUserToken({ user_id: "alice" });
    await expect(
      client.connectUser({ id: "alice" }, () => Promise.resolve(token)),
    ).rejects.toBeDefined();
  });

  test("after the socket drops, the SDK reconnects, recovers and keeps receiving the channel", async () => {
    const target = new URL(fm.baseUrl);
    const proxy = new TcpProxy(target.hostname, Number(target.port));
    const port = await proxy.start();
    try {
      const watcher = new StreamChat(fm.apiKey, {
        client: { baseURL: `http://127.0.0.1:${port}` },
      });
      const token = server.generateUserToken({ user_id: "bob" });
      await watcher.connectUser({ id: "bob" }, () => Promise.resolve(token));
      clients.push(watcher);
      const channel = watcher.channel("messaging", "general");
      await channel.watch();
      channel.activate();

      const recovered = next(watcher, "connection.recovered");
      proxy.drop();
      await recovered;
      const sender = await connect("alice");
      const received = next(watcher, "message.new", inGeneral);
      await sender
        .channel("messaging", "general")
        .sendMessage({ message: { text: "after the drop" } });
      const event = await received;
      if (event.type !== "message.new") throw new Error(`unexpected ${event.type}`);
      expect(event.message.text).toBe("after the drop");
    } finally {
      await proxy.stop();
    }
  }, 30_000);

  test("health checks keep an idle socket open", async () => {
    const alice = await connect("alice", 1_000);
    const bob = await connect("bob");
    await alice.channel("messaging", "general").watch();
    await new Promise((resolve) => setTimeout(resolve, 3_500));
    const received = next(alice, "message.new", inGeneral);
    await bob.channel("messaging", "general").sendMessage({ message: { text: "still there?" } });
    expect((await received).type).toBe("message.new");
  }, 20_000);
});
