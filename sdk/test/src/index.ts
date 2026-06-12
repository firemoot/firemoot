/**
 * @firemoot/test - boots and seeds a Firemoot instance for downstream CI
 * (PLAN.md M4.5). `startFiremoot()` runs the Docker image + Postgres via
 * Testcontainers, waits healthy, and returns a driver (`baseUrl`/`wsUrl`,
 * server-trusted SDK, `createToken`, `seed`, `stop`). Firemoot's own SDK suite
 * is the first consumer (dogfood gate); the M4.6 reconnect-chaos tests are next.
 */
export * from "./instance.js";
export * from "./seed.js";
