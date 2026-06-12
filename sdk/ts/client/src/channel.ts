import type { ChannelState as HydratedChannel, Message } from "@firemoot/core";

import { TypedEmitter, type Handler } from "./emitter.js";
import {
  type AnyFiremootEvent,
  ClientFrames,
  type FiremootEvent,
  type ServerEventType,
} from "./events.js";
import { NONCE_KEY, type OptimisticMessage, Outbox } from "./outbox.js";
import type { RestApi } from "./rest.js";
import {
  applyEvent,
  type ChannelState,
  emptyChannelState,
  type Member,
  type ReadState,
} from "./state.js";

export interface SendMessageInput {
  text?: string;
  custom?: Record<string, unknown>;
  attachments?: unknown;
  parentMessageId?: string;
  type?: string;
}

/** A file to upload and attach (browser `File` satisfies this shape). */
export interface FiremootFile {
  name: string;
  /** MIME type; `image/*` becomes an `image` attachment, otherwise `file`. */
  type: string;
  size: number;
  /** The bytes to PUT to the presigned URL. */
  body: BodyInit;
}

export interface ChannelDeps {
  rest: RestApi;
  /** Sends a raw WS frame; returns false when not currently connected. */
  send: (frame: string) => boolean;
  selfUserId?: string;
  typingThrottleMs?: number;
  now?: () => number;
  randomNonce?: () => string;
  /** PUTs upload bytes to the presigned URL; injectable for tests. */
  putFile?: (url: string, body: BodyInit, contentType: string) => Promise<void>;
}

export type ChannelEvents = { [K in ServerEventType]: FiremootEvent<K> } & {
  change: void;
};

function mergeNonce(custom: unknown, nonce: string): Record<string, unknown> {
  const base =
    typeof custom === "object" && custom !== null ? (custom as Record<string, unknown>) : {};
  return { ...base, [NONCE_KEY]: nonce };
}

/**
 * A live handle on one channel (PLAN.md M4.1). `watch()` loads recent state and
 * subscribes; thereafter the state cache is driven by the WS event stream.
 * `sendMessage` is optimistic - the message appears immediately and is
 * reconciled (by nonce) the moment the server confirms it, whichever of the REST
 * response or the `message.new` event arrives first.
 */
export class Channel {
  readonly cid: string;
  private state: ChannelState;
  private readonly outbox = new Outbox();
  private readonly emitter = new TypedEmitter<ChannelEvents>();
  private readonly typingThrottleMs: number;
  private readonly now: () => number;
  private readonly newNonce: () => string;
  private watching = false;
  private lastTypingAt = Number.NEGATIVE_INFINITY;

  constructor(
    readonly type: string,
    readonly id: string,
    private readonly deps: ChannelDeps,
  ) {
    this.cid = `${type}:${id}`;
    this.state = emptyChannelState(this.cid);
    this.typingThrottleMs = deps.typingThrottleMs ?? 3000;
    this.now = deps.now ?? (() => Date.now());
    this.newNonce = deps.randomNonce ?? (() => crypto.randomUUID());
  }

  get isWatching(): boolean {
    return this.watching;
  }

  get lastSeq(): number {
    return this.state.lastSeq;
  }

  /** Confirmed messages (by seq) followed by any optimistic sends still in flight. */
  get messages(): Message[] {
    return [...this.state.messages, ...this.outbox.list().map((o) => o.message)];
  }

  get typing(): string[] {
    return this.state.typing;
  }

  /** Optimistic sends still in flight or failed (for send-status UI / retry). */
  get pendingSends(): OptimisticMessage[] {
    return this.outbox.list();
  }

  get read(): ReadState | null {
    return this.state.read;
  }

  /** The channel's members (userId, role), as hydrated and kept by member.* events. */
  get members(): Member[] {
    return this.state.members;
  }

  /** Read receipts: userId -> lastReadSeq for every member. */
  get readReceipts(): Record<string, number> {
    return this.state.reads;
  }

  on<K extends keyof ChannelEvents>(type: K, handler: Handler<ChannelEvents[K]>): () => void {
    return this.emitter.on(type, handler);
  }

  off<K extends keyof ChannelEvents>(type: K, handler: Handler<ChannelEvents[K]>): void {
    this.emitter.off(type, handler);
  }

