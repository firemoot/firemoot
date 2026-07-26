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
