// Firemoot WebSocket soak (PLAN.md M3.6, SPEC.md §12).
//
// N idle WS subscribers watch one channel while a sender posts M messages/s over
// the REST API. Each message carries `custom.sentAtMs`; a subscriber records the
// end-to-end delivery latency (receipt time - send time) into a Trend whose p99
// is a CI threshold. The companion nightly workflow additionally gates on the
// firemoot container's RSS. Auth is minted in-script: an HS256 JWT (signed with
// the API secret) for each WS user, and the FIREMOOT-HMAC-SHA256 request
// signature for the REST sender - so the soak exercises the real auth paths.
//
// Tunables (env): SOAK_BASE_URL, SOAK_API_KEY, SOAK_API_SECRET, SOAK_VUS,
// SOAK_RATE, SOAK_SECONDS, SOAK_LAT_P99_MS.

import crypto from "k6/crypto";
import encoding from "k6/encoding";
import httpClient from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";
import { WebSocket } from "k6/websockets";

const BASE_URL = __ENV.SOAK_BASE_URL || "http://localhost:6668";
const API_KEY = __ENV.SOAK_API_KEY || "firemoot";
const API_SECRET = __ENV.SOAK_API_SECRET || "change-me";
const VUS = parseInt(__ENV.SOAK_VUS || "50", 10);
const RATE = parseInt(__ENV.SOAK_RATE || "10", 10);
const SECONDS = parseInt(__ENV.SOAK_SECONDS || "60", 10);
const P99_MS = __ENV.SOAK_LAT_P99_MS || "1000";

const SENDER = "soak-sender";
const CH_TYPE = "messaging";
const WS_URL = BASE_URL.replace(/^http/, "ws") + "/v1/ws";

const deliveryLatency = new Trend("ws_delivery_latency", true);
const delivered = new Counter("ws_delivered");
const connectErrors = new Counter("ws_connect_errors");

export const options = {
  scenarios: {
    subscribers: {
      executor: "per-vu-iterations",
      vus: VUS,
      iterations: 1,
      maxDuration: `${SECONDS + 12}s`,
      exec: "subscriber",
    },
    sender: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: `${SECONDS}s`,
      startTime: "4s", // let the subscribers connect first
      preAllocatedVUs: Math.max(10, RATE),
      exec: "sender",
    },
  },
  thresholds: {
    ws_delivery_latency: [`p(99)<${P99_MS}`],
    ws_delivered: ["count>0"], // auth/wiring broke if nothing arrived
    ws_connect_errors: ["count==0"],
    checks: ["rate>0.99"],
  },
};

function b64urlJson(obj) {
  return encoding.b64encode(JSON.stringify(obj), "rawurl");
}

function mintJwt(sub, ttlSeconds) {
  const header = b64urlJson({ alg: "HS256", typ: "JWT" });
  const now = Math.floor(Date.now() / 1000);
  const payload = b64urlJson({ sub, exp: now + ttlSeconds });
  const signingInput = `${header}.${payload}`;
  const signature = crypto.hmac("sha256", API_SECRET, signingInput, "base64rawurl");
  return `${signingInput}.${signature}`;
}

function signedHeaders(method, path, body) {
  const ts = Math.floor(Date.now() / 1000);
  const bodyHashHex = crypto.sha256(body, "hex");
  const canonical = ["FIREMOOT-HMAC-SHA256", method.toUpperCase(), path, ts, bodyHashHex].join(
    "\n",
  );
  return {
    "Content-Type": "application/json",
    "X-Firemoot-Key": API_KEY,
    "X-Firemoot-Timestamp": String(ts),
    "X-Firemoot-Signature": crypto.hmac("sha256", API_SECRET, canonical, "hex"),
  };
}

function postSigned(path, payload) {
  const body = JSON.stringify(payload);
  return httpClient.post(BASE_URL + path, body, { headers: signedHeaders("POST", path, body) });
}

// Create the sender user and a FRESH channel per run, before the scenarios start.
// A unique channel id means subscribing from seq 0 replays nothing, so stale
// events from a previous run can't pollute the latency metric. The returned data
// is handed to every VU iteration.
export function setup() {
  const chId = `soak-${Date.now()}`;
  const cid = `${CH_TYPE}:${chId}`;
  const sendPath = `/v1/channels/${CH_TYPE}/${chId}/messages`;
  const user = postSigned("/v1/users", { id: SENDER });
  check(user, { "setup user ok": (r) => r.status < 300 });
  const channel = postSigned("/v1/channels", { type: CH_TYPE, id: chId, createdBy: SENDER });
  check(channel, { "setup channel ok": (r) => r.status < 300 });
  return { cid, sendPath };
}

export function subscriber(data) {
  const url = `${WS_URL}?token=${mintJwt(`soak-${__VU}`, 3600)}`;
  const socket = new WebSocket(url);

  socket.onopen = () => {
    socket.send(JSON.stringify({ type: "subscribe", channels: { [data.cid]: 0 } }));
  };

  socket.onmessage = (event) => {
    let frame;
    try {
      frame = JSON.parse(event.data);
    } catch (_) {
      return;
    }
    if (frame.type !== "message.new") return;
    const sentAt = frame.data && frame.data.custom && frame.data.custom.sentAtMs;
    if (typeof sentAt === "number") {
      deliveryLatency.add(Date.now() - sentAt);
      delivered.add(1);
    }
  };

  socket.onerror = () => connectErrors.add(1);

  // Hold the connection open for the soak window, then close to end the iteration.
  setTimeout(() => socket.close(), (SECONDS + 6) * 1000);
}

export function sender(data) {
  const res = postSigned(data.sendPath, {
    userId: SENDER,
    text: "soak",
    custom: { sentAtMs: Date.now() },
  });
  check(res, { "send accepted": (r) => r.status === 201 || r.status === 200 });
}
