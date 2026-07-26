import {
  coreRestApi,
  createBearerAuthorizer,
  createHmacAuthorizer,
  FiremootClient,
  type FiremootClientConfig,
  FiremootServer,
  type RestApi,
  type ServerRestApi,
} from "@firemoot/client";
import type { ChannelQuery, Message } from "@firemoot/core";

import {
  type ServerChannelDeps,
  StreamChatChannel,
  type StreamCompatChannelData,
  type StreamEventHandler,
} from "./channel.js";
import { hmacHex, signUserToken, timingSafeEqual } from "./crypto.js";
import { FiremootCompatError, installUnsupported, type UnsupportedMethods } from "./errors.js";
import {
  type ChannelSort,
  FIREMOOT_CLIENT_EVENT,
  lastMessageSortDirection,
  STREAM_EVENT_NAME,
  type StreamClientEventType,
  type StreamCompatEvent,
  toStreamClientEvent,
} from "./events.js";
import { asRecord } from "./message.js";
import { type CompatRestApi, coreCompatRestApi } from "./transport.js";

/** Stream's `queryChannels` filter shapes, as far as this adapter can honour them. */
export interface StreamCompatChannelFilters {
  type?: string;
  id?: string | { $in: string[] };
  cid?: string | { $in: string[] };
  members?: { $in?: string[] };
  [filter: string]: unknown;
}

/** A user record for `connectUser` / `upsertUser`. Custom fields sit at the top level. */
export interface StreamCompatUser {
  id: string;
  name?: string;
  image?: string;
  role?: string;
  [custom: string]: unknown;
}

/** A token string or a provider resolved on every (re)connect, as Stream accepts. */
export type TokenOrProvider = string | (() => string | Promise<string>);

/**
 * Firemoot-specific options. These have no `stream-chat` equivalent; a migrating
 * app never needs them except `webhookSecret`, and the rest are test seams.
 */
export interface FiremootCompatOptions {
  /**
   * The secret a webhook **endpoint** signs its deliveries with. Firemoot signs
   * webhooks with the endpoint's own secret rather than the API secret (Stream
   * uses the API secret for both), so `verifyWebhook` needs it. Falls back to the
   * API secret, which is only correct if you configured the endpoint with it.
   */
  webhookSecret?: string;
  /** How long `createToken` tokens live when no `exp` is given. Default one hour. */
  tokenTtlSeconds?: number;
  /** Unix-millis clock; injectable for tests. */
  now?: () => number;
  rest?: RestApi;
  serverRest?: ServerRestApi;
  compatRest?: CompatRestApi;
  socketFactory?: FiremootClientConfig["socketFactory"];
}

/**
 * `stream-chat`'s constructor options. Only `baseURL` carries meaning here (it is
 * the Firemoot server URL); the rest of Stream's transport knobs are accepted and
 * ignored so a migrating app's existing option object still compiles.
 */
export interface StreamCompatOptions {
  baseURL?: string;
  firemoot?: FiremootCompatOptions;
  [option: string]: unknown;
}

/**
 * `stream-chat` surface with no Firemoot equivalent. Each throws a
 * `FiremootCompatError` naming the method - never a silent no-op.
 */
