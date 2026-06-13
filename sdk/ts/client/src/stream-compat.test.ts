import type {
  ChannelState as HydratedChannel,
  Channel as ChannelData,
  Message,
} from "@firemoot/core";
import { describe, expect, test } from "vitest";

import { Channel } from "./channel.js";
import type { RestApi } from "./rest.js";
import { streamChannelState } from "./stream-compat.js";

const CID = "messaging:general";

function msg(id: string, seq: number, createdAt: string): Message {
  return {
    id,
    cid: CID,
    seq,
    type: "regular",
    custom: {},
    attachments: [],
    replyCount: 0,
    createdAt,
    updatedAt: createdAt,
  };
}

const channelData: ChannelData = {
  cid: CID,
  type: "messaging",
  id: "general",
  custom: {},
  frozen: false,
  archived: false,
  currentSeq: 10,
  createdAt: "t",
  updatedAt: "t",
};

const rejectingRest: RestApi = {
  getChannel: () => Promise.reject(new Error("unused")),
  getMessages: () => Promise.reject(new Error("unused")),
  sendMessage: () => Promise.reject(new Error("unused")),
  editMessage: () => Promise.reject(new Error("unused")),
  deleteMessage: () => Promise.reject(new Error("unused")),
  addReaction: () => Promise.reject(new Error("unused")),
  removeReaction: () => Promise.reject(new Error("unused")),
  markRead: () => Promise.reject(new Error("unused")),
  queryChannels: () => Promise.reject(new Error("unused")),
  createUpload: () => Promise.reject(new Error("unused")),
};

function mkChannel(): Channel {
  return new Channel("messaging", "general", {
    rest: rejectingRest,
    send: () => true,
    selfUserId: "alice",
  });
}

describe("streamChannelState projection", () => {
  test("reflects a hydrated channel: members, read receipts, unread, latest message", () => {
    const channel = mkChannel();
    const hydrated: HydratedChannel = {
      channel: channelData,
      members: [
        { userId: "alice", role: "owner", lastReadSeq: 5 },
        { userId: "bob", role: "member", lastReadSeq: 3 },
      ],
      read: { lastReadSeq: 5, unreadCount: 2 },
      latestMessage: msg("m1", 10, "2026-06-12T10:00:00Z"),
    };
    channel.hydrate(hydrated);

    const state = streamChannelState(channel);
    expect(state.messages.map((m) => m.id)).toEqual(["m1"]);
    expect(state.members).toEqual({
      alice: { userId: "alice", role: "owner" },
      bob: { userId: "bob", role: "member" },
    });
    expect(state.read).toEqual({
      alice: { userId: "alice", lastReadSeq: 5 },
      bob: { userId: "bob", lastReadSeq: 3 },
    });
    expect(state.unreadCount).toBe(2);
    expect(state.last_message_at).toBe("2026-06-12T10:00:00Z");
  });

  test("tracks other members' read receipts and the caller's own unread separately", () => {
    const channel = mkChannel();
    channel.hydrate({
      channel: channelData,
      members: [
        { userId: "alice", role: "owner", lastReadSeq: 5 },
        { userId: "bob", role: "member", lastReadSeq: 3 },
      ],
      read: { lastReadSeq: 5, unreadCount: 2 },
      latestMessage: msg("m1", 10, "2026-06-12T10:00:00Z"),
    });

    // Bob reads to seq 10: his receipt moves; the caller's unread is untouched.
    channel.handleEvent({
      type: "read.updated",
      cid: CID,
      seq: 11,
      data: { cid: CID, userId: "bob", lastReadSeq: 10, unreadCount: 0, totalUnread: 0 },
    });
    let state = streamChannelState(channel);
    expect(state.read["bob"]?.lastReadSeq).toBe(10);
    expect(state.unreadCount).toBe(2);

    // Alice (self) reads: her receipt and her unread both move.
    channel.handleEvent({
      type: "read.updated",
      cid: CID,
      seq: 12,
      data: { cid: CID, userId: "alice", lastReadSeq: 10, unreadCount: 0, totalUnread: 0 },
    });
    state = streamChannelState(channel);
    expect(state.read["alice"]?.lastReadSeq).toBe(10);
    expect(state.unreadCount).toBe(0);
  });

  test("maintains members and the latest message across member.* and message.new", () => {
    const channel = mkChannel();
    channel.hydrate({
      channel: channelData,
      members: [{ userId: "alice", role: "owner", lastReadSeq: 5 }],
      read: { lastReadSeq: 5, unreadCount: 0 },
      latestMessage: msg("m1", 10, "2026-06-12T10:00:00Z"),
    });

    channel.handleEvent({
      type: "member.added",
      cid: CID,
      seq: 11,
      data: { cid: CID, userId: "carol", role: "member" },
    });
    channel.handleEvent({
      type: "message.new",
      cid: CID,
      seq: 12,
      data: msg("m2", 12, "2026-06-12T11:00:00Z"),
    });

    const state = streamChannelState(channel);
    expect(Object.keys(state.members).sort()).toEqual(["alice", "carol"]);
    expect(state.read["carol"]?.lastReadSeq).toBe(0);
    expect(state.messages.map((m) => m.id)).toEqual(["m1", "m2"]);
    expect(state.last_message_at).toBe("2026-06-12T11:00:00Z");

    channel.handleEvent({
      type: "member.removed",
      cid: CID,
      seq: 13,
      data: { cid: CID, userId: "carol" },
    });
    const after = streamChannelState(channel);
    expect(Object.keys(after.members)).toEqual(["alice"]);
    expect(after.read["carol"]).toBeUndefined();
  });
});
