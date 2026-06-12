import type { ChannelQuery } from "@firemoot/core";

import { Channel } from "./channel.js";
import {
  Connection,
  type ConnectionStatus,
  type ReconnectPolicy,
  type SocketFactory,
} from "./connection.js";
import { TypedEmitter, type Handler } from "./emitter.js";
import {
  type AnyFiremootEvent,
  type FiremootEvent,
  type ServerEventType,
  type ServerFrame,
} from "./events.js";
import { coreRestApi, type RestApi } from "./rest.js";

export type ClientEvents = { [K in ServerEventType]: FiremootEvent<K> } & {
  /** Every server event, type-erased - convenient for logging or a single sink. */
  event: AnyFiremootEvent;
  connected: { connectionId: string };
  disconnected: { willReconnect: boolean };
  reconnecting: { attempt: number; delayMs: number };
  status: ConnectionStatus;
  resync: { cid: string };
  error: { error: unknown };
};

export interface FiremootClientConfig {
  baseUrl: string;
  /** The connected user; stamps optimistic sends and scopes the unread badge. */
  userId?: string;
  /** A static WS token, or use `tokenProvider` for refresh-on-(re)connect. */
  token?: string;
  tokenProvider?: () => string | Promise<string>;
  /** Overrides the WS URL (default: `baseUrl` with http->ws + `/v1/ws`). */
  wsUrl?: string;
  /** A custom REST adapter (default: `@firemoot/core` against `baseUrl`). */
  rest?: RestApi;
  socketFactory?: SocketFactory;
  reconnect?: Partial<ReconnectPolicy>;
  typingThrottleMs?: number;
}

/**
 * The top-level client (PLAN.md M4.1): owns the WS connection and a registry of
 * `Channel` handles, routes the event stream to them by cid, and re-subscribes
 * every watched channel from its resume seq across reconnects. Presence and
 * user-directed notifications surface on the client-level emitter.
 */
export class FiremootClient {
  private readonly rest: RestApi;
  private readonly connection: Connection;
  private readonly channels = new Map<string, Channel>();
  private readonly emitter = new TypedEmitter<ClientEvents>();

  constructor(private readonly config: FiremootClientConfig) {
    this.rest =
      config.rest ?? coreRestApi({ baseUrl: config.baseUrl, authorize: () => this.authHeaders() });
    this.connection = new Connection({
      urlProvider: () => this.wsUrl(),
      subscriptions: () => this.subscriptions(),
      ...(config.socketFactory ? { socketFactory: config.socketFactory } : {}),
      ...(config.reconnect ? { reconnect: config.reconnect } : {}),
    });
    this.connection.on("frame", (frame) => this.onFrame(frame));
    this.connection.on("connected", (e) =>
      this.emitter.emit("connected", { connectionId: e.connectionId }),
    );
    this.connection.on("disconnected", (e) => this.emitter.emit("disconnected", e));
    this.connection.on("reconnecting", (e) => this.emitter.emit("reconnecting", e));
    this.connection.on("status", (s) => this.emitter.emit("status", s));
    this.connection.on("error", (e) => this.emitter.emit("error", e));
  }

  get connectionState(): ConnectionStatus {
    return this.connection.status;
  }

  on<K extends keyof ClientEvents>(type: K, handler: Handler<ClientEvents[K]>): () => void {
    return this.emitter.on(type, handler);
  }

  off<K extends keyof ClientEvents>(type: K, handler: Handler<ClientEvents[K]>): void {
    this.emitter.off(type, handler);
  }

  connect(): Promise<void> {
    return this.connection.connect();
  }

  disconnect(): void {
    this.connection.close();
  }

  /** Returns the (memoised) handle for a channel. */
  channel(type: string, id: string): Channel {
    const cid = `${type}:${id}`;
    const existing = this.channels.get(cid);
    if (existing) return existing;
    const channel = new Channel(type, id, {
      rest: this.rest,
      send: (frame) => this.connection.send(frame),
      ...(this.config.userId !== undefined ? { selfUserId: this.config.userId } : {}),
      ...(this.config.typingThrottleMs !== undefined
        ? { typingThrottleMs: this.config.typingThrottleMs }
        : {}),
    });
    this.channels.set(cid, channel);
    return channel;
  }

  /**
   * Queries channels (server-side authorised to the connected user) and returns
   * a `Channel` handle per result, each seeded from the hydrated response
   * (members, read state, latest message). With `{ watch: true }` each handle is
   * subscribed over the WS connection (Stream's `queryChannels({watch:true})`
   * semantics) and re-subscribed automatically across reconnects.
   */
  async queryChannels(
    query: ChannelQuery = {},
    opts: { watch?: boolean } = {},
  ): Promise<Channel[]> {
    const page = await this.rest.queryChannels(query);
    return (page.channels ?? []).map((cs) => {
      const channel = this.channel(cs.channel.type, cs.channel.id);
      channel.hydrate(cs);
      if (opts.watch) channel.watchFromHydrated();
      return channel;
    });
  }

  private subscriptions(): Record<string, number> {
    const subscriptions: Record<string, number> = {};
    for (const channel of this.channels.values()) {
      if (channel.isWatching) subscriptions[channel.cid] = channel.lastSeq;
    }
    return subscriptions;
  }

  /** Bearer-auth headers for the default REST adapter (the connected user's JWT). */
  private async authHeaders(): Promise<Record<string, string>> {
    const token = this.config.tokenProvider ? await this.config.tokenProvider() : this.config.token;
    return token !== undefined ? { Authorization: `Bearer ${token}` } : {};
  }

  private async wsUrl(): Promise<string> {
    const base = this.config.wsUrl ?? `${this.config.baseUrl.replace(/^http/, "ws")}/v1/ws`;
    const token = this.config.tokenProvider ? await this.config.tokenProvider() : this.config.token;
    return token !== undefined ? `${base}?token=${encodeURIComponent(token)}` : base;
  }

  private onFrame(frame: ServerFrame): void {
    if (frame.type === "resync_required") {
      this.emitter.emit("resync", { cid: frame.cid });
      void this.channels.get(frame.cid)?.resync();
      return;
    }
    if (frame.type === "hello" || frame.type === "pong") return;
    const event: AnyFiremootEvent = frame;
    this.emitter.emit("event", event);
    this.emitter.emit(event.type, event);
    this.channels.get(event.cid)?.handleEvent(event);
  }
}
