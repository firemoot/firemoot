import type { Channel, Message } from "@firemoot/core";
import { describe, expect, test } from "vitest";

import type { AnyFiremootEvent } from "./events.js";
import { applyEvent, emptyChannelState } from "./state.js";

function msg(id: string, seq: number, over: Partial<Message> = {}): Message {
  return {
    id,
    cid: "messaging:general",
    seq,
    type: "regular",
    custom: {},
    attachments: [],
    replyCount: 0,
    createdAt: "t",
    updatedAt: "t",
    ...over,
  };
}

describe("applyEvent", () => {
  test("appends new messages, replaces by id, advances seq", () => {
    let s = emptyChannelState("messaging:general");
    s = applyEvent(s, {
      type: "message.new",
      cid: s.cid,
      seq: 1,
      data: msg("a", 1, { text: "hi" }),
    });
    s = applyEvent(s, { type: "message.new", cid: s.cid, seq: 2, data: msg("b", 2) });
    s = applyEvent(s, {
      type: "message.updated",
      cid: s.cid,
      seq: 3,
      data: msg("a", 1, { text: "edited" }),
    });
    expect(s.messages.map((m) => m.id)).toEqual(["a", "b"]);
    expect(s.messages[0]?.text).toBe("edited");
    expect(s.lastSeq).toBe(3);
  });

  test("out-of-order arrivals are inserted by seq", () => {
    let s = emptyChannelState("c");
    s = applyEvent(s, { type: "message.new", cid: "c", seq: 5, data: msg("e", 5) });
    s = applyEvent(s, { type: "message.new", cid: "c", seq: 2, data: msg("b", 2) });
    expect(s.messages.map((m) => m.seq)).toEqual([2, 5]);
  });

  test("reaction events set per-message counts", () => {
    let s = emptyChannelState("c");
    s = applyEvent(s, {
      type: "reaction.new",
      cid: "c",
      seq: 4,
      data: { messageId: "a", userId: "u", type: "like", counts: { like: 1 } },
    });
    expect(s.reactions["a"]).toEqual({ like: 1 });
  });

  test("read.updated only moves my own unread badge", () => {
    let s = emptyChannelState("c");
    s = applyEvent(
      s,
      {
        type: "read.updated",
        cid: "c",
        seq: 6,
        data: { cid: "c", userId: "other", lastReadSeq: 3, unreadCount: 0, totalUnread: 0 },
      },
      "me",
    );
    expect(s.read).toBeNull();
    expect(s.lastSeq).toBe(6);
    s = applyEvent(
      s,
      {
        type: "read.updated",
        cid: "c",
        seq: 7,
        data: { cid: "c", userId: "me", lastReadSeq: 5, unreadCount: 2, totalUnread: 9 },
      },
      "me",
    );
    expect(s.read).toEqual({ lastReadSeq: 5, unreadCount: 2, totalUnread: 9 });
  });

  test("typing tracks others, ignores self, does not advance seq", () => {
    let s = emptyChannelState("c");
    s = applyEvent(s, { type: "typing.start", cid: "c", seq: 0, data: { userId: "bob" } }, "me");
    s = applyEvent(s, { type: "typing.start", cid: "c", seq: 0, data: { userId: "me" } }, "me");
    expect(s.typing).toEqual(["bob"]);
    s = applyEvent(s, { type: "typing.stop", cid: "c", seq: 0, data: { userId: "bob" } }, "me");
    expect(s.typing).toEqual([]);
    expect(s.lastSeq).toBe(0);
  });

  test("channel.updated stores meta; channel.deleted flags deleted", () => {
    let s = emptyChannelState("c");
    const channel: Channel = {
      cid: "c",
      type: "messaging",
      id: "general",
      custom: {},
      frozen: true,
      archived: false,
      currentSeq: 8,
      createdAt: "t",
      updatedAt: "t",
    };
    s = applyEvent(s, { type: "channel.updated", cid: "c", seq: 8, data: channel });
    expect(s.channel?.frozen).toBe(true);
    s = applyEvent(s, { type: "channel.deleted", cid: "c", seq: 9, data: { cid: "c" } });
    expect(s.deleted).toBe(true);
  });

  test("re-applying a replayed event is idempotent", () => {
    let s = emptyChannelState("c");
    const event: AnyFiremootEvent = { type: "message.new", cid: "c", seq: 1, data: msg("a", 1) };
    s = applyEvent(s, event);
    s = applyEvent(s, event);
    expect(s.messages).toHaveLength(1);
    expect(s.lastSeq).toBe(1);
  });
});
