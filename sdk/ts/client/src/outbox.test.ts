import type { Message } from "@firemoot/core";
import { describe, expect, test } from "vitest";

import { NONCE_KEY, Outbox } from "./outbox.js";

function message(id: string, seq: number, nonce: string | null): Message {
  return {
    id,
    cid: "c",
    seq,
    type: "regular",
    custom: nonce === null ? {} : { [NONCE_KEY]: nonce },
    attachments: [],
    replyCount: 0,
    createdAt: "t",
    updatedAt: "t",
  };
}

describe("Outbox", () => {
  test("a confirmed message with the same nonce drops the optimistic one", () => {
    const outbox = new Outbox();
    outbox.add({ nonce: "n1", status: "sending", message: message("temp-n1", 0, "n1") });
    expect(outbox.list()).toHaveLength(1);
    expect(outbox.confirm(message("real", 7, "n1"))).toBe(true);
    expect(outbox.list()).toHaveLength(0);
  });

  test("confirm is order-independent and idempotent", () => {
    const outbox = new Outbox();
    outbox.add({ nonce: "n1", status: "sending", message: message("temp-n1", 0, "n1") });
    expect(outbox.confirm(message("real", 7, "n1"))).toBe(true);
    expect(outbox.confirm(message("real", 7, "n1"))).toBe(false);
  });

  test("a message without a nonce never confirms a pending send", () => {
    const outbox = new Outbox();
    outbox.add({ nonce: "n1", status: "sending", message: message("temp-n1", 0, "n1") });
    expect(outbox.confirm(message("real", 7, null))).toBe(false);
    expect(outbox.list()).toHaveLength(1);
  });

  test("markFailed flips status but retains the send for retry/discard", () => {
    const outbox = new Outbox();
    outbox.add({ nonce: "n1", status: "sending", message: message("temp-n1", 0, "n1") });
    outbox.markFailed("n1", new Error("boom"));
    expect(outbox.list()[0]?.status).toBe("failed");
    outbox.remove("n1");
    expect(outbox.list()).toHaveLength(0);
  });
});
