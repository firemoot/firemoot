import { Channel, FiremootServer, type RestApi, type ServerRestApi } from "@firemoot/client";
import type { Message } from "@firemoot/core";
import { describe, expect, test } from "vitest";

import {
  type ServerChannelDeps,
  StreamChatChannel,
  type StreamCompatChannelData,
} from "./channel.js";
import { FiremootCompatError } from "./errors.js";
import {
  CID,
  channelState,
  message,
  rejectingCompatRest,
  rejectingRest,
  rejectingServerRest,
} from "./fakes.js";
import { isDuplicateChannelError, isDuplicateMessageError } from "./message.js";
import type { CompatRestApi } from "./transport.js";

function browserFacade(rest: Partial<RestApi> = {}): {
  facade: StreamChatChannel;
  channel: Channel;
  sent: string[];
} {
  const sent: string[] = [];
  const channel = new Channel("messaging", "general", {
    rest: { ...rejectingRest, ...rest },
    send: (frame) => {
      sent.push(frame);
      return true;
    },
    selfUserId: "alice",
    // Uploads presign then PUT; stub the PUT so no test touches the network.
    putFile: () => Promise.resolve(),
  });
  return { facade: new StreamChatChannel("messaging", "general", channel, null), channel, sent };
}

function serverFacade(
  overrides: {
    rest?: Partial<RestApi>;
    serverRest?: Partial<ServerRestApi>;
    compat?: Partial<CompatRestApi>;
  } = {},
  data: StreamCompatChannelData = {},
): { facade: StreamChatChannel; deps: ServerChannelDeps } {
  const deps: ServerChannelDeps = {
    server: new FiremootServer({
      baseUrl: "http://firemoot.test",
      apiKey: "key",
      apiSecret: "secret",
      rest: { ...rejectingServerRest, ...overrides.serverRest },
    }),
    rest: { ...rejectingRest, ...overrides.rest },
    compat: { ...rejectingCompatRest, ...overrides.compat },
  };
  return { facade: new StreamChatChannel("messaging", "general", null, deps, data), deps };
}

describe("browser mode: state projection", () => {
  test("projects the live reducer into Stream's channel.state shape", () => {
    const { facade, channel } = browserFacade();
    channel.hydrate(
      channelState("general", {
        channel: {
          ...channelState("general").channel,
          custom: { topic: "release planning" },
          currentSeq: 4,
        },
        members: [
          { userId: "alice", role: "member", lastReadSeq: 4 },
          { userId: "bob", role: "member", lastReadSeq: 2 },
        ],
        latestMessage: message("m4", 4, { text: "shipping today", userId: "bob" }),
        read: { lastReadSeq: 4, unreadCount: 0 },
      }),
    );

    const state = facade.state;
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]).toMatchObject({
      id: "m4",
      text: "shipping today",
      user: { id: "bob" },
    });
    expect(state.members).toEqual({
      alice: { userId: "alice", role: "member" },
      bob: { userId: "bob", role: "member" },
    });
    expect(state.read["bob"]).toEqual({ userId: "bob", lastReadSeq: 2 });
    expect(state.unreadCount).toBe(0);
    expect(state.last_message_at).toBe("2026-07-26T10:00:00Z");
    expect(facade.data).toMatchObject({
      topic: "release planning",
      id: "general",
      type: "messaging",
      cid: CID,
    });
  });

  test("lastMessage and countUnread read off the same projection", () => {
    const { facade, channel } = browserFacade();
    expect(facade.lastMessage()).toBeUndefined();
    channel.handleEvent({ type: "message.new", cid: CID, seq: 1, data: message("m1", 1) });
    expect(facade.lastMessage()?.id).toBe("m1");
    expect(facade.countUnread()).toBe(0);
  });
});