const UNSUPPORTED_CLIENT_METHODS = [
  "addDevice",
  "banUser",
  "blockUser",
  "createBlockList",
  "createChannelType",
  "createCommand",
  "createPermission",
  "createPoll",
  "createRole",
  "createSegment",
  "deactivateUser",
  "deleteBlockList",
  "deleteChannels",
  "deleteChannelType",
  "deleteCommand",
  "deleteFile",
  "deleteImage",
  "deletePermission",
  "deletePoll",
  "deleteRole",
  "deleteUsers",
  "devToken",
  "exportChannels",
  "exportUser",
  "exportUsers",
  "flagUser",
  "getAppSettings",
  "getBlockedUsers",
  "getBlockList",
  "getChannelType",
  "getCommand",
  "getDevices",
  "getMessage",
  "getPermission",
  "getPoll",
  "getRateLimits",
  "getThread",
  "getUnreadCount",
  "getUnreadCountBatch",
  "hydrateActiveChannels",
  "listBlockLists",
  "listChannelTypes",
  "listCommands",
  "listPermissions",
  "listRoles",
  "markChannelsRead",
  "muteUser",
  "partialUpdateMessage",
  "partialUpdateUser",
  "pinMessage",
  "queryBannedUsers",
  "queryMessageFlags",
  "queryReactions",
  "queryThreads",
  "queryUsers",
  "reactivateUser",
  "removeDevice",
  "removeShadowBan",
  "restoreUsers",
  "revokeTokens",
  "revokeUserToken",
  "revokeUsersToken",
  "search",
  "sendUserCustomEvent",
  "setGuestUser",
  "shadowBan",
  "sync",
  "testPushSettings",
  "translateMessage",
  "unbanUser",
  "unBlockUser",
  "undeleteMessage",
  "unflagMessage",
  "unflagUser",
  "unmuteUser",
  "unpinMessage",
  "updateAppSettings",
  "updateChannelType",
  "updateCommand",
  "updateMessage",
  "updatePermission",
  "upsertPushProvider",
  "uploadFile",
  "uploadImage",
] as const;

export interface StreamChat extends UnsupportedMethods<typeof UNSUPPORTED_CLIENT_METHODS> {}

/**
 * A drop-in `stream-chat` `StreamChat` over Firemoot.
 *
 * The two modes fuse exactly as `stream-chat`'s own single class does:
 *
 *  - `new StreamChat(key, { baseURL })` - **browser mode**. `connectUser()` opens
 *    the WebSocket, `channel()`/`queryChannels()` hand back live facades.
 *  - `new StreamChat(key, secret, { baseURL })` - **server-trusted mode**. Every
 *    call is HMAC-signed, `createToken()` mints end-user JWTs and channel
 *    operations may act as any user. Holds the API secret: server-side only.
 *
 * The only thing a migrating app changes is configuration: the API key/secret and
 * `baseURL` (or `setBaseURL()`), pointed at your Firemoot server.
 */
export class StreamChat {
  /** The singleton `getInstance()` hands out. Assign `undefined` to reset it. */
  static _instance: StreamChat | undefined;

  readonly key: string;
  private readonly secret: string | undefined;
  private readonly options: StreamCompatOptions;
  private readonly firemoot: FiremootCompatOptions;
  private readonly clock: () => number;

  private baseURL = "";
  private client: FiremootClient | null = null;
  private currentUser: StreamCompatUser | null = null;
  private token: TokenOrProvider | null = null;
  private readonly channels = new Map<string, StreamChatChannel>();
  private serverDeps: ServerChannelDeps | null = null;
  private compatRest: CompatRestApi | null = null;

  constructor(key: string, options?: StreamCompatOptions);
  constructor(key: string, secret?: string, options?: StreamCompatOptions);
  constructor(
    key: string,
    secretOrOptions?: string | StreamCompatOptions,
    maybeOptions?: StreamCompatOptions,
  ) {
    this.key = key;
    this.secret = typeof secretOrOptions === "string" ? secretOrOptions : undefined;
    this.options =
      maybeOptions ??
      (typeof secretOrOptions === "object" && secretOrOptions ? secretOrOptions : {});
    this.firemoot = this.options.firemoot ?? {};
    this.clock = this.firemoot.now ?? (() => Date.now());
    if (typeof this.options.baseURL === "string") this.setBaseURL(this.options.baseURL);
  }

  /**
   * The singleton form. Like Stream's, the first call wins: later calls return the
   * same instance and ignore their key and options.
   */
  static getInstance(key: string, options?: StreamCompatOptions): StreamChat;
  static getInstance(key: string, secret?: string, options?: StreamCompatOptions): StreamChat;
  static getInstance(
    key: string,
    secretOrOptions?: string | StreamCompatOptions,
    options?: StreamCompatOptions,
  ): StreamChat {
    if (!StreamChat._instance) {
      StreamChat._instance =
        typeof secretOrOptions === "string"
          ? new StreamChat(key, secretOrOptions, options)
          : new StreamChat(key, secretOrOptions);
    }
    return StreamChat._instance;
  }

