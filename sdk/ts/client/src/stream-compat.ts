import type { Message } from "@firemoot/core";

import type { Channel } from "./channel.js";

/** Where a member has read up to (a read receipt). */
export interface StreamCompatRead {
  userId: string;
  lastReadSeq: number;
}

/** A channel member in the compat view. */
export interface StreamCompatMember {
  userId: string;
  role: string;
}

/**
 * A Stream `channel.state`-shaped projection of a Firemoot {@link Channel}, to
 * ease migrating UIs that read `channel.state.{messages, members, read,
 * unreadCount, last_message_at}`. `members` and `read` are keyed by `userId`,
 * mirroring Stream's `channel.state`. This is a *pure projection* of the client's
 * reducer - recompute it whenever the channel emits `change`.
 */
export interface StreamCompatChannelState {
  /** Confirmed messages by ascending seq, then any in-flight optimistic sends. */
  messages: Message[];
  /** Members keyed by userId. */
  members: Record<string, StreamCompatMember>;
  /** Read receipts keyed by userId (every member's last-read position). */
  read: Record<string, StreamCompatRead>;
  /** The calling user's unread count (0 if unknown). */
  unreadCount: number;
  /** ISO timestamp of the latest known message, or null if none. */
  last_message_at: string | null;
}

/**
 * Projects a {@link Channel} into a Stream-`channel.state`-compatible read model.
 * Everything here is already maintained by the client's reducer (hydration plus
 * `message.*`, `read.updated`, `member.*` events); this only reshapes it.
 */
export function streamChannelState(channel: Channel): StreamCompatChannelState {
  const members: Record<string, StreamCompatMember> = {};
  for (const member of channel.members) {
    members[member.userId] = { userId: member.userId, role: member.role };
  }

  const read: Record<string, StreamCompatRead> = {};
  for (const [userId, lastReadSeq] of Object.entries(channel.readReceipts)) {
    read[userId] = { userId, lastReadSeq };
  }

  const messages = channel.messages;
  const lastMessage = messages.at(-1);

  return {
    messages,
    members,
    read,
    unreadCount: channel.read?.unreadCount ?? 0,
    last_message_at: lastMessage?.createdAt ?? null,
  };
}
