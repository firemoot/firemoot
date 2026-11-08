import { createHmac } from "node:crypto";

import { describe, expect, test } from "vitest";

import { StreamChat } from "./client.js";
import { normalizeWebhookEvent, verifyWebhookSignature } from "./webhook.js";

const SECRET = "endpoint-secret";
const BODY = JSON.stringify({
  type: "message.new",
  cid: "messaging:general",
  seq: 7,
  data: { id: "m1", text: "hello", userId: "bob" },
});

function digest(body: string, secret = SECRET): string {
  return createHmac("sha256", secret).update(body).digest("hex");
}

describe("verifyWebhookSignature", () => {
  test("accepts X-Signature, the bare hex digest Stream sends", () => {
    expect(verifyWebhookSignature(SECRET, BODY, digest(BODY))).toBe(true);
  });

  test("accepts X-Firemoot-Signature, the same digest with the sha256= prefix", () => {
    expect(verifyWebhookSignature(SECRET, BODY, `sha256=${digest(BODY)}`)).toBe(true);
  });

  test("rejects a wrong secret, a tampered body and a missing header", () => {
    expect(verifyWebhookSignature("wrong-secret", BODY, digest(BODY))).toBe(false);
    expect(verifyWebhookSignature(SECRET, `${BODY} `, digest(BODY))).toBe(false);
    expect(verifyWebhookSignature(SECRET, BODY, null)).toBe(false);
    expect(verifyWebhookSignature(SECRET, BODY, "")).toBe(false);
  });

  test("rejects a truncated signature rather than matching on a prefix", () => {
    expect(verifyWebhookSignature(SECRET, BODY, digest(BODY).slice(0, 32))).toBe(false);
  });
});

describe("StreamChat.verifyWebhook", () => {
  test("verifies with the configured endpoint secret", () => {
    const client = new StreamChat("api-key", "api-secret", {
      baseURL: "http://firemoot.test",
      firemoot: { webhookSecret: SECRET },
    });
    expect(client.verifyWebhook(BODY, digest(BODY))).toBe(true);
    expect(client.verifyWebhook(BODY, `sha256=${digest(BODY)}`)).toBe(true);
    expect(client.verifyWebhook(BODY, digest(BODY, "api-secret"))).toBe(false);
  });

  test("falls back to the API secret when no endpoint secret is configured", () => {
    const client = new StreamChat("api-key", "api-secret", { baseURL: "http://firemoot.test" });
    expect(client.verifyWebhook(BODY, digest(BODY, "api-secret"))).toBe(true);
  });

  test("accepts a raw byte body, as Stream's Buffer overload does", () => {
    const client = new StreamChat("api-key", "api-secret", {
      baseURL: "http://firemoot.test",
      firemoot: { webhookSecret: SECRET },
    });
    expect(client.verifyWebhook(new TextEncoder().encode(BODY), digest(BODY))).toBe(true);
  });

  test("a browser client with no secret cannot verify anything", () => {
    const client = new StreamChat("api-key", { baseURL: "http://firemoot.test" });
    expect(client.verifyWebhook(BODY, digest(BODY))).toBe(false);
  });
});

describe("normalizeWebhookEvent", () => {
  test("message.new becomes a Stream-shaped event with message.user.id", () => {
    expect(normalizeWebhookEvent(JSON.parse(BODY))).toEqual({
      type: "message.new",
      cid: "messaging:general",
      message: { id: "m1", text: "hello", user: { id: "bob" } },
    });
  });

  test("read.updated becomes Stream's message.read", () => {
    expect(
      normalizeWebhookEvent({
        type: "read.updated",
        cid: "messaging:general",
        seq: 8,
        data: { userId: "carol", lastReadSeq: 7 },
      }),
    ).toEqual({
      type: "message.read",
      cid: "messaging:general",
      user: { id: "carol" },
    });
  });

  test("user.unread_message_reminder is rebuilt in Stream's shape", () => {
    const reminder = {
      type: "user.unread_message_reminder",
      data: {
        user: { id: "bob", name: "Bob", image: null, role: "user", custom: { tier: "gold" } },
        channels: [
          {
            channel: {
              cid: "messaging:dm",
              type: "messaging",
              id: "dm",
              createdBy: "alice",
              custom: { topic: "booking" },
              frozen: false,
              createdAt: "2026-11-08T10:00:00Z",
              updatedAt: "2026-11-08T10:00:00Z",
              lastMessageAt: "2026-11-08T10:05:00Z",
            },
            members: [
              { userId: "alice", role: "owner", lastReadSeq: 2 },
              { userId: "bob", role: "member", lastReadSeq: 0 },
            ],
            messages: [
              {
                id: "m2",
                userId: "alice",
                type: "regular",
                text: "still there?",
                custom: {},
                attachments: [],
                parentMessageId: null,
                createdAt: "2026-11-08T10:05:00Z",
              },
              {
                id: "m1",
                userId: null,
                type: "system",
                text: "booking confirmed",
                custom: {},
                attachments: [],
                parentMessageId: null,
                createdAt: "2026-11-08T10:00:00Z",
              },
            ],
          },
        ],
      },
    };
    expect(normalizeWebhookEvent(reminder)).toEqual({
      type: "user.unread_message_reminder",
      user: { id: "bob", name: "Bob", role: "user", tier: "gold" },
      channels: {
        "messaging:dm": {
          channel: {
            topic: "booking",
            cid: "messaging:dm",
            id: "dm",
            type: "messaging",
            created_at: "2026-11-08T10:00:00Z",
            updated_at: "2026-11-08T10:00:00Z",
            last_message_at: "2026-11-08T10:05:00Z",
            created_by: { id: "alice" },
            frozen: false,
            member_count: 2,
            members: [
              { user_id: "alice", user: { id: "alice" }, role: "owner" },
              { user_id: "bob", user: { id: "bob" }, role: "member" },
            ],
          },
          messages: [
            {
              id: "m2",
              type: "regular",
              text: "still there?",
              created_at: "2026-11-08T10:05:00Z",
              user: { id: "alice" },
              attachments: [],
            },
            {
              id: "m1",
              type: "system",
              text: "booking confirmed",
              created_at: "2026-11-08T10:00:00Z",
              attachments: [],
            },
          ],
        },
      },
    });
  });

  test("any other event passes through under its own type", () => {
    expect(
      normalizeWebhookEvent({ type: "member.added", cid: "messaging:general", data: {} }),
    ).toEqual({ type: "member.added", cid: "messaging:general" });
  });

  test("a non-object body degrades to an empty event rather than throwing", () => {
    expect(normalizeWebhookEvent(null)).toEqual({});
    expect(normalizeWebhookEvent("nonsense")).toEqual({});
  });
});
