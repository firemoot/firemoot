import type { FiremootServer, MemberInput, RestApi } from "@firemoot/client";
import type { UpsertUserRequest } from "@firemoot/core";

export interface SeedChannel {
  type: string;
  id: string;
  createdBy?: string;
  custom?: unknown;
  members?: MemberInput[];
}

export interface SeedMessage {
  type: string;
  id: string;
  userId: string;
  text?: string;
  custom?: unknown;
}

/** A fixture to provision: users, channels (with members) and messages, in that order. */
export interface SeedSpec {
  users?: UpsertUserRequest[];
  channels?: SeedChannel[];
  messages?: SeedMessage[];
}

/** The (server-trusted) operations seeding needs; the live instance supplies them. */
export interface SeedBackend {
  server: Pick<FiremootServer, "upsertUser" | "createChannel">;
  rest: Pick<RestApi, "sendMessage">;
}

/**
 * Applies a [[SeedSpec]] in dependency order (users, then channels + members,
 * then messages) so downstream suites start from a known fixture. Server-trusted
 * throughout - the seed runs with the API key, not an end-user token.
 */
export async function seed(backend: SeedBackend, spec: SeedSpec): Promise<void> {
  for (const user of spec.users ?? []) {
    await backend.server.upsertUser(user);
  }
  for (const channel of spec.channels ?? []) {
    await backend.server.createChannel(
      {
        type: channel.type,
        id: channel.id,
        ...(channel.createdBy !== undefined ? { createdBy: channel.createdBy } : {}),
        ...(channel.custom !== undefined ? { custom: channel.custom } : {}),
      },
      channel.members ?? [],
    );
  }
  for (const message of spec.messages ?? []) {
    await backend.rest.sendMessage(message.type, message.id, {
      userId: message.userId,
      ...(message.text !== undefined ? { text: message.text } : {}),
      ...(message.custom !== undefined ? { custom: message.custom } : {}),
    });
  }
}
