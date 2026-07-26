import type {
  Channel as FiremootChannel,
  FiremootServer,
  MemberInput,
  RestApi,
} from "@firemoot/client";
import { streamChannelState } from "@firemoot/client";
import type { Message } from "@firemoot/core";

import {
  installUnsupported,
  unsupported,
  type UnsupportedMethods,
  FiremootCompatError,
} from "./errors.js";
import {
  FIREMOOT_CHANNEL_EVENT,
  type StreamChannelEventType,
  type StreamCompatEvent,
  toStreamChannelEvent,
} from "./events.js";
import {
  asRecord,
  duplicateChannelError,
  rethrowAsError,
  type StreamCompatMessage,
  toStreamMessage,
} from "./message.js";
import type { CompatRestApi } from "./transport.js";

export type StreamEventHandler = (event: StreamCompatEvent) => void;

/** Membership and custom fields passed at `client.channel(type, id, data)` time. */
export interface StreamCompatChannelData {
  members?: string[];
  created_by_id?: string;
  [custom: string]: unknown;
}

/**
 * An outgoing message. In server-trusted mode it may be attributed to any
 * `user_id`; in browser mode the connected user is always the author. Unknown
 * top-level keys are folded into the message's custom data, matching Stream.
 */
export interface StreamCompatOutgoingMessage {
  id?: string;
  text?: string;
  type?: string;
  user_id?: string;
  parent_id?: string;
  attachments?: unknown;
  custom?: Record<string, unknown>;
  [field: string]: unknown;
}

export interface StreamCompatChannelState {
  messages: StreamCompatMessage[];
  members: Record<string, { userId: string; role: string }>;
  read: Record<string, { userId: string; lastReadSeq: number }>;
  unreadCount: number;
  last_message_at: string | null;
}

export interface StreamCompatQueryOptions {
  /** `id_lt`: messages older than this message id (a Stream message-id cursor). */
  messages?: { limit?: number; id_lt?: string };
  watch?: boolean;
  state?: boolean;
}

export interface StreamCompatQueryResult {
  channel?: Record<string, unknown>;
  messages: StreamCompatMessage[];
}

/** The server-trusted transports a channel needs when the client holds an API secret. */
export interface ServerChannelDeps {
  server: FiremootServer;
  rest: RestApi;
  compat: CompatRestApi;
}

/**
 * `stream-chat` surface with no Firemoot equivalent. Each throws a
 * `FiremootCompatError` naming the method - never a silent no-op.
 */
const UNSUPPORTED_CHANNEL_METHODS = [
  "acceptInvite",
  "addFilterTags",
  "addModerators",
  "archive",
  "assignRoles",
  "banUser",
  "clean",
  "clearAIIndicator",
  "countUnreadMentions",
  "createDraft",
  "deleteDraft",
  "deleteFile",
  "deleteImage",
  "demoteModerators",
  "disableSlowMode",
  "enableSlowMode",
  "getConfig",
  "getDraft",
  "getMessagesById",
  "getPinnedMessages",
  "getReactions",
  "getReplies",
  "hide",
  "inviteMembers",
  "markAsReadRequest",
  "markReadLocally",
  "markUnread",
  "mute",
  "muteStatus",
  "partialUpdateMember",
  "pin",
  "queryMembers",
  "rejectInvite",
  "removeFilterTags",
  "removeMembers",
  "removeShadowBan",
  "removeVote",
  "search",
  "sendAction",
  "sendSharedLocation",
  "shadowBan",
  "show",
  "stopAIResponse",
  "stopLiveLocationSharing",
  "truncate",
  "unarchive",
  "unbanUser",
  "unmute",
  "unpin",
  "update",
  "updateAIState",
  "updateMemberPartial",
  "vote",
] as const;

export interface StreamChatChannel extends UnsupportedMethods<typeof UNSUPPORTED_CHANNEL_METHODS> {}

/**
 * A Stream-`Channel`-shaped facade.
 *
 * Like `stream-chat`'s own `Channel`, one class serves both modes: a browser
 * client backs it with a live `@firemoot/client` `Channel` (WS state, optimistic
 * sends, events), while a server-trusted client backs it with HMAC-signed REST
 * (create, query, membership, attribution to any user). Calling a method that
 * needs the other mode throws a `FiremootCompatError` saying so.
 */
