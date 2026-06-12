import type { Channel, Message } from "@firemoot/core";

import type { AnyFiremootEvent } from "./events.js";

export interface ReadState {
  lastReadSeq: number;
  unreadCount: number;
  totalUnread: number;
}

/** A channel member, as hydrated and then maintained by member.* events. */
export interface Member {
  userId: string;
  role: string;
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
  /** The channel's members (userId, role), maintained by member.* events. */
  members: Member[];
  /** userId -> lastReadSeq for every member - the read-receipt map. */
  reads: Record<string, number>;
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
    members: [],
    reads: {},
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

function addMember(members: Member[], member: Member): Member[] {
  const index = members.findIndex((m) => m.userId === member.userId);
  if (index < 0) return [...members, member];
  const next = members.slice();
  next[index] = member;
  return next;
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
    case "read.updated": {
      // Every read receipt (mine or another member's) updates the read map;
      // only my own moves my unread badge.
      const base = {
        ...state,
        lastSeq: advanceSeq(state, event.seq),
        reads: { ...state.reads, [event.data.userId]: event.data.lastReadSeq },
      };
      if (selfUserId !== undefined && event.data.userId !== selfUserId) return base;
      return {
        ...base,
        read: {
          lastReadSeq: event.data.lastReadSeq,
          unreadCount: event.data.unreadCount,
          totalUnread: event.data.totalUnread,
        },
      };
    }
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
      return {
        ...state,
        lastSeq: advanceSeq(state, event.seq),
        members: addMember(state.members, {
          userId: event.data.userId,
          role: event.data.role ?? "member",
        }),
        reads: { [event.data.userId]: 0, ...state.reads },
      };
    case "member.removed": {
      const reads = { ...state.reads };
      delete reads[event.data.userId];
      return {
        ...state,
        lastSeq: advanceSeq(state, event.seq),
        members: state.members.filter((m) => m.userId !== event.data.userId),
        reads,
      };
    }
    default:
      return state;
  }
}
