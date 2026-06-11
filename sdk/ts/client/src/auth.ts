import type { CoreRestConfig } from "./rest.js";

/**
 * Server-SDK request signing (SPEC.md §5), matching the server's `HmacSigner`:
 *
 *   canonical = FIREMOOT-HMAC-SHA256\n<METHOD>\n<path>\n<unixSeconds>\n<sha256Hex(body)>
 *
 * signed with HMAC-SHA256 over the API secret and sent as the
 * `X-Firemoot-Key` / `X-Firemoot-Timestamp` / `X-Firemoot-Signature` headers.
 * Uses WebCrypto so it runs in Node and the browser - but the API secret is a
 * server credential, so use this only in trusted (Node) contexts.
 */
export interface HmacAuthorizerConfig {
  apiKey: string;
  apiSecret: string;
  /** Unix-millis clock; injectable for tests. */
  now?: () => number;
}

const SCHEME = "FIREMOOT-HMAC-SHA256";

// TextEncoder output is always ArrayBuffer-backed; narrow it so WebCrypto's
// BufferSource (which excludes SharedArrayBuffer) accepts it under TS's generic
// Uint8Array.
function utf8(value: string): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(value) as Uint8Array<ArrayBuffer>;
}

function toHex(buffer: ArrayBuffer): string {
  return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function sha256Hex(value: string): Promise<string> {
  return toHex(await crypto.subtle.digest("SHA-256", utf8(value)));
}

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    utf8(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return toHex(await crypto.subtle.sign("HMAC", key, utf8(message)));
}

/**
 * Builds the `authorize` hook for `coreRestApi`. The body hash must cover the
 * exact bytes sent, so the body is serialised here the same way the transport
 * serialises it (`JSON.stringify`); pass-through string bodies are hashed as-is.
 */
export function createHmacAuthorizer(
  config: HmacAuthorizerConfig,
): NonNullable<CoreRestConfig["authorize"]> {
  const clock = config.now ?? (() => Date.now());
  return async ({ method, path, body }) => {
    const timestamp = Math.floor(clock() / 1000);
    const serialised =
      body === undefined ? "" : typeof body === "string" ? body : JSON.stringify(body);
    const bodyHash = await sha256Hex(serialised);
    const canonical = [SCHEME, method.toUpperCase(), path, timestamp, bodyHash].join("\n");
    const signature = await hmacSha256Hex(config.apiSecret, canonical);
    return {
      "X-Firemoot-Key": config.apiKey,
      "X-Firemoot-Timestamp": String(timestamp),
      "X-Firemoot-Signature": signature,
    };
  };
}