  /**
   * Points the client at a Firemoot server. Stream apps call this (or pass
   * `options.baseURL`) to target a region or a local instance; here it is the one
   * setting that has to change to migrate.
   */
  setBaseURL(baseURL: string): void {
    this.baseURL = baseURL.replace(/\/+$/, "");
    // Rebuilding the transports keeps a post-construction setBaseURL() honest.
    this.serverDeps = null;
    this.compatRest = null;
  }

  getAuthType(): "anonymous" | "jwt" {
    return "jwt";
  }

  get userID(): string | undefined {
    return this.currentUser?.id;
  }

  /**
   * The connected user. Only what was passed to `connectUser` is populated -
   * Firemoot owns the authoritative user record server-side and this is not
   * refetched from it.
   */
  get user(): StreamCompatUser | null {
    return this.currentUser;
  }

  private requireBaseUrl(): string {
    if (!this.baseURL) {
      throw new FiremootCompatError(
        "@firemoot/stream-compat needs your Firemoot server URL: " +
          "new StreamChat(key, { baseURL: 'https://chat.example.com' }), or client.setBaseURL(url).",
      );
    }
    return this.baseURL;
  }

  private requireSecret(method: string): string {
    if (this.secret === undefined) {
      throw new FiremootCompatError(
        `client.${method}() is a server-side operation and needs your API secret: ` +
          `new StreamChat(key, secret, { baseURL }). Never do this in a browser.`,
      );
    }
    return this.secret;
  }

  private requireClient(method: string): FiremootClient {
    if (!this.client) {
      throw new FiremootCompatError(
        `client.${method}() needs a connected user - call connectUser() first.`,
      );
    }
    return this.client;
  }

  /** The HMAC-signed server transports, built lazily so `setBaseURL` can precede them. */
  private server(method: string): ServerChannelDeps {
    const secret = this.requireSecret(method);
    const baseUrl = this.requireBaseUrl();
    if (!this.serverDeps) {
      const authorize = createHmacAuthorizer({
        apiKey: this.key,
        apiSecret: secret,
        ...(this.firemoot.now ? { now: this.firemoot.now } : {}),
      });
      this.serverDeps = {
        server: new FiremootServer({
          baseUrl,
          apiKey: this.key,
          apiSecret: secret,
          ...(this.firemoot.now ? { now: this.firemoot.now } : {}),
          ...(this.firemoot.serverRest ? { rest: this.firemoot.serverRest } : {}),
        }),
        rest: this.firemoot.rest ?? coreRestApi({ baseUrl, authorize }),
        compat: this.firemoot.compatRest ?? coreCompatRestApi({ baseUrl, authorize }),
      };
    }
    return this.serverDeps;
  }

  /** The bearer-authenticated extra REST ops for the connected user (flagging). */
  private userCompatRest(): CompatRestApi {
    if (!this.compatRest) {
      const baseUrl = this.requireBaseUrl();
      this.compatRest =
        this.firemoot.compatRest ??
        coreCompatRestApi({
          baseUrl,
          authorize: createBearerAuthorizer(() => this.resolveToken()),
        });
    }
    return this.compatRest;
  }

  private async resolveToken(): Promise<string> {
    if (this.token === null) throw new FiremootCompatError("no user is connected");
    return typeof this.token === "function" ? this.token() : this.token;
  }

  /**
   * Stream's `connectUser({ id, name?, role? }, tokenOrProvider)`. Firemoot
   * provisions the user server-side (the token route upserts name and role), so
   * those fields are accepted for signature parity but not sent from here. A
   * repeat call for the same id is a no-op; reconnect after `disconnectUser()`.
   */
  async connectUser(user: StreamCompatUser, tokenOrProvider: TokenOrProvider): Promise<void> {
    if (this.client && this.currentUser?.id === user.id) return;
    if (this.client) await this.disconnectUser();
    this.token = tokenOrProvider;
    this.client = new FiremootClient({
      baseUrl: this.requireBaseUrl(),
      userId: user.id,
      tokenProvider: () => this.resolveToken(),
      ...(this.firemoot.socketFactory ? { socketFactory: this.firemoot.socketFactory } : {}),
      ...(this.firemoot.rest ? { rest: this.firemoot.rest } : {}),
    });
    await this.client.connect();
    this.currentUser = user;
  }

