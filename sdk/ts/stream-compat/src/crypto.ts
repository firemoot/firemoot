/**
 * A synchronous, dependency-free HMAC-SHA256 (FIPS 180-4 / RFC 2104).
 *
 * Both `createToken` and `verifyWebhook` are **synchronous** in `stream-chat`, so
 * matching its signatures rules out WebCrypto (`crypto.subtle` is promise-only).
 * The alternative - `node:crypto` - would make this package unbundleable for the
 * browser, and a drop-in `stream-chat` alias has to survive being pulled into a
 * client bundle. So the primitive is implemented here: ~100 lines, isomorphic,
 * no conditional exports, no bundler configuration for the adopting app.
 *
 * `crypto.test.ts` differential-tests this against `node:crypto` over randomised
 * inputs (spanning the block-boundary and long-key cases) plus the RFC 4231
 * vectors, so a regression here fails loudly rather than silently mis-signing.
 */

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const BLOCK_BYTES = 64;

function rotr(value: number, bits: number): number {
  return (value >>> bits) | (value << (32 - bits));
}

/** SHA-256 of `bytes`, as 32 raw bytes. */
export function sha256(bytes: Uint8Array): Uint8Array {
  const h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);

  // Pad to a whole number of blocks: 0x80, zeroes, then the bit length as a big-
  // endian u64. The length always needs 9 spare bytes, hence the +9 before rounding.
  const blocks = Math.ceil((bytes.length + 9) / BLOCK_BYTES);
  const padded = new Uint8Array(blocks * BLOCK_BYTES);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  const bitLength = BigInt(bytes.length) * 8n;
  const view = new DataView(padded.buffer);
  view.setBigUint64(padded.length - 8, bitLength, false);

  const w = new Uint32Array(64);
  for (let block = 0; block < blocks; block++) {
    const offset = block * BLOCK_BYTES;
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4, false);
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15] as number;
      const b = w[i - 2] as number;
      const s0 = rotr(a, 7) ^ rotr(a, 18) ^ (a >>> 3);
      const s1 = rotr(b, 17) ^ rotr(b, 19) ^ (b >>> 10);
      w[i] = ((w[i - 16] as number) + s0 + (w[i - 7] as number) + s1) >>> 0;
    }

    let a = h[0] as number;
    let b = h[1] as number;
    let c = h[2] as number;
    let d = h[3] as number;
    let e = h[4] as number;
    let f = h[5] as number;
    let g = h[6] as number;
    let hh = h[7] as number;

    for (let i = 0; i < 64; i++) {
      const s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = (hh + s1 + ch + (K[i] as number) + (w[i] as number)) >>> 0;
      const s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (s0 + maj) >>> 0;
      hh = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }

    h[0] = ((h[0] as number) + a) >>> 0;
    h[1] = ((h[1] as number) + b) >>> 0;
    h[2] = ((h[2] as number) + c) >>> 0;
    h[3] = ((h[3] as number) + d) >>> 0;
    h[4] = ((h[4] as number) + e) >>> 0;
    h[5] = ((h[5] as number) + f) >>> 0;
    h[6] = ((h[6] as number) + g) >>> 0;
    h[7] = ((h[7] as number) + hh) >>> 0;
  }

  const digest = new Uint8Array(32);
  const out = new DataView(digest.buffer);
  for (let i = 0; i < 8; i++) out.setUint32(i * 4, h[i] as number, false);
  return digest;
}

/** HMAC-SHA256 (RFC 2104) of `message` under `key`, as 32 raw bytes. */
export function hmacSha256(key: Uint8Array, message: Uint8Array): Uint8Array {
  const block = new Uint8Array(BLOCK_BYTES);
  block.set(key.length > BLOCK_BYTES ? sha256(key) : key);

  const inner = new Uint8Array(BLOCK_BYTES + message.length);
  const outer = new Uint8Array(BLOCK_BYTES + 32);
  for (let i = 0; i < BLOCK_BYTES; i++) {
    inner[i] = (block[i] as number) ^ 0x36;
    outer[i] = (block[i] as number) ^ 0x5c;
  }
  inner.set(message, BLOCK_BYTES);
  outer.set(sha256(inner), BLOCK_BYTES);
  return sha256(outer);
}

export function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

export function toHex(bytes: Uint8Array): string {
  let hex = "";
  for (const byte of bytes) hex += byte.toString(16).padStart(2, "0");
  return hex;
}

/** HMAC-SHA256 as lowercase hex - the shape both Firemoot and Stream sign webhooks with. */
export function hmacHex(secret: string, message: string): string {
  return toHex(hmacSha256(utf8(secret), utf8(message)));
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export function base64Url(bytes: Uint8Array): string {
  return toBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Compares two strings in time independent of where they first differ, so a
 * caller cannot probe a signature byte by byte. Length is not secret (both sides
 * are fixed-width hex digests), so an early length exit is fine.
 */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * Mints an HS256 JWT with Firemoot's end-user claims (`sub` + a required `exp`;
 * see `JwtAuth` on the server), synchronously. `FiremootServer.createToken` mints
 * the same token asynchronously via WebCrypto - this exists so the compat layer
 * can keep `stream-chat`'s synchronous `createToken` signature.
 */
export function signUserToken(
  secret: string,
  userId: string,
  claims: { exp: number; iat?: number; role?: string },
): string {
  const header = base64Url(utf8(JSON.stringify({ alg: "HS256", typ: "JWT" })));
  const payload = base64Url(
    utf8(
      JSON.stringify({
        sub: userId,
        exp: claims.exp,
        ...(claims.iat !== undefined ? { iat: claims.iat } : {}),
        ...(claims.role !== undefined ? { role: claims.role } : {}),
      }),
    ),
  );
  const signingInput = `${header}.${payload}`;
  return `${signingInput}.${base64Url(hmacSha256(utf8(secret), utf8(signingInput)))}`;
}
