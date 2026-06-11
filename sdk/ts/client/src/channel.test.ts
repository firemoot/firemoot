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