  /** @deprecated Stream's older name for `connectUser`. */
  setUser(user: StreamCompatUser, tokenOrProvider: TokenOrProvider): Promise<void> {
    return this.connectUser(user, tokenOrProvider);
  }

  async disconnectUser(): Promise<void> {
    this.client?.disconnect();
    this.client = null;
    this.currentUser = null;
    this.token = null;
    this.compatRest = null;
    this.channels.clear();
  }

  /** Closes the socket without forgetting the user (Stream's `closeConnection`). */
  closeConnection(): Promise<void> {
    this.client?.disconnect();
    return Promise.resolve();
  }

  /** Reopens the socket for the already-set user (Stream's `openConnection`). */
  openConnection(): Promise<void> {
    return this.requireClient("openConnection").connect();
  }

  /** A memoised channel facade (Stream's `client.channel(type, id, data)`). */
  channel(type: string, id?: string | null, data?: StreamCompatChannelData): StreamChatChannel {
    if (!id) {
      throw new FiremootCompatError(
        "client.channel() requires a channel id - Firemoot does not support " +
          "distinct-channel creation from a member list.",
      );
    }
    const cid = `${type}:${id}`;
    const existing = this.channels.get(cid);
    // A later call carrying `data` (the create path) must not reuse a facade
    // memoised without it, or create() would provision an empty channel.
    if (existing && data === undefined) return existing;
    const facade = this.secret
      ? new StreamChatChannel(type, id, null, this.server("channel"), data ?? {})
      : new StreamChatChannel(
          type,
          id,
          this.requireClient("channel").channel(type, id),
          null,
          data ?? {},
        );
    this.channels.set(cid, facade);
    return facade;
  }

  /**
   * Stream's `queryChannels(filter, sort, options)`. Translates the `{ type, id,
   * cid, members: { $in } }` filter shapes into a Firemoot `ChannelQuery`. In
   * browser mode `watch: true` subscribes each result over the WebSocket, exactly
   * as Stream does; all matching channels are returned, including ones with no
   * messages yet (Stream returns those by default too).
   */
  async queryChannels(
    filter: StreamCompatChannelFilters = {},
    sort?: ChannelSort,
    options: { watch?: boolean; limit?: number } = {},
  ): Promise<StreamChatChannel[]> {
    const direction = lastMessageSortDirection(sort);
    const query = toChannelQuery(filter, options.limit);

    if (this.secret) {
      const deps = this.server("queryChannels");
      const page = await deps.rest.queryChannels(query);
      return (page.channels ?? []).map((state) => {
        const facade = new StreamChatChannel(state.channel.type, state.channel.id, null, deps, {});
        facade.hydrateFromState(state.channel.custom, state.latestMessage as Message | undefined);
        this.channels.set(state.channel.cid, facade);
        return facade;
      });
    }

    const client = this.requireClient("queryChannels");
    const channels = await client.queryChannels(query, { watch: options.watch ?? false });
    // Sort by coalesce(last_message_at, created_at), mirroring the server's own
    // ordering and Stream's `last_message_at` default. ISO-8601 UTC timestamps
    // compare correctly as strings; a channel with neither sorts last.
    return channels
      .map((channel) => {
        const facade = new StreamChatChannel(channel.type, channel.id, channel, null, {});
        this.channels.set(channel.cid, facade);
        return {
          facade,
          key: facade.state.last_message_at ?? channel.meta?.createdAt ?? "",
        };
      })
      .sort((a, b) => direction * a.key.localeCompare(b.key))
      .map(({ facade }) => facade);
  }

