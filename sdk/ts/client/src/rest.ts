import {
  type AddReactionRequest,
  type Channel,
  type ChannelQuery,
  type ChannelStatePage,
  type CreateUploadRequest,
  deleteV1ChannelsTypeIdMessagesMessageid,
  deleteV1ChannelsTypeIdMessagesMessageidReactionsReactiontypeUserid,
  type EditMessageRequest,
  getV1ChannelsTypeId,
  getV1ChannelsTypeIdMessages,
  type MarkReadRequest,
  type Message,
  type MessagePage,
  patchV1ChannelsTypeIdMessagesMessageid,
  postV1ChannelsQuery,
  postV1ChannelsTypeIdMessages,
  postV1ChannelsTypeIdMessagesMessageidReactions,
  postV1ChannelsTypeIdRead,
  postV1Uploads,
  type ReactionSummary,
  type ReadStateResponse,
  type SendMessageRequest,
  type UploadTicket,
} from "@firemoot/core";

/** The REST surface the value layer needs, abstracted so it can be faked in tests. */
export interface RestApi {
  getChannel(type: string, id: string): Promise<Channel>;
  getMessages(
    type: string,
    id: string,
    query?: { before_seq?: number; before_id?: string; limit?: number },
  ): Promise<MessagePage>;
  sendMessage(type: string, id: string, body: SendMessageRequest): Promise<Message>;
  editMessage(
    type: string,
    id: string,
    messageId: string,
    body: EditMessageRequest,
  ): Promise<Message>;
  deleteMessage(type: string, id: string, messageId: string): Promise<void>;
  addReaction(
    type: string,
    id: string,
    messageId: string,
    body: AddReactionRequest,
  ): Promise<ReactionSummary>;
  removeReaction(
    type: string,
    id: string,
    messageId: string,
    reactionType: string,
    userId: string,
  ): Promise<ReactionSummary>;
  markRead(type: string, id: string, body: MarkReadRequest): Promise<ReadStateResponse>;
  queryChannels(query: ChannelQuery): Promise<ChannelStatePage>;
  createUpload(body: CreateUploadRequest): Promise<UploadTicket>;
}

export interface CoreRestConfig {
  baseUrl: string;
  /** Per-request auth headers (e.g. HMAC signing). Called for every call. */
  authorize?: (request: {
    method: string;
    path: string;
    body?: unknown;
  }) => Record<string, string> | Promise<Record<string, string>>;
}

/** The default `RestApi`, backed by the generated `@firemoot/core` client. */
export function coreRestApi(config: CoreRestConfig): RestApi {
  const auth = async (
    method: string,
    path: string,
    body?: unknown,
  ): Promise<Record<string, string>> =>
    config.authorize ? config.authorize({ method, path, body }) : {};

  return {
    async getChannel(type, id) {
      const { data } = await getV1ChannelsTypeId({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id },
        headers: await auth("GET", `/v1/channels/${type}/${id}`),
      });
      return data.channel;
    },
    async getMessages(type, id, query) {
      const { data } = await getV1ChannelsTypeIdMessages({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id },
        ...(query ? { query } : {}),
        headers: await auth("GET", `/v1/channels/${type}/${id}/messages`),
      });
      return data;
    },
    async sendMessage(type, id, body) {
      const { data } = await postV1ChannelsTypeIdMessages({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id },
        body,
        headers: await auth("POST", `/v1/channels/${type}/${id}/messages`, body),
      });
      return data;
    },
    async editMessage(type, id, messageId, body) {
      const { data } = await patchV1ChannelsTypeIdMessagesMessageid({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id, messageId },
        body,
        headers: await auth("PATCH", `/v1/channels/${type}/${id}/messages/${messageId}`, body),
      });
      return data;
    },
    async deleteMessage(type, id, messageId) {
      await deleteV1ChannelsTypeIdMessagesMessageid({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id, messageId },
        headers: await auth("DELETE", `/v1/channels/${type}/${id}/messages/${messageId}`),
      });
    },
    async addReaction(type, id, messageId, body) {
      const { data } = await postV1ChannelsTypeIdMessagesMessageidReactions({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id, messageId },
        body,
        headers: await auth(
          "POST",
          `/v1/channels/${type}/${id}/messages/${messageId}/reactions`,
          body,
        ),
      });
      return data;
    },
    async removeReaction(type, id, messageId, reactionType, userId) {
      const { data } = await deleteV1ChannelsTypeIdMessagesMessageidReactionsReactiontypeUserid({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id, messageId, reactionType, userId },
        headers: await auth(
          "DELETE",
          `/v1/channels/${type}/${id}/messages/${messageId}/reactions/${reactionType}/${userId}`,
        ),
      });
      return data;
    },
    async markRead(type, id, body) {
      const { data } = await postV1ChannelsTypeIdRead({
        baseUrl: config.baseUrl,
        throwOnError: true,
        path: { type, id },
        body,
        headers: await auth("POST", `/v1/channels/${type}/${id}/read`, body),
      });
      return data;
    },
    async queryChannels(query) {
      const { data } = await postV1ChannelsQuery({
        baseUrl: config.baseUrl,
        throwOnError: true,
        body: query,
        headers: await auth("POST", `/v1/channels/query`, query),
      });
      return data;
    },
    async createUpload(body) {
      const { data } = await postV1Uploads({
        baseUrl: config.baseUrl,
        throwOnError: true,
        body,
        headers: await auth("POST", `/v1/uploads`, body),
      });
      return data;
    },
  };
}
