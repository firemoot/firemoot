import type { Message } from "@firemoot/core";
import { afterEach, describe, expect, test, vi } from "vitest";

import { FiremootClient } from "./client.js";
import type { Socket, SocketFactory } from "./connection.js";
import type { PresenceEvent } from "./events.js";
import type { RestApi } from "./rest.js";

const CID = "messaging:general";

function msg(id: string, seq: number): Message {
  return {
    id,
    cid: CID,
    seq,
    type: "regular",
    custom: {},
    attachments: [],
    replyCount: 0,
    createdAt: "t",
    updatedAt: "t",
  };
}

class FakeSocket implements Socket {
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((data: string) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: ((error: unknown) => void) | null = null;
  send(data: string): void {
    this.sent.push(data);
  }
  close(): void {
    this.onclose?.();
  }
  hello(): void {
    this.onmessage?.(
      JSON.stringify({
        type: "hello",
        connectionId: "c1",
        serverTime: "t",
        me: {},
        totalUnread: 0,
      }),
    );
  }
  deliver(frame: unknown): void {
    this.onmessage?.(JSON.stringify(frame));
  }
  drop(): void {
    this.onclose?.();
  }
}

const rest: RestApi = {
  getChannel: () =>
    Promise.resolve({
      cid: CID,
      type: "messaging",
      id: "general",
      custom: {},
      frozen: false,
      archived: false,
      currentSeq: 0,
      createdAt: "t",
      updatedAt: "t",
    }),
  getMessages: () => Promise.resolve({ messages: [] }),
  sendMessage: () => Promise.reject(new Error("unused")),
  editMessage: () => Promise.reject(new Error("unused")),
  deleteMessage: () => Promise.resolve(),
  addReaction: () => Promise.reject(new Error("unused")),
  removeReaction: () => Promise.reject(new Error("unused")),
  markRead: () => Promise.reject(new Error("unused")),
};

function mkClient(): { client: FiremootClient; sockets: FakeSocket[] } {
  const sockets: FakeSocket[] = [];
  const factory: SocketFactory = () => {
    const socket = new FakeSocket();
    sockets.push(socket);
    return socket;
  };
  const client = new FiremootClient({
    baseUrl: "http://localhost:6668",
    userId: "alice",
    token: "tok",
    socketFactory: factory,
    reconnect: { baseDelayMs: 10, maxDelayMs: 10 },
    rest,
  });
  return { client, sockets };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("FiremootClient routing", () => {
  test("routes channel events to the channel and surfaces them globally", async () => {
    const { client, sockets } = mkClient();
    const channel = client.channel("messaging", "general");
    const promise = client.connect();
    await Promise.resolve();
    sockets[0]?.hello();
    await promise;

    let globalEvents = 0;
    const presence: PresenceEvent[] = [];
    client.on("event", () => (globalEvents += 1));
    client.on("presence.changed", (e) => presence.push(e.data));

    sockets[0]?.deliver({ type: "message.new", cid: CID, seq: 3, data: msg("a", 3) });
    expect(channel.messages.map((m) => m.id)).toEqual(["a"]);

    sockets[0]?.deliver({
      type: "presence.changed",
      cid: "",
      seq: 0,
      data: { userId: "bob", status: "online" },
    });
    expect(presence).toEqual([{ userId: "bob", status: "online" }]);
    expect(globalEvents).toBe(2);
  });

  test("resubscribes watched channels with their resume seq after a reconnect", async () => {
    vi.useFakeTimers();
    const { client, sockets } = mkClient();
    const channel = client.channel("messaging", "general");
    const promise = client.connect();
    await Promise.resolve();
    sockets[0]?.hello();
    await promise;

    await channel.watch();
    sockets[0]?.deliver({ type: "message.new", cid: CID, seq: 7, data: msg("x", 7) });
    expect(channel.lastSeq).toBe(7);

    sockets[0]?.drop();
    await vi.advanceTimersByTimeAsync(50);
    sockets[1]?.hello();

    expect(sockets[1]?.sent).toContain(
      JSON.stringify({ type: "subscribe", channels: { "messaging:general": 7 } }),
    );
  });
});