describe("browser mode: actions", () => {
  test("sendMessage returns the Stream-shaped { message }", async () => {
    const { facade } = browserFacade({
      sendMessage: (_type, _id, body) =>
        Promise.resolve(message("m-sent", 5, body.text !== undefined ? { text: body.text } : {})),
    });
    const result = await facade.sendMessage({ text: "hello" });
    expect(result.message).toMatchObject({ id: "m-sent", text: "hello", type: "regular" });
  });

  test("unknown top-level keys are folded into custom, as Stream stores them", async () => {
    let sentCustom: unknown;
    const { facade } = browserFacade({
      sendMessage: (_type, _id, body) => {
        sentCustom = body.custom;
        return Promise.resolve(message("m-sent", 5));
      },
    });
    await facade.sendMessage({ text: "hi", priority: "high", custom: { source: "web" } });
    expect(sentCustom).toMatchObject({ priority: "high", source: "web" });
  });

  test("parent_id maps onto Firemoot's parentMessageId", async () => {
    let parent: string | undefined;
    const { facade } = browserFacade({
      sendMessage: (_type, _id, body) => {
        parent = body.parentMessageId;
        return Promise.resolve(message("m-sent", 5));
      },
    });
    await facade.sendMessage({ text: "reply", parent_id: "m1" });
    expect(parent).toBe("m1");
  });

  test("keystroke and stopTyping emit the WS typing frames", async () => {
    const { facade, sent } = browserFacade();
    await facade.keystroke();
    await facade.stopTyping();
    expect(sent.map((f) => (JSON.parse(f) as { type: string }).type)).toEqual([
      "typing.start",
      "typing.stop",
    ]);
  });

  test("markRead delegates to the live channel", async () => {
    let marked = false;
    const { facade } = browserFacade({
      markRead: () => {
        marked = true;
        return Promise.resolve({ lastReadSeq: 3, unreadCount: 0, totalUnread: 0 });
      },
    });
    await facade.markRead();
    expect(marked).toBe(true);
  });

  test("sendImage and sendFile take Stream's 3-argument upload form", async () => {
    const uploads: Array<{ filename: string; mime: string }> = [];
    const { facade } = browserFacade({
      createUpload: (body) => {
        uploads.push({ filename: body.filename, mime: body.mime });
        return Promise.resolve({
          uploadId: `u-${body.filename}`,
          uploadUrl: "http://uploads.test/put",
          objectUrl: `http://cdn.test/${body.filename}`,
          expiresInSeconds: 900,
        });
      },
    });

    const image = await facade.sendImage({ size: 12 }, "logo.png", "image/png");
    const file = await facade.sendFile({ size: 34 }, "notes.pdf", "application/pdf");

    expect(image).toEqual({ file: "http://cdn.test/logo.png" });
    expect(file).toEqual({ file: "http://cdn.test/notes.pdf" });
    expect(uploads).toEqual([
      { filename: "logo.png", mime: "image/png" },
      { filename: "notes.pdf", mime: "application/pdf" },
    ]);
  });

  test("sendReaction returns Stream's { reaction } and deleteReaction delegates", async () => {
    const removed: string[] = [];
    const { facade } = browserFacade({
      addReaction: () => Promise.resolve({ messageId: "m1", counts: { like: 1 } }),
      removeReaction: (_t, _i, _m, reactionType) => {
        removed.push(reactionType);
        return Promise.resolve({ messageId: "m1", counts: {} });
      },
    });
    expect(await facade.sendReaction("m1", { type: "like" })).toEqual({
      reaction: { type: "like", message_id: "m1" },
    });
    await facade.deleteReaction("m1", "like");
    expect(removed).toEqual(["like"]);
  });
});

describe("browser mode: event subscriptions", () => {
  test("on() delivers Stream-shaped events and unsubscribe detaches", () => {
    const { facade, channel } = browserFacade();
    const seen: string[] = [];
    const subscription = facade.on("message.new", (event) => {
      seen.push(String(event.message?.["id"]));
    });

    channel.handleEvent({ type: "message.new", cid: CID, seq: 1, data: message("m1", 1) });
    subscription.unsubscribe();
    channel.handleEvent({ type: "message.new", cid: CID, seq: 2, data: message("m2", 2) });

    expect(seen).toEqual(["m1"]);
  });

  test("off(type, handler) detaches the exact handler on() attached", () => {
    const { facade, channel } = browserFacade();
    const seen: string[] = [];
    const handler = (): void => {
      seen.push("fired");
    };
    facade.on("message.new", handler);
    facade.on("message.new", () => seen.push("other"));

    facade.off("message.new", handler);
    channel.handleEvent({ type: "message.new", cid: CID, seq: 1, data: message("m1", 1) });

    expect(seen).toEqual(["other"]);
  });

  test("Stream's message.read subscription is fed by Firemoot's read.updated", () => {
    const { facade, channel } = browserFacade();
    const readers: Array<string | undefined> = [];
    facade.on("message.read", (event) => readers.push(event.user?.id));

    channel.handleEvent({
      type: "read.updated",
      cid: CID,
      seq: 2,
      data: { cid: CID, userId: "bob", lastReadSeq: 2, unreadCount: 0, totalUnread: 0 },
    });

    expect(readers).toEqual(["bob"]);
  });
});