  /** Loads recent state then subscribes over the WS connection. */
  async watch(limit = 50): Promise<void> {
    await this.loadInitial(limit);
    this.watching = true;
    this.subscribe();
  }

  /** Re-queries state and re-subscribes - the response to a `resync_required`. */
  async resync(limit = 50): Promise<void> {
    await this.loadInitial(limit);
    if (this.watching) this.subscribe();
  }

  /**
   * Seeds this channel from a hydrated `channels/query` (or get-channel) result:
   * channel meta, members and their read positions, the caller's read state and
   * the latest message (a conversation preview). `lastSeq` becomes the channel's
   * current seq, so a subsequent `watchFromHydrated()` goes straight to live
   * without replaying history.
   */
  hydrate(dto: HydratedChannel): void {
    const members: Member[] = (dto.members ?? []).map((m) => ({
      userId: m.userId,
      role: m.role,
    }));
    const reads: Record<string, number> = {};
    for (const m of dto.members ?? []) reads[m.userId] = m.lastReadSeq;
    const latest = dto.latestMessage;
    this.state = {
      ...emptyChannelState(this.cid),
      channel: dto.channel,
      messages: latest ? [latest] : [],
      members,
      reads,
      read: dto.read
        ? {
            lastReadSeq: dto.read.lastReadSeq,
            unreadCount: dto.read.unreadCount,
            totalUnread: this.state.read?.totalUnread ?? 0,
          }
        : null,
      lastSeq: dto.channel.currentSeq,
    };
    this.emitter.emit("change", undefined);
  }

  /** Marks the (already hydrated) channel watched and subscribes from `lastSeq`. */
  watchFromHydrated(): void {
    this.watching = true;
    this.subscribe();
  }

  private async loadInitial(limit: number): Promise<void> {
    const [channel, page] = await Promise.all([
      this.deps.rest.getChannel(this.type, this.id),
      this.deps.rest.getMessages(this.type, this.id, { limit }),
    ]);
    const messages = (page.messages ?? []).slice().sort((a, b) => a.seq - b.seq);
    const last = messages.at(-1);
    this.state = {
      ...emptyChannelState(this.cid),
      channel,
      messages,
      lastSeq: last ? last.seq : 0,
    };
    this.emitter.emit("change", undefined);
  }

  private subscribe(): void {
    this.deps.send(ClientFrames.subscribe({ [this.cid]: this.state.lastSeq }));
  }

  /** Routes a decoded server event for this channel into the cache. */
  handleEvent(event: AnyFiremootEvent): void {
    this.state = applyEvent(this.state, event, this.deps.selfUserId);
    if (event.type === "message.new" || event.type === "message.updated") {
      this.outbox.confirm(event.data);
    }
    this.emitter.emit(event.type, event as ChannelEvents[typeof event.type]);
    this.emitter.emit("change", undefined);
  }

  async sendMessage(input: SendMessageInput): Promise<Message> {
    const nonce = this.newNonce();
    const custom = mergeNonce(input.custom, nonce);
    const optimistic = this.buildOptimistic(input, nonce, custom);
    this.outbox.add({ nonce, status: "sending", message: optimistic });
    this.emitter.emit("change", undefined);

    const body = {
      custom,
      ...(this.deps.selfUserId !== undefined ? { userId: this.deps.selfUserId } : {}),
      ...(input.text !== undefined ? { text: input.text } : {}),
      ...(input.attachments !== undefined ? { attachments: input.attachments } : {}),
      ...(input.parentMessageId !== undefined ? { parentMessageId: input.parentMessageId } : {}),
      ...(input.type !== undefined ? { type: input.type } : {}),
    };

    try {
      const sent = await this.deps.rest.sendMessage(this.type, this.id, body);
      this.state = applyEvent(
        this.state,
        { type: "message.new", cid: this.cid, seq: sent.seq, data: sent },
        this.deps.selfUserId,
      );
      this.outbox.confirm(sent);
      this.emitter.emit("change", undefined);
      return sent;
    } catch (error) {
      this.outbox.markFailed(nonce, error);
      this.emitter.emit("change", undefined);
      throw error;
    }
  }