  /**
   * Subscribes to a client-level event. Both of Stream's forms work:
   * `on(type, handler)` for one event and `on(handler)` for all of them.
   *
   * Two Stream event types are accepted but never fire - see
   * `FIREMOOT_CLIENT_EVENT` for why they are deliberate no-ops rather than errors.
   */
  on(handler: StreamEventHandler): { unsubscribe: () => void };
  on(type: StreamClientEventType, handler: StreamEventHandler): { unsubscribe: () => void };
  on(
    typeOrHandler: StreamClientEventType | StreamEventHandler,
    maybeHandler?: StreamEventHandler,
  ): { unsubscribe: () => void } {
    const client = this.requireClient("on");

    if (typeof typeOrHandler === "function") {
      const off = client.on("event", (event) => {
        const streamType = STREAM_EVENT_NAME[event.type];
        if (streamType === undefined) return;
        typeOrHandler(toStreamClientEvent(streamType as StreamClientEventType, event as unknown));
      });
      return { unsubscribe: off };
    }

    const handler = maybeHandler;
    if (!handler) throw new FiremootCompatError("client.on(type, handler) needs a handler");
    const firemootType = FIREMOOT_CLIENT_EVENT[typeOrHandler];
    if (firemootType === undefined) {
      throw new FiremootCompatError(
        `client.on("${typeOrHandler}") is not supported by @firemoot/stream-compat`,
      );
    }
    if (firemootType === null) return { unsubscribe: () => {} };
    const off = client.on(firemootType as never, (event) => {
      handler(toStreamClientEvent(typeOrHandler, event));
    });
    return { unsubscribe: off };
  }

  /** Detaches an `on(type, handler)` subscription (Stream's `off`). */
  off(type: StreamClientEventType, handler: StreamEventHandler): void {
    const firemootType = FIREMOOT_CLIENT_EVENT[type];
    if (!firemootType) return;
    this.requireClient("off").off(firemootType as never, handler as never);
  }

  /**
   * Stream's `flagMessage(messageId, { reason })`. Firemoot's flag endpoint is
   * channel-scoped, so the owning channel is resolved from the watched facades'
   * message state - flagging is always invoked from a rendered message, so it sits
   * in exactly one. The call is authenticated as the connected (reporting) user,
   * which is precisely whom the flag is attributed to.
   */
  async flagMessage(messageId: string, opts: { reason?: string } = {}): Promise<void> {
    if (this.secret) {
      throw new FiremootCompatError(
        "client.flagMessage() is only available on a connected browser client: Firemoot's flag " +
          "endpoint is channel-scoped and a server client cannot resolve the message's channel.",
      );
    }
    const userId = this.currentUser?.id;
    if (!userId) throw new FiremootCompatError("connect a user before flagging a message");
    const owner = [...this.channels.values()].find((facade) =>
      facade.state.messages.some((m) => m.id === messageId),
    );
    if (!owner) {
      throw new FiremootCompatError(
        `message ${messageId} is not in any watched channel, so its channel cannot be resolved`,
      );
    }
    await this.userCompatRest().flagMessage(owner.type, owner.id, messageId, {
      userId,
      ...(opts.reason !== undefined ? { reason: opts.reason } : {}),
    });
  }

  /**
   * Mints an end-user JWT, **synchronously**, matching `stream-chat`'s signature.
   *
   * Two differences worth knowing. The claims are Firemoot's (`sub`, plus a
   * required `exp`) rather than Stream's (`user_id`, no expiry) because the
   * Firemoot gateway is what verifies them. And where Stream mints a
   * never-expiring token when `exp` is omitted, Firemoot requires an expiry, so
   * this defaults to one hour (`firemoot.tokenTtlSeconds` to change it).
   *
   * `exp` and `iat` are seconds since the epoch, as in Stream.
   */
  createToken(userID: string, exp?: number, iat?: number): string {
    const secret = this.requireSecret("createToken");
    const ttl = this.firemoot.tokenTtlSeconds ?? 3600;
    return signUserToken(secret, userID, {
      exp: exp ?? Math.floor(this.clock() / 1000) + ttl,
      ...(iat !== undefined ? { iat } : {}),
    });
  }

