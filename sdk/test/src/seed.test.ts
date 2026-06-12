import { describe, expect, test } from "vitest";

import { seed, type SeedBackend } from "./seed.js";

function recordingBackend(): { backend: SeedBackend; calls: string[] } {
  const calls: string[] = [];
  const backend: SeedBackend = {
    server: {
      upsertUser: (body) => {
        calls.push(`upsertUser:${body.id}`);
        return Promise.resolve({
          id: body.id,
          role: "user",
          custom: {},
          createdAt: "t",
          updatedAt: "t",
        });
      },
      createChannel: (body, members = []) => {
        const memberList = members.map((m) => `${m.userId}/${m.role ?? "member"}`).join(",");
        calls.push(`createChannel:${body.type}:${body.id}[${memberList}]`);
        return Promise.resolve({
          cid: `${body.type}:${body.id}`,
          type: body.type,
          id: body.id,
          custom: {},
          frozen: false,
          archived: false,
          currentSeq: 0,
          createdAt: "t",
          updatedAt: "t",
        });
      },
    },
    rest: {
      sendMessage: (type, id, body) => {
        calls.push(`send:${type}:${id}:${body.userId}:${body.text ?? ""}`);
        return Promise.resolve({
          id: "m1",
          cid: `${type}:${id}`,
          seq: 1,
          type: "regular",
          custom: {},
          attachments: [],
          replyCount: 0,
          createdAt: "t",
          updatedAt: "t",
        });
      },
    },
  };
  return { backend, calls };
}

describe("seed", () => {
  test("provisions users, channels (with members) and messages in order", async () => {
    const { backend, calls } = recordingBackend();
    await seed(backend, {
      users: [{ id: "alice" }, { id: "bob" }],
      channels: [
        { type: "messaging", id: "general", createdBy: "alice", members: [{ userId: "bob" }] },
      ],
      messages: [{ type: "messaging", id: "general", userId: "alice", text: "hi" }],
    });
    expect(calls).toEqual([
      "upsertUser:alice",
      "upsertUser:bob",
      "createChannel:messaging:general[bob/member]",
      "send:messaging:general:alice:hi",
    ]);
  });

  test("an empty spec is a no-op", async () => {
    const { backend, calls } = recordingBackend();
    await seed(backend, {});
    expect(calls).toEqual([]);
  });
});
