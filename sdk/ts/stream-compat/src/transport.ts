import type { CoreRestConfig } from "@firemoot/client";
import {
  deleteV1ChannelsTypeId,
  deleteV1UsersId,
  patchV1ChannelsTypeId,
  postV1ChannelsTypeIdMessagesMessageidFlag,
} from "@firemoot/core";

/**
 * The handful of REST operations this adapter needs that `@firemoot/client`'s
 * `RestApi`/`ServerRestApi` do not cover. Abstracted for the same reason those
 * are: so the facades can be unit-tested without HTTP.
 */
export interface CompatRestApi {
  patchChannelCustom(type: string, id: string, custom: Record<string, unknown>): Promise<void>;
  deleteChannel(type: string, id: string): Promise<void>;
  deleteUser(userId: string): Promise<void>;
  flagMessage(
    type: string,
    id: string,
    messageId: string,
    body: { userId: string; reason?: string },
  ): Promise<void>;
}

export interface CompatRestConfig {
  baseUrl: string;
  authorize: NonNullable<CoreRestConfig["authorize"]>;
}

/** The default [[CompatRestApi]], backed by the generated `@firemoot/core` client. */
export function coreCompatRestApi(config: CompatRestConfig): CompatRestApi {
  const auth = (method: string, path: string, body?: unknown): Promise<Record<string, string>> =>
    Promise.resolve(config.authorize({ method, path, ...(body !== undefined ? { body } : {}) }));

  return {
    async patchChannelCustom(type, id, custom) {
      const body = { custom };
      await patchV1ChannelsTypeId({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id },
        body,
        headers: await auth("PATCH", `/v1/channels/${type}/${id}`, body),
      });
    },
    async deleteChannel(type, id) {
      await deleteV1ChannelsTypeId({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id },
        headers: await auth("DELETE", `/v1/channels/${type}/${id}`),
      });
    },
    async deleteUser(userId) {
      await deleteV1UsersId({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { id: userId },
        headers: await auth("DELETE", `/v1/users/${userId}`),
      });
    },
    async flagMessage(type, id, messageId, body) {
      await postV1ChannelsTypeIdMessagesMessageidFlag({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id, messageId },
        body,
        headers: await auth("POST", `/v1/channels/${type}/${id}/messages/${messageId}/flag`, body),
      });
    },
  };
}
