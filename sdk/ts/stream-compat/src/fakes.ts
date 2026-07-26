/**
 * Shared test doubles. Excluded from the build in tsconfig.json - this file
 * exists only so the suites can inject a fake socket and fake REST transports,
 * the same seams `@firemoot/client` uses to test its own value layer.
 */
import type { RestApi, ServerRestApi, Socket } from "@firemoot/client";
import type { Channel, ChannelState, Message } from "@firemoot/core";

import type { CompatRestApi } from "./transport.js";

export const CID = "messaging:general";

export function message(id: string, seq: number, overrides: Partial<Message> = {}): Message {
  return {
    id,
    cid: CID,
    seq,
    type: "regular",
    custom: {},
    attachments: [],
    replyCount: 0,
    createdAt: "2026-07-26T10:00:00Z",
    updatedAt: "2026-07-26T10:00:00Z",
    ...overrides,
  };
}

export function channelMeta(id: string, overrides: Partial<Channel> = {}): Channel {
  return {
    cid: `messaging:${id}`,
    type: "messaging",
    id,
    custom: {},
    frozen: false,
    archived: false,
    currentSeq: 0,
    createdAt: "2026-07-26T09:00:00Z",
    updatedAt: "2026-07-26T09:00:00Z",
    ...overrides,
  };
}

export function channelState(id: string, overrides: Partial<ChannelState> = {}): ChannelState {
  return { channel: channelMeta(id), members: [], ...overrides };
}

/** Every method rejects, so a test only has to override what it expects to be called. */
export const rejectingRest: RestApi = {
  getChannel: () => Promise.reject(new Error("getChannel: unexpected call")),
  getMessages: () => Promise.reject(new Error("getMessages: unexpected call")),
  sendMessage: () => Promise.reject(new Error("sendMessage: unexpected call")),
  editMessage: () => Promise.reject(new Error("editMessage: unexpected call")),
  deleteMessage: () => Promise.reject(new Error("deleteMessage: unexpected call")),
  addReaction: () => Promise.reject(new Error("addReaction: unexpected call")),
  removeReaction: () => Promise.reject(new Error("removeReaction: unexpected call")),
  markRead: () => Promise.reject(new Error("markRead: unexpected call")),
  queryChannels: () => Promise.reject(new Error("queryChannels: unexpected call")),
  createUpload: () => Promise.reject(new Error("createUpload: unexpected call")),
};

export const rejectingServerRest: ServerRestApi = {
  upsertUser: () => Promise.reject(new Error("upsertUser: unexpected call")),
  createChannel: () => Promise.reject(new Error("createChannel: unexpected call")),
  addMember: () => Promise.reject(new Error("addMember: unexpected call")),
  deleteMessage: () => Promise.reject(new Error("deleteMessage: unexpected call")),
};

export const rejectingCompatRest: CompatRestApi = {
  patchChannelCustom: () => Promise.reject(new Error("patchChannelCustom: unexpected call")),
  deleteChannel: () => Promise.reject(new Error("deleteChannel: unexpected call")),
  deleteUser: () => Promise.reject(new Error("deleteUser: unexpected call")),
  flagMessage: () => Promise.reject(new Error("flagMessage: unexpected call")),
};

export class FakeSocket implements Socket {
  sent: string[] = [];
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((data: string) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: ((error: unknown) => void) | null = null;

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.closed = true;
    this.onclose?.();
  }

  hello(): void {
    this.onmessage?.(
      JSON.stringify({
        type: "hello",
        connectionId: "c1",
        serverTime: "2026-07-26T10:00:00Z",
        me: {},
        totalUnread: 0,
      }),
    );
  }

  deliver(frame: unknown): void {
    this.onmessage?.(JSON.stringify(frame));
  }

  /** The frames sent as parsed objects, for asserting on subscribe/typing. */
  frames(): Array<Record<string, unknown>> {
    return this.sent.map((raw) => JSON.parse(raw) as Record<string, unknown>);
  }
}

/**
 * Drives a pending connect to the point where the socket exists, then completes
 * the handshake. Flushing in a loop (rather than a fixed number of microtasks)
 * keeps these suites immune to the await-count sensitivity that the client's own
 * connection tests have.
 */
export async function completeHandshake(sockets: FakeSocket[]): Promise<FakeSocket> {
  for (let i = 0; i < 50 && sockets.length === 0; i++) await Promise.resolve();
  const socket = sockets[0];
  if (!socket) throw new Error("no socket was opened");
  socket.hello();
  return socket;
}