describe("server mode: provisioning", () => {
  test("create() provisions the channel with its members and custom fields", async () => {
    const created: unknown[] = [];
    const members: string[] = [];
    const { facade } = serverFacade(
      {
        rest: { queryChannels: () => Promise.resolve({ channels: [] }) },
        serverRest: {
          createChannel: (body) => {
            created.push(body);
            return Promise.resolve(channelState("general").channel);
          },
          addMember: (_type, _id, body) => {
            members.push(body.userId);
            return Promise.resolve();
          },
        },
      },
      { members: ["alice", "bob"], created_by_id: "alice", topic: "support" },
    );

    await facade.create();

    expect(created).toEqual([
      { type: "messaging", id: "general", createdBy: "alice", custom: { topic: "support" } },
    ]);
    expect(members).toEqual(["alice", "bob"]);
    expect(facade.data).toMatchObject({ topic: "support" });
  });

  test("create() on an existing channel tops up members then throws Stream's duplicate shape", async () => {
    const added: string[] = [];
    const { facade } = serverFacade(
      {
        rest: {
          queryChannels: () => Promise.resolve({ channels: [channelState("general")] }),
        },
        serverRest: {
          addMember: (_type, _id, body) => {
            added.push(body.userId);
            return Promise.resolve();
          },
        },
      },
      { members: ["alice", "bob"] },
    );

    await expect(facade.create()).rejects.toMatchObject({ code: 4 });
    // Membership is topped up first, so an existing channel still gains its
    // intended members before the caller sees "already exists".
    expect(added).toEqual(["alice", "bob"]);
  });

  test("the duplicate-create error is classified by the Stream-compatible helper", async () => {
    const { facade } = serverFacade({
      rest: { queryChannels: () => Promise.resolve({ channels: [channelState("general")] }) },
      serverRest: { addMember: () => Promise.resolve() },
    });
    const error = await facade.create().catch((e: unknown) => e);
    expect(isDuplicateChannelError(error)).toBe(true);
  });
});

describe("server mode: query and updates", () => {
  test("query() returns the channel and messages oldest-first", async () => {
    const { facade } = serverFacade({
      rest: {
        // The server returns newest-first; readers expect oldest-first.
        getMessages: () => Promise.resolve({ messages: [message("m2", 2), message("m1", 1)] }),
        queryChannels: () =>
          Promise.resolve({
            channels: [
              channelState("general", {
                channel: { ...channelState("general").channel, custom: { topic: "support" } },
              }),
            ],
          }),
      },
    });

    const result = await facade.query({ messages: { limit: 50 } });

    expect(result.messages.map((m) => m.id)).toEqual(["m1", "m2"]);
    expect(result.channel).toMatchObject({ topic: "support", id: "general", cid: CID });
    expect(facade.state.messages.map((m) => m.id)).toEqual(["m1", "m2"]);
  });

  test("a Stream id_lt cursor becomes the server's before_id", async () => {
    let query: { limit?: number; before_id?: string } | undefined;
    const { facade } = serverFacade({
      rest: {
        getMessages: (_type, _id, q) => {
          query = q;
          return Promise.resolve({ messages: [] });
        },
        queryChannels: () => Promise.resolve({ channels: [] }),
      },
    });

    await facade.query({ messages: { limit: 25, id_lt: "m9" } });

    expect(query).toEqual({ limit: 25, before_id: "m9" });
  });

  test("updatePartial patches custom and updates the local view", async () => {
    let patched: Record<string, unknown> | undefined;
    const { facade } = serverFacade({
      compat: {
        patchChannelCustom: (_type, _id, custom) => {
          patched = custom;
          return Promise.resolve();
        },
      },
    });

    await facade.updatePartial({ set: { topic: "renamed" } });

    expect(patched).toEqual({ topic: "renamed" });
    expect(facade.data).toMatchObject({ topic: "renamed" });
  });

  test("delete and addMembers reach the right transports", async () => {
    const added: string[] = [];
    let deleted = false;
    const { facade } = serverFacade({
      compat: {
        deleteChannel: () => {
          deleted = true;
          return Promise.resolve();
        },
      },
      serverRest: {
        addMember: (_type, _id, body) => {
          added.push(body.userId);
          return Promise.resolve();
        },
      },
    });

    await facade.delete();
    await facade.addMembers(["carol"]);

    expect(deleted).toBe(true);
    expect(added).toEqual(["carol"]);
  });
});

