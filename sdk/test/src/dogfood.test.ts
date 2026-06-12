import { FiremootClient } from "@firemoot/client";
import { afterAll, beforeAll, describe, expect, test, vi } from "vitest";

import { type FiremootInstance, startFiremoot } from "./index.js";

// Opt-in: needs Docker and a built `firemoot:latest` image. The CI `dogfood` job
// builds the image then runs with FIREMOOT_DOGFOOD=1; the normal sdk job skips it.
const RUN = process.env["FIREMOOT_DOGFOOD"] === "1";

describe.runIf(RUN)("dogfood: @firemoot/client against a real Firemoot", () => {
  let fm: FiremootInstance;

  beforeAll(async () => {
    fm = await startFiremoot();
    await fm.seed({
      users: [{ id: "alice" }, { id: "bob" }],
      channels: [
        {
          type: "messaging",
          id: "general",
          createdBy: "alice",
          members: [{ userId: "bob", role: "member" }],
        },
      ],
    });
  }, 180_000);

  afterAll(async () => {
    await fm?.stop();
  });

  test("connects, sends over REST and receives the message.new over WS", async () => {
    const token = await fm.createToken("alice");
    const client = new FiremootClient({
      baseUrl: fm.baseUrl,
      wsUrl: fm.wsUrl,
      userId: "alice",
      token,
    });
    const received: string[] = [];
    await client.connect();
    const channel = client.channel("messaging", "general");
    channel.on("message.new", (event) => {
      if (typeof event.data.text === "string") received.push(event.data.text);
    });
    await channel.watch();

    const sent = await channel.sendMessage({ text: "hello from dogfood" });
    expect(sent.userId).toBe("alice");
    await vi.waitFor(() => expect(received).toContain("hello from dogfood"), { timeout: 10_000 });

    client.disconnect();
  });

  test("queryChannels returns the caller's channel, hydrated with its members", async () => {
    const token = await fm.createToken("alice");
    const client = new FiremootClient({
      baseUrl: fm.baseUrl,
      wsUrl: fm.wsUrl,
      userId: "alice",
      token,
    });
    await client.connect();

    const channels = await client.queryChannels({}, { watch: true });
    const general = channels.find((c) => c.cid === "messaging:general");
    expect(general).toBeDefined();
    expect(general?.members.map((m) => m.userId).sort()).toEqual(["alice", "bob"]);

    client.disconnect();
  });
});