  /**
   * Creates or updates a user. Stream carries custom fields at the top level of
   * the user object while Firemoot nests them under `custom`, so any key beyond
   * `id`/`name`/`image`/`role` is folded in.
   */
  async upsertUser(user: StreamCompatUser): Promise<void> {
    const deps = this.server("upsertUser");
    const { id, name, image, role, ...custom } = user;
    await deps.server.upsertUser({
      id,
      ...(name !== undefined ? { name } : {}),
      ...(image !== undefined ? { image } : {}),
      ...(role !== undefined ? { role } : {}),
      ...(Object.keys(custom).length > 0 ? { custom } : {}),
    });
  }

  /** Creates or updates several users (Stream's `upsertUsers`). */
  async upsertUsers(users: StreamCompatUser[]): Promise<void> {
    for (const user of users) await this.upsertUser(user);
  }

  /**
   * Deletes a user. Firemoot's delete is always a GDPR hard-delete - it scrubs
   * authored content and removes memberships and reactions - so Stream's
   * `deleteConversations`/`hardDelete` options have no distinct meaning.
   */
  async deleteUser(userId: string, _options?: Record<string, unknown>): Promise<void> {
    await this.server("deleteUser").compat.deleteUser(userId);
  }

  /**
   * Deletes a message by its id alone. Firemoot's delete is always the soft-delete
   * tombstone, so Stream's `hard` flag has no distinct meaning.
   */
  async deleteMessage(messageId: string, _hard?: boolean): Promise<void> {
    await this.server("deleteMessage").server.deleteMessage(messageId);
  }

  /**
   * Verifies a webhook delivery's signature, matching Stream's
   * `verifyWebhook(rawBody, xSignature)`. Firemoot sends the digest twice: as
   * `X-Signature` (bare lowercase hex, the shape Stream sends) and as
   * `X-Firemoot-Signature` (`sha256=`-prefixed). Either is accepted.
   *
   * The key is `firemoot.webhookSecret` if set, else the API secret. Firemoot
   * signs each delivery with the **endpoint's** secret, not the API secret, so set
   * `webhookSecret` unless you configured them to be the same value.
   */
  verifyWebhook(requestBody: string | Uint8Array, xSignature: string | undefined | null): boolean {
    const secret = this.firemoot.webhookSecret ?? this.secret;
    if (!secret || !xSignature) return false;
    const body =
      typeof requestBody === "string" ? requestBody : new TextDecoder().decode(requestBody);
    const expected = hmacHex(secret, body);
    const offered = xSignature.startsWith("sha256=") ? xSignature.slice(7) : xSignature;
    return timingSafeEqual(offered.toLowerCase(), expected);
  }
}

installUnsupported(StreamChat.prototype, "client", UNSUPPORTED_CLIENT_METHODS);

/** Translates the Stream filter shapes onto a Firemoot `ChannelQuery`. */
export function toChannelQuery(filter: StreamCompatChannelFilters, limit?: number): ChannelQuery {
  const query: ChannelQuery = {};
  if (filter.type !== undefined) query.type = filter.type;

  const cids: string[] = [];
  const ids = idList(filter.id);
  if (ids.length > 0) {
    if (filter.type === undefined) {
      throw new FiremootCompatError(
        "queryChannels({ id }) also needs a `type` - Firemoot addresses channels by `type:id`.",
      );
    }
    cids.push(...ids.map((id) => `${filter.type}:${id}`));
  }
  cids.push(...idList(filter.cid));
  if (cids.length > 0) query.cids = cids;

  const members = filter.members?.$in;
  if (members !== undefined) query.members = members;
  // The server clamps a page to 100 regardless; clamping here keeps the request honest.
  if (typeof limit === "number") query.limit = Math.min(limit, 100);
  return query;
}

function idList(value: unknown): string[] {
  if (typeof value === "string") return [value];
  const inClause = asRecord(value)["$in"];
  return Array.isArray(inClause) ? inClause.filter((v): v is string => typeof v === "string") : [];
}

export { UNSUPPORTED_CLIENT_METHODS };
export type { StreamCompatEvent };
