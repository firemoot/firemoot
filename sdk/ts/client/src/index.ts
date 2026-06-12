/**
 * @firemoot/client - the hand-written value layer over @firemoot/core.
 *
 * The connection lifecycle, channel-state reducer, optimistic outbox and typed
 * event vocabulary (PLAN.md M4.1). `FiremootClient` and `Channel` compose these
 * into the high-level API.
 */
export * from "./events.js";
export * from "./emitter.js";
export * from "./state.js";
export * from "./outbox.js";
export * from "./connection.js";
export * from "./rest.js";
export * from "./auth.js";
export * from "./channel.js";
export * from "./client.js";
export * from "./server.js";
