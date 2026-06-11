import type { Message } from "@firemoot/core";

/** The custom-field key carrying the client nonce that reconciles an optimistic send. */
export const NONCE_KEY = "_firemootNonce";

export type OptimisticStatus = "sending" | "failed";

export interface OptimisticMessage {
  nonce: string;
  status: OptimisticStatus;
  message: Message;
  error?: unknown;
}

/** Reads the reconciliation nonce a message was sent with, if present. */
export function nonceOf(message: Message): string | null {
  const custom = message.custom;
  if (typeof custom !== "object" || custom === null) return null;
  const value = (custom as Record<string, unknown>)[NONCE_KEY];
  return typeof value === "string" ? value : null;
}

/**
 * Tracks optimistic (not-yet-confirmed) sends. An optimistic message is dropped
 * the moment a confirmed server message carrying the same nonce appears - whether
 * that arrives first as the REST response or as the `message.new` WS event, so the
 * handoff is order-independent and never duplicates. A failed send is retained
 * (status `failed`) so the UI can offer a retry or discard.
 */
export class Outbox {
  private readonly pending = new Map<string, OptimisticMessage>();

  add(optimistic: OptimisticMessage): void {
    this.pending.set(optimistic.nonce, optimistic);
  }

  /** Confirms (and drops) any pending send matching this server message's nonce. */
  confirm(message: Message): boolean {
    const nonce = nonceOf(message);
    if (nonce !== null && this.pending.delete(nonce)) return true;
    return false;
  }

  markFailed(nonce: string, error: unknown): void {
    const optimistic = this.pending.get(nonce);
    if (optimistic) {
      optimistic.status = "failed";
      optimistic.error = error;
    }
  }

  remove(nonce: string): void {
    this.pending.delete(nonce);
  }

  has(nonce: string): boolean {
    return this.pending.has(nonce);
  }

  /** Optimistic messages still in flight or failed, in insertion order. */
  list(): OptimisticMessage[] {
    return [...this.pending.values()];
  }

  clear(): void {
    this.pending.clear();
  }
}
