import type { Channel as ChannelData, Message } from "@firemoot/core";
import { describe, expect, test } from "vitest";

import { Channel, type ChannelDeps } from "./channel.js";
import { NONCE_KEY } from "./outbox.js";
import type { RestApi } from "./rest.js";

const CID = "messaging:general";

function msg(id: string, seq: number, nonce?: string): Message {
  return {
    id,
    cid: CID,
    seq,
    type: "regular",
    custom: nonce === undefined ? {} : { [NONCE_KEY]: nonce },
    attachments: [],
    replyCount: 0,
    createdAt: "t",
    updatedAt: "t",
  };
}

const channelData: ChannelData = {
  cid: CID,
  type: "messaging",
  id: "general",
  custom: {},
  frozen: false,
  archived: false,
  currentSeq: 0,
  createdAt: "t",
  updatedAt: "t",
};

function fakeRest(over: Partial<RestApi> = {}): RestApi {
  const reject = (): Promise<never> => Promise.reject(new Error("not implemented"));
  return {
    getChannel: () => Promise.resolve(channelData),
    getMessages: () => Promise.resolve({ messages: [] }),
    sendMessage: reject,
    editMessage: reject,
    deleteMessage: () => Promise.resolve(),
    addReaction: reject,
    removeReaction: reject,
    markRead: reject,
    queryChannels: () => Promise.resolve({ channels: [] }),
    createUpload: reject,
    ...over,
  };
}

interface Harness {
  channel: Channel;
  sends: string[];
}

