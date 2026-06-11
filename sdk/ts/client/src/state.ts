import type { Channel, Message } from "@firemoot/core";

import type { AnyFiremootEvent } from "./events.js";

export interface ReadState {
  lastReadSeq: number;
  unreadCount: number;
  totalUnread: number;
}

/**
 * The cached state of one channel, derived purely from the server event stream.
 * `messages` holds confirmed (server) messages ordered by `seq`; optimistic sends
 * live in the outbox until confirmed. `lastSeq` is the resume watermark.
 */
export interface ChannelState {
  cid: string;
  channel: Channel | null;
  deleted: boolean;
  messages: Message[];
  /** messageId -> per-type reaction counts. */
  reactions: Record<string, Record<string, number>>;
  /** userIds currently typing (excluding self). */
  typing: string[];
  read: ReadState | null;
  lastSeq: number;
}

export function emptyChannelState(cid: string): ChannelState {
  return {
    cid,
    channel: null,
    deleted: false,
    messages: [],
    reactions: {},
    typing: [],
    read: null,
    lastSeq: 0,
  };
}

function advanceSeq(state: ChannelState, seq: number): number {
  return seq > state.lastSeq ? seq : state.lastSeq;
}

function upsertBySeq(messages: Message[], message: Message): Message[] {
  const index = messages.findIndex((m) => m.id === message.id);
  if (index >= 0) {
    const next = messages.slice();
    next[index] = message;
    return next;
  }
  // New message: insert keeping ascending seq order (usually an append).
  const at = messages.findIndex((m) => m.seq > message.seq);
  if (at < 0) return [...messages, message];
  return [...messages.slice(0, at), message, ...messages.slice(at)];
}

function withoutUser(users: string[], userId: string): string[] {
  return users.filter((u) => u !== userId);
}

function addUser(users: string[], userId: string): string[] {
  return users.includes(userId) ? users : [...users, userId];
}

/**
 * Folds one decoded server event into the channel state. `selfUserId`, when
 * given, scopes `read.updated` to the connected user's own badge (other users'
 * read receipts surface through the emitter but don't move *my* unread counts).
 * Events not relevant to a single channel's state (presence, notifications) are
 * returned unchanged.
 */
export function applyEvent(
  state: ChannelState,
  event: AnyFiremootEvent,
  selfUserId?: string,
): ChannelState {
  switch (event.type) {
    case "message.new":
    case "message.updated":
    case "message.deleted":
      return {
        ...state,
        lastSeq: advanceSeq(state, event.seq),
        messages: upsertBySeq(state.messages, event.data),
      };
    case "reaction.new":
    case "reaction.deleted":
      return {
        ...state,
        lastSeq: advanceSeq(state, event.seq),
        reactions: { ...state.reactions, [event.data.messageId]: event.data.counts },
      };
    case "read.updated":
      if (selfUserId !== undefined && event.data.userId !== selfUserId) {
        return { ...state, lastSeq: advanceSeq(state, event.seq) };
      }
      return {
        ...state,
        lastSeq: advanceSeq(state, event.seq),
        read: {
          lastReadSeq: event.data.lastReadSeq,
          unreadCount: event.data.unreadCount,
          totalUnread: event.data.totalUnread,
        },
      };
    case "typing.start":
      return event.data.userId === selfUserId
        ? state
        : { ...state, typing: addUser(state.typing, event.data.userId) };
    case "typing.stop":
      return { ...state, typing: withoutUser(state.typing, event.data.userId) };
    case "channel.updated":
      return { ...state, lastSeq: advanceSeq(state, event.seq), channel: event.data };
    case "channel.deleted":
      return { ...state, lastSeq: advanceSeq(state, event.seq), deleted: true };
    case "member.added":
    case "member.removed":
      return { ...state, lastSeq: advanceSeq(state, event.seq) };
    default:
      return state;
  }
}