  /**
   * Uploads a file (presign -> PUT -> attach) then sends it as a message. The
   * attachment carries `url` (the object URL); the server's thumbnailer patches
   * `thumbUrl` onto it asynchronously via a later `message.updated`.
   */
  async sendFileMessage(
    file: FiremootFile,
    input: { text?: string; custom?: Record<string, unknown> } = {},
  ): Promise<Message> {
    if (this.deps.selfUserId === undefined) throw new Error("a userId is required to upload");
    const ticket = await this.deps.rest.createUpload({
      userId: this.deps.selfUserId,
      filename: file.name,
      mime: file.type,
      sizeBytes: file.size,
    });
    const put = this.deps.putFile ?? defaultPutFile;
    await put(ticket.uploadUrl, file.body, file.type);
    const attachment = {
      type: file.type.startsWith("image/") ? "image" : "file",
      url: ticket.objectUrl,
      name: file.name,
      mime: file.type,
      size: file.size,
    };
    return this.sendMessage({
      attachments: [attachment],
      ...(input.text !== undefined ? { text: input.text } : {}),
      ...(input.custom !== undefined ? { custom: input.custom } : {}),
    });
  }

  editMessage(messageId: string, text: string, custom?: Record<string, unknown>): Promise<Message> {
    return this.deps.rest.editMessage(this.type, this.id, messageId, {
      text,
      ...(custom !== undefined ? { custom } : {}),
    });
  }

  deleteMessage(messageId: string): Promise<void> {
    return this.deps.rest.deleteMessage(this.type, this.id, messageId);
  }

  async react(messageId: string, reactionType: string): Promise<void> {
    if (this.deps.selfUserId === undefined) throw new Error("a userId is required to react");
    await this.deps.rest.addReaction(this.type, this.id, messageId, {
      userId: this.deps.selfUserId,
      type: reactionType,
    });
  }

  /** Removes the connected user's own reaction (the server forbids removing others'). */
  async removeReaction(messageId: string, reactionType: string): Promise<void> {
    if (this.deps.selfUserId === undefined)
      throw new Error("a userId is required to remove a reaction");
    await this.deps.rest.removeReaction(
      this.type,
      this.id,
      messageId,
      reactionType,
      this.deps.selfUserId,
    );
  }

  async markRead(seq?: number): Promise<void> {
    if (this.deps.selfUserId === undefined) throw new Error("a userId is required to mark read");
    const result = await this.deps.rest.markRead(this.type, this.id, {
      userId: this.deps.selfUserId,
      ...(seq !== undefined ? { seq } : {}),
    });
    this.state = {
      ...this.state,
      read: {
        lastReadSeq: result.lastReadSeq,
        unreadCount: result.unreadCount,
        totalUnread: result.totalUnread,
      },
    };
    this.emitter.emit("change", undefined);
  }

  /** Sends a throttled `typing.start` (at most once per throttle window). */
  keystroke(): void {
    const now = this.now();
    if (now - this.lastTypingAt >= this.typingThrottleMs) {
      this.lastTypingAt = now;
      this.deps.send(ClientFrames.typingStart(this.cid));
    }
  }

  stopTyping(): void {
    this.lastTypingAt = Number.NEGATIVE_INFINITY;
    this.deps.send(ClientFrames.typingStop(this.cid));
  }

  /** Stops tracking this channel client-side (the protocol has no unsubscribe frame). */
  unwatch(): void {
    this.watching = false;
  }

  private buildOptimistic(
    input: SendMessageInput,
    nonce: string,
    custom: Record<string, unknown>,
  ): Message {
    const stamp = new Date().toISOString();
    return {
      id: `firemoot-temp-${nonce}`,
      cid: this.cid,
      seq: Number.MAX_SAFE_INTEGER,
      type: input.type ?? "regular",
      custom,
      attachments: input.attachments ?? [],
      replyCount: 0,
      createdAt: stamp,
      updatedAt: stamp,
      ...(this.deps.selfUserId !== undefined ? { userId: this.deps.selfUserId } : {}),
      ...(input.text !== undefined ? { text: input.text } : {}),
      ...(input.parentMessageId !== undefined ? { parentMessageId: input.parentMessageId } : {}),
    };
  }
}

/** The default upload transport: a plain `PUT` of the bytes to the presigned URL. */
async function defaultPutFile(url: string, body: BodyInit, contentType: string): Promise<void> {
  const response = await fetch(url, {
    method: "PUT",
    body,
    headers: { "Content-Type": contentType },
  });
  if (!response.ok) throw new Error(`upload PUT failed: ${response.status}`);
}
