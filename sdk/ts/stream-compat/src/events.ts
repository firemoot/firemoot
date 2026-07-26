import type { Message } from "@firemoot/core";

import { asRecord, toStreamMessage } from "./message.js";

/** The Stream-shaped event object passed to `on` handlers. */
export interface StreamCompatEvent {
  type: string;
  cid: string;
  message?: Record<string, unknown>;
  reaction?: Record<string, unknown>;
  user?: { id: string };
}

export type StreamChannelEventType =
  | "message.new"
  | "message.updated"
  | "message.deleted"
  | "reaction.new"
  | "reaction.deleted"
  | "typing.start"
  | "typing.stop"
  | "message.read";

/** Maps the Stream channel-event name onto the Firemoot event name. */
export const FIREMOOT_CHANNEL_EVENT: Record<StreamChannelEventType, string> = {
  "message.new": "message.new",
  "message.updated": "message.updated",
  "message.deleted": "message.deleted",
  "reaction.new": "reaction.new",
  "reaction.deleted": "reaction.deleted",
  "typing.start": "typing.start",
  "typing.stop": "typing.stop",
  "message.read": "read.updated",
};

export type StreamClientEventType =
  | "message.new"
  | "message.read"
  | "notification.added_to_channel"
  | "notification.removed_from_channel"
  | "notification.message_new"
  | "notification.mark_read"
  | "notification.mark_unread"
  | "connection.changed"
  | "connection.recovered";

/**
 * Stream client-event -> Firemoot client-event name. `null` marks a **deliberate
 * no-op**: the subscription is accepted and simply never fires, because the event
 * has no Firemoot equivalent and real apps subscribe to it harmlessly.
 *
 *  - `notification.message_new`: watched channels' own `message.new` already
 *    covers the live UX under `queryChannels({ watch: true })`.
 *  - `notification.mark_unread`: Firemoot has no mark-unread.
 *
 * These two are the only silent subscriptions in the adapter; everything else
 * either maps to a real event or throws.
 */
export const FIREMOOT_CLIENT_EVENT: Record<StreamClientEventType, string | null> = {
  "message.new": "message.new",
  "message.read": "read.updated",
  "notification.added_to_channel": "notification.added_to_channel",
  "notification.removed_from_channel": "notification.removed_from_channel",
  "notification.message_new": null,
  "notification.mark_read": "read.updated",
  "notification.mark_unread": null,
  "connection.changed": "status",
  "connection.recovered": "connected",
};

/** Reshapes a Firemoot event `data` into the Stream-shaped channel event. */
export function toStreamChannelEvent(
  streamType: StreamChannelEventType,
  cid: string,
  data: unknown,
): StreamCompatEvent {
  const d = asRecord(data);
  const userId = typeof d["userId"] === "string" ? d["userId"] : undefined;
  const event: StreamCompatEvent = { type: streamType, cid };
  switch (streamType) {
    case "message.new":
    case "message.updated":
    case "message.deleted":
      event.message = toStreamMessage(d as unknown as Message);
      if (userId) event.user = { id: userId };
      break;
    case "reaction.new":
    case "reaction.deleted":
      event.reaction = d;
      if (userId) event.user = { id: userId };
      break;
    case "typing.start":
    case "typing.stop":
    case "message.read":
      if (userId) event.user = { id: userId };
      break;
  }
  return event;
}

/** Reshapes a Firemoot client-level event into the Stream-shaped client event. */
export function toStreamClientEvent(
  streamType: StreamClientEventType,
  raw: unknown,
): StreamCompatEvent {
  const e = asRecord(raw);
  const cid = typeof e["cid"] === "string" ? e["cid"] : "";
  const data = asRecord(e["data"]);
  const userId = typeof data["userId"] === "string" ? data["userId"] : undefined;
  const event: StreamCompatEvent = { type: streamType, cid };
  if (streamType === "message.new") event.message = toStreamMessage(data as unknown as Message);
  if (userId) event.user = { id: userId };
  return event;
}

/**
 * Firemoot event name -> the Stream name for the same thing, used by the
 * all-events form of `client.on(handler)`. This is the canonical direction:
 * `FIREMOOT_CLIENT_EVENT` is many-to-one (three Stream names map onto
 * `read.updated`), so the firehose picks the one Stream calls it by.
 */
export const STREAM_EVENT_NAME: Record<string, string> = {
  "message.new": "message.new",
  "message.updated": "message.updated",
  "message.deleted": "message.deleted",
  "reaction.new": "reaction.new",
  "reaction.deleted": "reaction.deleted",
  "typing.start": "typing.start",
  "typing.stop": "typing.stop",
  "read.updated": "message.read",
  "presence.changed": "user.presence.changed",
  "channel.updated": "channel.updated",
  "channel.deleted": "channel.deleted",
  "member.added": "member.added",
  "member.removed": "member.removed",
  "notification.added_to_channel": "notification.added_to_channel",
  "notification.removed_from_channel": "notification.removed_from_channel",
};

/** Stream's `ChannelSort` shape - a `{ field: 1 | -1 }` map or an array of them. */
export type ChannelSort = Record<string, number> | Array<Record<string, number>>;

/**
 * Resolves a requested sort to a `last_message_at` direction (-1 desc / 1 asc).
 * The server orders `queryChannels` by `coalesce(last_message_at, created_at)`
 * desc, so that is the only field this adapter can honour; sorting by anything
 * else would be silently mis-ordered, so it throws rather than pretend. An
 * absent or empty sort defaults to newest-first, matching Stream.
 */
export function lastMessageSortDirection(sort?: ChannelSort): -1 | 1 {
  const entries = (Array.isArray(sort) ? sort : sort ? [sort] : []).flatMap((s) =>
    Object.entries(s),
  );
  if (entries.length === 0) return -1;
  const unsupportedFields = entries.filter(([field]) => field !== "last_message_at");
  if (unsupportedFields.length > 0) {
    throw new Error(
      `@firemoot/stream-compat: queryChannels only sorts by last_message_at, got: ${unsupportedFields
        .map(([field]) => field)
        .join(", ")}`,
    );
  }
  return entries[entries.length - 1]?.[1] === 1 ? 1 : -1;
}