describe("server mode: sending", () => {
  test("a caller-supplied id and user_id pass straight through", async () => {
    let body: Record<string, unknown> | undefined;
    const { facade } = serverFacade({
      rest: {
        sendMessage: (_type, _id, sent) => {
          body = sent as unknown as Record<string, unknown>;
          return Promise.resolve(message("seed-1", 1, { text: "seeded", userId: "bob" }));
        },
      },
    });

    const result = await facade.sendMessage({ id: "seed-1", text: "seeded", user_id: "bob" });

    expect(body).toMatchObject({ id: "seed-1", text: "seeded", userId: "bob" });
    expect(result.message).toMatchObject({ id: "seed-1", user: { id: "bob" } });
  });

  test("a 409 Problem body is rethrown as an Error carrying the status", async () => {
    const { facade } = serverFacade({
      rest: {
        // The generated client throws the parsed Problem body, not an Error.
        sendMessage: () =>
          Promise.reject({
            type: "about:blank",
            title: "Conflict",
            detail: "message with id seed-1 already exists",
            status: 409,
          }),
      },
    });

    const error = await facade
      .sendMessage({ id: "seed-1", text: "seeded", user_id: "bob" })
      .catch((e: unknown) => e);

    expect(error).toBeInstanceOf(Error);
    expect(error).toMatchObject({ status: 409 });
    expect(isDuplicateMessageError(error)).toBe(true);
  });

  test("sendMessage without a user_id fails loudly rather than guessing an author", async () => {
    const { facade } = serverFacade();
    await expect(facade.sendMessage({ text: "orphan" })).rejects.toBeInstanceOf(
      FiremootCompatError,
    );
  });
});

describe("the deliberate server-mode no-ops", () => {
  test("watch() and sendEvent() resolve without calling anything", async () => {
    const { facade } = serverFacade();
    await expect(facade.watch()).resolves.toBeUndefined();
    await expect(facade.sendEvent()).resolves.toBeUndefined();
  });
});

describe("loud failures", () => {
  test("an uncovered Stream method throws a FiremootCompatError naming it", () => {
    const { facade } = browserFacade();
    expect(() => facade.mute()).toThrow(FiremootCompatError);
    expect(() => facade.mute()).toThrow(/channel\.mute\(\) is not supported/);
    expect(() => facade.truncate()).toThrow(/compatibility table/);
  });

  test("the stubs are non-enumerable, so they do not leak into spreads", () => {
    const { facade } = browserFacade();
    expect(Object.keys(facade)).not.toContain("mute");
    expect(typeof facade.mute).toBe("function");
  });

  test("a browser-only method on a server channel explains which mode is needed", () => {
    const { facade } = serverFacade();
    expect(() => facade.on("message.new", () => {})).toThrow(/needs a connected user/);
  });

  test("a server-only method on a browser channel explains which mode is needed", async () => {
    const { facade } = browserFacade();
    await expect(facade.create()).rejects.toThrow(/server-side operation/);
  });
});

describe("browser mode: query", () => {
  test("query() watches the channel and returns its state", async () => {
    const messages: Message[] = [message("m1", 1), message("m2", 2)];
    const { facade, sent } = browserFacade({
      getChannel: () => Promise.resolve(channelState("general").channel),
      getMessages: () => Promise.resolve({ messages }),
    });

    const result = await facade.query({ messages: { limit: 10 } });

    expect(result.messages.map((m) => m.id)).toEqual(["m1", "m2"]);
    expect(sent.map((f) => (JSON.parse(f) as { type: string }).type)).toEqual(["subscribe"]);
  });
});
