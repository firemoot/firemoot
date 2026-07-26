import type { Message } from "@firemoot/core";

/** A Stream-`MessageResponse`-shaped message. */
export interface StreamCompatMessage {
  id: string;
  text?: string;
  type: string;
  created_at: string;
  updated_at: string;
  user?: { id: string };
  attachments: unknown;
  [key: string]: unknown;
}

/**
 * Maps a Firemoot message onto the Stream message shape (`id`, `text`, `type`,
 * `created_at`, `user.id`, `attachments`). Custom fields are spread to the top
 * level, matching how Stream surfaces message custom data.
 */
export function toStreamMessage(m: Message): StreamCompatMessage {
  const custom =
    m.custom && typeof m.custom === "object" ? (m.custom as Record<string, unknown>) : {};
  return {
    ...custom,
    id: m.id,
    type: m.type,
    created_at: m.createdAt,
    updated_at: m.updatedAt,
    attachments: m.attachments ?? [],
    ...(m.text !== undefined ? { text: m.text } : {}),
    ...(m.userId !== undefined ? { user: { id: m.userId } } : {}),
  };
}

export function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : {};
}

/**
 * The generated core client's `throwOnError` throws the parsed Problem body (a
 * plain object), which stringifies uselessly and defeats callers matching on it.
 * Rethrow it as a real `Error` carrying the Problem detail *and* the numeric
 * status, so duplicate detection keys off the 409 structurally rather than off
 * the server's error copy.
 */
export function rethrowAsError(err: unknown): never {
  if (err && typeof err === "object" && !(err instanceof Error)) {
    const problem = err as { detail?: unknown; title?: unknown; status?: unknown };
    const detail = typeof problem.detail === "string" ? problem.detail : "";
    const title = typeof problem.title === "string" ? problem.title : "";
    const error = new Error(detail || title || JSON.stringify(err));
    if (typeof problem.status === "number") throw Object.assign(error, { status: problem.status });
    throw error;
  }
  throw err;
}

function errorCode(err: unknown): number | undefined {
  return err && typeof err === "object" && "code" in err && typeof err.code === "number"
    ? err.code
    : undefined;
}

function errorStatus(err: unknown): number | undefined {
  return err && typeof err === "object" && "status" in err && typeof err.status === "number"
    ? err.status
    : undefined;
}

/**
 * "Channel already exists", as thrown by `channel.create()`. Stream throws code
 * 4/9 (or a matching message); this adapter throws the same shape, so a migrating
 * app's existing classifier keeps working.
 */
export function isDuplicateChannelError(err: unknown): boolean {
  const code = errorCode(err);
  const message = err instanceof Error ? err.message : String(err);
  return code === 4 || code === 9 || /already exists|duplicate|same id/i.test(message);
}

/**
 * "Message id already used", as thrown by `sendMessage()` when re-seeding with a
 * caller-supplied id. Stream throws code 4/9 with an "already exists" message;
 * Firemoot's 409 arrives as a normalised Error carrying `status: 409` - a
 * structural match, so classification does not depend on either server's copy.
 */
export function isDuplicateMessageError(err: unknown): boolean {
  const code = errorCode(err);
  if (code === 4 || code === 9 || errorStatus(err) === 409) return true;
  const message = err instanceof Error ? err.message : String(err);
  return /already exists|duplicate/i.test(message);
}

/** A duplicate-channel error shaped like Stream's, so the classifiers above match. */
export function duplicateChannelError(channelId: string): Error & { code: number } {
  return Object.assign(new Error(`Channel ${channelId} already exists`), { code: 4 });
}
