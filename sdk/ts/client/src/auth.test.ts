import { createHash, createHmac } from "node:crypto";
import { describe, expect, test } from "vitest";

import { createBearerAuthorizer, createHmacAuthorizer } from "./auth.js";

// Independent reference using Node's crypto, which produces the same HMAC-SHA256
// the server's JVM HmacSigner does - so this pins the WebCrypto implementation to
// the exact wire scheme.
function referenceSignature(
  secret: string,
  method: string,
  path: string,
  ts: number,
  body: string,
): string {
  const bodyHash = createHash("sha256").update(body).digest("hex");
  const canonical = ["FIREMOOT-HMAC-SHA256", method.toUpperCase(), path, ts, bodyHash].join("\n");
  return createHmac("sha256", secret).update(canonical).digest("hex");
}

describe("createHmacAuthorizer", () => {
  test("signs a body-less request to match the server scheme", async () => {
    const authorize = createHmacAuthorizer({
      apiKey: "firemoot",
      apiSecret: "change-me",
      now: () => 1_700_000_000_000,
    });
    const headers = await authorize({ method: "GET", path: "/v1/channels/messaging/general" });
    expect(headers["X-Firemoot-Key"]).toBe("firemoot");
    expect(headers["X-Firemoot-Timestamp"]).toBe("1700000000");
    expect(headers["X-Firemoot-Signature"]).toBe(
      referenceSignature("change-me", "GET", "/v1/channels/messaging/general", 1_700_000_000, ""),
    );
  });

  test("hashes the JSON body bytes", async () => {
    const authorize = createHmacAuthorizer({
      apiKey: "k",
      apiSecret: "s",
      now: () => 1_700_000_500_000,
    });
    const body = { userId: "alice", text: "hi" };
    const headers = await authorize({
      method: "POST",
      path: "/v1/channels/messaging/general/messages",
      body,
    });
    expect(headers["X-Firemoot-Signature"]).toBe(
      referenceSignature(
        "s",
        "POST",
        "/v1/channels/messaging/general/messages",
        1_700_000_500,
        JSON.stringify(body),
      ),
    );
  });

  test("upper-cases the method", async () => {
    const authorize = createHmacAuthorizer({ apiKey: "k", apiSecret: "s", now: () => 1000 });
    const lower = await authorize({ method: "post", path: "/p" });
    const upper = await authorize({ method: "POST", path: "/p" });
    expect(lower["X-Firemoot-Signature"]).toBe(upper["X-Firemoot-Signature"]);
  });
});

describe("createBearerAuthorizer", () => {
  test("sends the user JWT as a Bearer header", async () => {
    const authorize = createBearerAuthorizer("jwt-123");
    expect(await authorize({ method: "GET", path: "/v1/channels/messaging/general" })).toEqual({
      Authorization: "Bearer jwt-123",
    });
  });

  test("resolves a token provider on every request (refresh)", async () => {
    let n = 0;
    const authorize = createBearerAuthorizer(() => `t${++n}`);
    expect(await authorize({ method: "GET", path: "/p" })).toEqual({ Authorization: "Bearer t1" });
    expect(await authorize({ method: "GET", path: "/p" })).toEqual({ Authorization: "Bearer t2" });
  });
});
