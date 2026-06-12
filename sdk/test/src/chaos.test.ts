import { FiremootClient } from "@firemoot/client";
import { afterAll, beforeAll, describe, expect, test, vi } from "vitest";

import { type FiremootInstance, startFiremoot } from "./index.js";
import { TcpProxy } from "./tcp-proxy.js";

// Opt-in (Docker), runs in the CI `dogfood` job alongside the dogfood suite.
const RUN = process.env["FIREMOOT_DOGFOOD"] === "1";

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

describe.runIf(RUN)("chaos: @firemoot/client survives a mid-stream WS drop", () => {
  let fm: FiremootInstance;

  beforeAll(async () => {
    fm = await startFiremoot();
    await fm.seed({
      users: [{ id: "sender" }, { id: "watcher" }],
      channels: [
        {
          type: "messaging",
          id: "chaos",
          createdBy: "watcher",
          members: [{ userId: "sender", role: "member" }],
        },
      ],
    });
  }, 180_000);

  afterAll(async () => {
    await fm?.stop();
  });

  test("reconnects through a severed proxy with zero message loss or duplication", async () => {
    // The WS tunnels through a TCP proxy we can cut; REST goes direct.
    const target = new URL(fm.baseUrl);
    const proxy = new TcpProxy(target.hostname, Number(target.port));
    const proxyPort = await proxy.start();

    const token = await fm.createToken("watcher");
    const client = new FiremootClient({
      baseUrl: fm.baseUrl,
      wsUrl: `ws://127.0.0.1:${proxyPort}/v1/ws`,
      userId: "watcher",
      token,
      reconnect: { baseDelayMs: 50, maxDelayMs: 200 },
    });

    // Count receipts per message index - the assertion is completeness + uniqueness.
    const received = new Map<number, number>();
    let reconnected = false;
    client.on("reconnecting", () => {
      reconnected = true;
    });
    await client.connect();
    const channel = client.channel("messaging", "chaos");
    channel.on("message.new", (event) => {
      const custom = event.data.custom as { idx?: unknown } | null;
      const idx = custom?.idx;
      if (typeof idx === "number") received.set(idx, (received.get(idx) ?? 0) + 1);
    });
    await channel.watch();

    const total = 24;
    for (let i = 0; i < total; i += 1) {
      await fm.rest.sendMessage("messaging", "chaos", {
        userId: "sender",
        text: `m${i}`,
        custom: { idx: i },
      });
      // Sever the connection mid-stream; messages keep flowing during the gap.
      if (i === 8) proxy.drop();
      await sleep(20);
    }

    await vi.waitFor(() => expect(received.size).toBe(total), { timeout: 20_000 });

    // The drop actually happened, and every message arrived exactly once.
    expect(reconnected).toBe(true);
    for (const [idx, count] of received) {
      expect(count, `message ${idx} delivered exactly once`).toBe(1);
    }
    expect([...received.keys()].sort((a, b) => a - b)).toEqual(
      Array.from({ length: total }, (_, i) => i),
    );

    client.disconnect();
    await proxy.stop();
  }, 60_000);
});
