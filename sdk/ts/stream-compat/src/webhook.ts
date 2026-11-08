import { hmacHex, timingSafeEqual } from "./crypto.js";
import { asRecord } from "./message.js";

/**
 * Verifies a Firemoot webhook delivery without a `StreamChat` instance, for
 * handlers that only need the check. `StreamChat.verifyWebhook` is the
 * Stream-shaped equivalent.
 *
 * Firemoot sends the same digest twice - `X-Signature` (bare lowercase hex, the
 * shape Stream sends) and `X-Firemoot-Signature` (`sha256=`-prefixed) - and both
 * are accepted here. The key is the **endpoint's** secret, not the API secret.
 */
export function verifyWebhookSignature(
  secret: string,
  rawBody: string,
  signature: string | undefined | null,
): boolean {
  if (!signature) return false;
  const offered = signature.startsWith("sha256=") ? signature.slice(7) : signature;
  return timingSafeEqual(offered.toLowerCase(), hmacHex(secret, rawBody));
}

/**
 * Reshapes a body from a webhook endpoint registered with the deprecated
 * `firemoot` format (`{ type, cid, seq, data }`, camelCase) into the
 * Stream-shaped event a handler written against Stream consumes. A body from a
 * `stream`-format endpoint (the default) is already Stream's payload and is
 * returned unchanged, so this is safe to leave in place while endpoints migrate.
 *
 * For `firemoot` bodies: `message.new` stays `message.new`, `read.updated`
 * becomes Stream's `message.read`, and `user.unread_message_reminder` is rebuilt
 * in Stream's shape (`user`, plus `channels` keyed by cid); anything else passes
 * through under its own type, which a Stream-shaped handler will simply not
 * match.
 *
 * @deprecated Register endpoints with `format: "stream"` (the default) and read
 * the body directly; the `firemoot` format will be removed.
 */
export function normalizeWebhookEvent(parsed: unknown): Record<string, unknown> {
  const event = asRecord(parsed);
  if (Object.keys(event).length === 0) return {};
  if (!isFiremootEnvelope(event)) return event;
  const type = typeof event["type"] === "string" ? event["type"] : "";
  const cid = typeof event["cid"] === "string" ? event["cid"] : undefined;
  const data = asRecord(event["data"]);
  const userId = typeof data["userId"] === "string" ? data["userId"] : undefined;

  if (type === "message.new") {
    const message: Record<string, unknown> = {};
    if (typeof data["id"] === "string") message["id"] = data["id"];
    if (typeof data["text"] === "string") message["text"] = data["text"];
    if (userId) message["user"] = { id: userId };
    return { type: "message.new", ...(cid ? { cid } : {}), message };
  }
  if (type === "read.updated") {
    return {
      type: "message.read",
      ...(cid ? { cid } : {}),
      ...(userId ? { user: { id: userId } } : {}),
    };
  }
  if (type === "user.unread_message_reminder") {
    return { type, ...toStreamReminder(data) };
  }
  return { type, ...(cid ? { cid } : {}) };
}

/** Only the deprecated `firemoot` format wraps its payload in `data`; Stream's never does. */
function isFiremootEnvelope(event: Record<string, unknown>): boolean {
  const data = event["data"];
  return data !== null && typeof data === "object" && !Array.isArray(data);
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function stringField(
  source: Record<string, unknown>,
  from: string,
  to: string = from,
): Record<string, string> {
  const value = source[from];
  return typeof value === "string" ? { [to]: value } : {};
}

function toStreamUser(user: Record<string, unknown>): Record<string, unknown> {
  return {
    ...asRecord(user["custom"]),
    ...stringField(user, "id"),
    ...stringField(user, "name"),
    ...stringField(user, "image"),
    ...stringField(user, "role"),
  };
}

function toStreamReminderMessage(message: Record<string, unknown>): Record<string, unknown> {
  return {
    ...asRecord(message["custom"]),
    ...stringField(message, "id"),
    ...stringField(message, "type"),
    ...stringField(message, "text"),
    ...stringField(message, "createdAt", "created_at"),
    ...stringField(message, "parentMessageId", "parent_id"),
    ...(typeof message["userId"] === "string" ? { user: { id: message["userId"] } } : {}),
    attachments: asArray(message["attachments"]),
  };
}

/**
 * Firemoot sends the reminder's content in its own camelCase shapes, with
 * `channels` as a list of `{ channel, members, messages }`. Stream keys
 * `channels` by cid and nests the member list inside the channel, so a handler
 * reading `event.channels[cid].channel.members` or `messages[0].user.id` works
 * unchanged.
 */
function toStreamReminder(data: Record<string, unknown>): Record<string, unknown> {
  const channels: Record<string, unknown> = {};
  for (const entry of asArray(data["channels"])) {
    const wrapper = asRecord(entry);
    const channel = asRecord(wrapper["channel"]);
    const cid = channel["cid"];
    if (typeof cid !== "string") continue;
    const members = asArray(wrapper["members"])
      .map(asRecord)
      .filter((m) => typeof m["userId"] === "string")
      .map((m) => ({
        user_id: m["userId"],
        user: { id: m["userId"] },
        ...stringField(m, "role"),
      }));
    channels[cid] = {
      channel: {
        ...asRecord(channel["custom"]),
        cid,
        ...stringField(channel, "id"),
        ...stringField(channel, "type"),
        ...stringField(channel, "createdAt", "created_at"),
        ...stringField(channel, "updatedAt", "updated_at"),
        ...stringField(channel, "lastMessageAt", "last_message_at"),
        ...(typeof channel["createdBy"] === "string"
          ? { created_by: { id: channel["createdBy"] } }
          : {}),
        frozen: channel["frozen"] === true,
        member_count: members.length,
        members,
      },
      messages: asArray(wrapper["messages"]).map((m) => toStreamReminderMessage(asRecord(m))),
    };
  }
  const user = asRecord(data["user"]);
  return { ...(typeof user["id"] === "string" ? { user: toStreamUser(user) } : {}), channels };
}