function mk(rest: Partial<RestApi> = {}, deps: Partial<ChannelDeps> = {}): Harness {
  const sends: string[] = [];
  const channel = new Channel("messaging", "general", {
    rest: fakeRest(rest),
    send: (frame) => {
      sends.push(frame);
      return true;
    },
    selfUserId: "alice",
    typingThrottleMs: 1000,
    randomNonce: () => "n1",
    ...deps,
  });
  return { channel, sends };
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe("Channel optimistic send", () => {
  test("appears immediately and reconciles when the REST response arrives", async () => {
    const gate = deferred<Message>();
    const { channel } = mk({ sendMessage: () => gate.promise });
    const promise = channel.sendMessage({ text: "hi" });

    expect(channel.messages).toHaveLength(1);
    expect(channel.messages[0]?.id).toBe("firemoot-temp-n1");
    expect(channel.pendingSends[0]?.status).toBe("sending");

    gate.resolve(msg("real", 5, "n1"));
    await promise;

    expect(channel.pendingSends).toHaveLength(0);
    expect(channel.messages.map((m) => m.id)).toEqual(["real"]);
  });

  test("reconciles when message.new arrives before the REST response (no duplicate)", async () => {
    const gate = deferred<Message>();
    const { channel } = mk({ sendMessage: () => gate.promise });
    const promise = channel.sendMessage({ text: "hi" });

    channel.handleEvent({ type: "message.new", cid: CID, seq: 5, data: msg("real", 5, "n1") });
    expect(channel.pendingSends).toHaveLength(0);
    expect(channel.messages.map((m) => m.id)).toEqual(["real"]);

    gate.resolve(msg("real", 5, "n1"));
    await promise;
    expect(channel.messages.map((m) => m.id)).toEqual(["real"]);
  });

  test("a failed send is retained as failed", async () => {
    const { channel } = mk({ sendMessage: () => Promise.reject(new Error("boom")) });
    await expect(channel.sendMessage({ text: "hi" })).rejects.toThrow("boom");
    expect(channel.pendingSends).toHaveLength(1);
    expect(channel.pendingSends[0]?.status).toBe("failed");
  });
});

describe("Channel event handling", () => {
  test("applies events to the cache and emits typed + change", () => {
    const { channel } = mk();
    const ids: string[] = [];
    let changes = 0;
    channel.on("message.new", (e) => ids.push(e.data.id));
    channel.on("change", () => (changes += 1));

    channel.handleEvent({ type: "message.new", cid: CID, seq: 1, data: msg("a", 1) });
    expect(channel.messages.map((m) => m.id)).toEqual(["a"]);
    expect(ids).toEqual(["a"]);
    expect(changes).toBeGreaterThan(0);

    channel.handleEvent({ type: "typing.start", cid: CID, seq: 0, data: { userId: "bob" } });
    expect(channel.typing).toEqual(["bob"]);
  });
});

describe("Channel typing throttle", () => {
  test("emits typing.start at most once per window", () => {
    let clock = 0;
    const { channel, sends } = mk({}, { now: () => clock });
    channel.keystroke();
    channel.keystroke();
    clock = 1000;
    channel.keystroke();
    const starts = sends.filter((f) => f.includes('"typing.start"'));
    expect(starts).toHaveLength(2);
  });
});

describe("Channel watch", () => {
  test("loads recent state and subscribes with the resume seq", async () => {
    const { channel, sends } = mk({
      getMessages: () => Promise.resolve({ messages: [msg("b", 4), msg("a", 3)] }),
    });
    await channel.watch();
    expect(channel.isWatching).toBe(true);
    expect(channel.lastSeq).toBe(4);
    expect(channel.messages.map((m) => m.id)).toEqual(["a", "b"]);
    expect(sends).toContain(
      JSON.stringify({ type: "subscribe", channels: { "messaging:general": 4 } }),
    );
  });
});

describe("Channel removeReaction", () => {
  test("removes the connected user's own reaction via REST", async () => {
    const calls: Array<[string, string, string]> = [];
    const { channel } = mk({
      removeReaction: (_t, _i, messageId, reactionType, userId) => {
        calls.push([messageId, reactionType, userId]);
        return Promise.resolve({ messageId, counts: {} });
      },
    });
    await channel.removeReaction("m1", "like");
    expect(calls).toEqual([["m1", "like", "alice"]]);
  });
});

describe("Channel sendFileMessage", () => {
  test("presigns, PUTs the bytes, then sends a message with the attachment", async () => {
    const puts: Array<{ url: string; contentType: string }> = [];
    let sentAttachments: Array<Record<string, unknown>> = [];
    const { channel } = mk(
      {
        createUpload: () =>
          Promise.resolve({
            uploadId: "u1",
            uploadUrl: "https://s3/put",
            objectUrl: "https://cdn/obj.png",
            expiresInSeconds: 60,
          }),
        sendMessage: (_t, _i, body) => {
          sentAttachments = (body.attachments ?? []) as Array<Record<string, unknown>>;
          return Promise.resolve(msg("real", 5));
        },
      },
      {
        putFile: (url, _body, contentType) => {
          puts.push({ url, contentType });
          return Promise.resolve();
        },
      },
    );
    const result = await channel.sendFileMessage({
      name: "pic.png",
      type: "image/png",
      size: 123,
      body: "bytes",
    });
    expect(puts).toEqual([{ url: "https://s3/put", contentType: "image/png" }]);
    expect(result.id).toBe("real");
    expect(sentAttachments[0]).toMatchObject({
      type: "image",
      url: "https://cdn/obj.png",
      name: "pic.png",
      mime: "image/png",
    });
  });
});

describe("Channel hydrate", () => {
  test("seeds members, the read map, read state and the latest message", () => {
    const { channel } = mk();
    channel.hydrate({
      channel: { ...channelData, currentSeq: 9 },
      members: [
        { userId: "alice", role: "owner", lastReadSeq: 9 },
        { userId: "bob", role: "member", lastReadSeq: 4 },
      ],
      read: { lastReadSeq: 9, unreadCount: 0 },
      latestMessage: msg("last", 7),
    });
    expect(channel.members).toEqual([
      { userId: "alice", role: "owner" },
      { userId: "bob", role: "member" },
    ]);
    expect(channel.readReceipts).toEqual({ alice: 9, bob: 4 });
    expect(channel.read).toEqual({ lastReadSeq: 9, unreadCount: 0, totalUnread: 0 });
    expect(channel.messages.map((m) => m.id)).toEqual(["last"]);
    expect(channel.lastSeq).toBe(9);
  });

  test("watchFromHydrated subscribes from the channel's current seq", () => {
    const { channel, sends } = mk();
    channel.hydrate({ channel: { ...channelData, currentSeq: 9 }, members: [] });
    channel.watchFromHydrated();
    expect(channel.isWatching).toBe(true);
    expect(sends).toContain(
      JSON.stringify({ type: "subscribe", channels: { "messaging:general": 9 } }),
    );
  });
});
