import { afterEach, describe, expect, test, vi } from "vitest";

import {
  Connection,
  type ConnectionConfig,
  type Socket,
  type SocketFactory,
} from "./connection.js";

class FakeSocket implements Socket {
  sent: string[] = [];
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((data: string) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: ((error: unknown) => void) | null = null;

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.closed = true;
    this.onclose?.();
  }

  hello(connectionId = "c1"): void {
    this.onmessage?.(
      JSON.stringify({ type: "hello", connectionId, serverTime: "t", me: {}, totalUnread: 0 }),
    );
  }

  deliver(frame: unknown): void {
    this.onmessage?.(JSON.stringify(frame));
  }

  drop(): void {
    this.onclose?.();
  }
}

let sockets: FakeSocket[] = [];
let urlCalls = 0;

function makeConnection(over: Partial<ConnectionConfig> = {}): Connection {
  sockets = [];
  urlCalls = 0;
  const factory: SocketFactory = () => {
    const socket = new FakeSocket();
    sockets.push(socket);
    return socket;
  };
  return new Connection({
    urlProvider: () => {
      urlCalls += 1;
      return `ws://host/v1/ws?token=t${urlCalls}`;
    },
    subscriptions: () => ({ "messaging:general": 3 }),
    socketFactory: factory,
    random: () => 0,
    reconnect: { baseDelayMs: 100, maxDelayMs: 1000, maxRetries: Number.POSITIVE_INFINITY },
    ...over,
  });
}

const SUBSCRIBE = JSON.stringify({ type: "subscribe", channels: { "messaging:general": 3 } });

afterEach(() => {
  vi.useRealTimers();
});

describe("Connection", () => {
  test("connects on hello and resubscribes with the resume seq", async () => {
    const connection = makeConnection();
    const promise = connection.connect();
    await Promise.resolve();
    expect(sockets).toHaveLength(1);
    sockets[0]?.hello();
    await promise;
    expect(connection.status).toBe("connected");
    expect(sockets[0]?.sent).toEqual([SUBSCRIBE]);
  });

  test("reconnects with backoff and refreshes the URL each attempt", async () => {
    vi.useFakeTimers();
    const connection = makeConnection();
    const promise = connection.connect();
    await Promise.resolve();
    sockets[0]?.hello();
    await promise;
    expect(urlCalls).toBe(1);

    sockets[0]?.drop();
    expect(connection.status).toBe("reconnecting");
    await vi.advanceTimersByTimeAsync(60); // delay = min(1000,100)*0.5 = 50
    expect(sockets).toHaveLength(2);
    expect(urlCalls).toBe(2);
    sockets[1]?.hello("c2");
    expect(connection.status).toBe("connected");
    expect(sockets[1]?.sent[0]).toBe(SUBSCRIBE);
  });

  test("rejects connect once retries are exhausted", async () => {
    vi.useFakeTimers();
    const connection = makeConnection({
      reconnect: { baseDelayMs: 10, maxDelayMs: 10, maxRetries: 2 },
    });
    const promise = connection.connect();
    const rejected = expect(promise).rejects.toThrow();
    await Promise.resolve();

    sockets[0]?.drop(); // attempt 0 fails -> schedule (0 < 2)
    await vi.runOnlyPendingTimersAsync();
    sockets[1]?.drop(); // attempt 1 fails -> schedule (1 < 2)
    await vi.runOnlyPendingTimersAsync();
    sockets[2]?.drop(); // attempt 2 -> 2 < 2 is false -> closed

    await rejected;
    expect(connection.status).toBe("closed");
  });

  test("send only forwards once connected", async () => {
    const connection = makeConnection();
    expect(connection.send("x")).toBe(false);
    const promise = connection.connect();
    await Promise.resolve();
    expect(connection.send("x")).toBe(false);
    sockets[0]?.hello();
    await promise;
    expect(connection.send(JSON.stringify({ type: "ping" }))).toBe(true);
    expect(sockets[0]?.sent).toContain(JSON.stringify({ type: "ping" }));
  });

  test("close() closes the socket and does not reconnect", async () => {
    vi.useFakeTimers();
    const connection = makeConnection();
    const promise = connection.connect();
    await Promise.resolve();
    sockets[0]?.hello();
    await promise;

    connection.close();
    expect(connection.status).toBe("closed");
    expect(sockets[0]?.closed).toBe(true);
    await vi.advanceTimersByTimeAsync(5000);
    expect(sockets).toHaveLength(1);
  });

  test("forwards decoded server events and ignores unknown frames", async () => {
    const connection = makeConnection();
    const frames: string[] = [];
    connection.on("frame", (frame) => frames.push(frame.type));
    const promise = connection.connect();
    await Promise.resolve();
    sockets[0]?.hello();
    await promise;

    sockets[0]?.deliver({
      type: "message.new",
      cid: "messaging:general",
      seq: 4,
      data: {
        id: "m",
        cid: "messaging:general",
        seq: 4,
        type: "regular",
        custom: {},
        attachments: [],
        replyCount: 0,
        createdAt: "t",
        updatedAt: "t",
      },
    });
    sockets[0]?.deliver({ type: "not-a-real-event" });
    expect(frames).toEqual(["message.new"]);
  });
});
