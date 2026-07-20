import {
  type AddMemberRequest,
  type Channel,
  type CreateChannelRequest,
  deleteV1MessagesMessageid,
  postV1Channels,
  postV1ChannelsTypeIdMembers,
  postV1Users,
  type UpsertUserRequest,
  type User,
} from "@firemoot/core";

import { createHmacAuthorizer } from "./auth.js";
import type { CoreRestConfig } from "./rest.js";

/**
 * The server-trusted REST operations used by `FiremootServer`, abstracted so the
 * orchestration can be tested without HTTP. The default impl
 * ([[coreServerRestApi]]) wraps `@firemoot/core` with HMAC signing.
 */
export interface ServerRestApi {
  upsertUser(body: UpsertUserRequest): Promise<User>;
  createChannel(body: CreateChannelRequest): Promise<Channel>;
  addMember(type: string, id: string, body: AddMemberRequest): Promise<void>;
  deleteMessage(messageId: string): Promise<void>;
}

export function coreServerRestApi(config: {
  baseUrl: string;
  authorize: NonNullable<CoreRestConfig["authorize"]>;
}): ServerRestApi {
  const auth = (method: string, path: string, body?: unknown): Promise<Record<string, string>> =>
    Promise.resolve(config.authorize({ method, path, body }));
  return {
    async upsertUser(body) {
      const { data } = await postV1Users({
        baseUrl: config.baseUrl,
        throwOnError: true,
        body,
        headers: await auth("POST", "/v1/users", body),
      });
      return data;
    },
    async createChannel(body) {
      const { data } = await postV1Channels({
        baseUrl: config.baseUrl,
        throwOnError: true,
        body,
        headers: await auth("POST", "/v1/channels", body),
      });
      return data;
    },
    async addMember(type, id, body) {
      await postV1ChannelsTypeIdMembers({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id },
        body,
        headers: await auth("POST", `/v1/channels/${type}/${id}/members`, body),
      });
    },
    async deleteMessage(messageId) {
      await deleteV1MessagesMessageid({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { messageId },
        headers: await auth("DELETE", `/v1/messages/${messageId}`),
      });
    },
  };
}

export interface FiremootServerConfig {
  baseUrl: string;
  apiKey: string;
  /** The API secret: signs HMAC requests *and* mints end-user JWTs (HS256). */
  apiSecret: string;
  /** Unix-millis clock; injectable for tests. */
  now?: () => number;
  /** A custom server REST adapter (default: `@firemoot/core` + HMAC over `baseUrl`). */
  rest?: ServerRestApi;
}

/** A member to add at channel-create time. */
export interface MemberInput {
  userId: string;
  role?: string;
}

function utf8(value: string): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(value) as Uint8Array<ArrayBuffer>;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * The server SDK (SPEC.md §5): the trusted counterpart to `FiremootClient`. Mints
 * the end-user JWTs the client connects with (`createToken`, HS256 over the API
 * secret - the same key the gateway verifies) and runs the server-key admin
 * operations (`upsertUser`, channel + member provisioning) over HMAC-signed REST.
 * Use only in trusted contexts: it holds the API secret.
 */
export class FiremootServer {
  private readonly secret: string;
  private readonly clock: () => number;
  private readonly rest: ServerRestApi;

  constructor(config: FiremootServerConfig) {
    this.secret = config.apiSecret;
    this.clock = config.now ?? (() => Date.now());
    this.rest =
      config.rest ??
      coreServerRestApi({
        baseUrl: config.baseUrl,
        authorize: createHmacAuthorizer({
          apiKey: config.apiKey,
          apiSecret: config.apiSecret,
          ...(config.now ? { now: config.now } : {}),
        }),
      });
  }

  /**
   * Mints an HS256 JWT (`sub` = userId, required `exp`) for an end user to
   * connect the client with. Defaults to a one-hour expiry.
   */
  async createToken(userId: string, expiresAt?: Date): Promise<string> {
    const expMs = expiresAt?.getTime() ?? this.clock() + 3_600_000;
    const header = base64Url(utf8(JSON.stringify({ alg: "HS256", typ: "JWT" })));
    const payload = base64Url(utf8(JSON.stringify({ sub: userId, exp: Math.floor(expMs / 1000) })));
    const signingInput = `${header}.${payload}`;
    const key = await crypto.subtle.importKey(
      "raw",
      utf8(this.secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const signature = await crypto.subtle.sign("HMAC", key, utf8(signingInput));
    return `${signingInput}.${base64Url(new Uint8Array(signature))}`;
  }

  upsertUser(body: UpsertUserRequest): Promise<User> {
    return this.rest.upsertUser(body);
  }

  /**
   * Deletes a message by its id alone, resolving its channel server-side (Stream
   * parity). Soft-delete; a 404 surfaces as a rejected promise.
   */
  deleteMessage(messageId: string): Promise<void> {
    return this.rest.deleteMessage(messageId);
  }

  /** Adds members one by one (the REST endpoint is single-member, idempotent). */
  async addMembers(type: string, id: string, members: MemberInput[]): Promise<void> {
    for (const member of members) {
      await this.rest.addMember(type, id, {
        userId: member.userId,
        ...(member.role !== undefined ? { role: member.role } : {}),
      });
    }
  }

  /** Creates a channel and (optionally) adds its initial members in one call. */
  async createChannel(body: CreateChannelRequest, members: MemberInput[] = []): Promise<Channel> {
    const channel = await this.rest.createChannel(body);
    if (members.length > 0) await this.addMembers(body.type, body.id, members);
    return channel;
  }
}
