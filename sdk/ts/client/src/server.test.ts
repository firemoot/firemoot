import type { Channel, User } from "@firemoot/core";
import { describe, expect, test } from "vitest";

import { FiremootServer, type ServerRestApi } from "./server.js";

function user(id: string): User {
  return { id, role: "user", custom: {}, createdAt: "t", updatedAt: "t" };
}

function channel(type: string, id: string): Channel {
  return {
    cid: `${type}:${id}`,
    type,
    id,
    custom: {},
    frozen: false,
    archived: false,
    currentSeq: 0,
    createdAt: "t",
    updatedAt: "t",
  };
}

function recordingRest(): { rest: ServerRestApi; calls: string[] } {
  const calls: string[] = [];
  const rest: ServerRestApi = {
    upsertUser: (body) => {
      calls.push(`upsertUser:${body.id}`);
      return Promise.resolve(user(body.id));
    },
    createChannel: (body) => {
      calls.push(`createChannel:${body.type}:${body.id}`);
      return Promise.resolve(channel(body.type, body.id));
    },
    addMember: (type, id, body) => {
      calls.push(`addMember:${type}:${id}:${body.userId}:${body.role ?? ""}`);
      return Promise.resolve();
    },
  };
  return { rest, calls };
}

function base64UrlDecode(segment: string): string {
  const b64 = segment.replace(/-/g, "+").replace(/_/g, "/");
  return atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
}

async function hmacBase64Url(secret: string, message: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(message));
  let binary = "";
  for (const b of new Uint8Array(sig)) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const baseConfig = { baseUrl: "http://localhost:6668", apiKey: "k", apiSecret: "topsecret" };

describe("FiremootServer.createToken", () => {
  test("mints an HS256 JWT a verifier accepts, with sub and a default expiry", async () => {
    const { rest } = recordingRest();
    const server = new FiremootServer({ ...baseConfig, now: () => 1_000_000, rest });
    const token = await server.createToken("alice");
    const [header, payload, signature] = token.split(".");

    expect(JSON.parse(base64UrlDecode(header!))).toEqual({ alg: "HS256", typ: "JWT" });
    const claims = JSON.parse(base64UrlDecode(payload!));
    expect(claims.sub).toBe("alice");
    expect(claims.exp).toBe(Math.floor((1_000_000 + 3_600_000) / 1000));

    // The signature is a real HMAC over the signing input with the API secret.
    expect(signature).toBe(await hmacBase64Url("topsecret", `${header}.${payload}`));
  });

  test("honours an explicit expiry", async () => {
    const { rest } = recordingRest();
    const server = new FiremootServer({ ...baseConfig, rest });
    const expiresAt = new Date(2_000_000_000_000);
    const token = await server.createToken("bob", expiresAt);
    const claims = JSON.parse(base64UrlDecode(token.split(".")[1]!));
    expect(claims).toEqual({ sub: "bob", exp: 2_000_000_000 });
  });
});

describe("FiremootServer provisioning", () => {
  test("createChannel creates then adds each member in order", async () => {
    const { rest, calls } = recordingRest();
    const server = new FiremootServer({ ...baseConfig, rest });
    const created = await server.createChannel({ type: "messaging", id: "general" }, [
      { userId: "alice", role: "owner" },
      { userId: "bob" },
    ]);
    expect(created.cid).toBe("messaging:general");
    expect(calls).toEqual([
      "createChannel:messaging:general",
      "addMember:messaging:general:alice:owner",
      "addMember:messaging:general:bob:",
    ]);
  });

  test("createChannel with no members skips addMember", async () => {
    const { rest, calls } = recordingRest();
    const server = new FiremootServer({ ...baseConfig, rest });
    await server.createChannel({ type: "messaging", id: "solo" });
    expect(calls).toEqual(["createChannel:messaging:solo"]);
  });

  test("upsertUser delegates to the REST layer", async () => {
    const { rest, calls } = recordingRest();
    const server = new FiremootServer({ ...baseConfig, rest });
    const result = await server.upsertUser({ id: "carol", name: "Carol" });
    expect(result.id).toBe("carol");
    expect(calls).toEqual(["upsertUser:carol"]);
  });
});