export class StreamChatChannel {
  readonly cid: string;
  private serverData: Record<string, unknown> | undefined;
  private serverMessages: StreamCompatMessage[] = [];
  /**
   * The underlying unsubscribe fn per `(streamEventType, originalHandler)` pair,
   * so `off(type, handler)` can detach exactly what `on(type, handler)` attached
   * (the facade wraps every handler, so the live channel never sees the caller's
   * own function identity).
   */
  private readonly subscriptions = new Map<
    StreamChannelEventType,
    Map<StreamEventHandler, () => void>
  >();

  constructor(
    readonly type: string,
    readonly id: string,
    private readonly live: FiremootChannel | null,
    private readonly server: ServerChannelDeps | null,
    private readonly initData: StreamCompatChannelData = {},
  ) {
    this.cid = `${type}:${id}`;
  }

  private requireLive(method: string): FiremootChannel {
    if (!this.live) {
      throw new FiremootCompatError(
        `channel.${method}() needs a connected user - it is not available on a server-trusted ` +
          `client (constructed with an API secret). Call connectUser() on a browser client first.`,
      );
    }
    return this.live;
  }

  private requireServer(method: string): ServerChannelDeps {
    if (!this.server) {
      throw new FiremootCompatError(
        `channel.${method}() is a server-side operation - construct the client with your API ` +
          `secret (new StreamChat(key, secret, { baseURL })) to use it.`,
      );
    }
    return this.server;
  }

  /** The Stream-`channel.data`-shaped metadata: custom fields plus id/type/cid. */
  get data(): Record<string, unknown> {
    if (this.live) {
      const meta = this.live.meta;
      return {
        ...asRecord(meta?.custom),
        id: meta?.id ?? this.id,
        type: meta?.type ?? this.type,
        cid: this.cid,
      };
    }
    return { ...(this.serverData ?? {}), id: this.id, type: this.type, cid: this.cid };
  }

  /**
   * The Stream-`channel.state`-shaped view. In browser mode it is recomputed from
   * the live reducer on every read; in server mode it holds whatever the last
   * `query()`/`create()` returned (there is no server-side watch).
   */
  get state(): StreamCompatChannelState {
    if (!this.live) {
      return {
        messages: this.serverMessages,
        members: {},
        read: {},
        unreadCount: 0,
        last_message_at: this.serverMessages.at(-1)?.created_at ?? null,
      };
    }
    const projected = streamChannelState(this.live);
    return { ...projected, messages: projected.messages.map(toStreamMessage) };
  }

  /** The most recent message, or undefined (Stream's `channel.lastMessage()`). */
  lastMessage(): StreamCompatMessage | undefined {
    return this.state.messages.at(-1);
  }

  /** The connected user's unread count (Stream's `channel.countUnread()`). */
  countUnread(): number {
    return this.state.unreadCount;
  }

  async sendMessage(input: StreamCompatOutgoingMessage): Promise<{ message: StreamCompatMessage }> {
    const { id, text, type, user_id, parent_id, attachments, custom, ...extra } = input;
    // Unknown top-level keys become custom data, matching Stream (which stores
    // unrecognised message fields as custom).
    const folded: Record<string, unknown> = { ...custom, ...extra };

    if (this.live) {
      const message = await this.live.sendMessage({
        ...(text !== undefined ? { text } : {}),
        ...(attachments !== undefined ? { attachments } : {}),
        ...(parent_id !== undefined ? { parentMessageId: parent_id } : {}),
        ...(type !== undefined ? { type } : {}),
        ...(Object.keys(folded).length > 0 ? { custom: folded } : {}),
      });
      return { message: toStreamMessage(message) };
    }

    const deps = this.requireServer("sendMessage");
    if (!user_id) {
      throw new FiremootCompatError(
        "channel.sendMessage() on a server-trusted client needs a user_id to attribute the " +
          "message to.",
      );
    }
    try {
      // A caller-supplied `id` passes straight through - Firemoot dedupes a
      // re-send of the same id with a 409, just as Stream does.
      const sent = await deps.rest.sendMessage(this.type, this.id, {
        userId: user_id,
        ...(id !== undefined ? { id } : {}),
        ...(text !== undefined ? { text } : {}),
        ...(type !== undefined ? { type } : {}),
        ...(attachments !== undefined ? { attachments } : {}),
        ...(parent_id !== undefined ? { parentMessageId: parent_id } : {}),
        ...(Object.keys(folded).length > 0 ? { custom: folded } : {}),
      });
      return { message: toStreamMessage(sent) };
    } catch (err) {
      rethrowAsError(err);
    }
  }

