import { createHmac, createHash } from "node:crypto";

import { describe, expect, test } from "vitest";

import {
  hmacHex,
  hmacSha256,
  sha256,
  signUserToken,
  timingSafeEqual,
  toHex,
  utf8,
} from "./crypto.js";

/**
 * This module hand-rolls SHA-256/HMAC because `stream-chat`'s `createToken` and
 * `verifyWebhook` are synchronous (see crypto.ts). These tests are what make that
 * defensible: every digest is compared against `node:crypto`, which is the same
 * implementation the Firemoot server verifies against.
 */

const LENGTHS = [0, 1, 2, 3, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 129, 191, 256, 1000, 4096];

function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  for (let i = 0; i < length; i++) bytes[i] = Math.floor(Math.random() * 256);
  return bytes;
}

describe("sha256", () => {
  test("matches node:crypto across the padding block boundaries", () => {
    for (const length of LENGTHS) {
      const input = randomBytes(length);
      const expected = createHash("sha256").update(input).digest("hex");
      expect(toHex(sha256(input)), `length ${length}`).toBe(expected);
    }
  });

  test("known vectors", () => {
    expect(toHex(sha256(utf8("")))).toBe(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    );
    expect(toHex(sha256(utf8("abc")))).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
  });
});

describe("hmacSha256", () => {
  test("matches node:crypto across key and message lengths", () => {
    // Keys shorter than, equal to and longer than the 64-byte block exercise the
    // zero-pad path and the hash-the-key path respectively.
    for (const keyLength of [1, 20, 63, 64, 65, 128, 200]) {
      for (const messageLength of LENGTHS) {
        const key = randomBytes(keyLength);
        const message = randomBytes(messageLength);
        const expected = createHmac("sha256", key).update(message).digest("hex");
        expect(toHex(hmacSha256(key, message)), `key ${keyLength}, msg ${messageLength}`).toBe(
          expected,
        );
      }
    }
  });

  test("RFC 4231 test case 2", () => {
    const mac = hmacSha256(utf8("Jefe"), utf8("what do ya want for nothing?"));
    expect(toHex(mac)).toBe("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
  });

  test("hmacHex matches the webhook digest the server sends as X-Signature", () => {
    const body = JSON.stringify({ type: "message.new", cid: "messaging:general" });
    expect(hmacHex("endpoint-secret", body)).toBe(
      createHmac("sha256", "endpoint-secret").update(body).digest("hex"),
    );
  });
});

describe("timingSafeEqual", () => {
  test("compares equal and unequal values", () => {
    expect(timingSafeEqual("abc123", "abc123")).toBe(true);
    expect(timingSafeEqual("abc123", "abc124")).toBe(false);
    expect(timingSafeEqual("abc", "abcd")).toBe(false);
    expect(timingSafeEqual("", "")).toBe(true);
  });
});

describe("signUserToken", () => {
  test("mints an HS256 JWT the server's JwtAuth can verify", () => {
    const token = signUserToken("api-secret", "alice", { exp: 1_800_000_000 });
    const [header, payload, signature] = token.split(".");

    expect(JSON.parse(Buffer.from(header as string, "base64url").toString())).toEqual({
      alg: "HS256",
      typ: "JWT",
    });
    // Firemoot's JwtAuth requires `sub` and `exp` (Stream's own tokens use
    // `user_id` and no exp - the claims are Firemoot's, the signature is Stream's).
    expect(JSON.parse(Buffer.from(payload as string, "base64url").toString())).toEqual({
      sub: "alice",
      exp: 1_800_000_000,
    });
    expect(signature).toBe(
      createHmac("sha256", "api-secret").update(`${header}.${payload}`).digest("base64url"),
    );
  });

  test("carries optional iat and role claims", () => {
    const token = signUserToken("api-secret", "bob", {
      exp: 1_800_000_000,
      iat: 1_700_000_000,
      role: "admin",
    });
    const payload = token.split(".")[1] as string;
    expect(JSON.parse(Buffer.from(payload, "base64url").toString())).toEqual({
      sub: "bob",
      exp: 1_800_000_000,
      iat: 1_700_000_000,
      role: "admin",
    });
  });
});
