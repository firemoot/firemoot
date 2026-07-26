import { describe, expect, test } from "vitest";

import {
  FIREMOOT_CHANNEL_EVENT,
  FIREMOOT_CLIENT_EVENT,
  lastMessageSortDirection,
  STREAM_EVENT_NAME,
  toStreamChannelEvent,
  toStreamClientEvent,
} from "./events.js";
import { message } from "./fakes.js";

describe("channel event translation", () => {
  test("message events carry a Stream-shaped message and its author", () => {
    const event = toStreamChannelEvent(
      "message.new",
      "messaging:general",
      message("m1", 3, { text: "hi", userId: "bob" }),
    );
    expect(event).toMatchObject({
      type: "message.new",
      cid: "messaging:general",
      user: { id: "bob" },
    });
    expect(event.message).toMatchObject({
      id: "m1",
      text: "hi",
      type: "regular",
      created_at: "2026-07-26T10:00:00Z",
      user: { id: "bob" },
    });
  });

  test("message custom fields are spread to the top level, as Stream does", () => {
    const event = toStreamChannelEvent(
      "message.new",
      "messaging:general",
      message("m1", 3, { custom: { priority: "high" } }),
    );
    expect(event.message?.["priority"]).toBe("high");
  });

  test("reaction events pass the payload through under `reaction`", () => {
    const event = toStreamChannelEvent("reaction.new", "messaging:general", {
      messageId: "m1",
      userId: "alice",
      type: "like",
      counts: { like: 1 },
    });
    expect(event.reaction).toEqual({
      messageId: "m1",
      userId: "alice",
      type: "like",
      counts: { like: 1 },
    });
    expect(event.user).toEqual({ id: "alice" });
  });

  test("typing and read events carry only the user", () => {
    expect(toStreamChannelEvent("typing.start", "messaging:general", { userId: "bob" })).toEqual({
      type: "typing.start",
      cid: "messaging:general",
      user: { id: "bob" },
    });
    expect(
      toStreamChannelEvent("message.read", "messaging:general", {
        userId: "bob",
        lastReadSeq: 4,
      }),
    ).toEqual({ type: "message.read", cid: "messaging:general", user: { id: "bob" } });
  });

  test("Stream's message.read maps onto Firemoot's read.updated", () => {
    expect(FIREMOOT_CHANNEL_EVENT["message.read"]).toBe("read.updated");
  });
});

describe("client event translation", () => {
  test("message.new carries a Stream-shaped message payload", () => {
    const event = toStreamClientEvent("message.new", {
      type: "message.new",
      cid: "messaging:general",
      seq: 3,
      data: message("m1", 3, { text: "hi", userId: "bob" }),
    });
    expect(event.type).toBe("message.new");
    expect(event.cid).toBe("messaging:general");
    expect(event.message).toMatchObject({ id: "m1", text: "hi", user: { id: "bob" } });
    expect(event.user).toEqual({ id: "bob" });
  });

  test("message.read (from read.updated) carries the reader", () => {
    const event = toStreamClientEvent("message.read", {
      type: "read.updated",
      cid: "messaging:general",
      data: { userId: "carol", lastReadSeq: 4 },
    });
    expect(event).toEqual({
      type: "message.read",
      cid: "messaging:general",
      user: { id: "carol" },
    });
  });

  test("notification.added_to_channel passes the cid through", () => {
    const event = toStreamClientEvent("notification.added_to_channel", {
      type: "notification.added_to_channel",
      cid: "messaging:support",
      data: { cid: "messaging:support" },
    });
    expect(event).toEqual({ type: "notification.added_to_channel", cid: "messaging:support" });
  });

  test("a malformed event degrades to an empty cid rather than throwing", () => {
    expect(toStreamClientEvent("message.read", null)).toEqual({ type: "message.read", cid: "" });
  });
});

describe("the deliberate client-event no-ops", () => {
  test("only notification.message_new and notification.mark_unread have no equivalent", () => {
    const noops = Object.entries(FIREMOOT_CLIENT_EVENT)
      .filter(([, firemootType]) => firemootType === null)
      .map(([streamType]) => streamType);
    expect(noops.sort()).toEqual(["notification.mark_unread", "notification.message_new"]);
  });

  test("every other Stream client event maps onto a real Firemoot event", () => {
    expect(FIREMOOT_CLIENT_EVENT["message.new"]).toBe("message.new");
    expect(FIREMOOT_CLIENT_EVENT["notification.mark_read"]).toBe("read.updated");
    expect(FIREMOOT_CLIENT_EVENT["connection.changed"]).toBe("status");
    expect(FIREMOOT_CLIENT_EVENT["connection.recovered"]).toBe("connected");
  });

  test("the firehose reverse map names presence the way Stream does", () => {
    expect(STREAM_EVENT_NAME["presence.changed"]).toBe("user.presence.changed");
    expect(STREAM_EVENT_NAME["read.updated"]).toBe("message.read");
  });
});

describe("lastMessageSortDirection", () => {
  test("defaults to newest-first, as Stream does", () => {
    expect(lastMessageSortDirection()).toBe(-1);
    expect(lastMessageSortDirection({})).toBe(-1);
    expect(lastMessageSortDirection([])).toBe(-1);
  });

  test("honours both directions, given as an object or an array", () => {
    expect(lastMessageSortDirection({ last_message_at: -1 })).toBe(-1);
    expect(lastMessageSortDirection({ last_message_at: 1 })).toBe(1);
    expect(lastMessageSortDirection([{ last_message_at: 1 }])).toBe(1);
  });

  test("throws on a field the server cannot order by, rather than mis-ordering", () => {
    expect(() => lastMessageSortDirection({ created_at: -1 })).toThrow(
      /only sorts by last_message_at/,
    );
    expect(() => lastMessageSortDirection([{ last_message_at: -1 }, { member_count: 1 }])).toThrow(
      /member_count/,
    );
  });
});
