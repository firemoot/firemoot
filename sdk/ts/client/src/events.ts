import type { Channel, Message } from "@firemoot/core";

/**
 * The WebSocket event vocabulary (SPEC.md §5), typed against the exact `data`
 * payloads the server emits. Every server frame is `{ type, cid, seq, data }`
 * (see `Event.wire` on the server); these map each `type` to its `data` shape.
 */

export interface ReactionEvent {
  messageId: string;
  userId: string;
  type: string;
  counts: Record<string, number>;
}

export interface ReadEvent {
  cid: string;
  userId: string;
  lastReadSeq: number;
  unreadCount: number;
  totalUnread: number;
}

export interface TypingEvent {
  userId: string;
}

export interface PresenceEvent {
  userId: string;
  status: "online" | "offline";
  lastActiveAt?: string | null;
}

export interface MemberEvent {
  cid: string;
  userId: string;
  role?: string;
}

export interface ChannelRefEvent {
  cid: string;
}

/** Maps each server event `type` to the shape of its `data` field. */
export interface ServerEventData {
  "message.new": Message;
  "message.updated": Message;
  "message.deleted": Message;
  "reaction.new": ReactionEvent;
  "reaction.deleted": ReactionEvent;
  "read.updated": ReadEvent;
  "typing.start": TypingEvent;
  "typing.stop": TypingEvent;
  "presence.changed": PresenceEvent;
  "channel.updated": Channel;
  "channel.deleted": ChannelRefEvent;
  "member.added": MemberEvent;
  "member.removed": MemberEvent;
  "notification.added_to_channel": ChannelRefEvent;
  "notification.removed_from_channel": ChannelRefEvent;
}

export type ServerEventType = keyof ServerEventData;

/** A decoded server event frame for a given `type`. */
export interface FiremootEvent<T extends ServerEventType = ServerEventType> {
  type: T;
  cid: string;
  seq: number;
  data: ServerEventData[T];
}

/** The discriminated union of every decoded server event. */
export type AnyFiremootEvent = { [K in ServerEventType]: FiremootEvent<K> }[ServerEventType];

const SERVER_EVENT_TYPES: ReadonlySet<string> = new Set<ServerEventType>([
  "message.new",
  "message.updated",
  "message.deleted",
  "reaction.new",
  "reaction.deleted",
  "read.updated",
  "typing.start",
  "typing.stop",
  "presence.changed",
  "channel.updated",
  "channel.deleted",
  "member.added",
  "member.removed",
  "notification.added_to_channel",
  "notification.removed_from_channel",
]);

export function isServerEventType(value: unknown): value is ServerEventType {
  return typeof value === "string" && SERVER_EVENT_TYPES.has(value);
}

/** The `hello` handshake frame: identity plus the user's global unread badge. */
export interface HelloFrame {
  type: "hello";
  connectionId: string;
  serverTime: string;
  me: unknown;
  totalUnread: number;
}

/** The server's reply that a resume point is too old to replay. */
export interface ResyncFrame {
  type: "resync_required";
  cid: string;
}

export type ServerFrame = HelloFrame | ResyncFrame | AnyFiremootEvent | { type: "pong" };

/** Parses a raw text frame into a typed server frame, or null if unrecognised. */
export function decodeFrame(raw: string): ServerFrame | null {
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof json !== "object" || json === null) return null;
  const frame = json as Record<string, unknown>;
  const type = frame["type"];
  if (type === "hello") return frame as unknown as HelloFrame;
  if (type === "resync_required") return frame as unknown as ResyncFrame;
  if (type === "pong") return { type: "pong" };
  if (isServerEventType(type)) return frame as unknown as AnyFiremootEvent;
  return null;
}

/** Client -> server frame builders (SPEC.md §5). */
export const ClientFrames = {
  subscribe: (channels: Record<string, number>): string =>
    JSON.stringify({ type: "subscribe", channels }),
  typingStart: (cid: string): string => JSON.stringify({ type: "typing.start", cid }),
  typingStop: (cid: string): string => JSON.stringify({ type: "typing.stop", cid }),
  ping: (): string => JSON.stringify({ type: "ping" }),
};