  markRead(): Promise<void> {
    return this.requireLive("markRead").markRead();
  }

  keystroke(): Promise<void> {
    this.requireLive("keystroke").keystroke();
    return Promise.resolve();
  }

  stopTyping(): Promise<void> {
    this.requireLive("stopTyping").stopTyping();
    return Promise.resolve();
  }

  async sendReaction(
    messageId: string,
    reaction: { type: string },
  ): Promise<{ reaction: unknown }> {
    await this.requireLive("sendReaction").react(messageId, reaction.type);
    return { reaction: { type: reaction.type, message_id: messageId } };
  }

  deleteReaction(messageId: string, reactionType: string): Promise<void> {
    return this.requireLive("deleteReaction").removeReaction(messageId, reactionType);
  }

  /**
   * Uploads an image (presign then PUT) and returns its object URL under `file`,
   * matching Stream's `channel.sendImage`. Upload-only: the caller attaches the
   * URL and sends the message, so several files batch into one. `thumb_url` is
   * unknown at upload time - the server's thumbnailer patches it on later via a
   * `message.updated`.
   */
  async sendImage(
    file: { size: number; [key: string]: unknown },
    name: string,
    contentType: string,
  ): Promise<{ file: string; thumb_url?: string }> {
    const { url } = await this.requireLive("sendImage").uploadFile({
      name,
      type: contentType,
      size: file.size,
      body: file as unknown as BodyInit,
    });
    return { file: url };
  }

  /** Uploads a non-image file and returns its object URL (Stream's `channel.sendFile`). */
  async sendFile(
    file: { size: number; [key: string]: unknown },
    name: string,
    contentType: string,
  ): Promise<{ file: string }> {
    const { url } = await this.requireLive("sendFile").uploadFile({
      name,
      type: contentType,
      size: file.size,
      body: file as unknown as BodyInit,
    });
    return { file: url };
  }

  /**
   * Loads recent state and subscribes over the WS connection. On a server-trusted
   * client this is a **deliberate no-op**: Firemoot has no server-side watch, and
   * server code that calls `watch()` before `query()` (a common Stream idiom) is
   * served entirely by `query()`.
   */
  watch(): Promise<void> {
    if (!this.live) return Promise.resolve();
    return this.live.watch();
  }

  /** Stops tracking this channel client-side (Stream's `stopWatching`). */
  stopWatching(): Promise<void> {
    this.requireLive("stopWatching").unwatch();
    return Promise.resolve();
  }

  /**
   * Creates the channel with its `members` and custom fields. Firemoot does not
   * auto-create channels, so this provisions them. On a channel that already
   * exists it tops up membership and then throws a duplicate-shaped error,
   * mirroring Stream's native `create()`-on-existing so one call site serves both.
   */
  async create(): Promise<void> {
    const deps = this.requireServer("create");
    const members: MemberInput[] = (this.initData.members ?? []).map((userId) => ({ userId }));
    const page = await deps.rest.queryChannels({ type: this.type, cids: [this.cid] });
    if ((page.channels ?? []).length > 0) {
      await deps.server.addMembers(this.type, this.id, members);
      throw duplicateChannelError(this.id);
    }
    const { members: _members, created_by_id: createdBy, ...custom } = this.initData;
    await deps.server.createChannel(
      {
        type: this.type,
        id: this.id,
        ...(createdBy !== undefined ? { createdBy } : {}),
        custom,
      },
      members,
    );
    this.serverData = custom;
  }

  async updatePartial(update: { set: Record<string, unknown> }): Promise<void> {
    const deps = this.requireServer("updatePartial");
    await deps.compat.patchChannelCustom(this.type, this.id, update.set);
    this.serverData = { ...(this.serverData ?? {}), ...update.set };
  }

