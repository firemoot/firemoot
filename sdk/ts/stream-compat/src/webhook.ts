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
 * Reshapes a Firemoot wire event (`{ type, cid, seq, data }`, camelCase) into the
 * Stream-shaped event object a webhook handler written against Stream consumes
 * (`type`, `cid`, `message.text`, `message.user.id`, `user.id`).
 *
 * `message.new` stays `message.new` and `read.updated` becomes Stream's
 * `message.read`; anything else passes through under its own type, which a
 * Stream-shaped handler will simply not match. The cid format (`type:id`) already
 * matches Stream's.
 */
export function normalizeWebhookEvent(parsed: unknown): Record<string, unknown> {
  const event = asRecord(parsed);
  if (Object.keys(event).length === 0) return {};
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
  return { type, ...(cid ? { cid } : {}) };
}