  delete(): Promise<void> {
    return this.requireServer("delete").compat.deleteChannel(this.type, this.id);
  }

  addMembers(memberIds: string[]): Promise<void> {
    return this.requireServer("addMembers").server.addMembers(
      this.type,
      this.id,
      memberIds.map((userId) => ({ userId })),
    );
  }

  /**
   * Server-side event inject. A **deliberate no-op**: Firemoot's typing events are
   * client-only (a WS keystroke frame), so there is nothing to inject. Apps call
   * this for typing indicators from the server, where dropping it is harmless.
   */
  sendEvent(): Promise<void> {
    return Promise.resolve();
  }

  /**
   * Fetches the channel and a page of messages. On a browser client this watches
   * the channel; on a server-trusted client it is a plain read. A Stream `id_lt`
   * cursor maps onto the server's `before_id` (id-to-seq resolution happens
   * server-side, strictly-before, same ordering).
   */
  async query(options?: StreamCompatQueryOptions): Promise<StreamCompatQueryResult> {
    if (this.live) {
      await this.live.watch(options?.messages?.limit ?? 50);
      return { channel: this.data, messages: this.state.messages };
    }

    const deps = this.requireServer("query");
    const wanted = options?.messages;
    // The channel's custom and its messages are fetched together: Stream's query
    // response carries the channel alongside the messages, so `.data` and the
    // returned `.channel` stay at parity here.
    const [messagePage, channelPage] = await Promise.all([
      wanted
        ? deps.rest.getMessages(this.type, this.id, {
            limit: wanted.limit ?? 100,
            ...(wanted.id_lt !== undefined ? { before_id: wanted.id_lt } : {}),
          })
        : Promise.resolve(undefined),
      deps.rest.queryChannels({ type: this.type, cids: [this.cid], limit: 1 }),
    ]);

    if (messagePage) {
      // getMessages returns newest-first; readers expect oldest-first.
      this.serverMessages = (messagePage.messages ?? [])
        .map((m: Message) => toStreamMessage(m))
        .reverse();
    }

    const state = channelPage.channels?.[0];
    this.serverData = state ? asRecord(state.channel.custom) : undefined;
    const channel = state
      ? {
          ...asRecord(state.channel.custom),
          id: state.channel.id,
          type: state.channel.type,
          cid: state.channel.cid,
        }
      : undefined;

    return { ...(channel !== undefined ? { channel } : {}), messages: this.serverMessages };
  }

  /** Seeds a query-result channel's preview state (custom plus latest message). */
  hydrateFromState(custom: unknown, latestMessage: Message | undefined): void {
    this.serverData = asRecord(custom);
    this.serverMessages = latestMessage ? [toStreamMessage(latestMessage)] : [];
  }

  /**
   * Subscribes to a Stream-named channel event; the handler receives a
   * Stream-shaped event. Returns `{ unsubscribe }` to match Stream's `on`, and is
   * also detachable via `off(type, handler)` (the other half of Stream's API).
   */
  on(type: StreamChannelEventType, handler: StreamEventHandler): { unsubscribe: () => void } {
    const live = this.requireLive("on");
    const firemootType = FIREMOOT_CHANNEL_EVENT[type];
    if (firemootType === undefined) unsupported(`channel.on("${type}")`);
    const off = live.on(firemootType as never, (event) => {
      const e = event as { cid?: string; data?: unknown };
      handler(toStreamChannelEvent(type, e.cid ?? this.cid, e.data));
    });
    let forType = this.subscriptions.get(type);
    if (!forType) {
      forType = new Map();
      this.subscriptions.set(type, forType);
    }
    forType.set(handler, off);
    return { unsubscribe: () => this.off(type, handler) };
  }

  /** Detaches a handler previously attached with `on(type, handler)`. */
  off(type: StreamChannelEventType, handler: StreamEventHandler): void {
    const forType = this.subscriptions.get(type);
    const off = forType?.get(handler);
    if (!forType || !off) return;
    off();
    forType.delete(handler);
    if (forType.size === 0) this.subscriptions.delete(type);
  }
}

installUnsupported(StreamChatChannel.prototype, "channel", UNSUPPORTED_CHANNEL_METHODS);

export { UNSUPPORTED_CHANNEL_METHODS };
