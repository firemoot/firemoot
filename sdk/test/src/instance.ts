import { coreRestApi, createHmacAuthorizer, FiremootServer, type RestApi } from "@firemoot/client";
import { GenericContainer, Network, type StartedTestContainer, Wait } from "testcontainers";

import { seed, type SeedSpec } from "./seed.js";

export interface StartFiremootOptions {
  /** The Firemoot image to run (default `firemoot:latest`; build or pull first). */
  image?: string;
  /** The Postgres image (default `postgres:17`). */
  postgresImage?: string;
  /** Server API key id + secret the helper signs with and mints tokens from. */
  apiKey?: string;
  apiSecret?: string;
  /** Per-container startup timeout (default 120s - the image cold-boots in seconds). */
  startupTimeoutMs?: number;
}

/**
 * A running Firemoot, ready to drive: its mapped `baseUrl`/`wsUrl`, a
 * server-trusted SDK (`server`/`rest`), an end-user `createToken`, a `seed`
 * convenience and `stop`.
 */
export interface FiremootInstance {
  baseUrl: string;
  wsUrl: string;
  apiKey: string;
  apiSecret: string;
  server: FiremootServer;
  rest: RestApi;
  createToken(userId: string, expiresAt?: Date): Promise<string>;
  seed(spec: SeedSpec): Promise<void>;
  stop(): Promise<void>;
}

const DEFAULTS = {
  image: "firemoot:latest",
  postgresImage: "postgres:17",
  apiKey: "firemoot-test",
  apiSecret: "firemoot-test-secret",
  startupTimeoutMs: 120_000,
} as const;

/**
 * Boots a real Firemoot (the Docker image + Postgres on a private network via
 * Testcontainers), waits for `/healthz`, and hands back a driver. Random mapped
 * host ports make it parallel-safe; the containers are reaped on `stop()` (and by
 * Testcontainers' Ryuk if the process dies). The reference compose file remains
 * the *deployment* artifact - this is the *test* lifecycle.
 */
export async function startFiremoot(options: StartFiremootOptions = {}): Promise<FiremootInstance> {
  const image = options.image ?? DEFAULTS.image;
  const postgresImage = options.postgresImage ?? DEFAULTS.postgresImage;
  const apiKey = options.apiKey ?? DEFAULTS.apiKey;
  const apiSecret = options.apiSecret ?? DEFAULTS.apiSecret;
  const startupTimeoutMs = options.startupTimeoutMs ?? DEFAULTS.startupTimeoutMs;

  const network = await new Network().start();
  let postgres: StartedTestContainer | undefined;
  let app: StartedTestContainer | undefined;
  try {
    postgres = await new GenericContainer(postgresImage)
      .withNetwork(network)
      .withNetworkAliases("postgres")
      .withEnvironment({
        POSTGRES_USER: "firemoot",
        POSTGRES_PASSWORD: "firemoot",
        POSTGRES_DB: "firemoot",
      })
      .withWaitStrategy(Wait.forLogMessage(/database system is ready to accept connections/, 2))
      .withStartupTimeout(startupTimeoutMs)
      .start();

    app = await new GenericContainer(image)
      .withNetwork(network)
      .withEnvironment({
        FIREMOOT_HTTP_HOST: "0.0.0.0",
        FIREMOOT_HTTP_PORT: "6668",
        FIREMOOT_DB_HOST: "postgres",
        FIREMOOT_DB_PORT: "5432",
        FIREMOOT_DB_NAME: "firemoot",
        FIREMOOT_DB_USER: "firemoot",
        FIREMOOT_DB_PASSWORD: "firemoot",
        FIREMOOT_API_KEY_ID: apiKey,
        FIREMOOT_API_SECRET: apiSecret,
      })
      .withExposedPorts(6668)
      .withWaitStrategy(Wait.forHttp("/healthz", 6668).forStatusCode(200))
      .withStartupTimeout(startupTimeoutMs)
      .start();
  } catch (error) {
    await app?.stop().catch(() => undefined);
    await postgres?.stop().catch(() => undefined);
    await network.stop().catch(() => undefined);
    throw error;
  }

  const startedApp = app;
  const startedPostgres = postgres;
  const host = startedApp.getHost();
  const port = startedApp.getMappedPort(6668);
  const baseUrl = `http://${host}:${port}`;
  const wsUrl = `ws://${host}:${port}/v1/ws`;

  const server = new FiremootServer({ baseUrl, apiKey, apiSecret });
  const rest = coreRestApi({ baseUrl, authorize: createHmacAuthorizer({ apiKey, apiSecret }) });

  return {
    baseUrl,
    wsUrl,
    apiKey,
    apiSecret,
    server,
    rest,
    createToken: (userId, expiresAt) => server.createToken(userId, expiresAt),
    seed: (spec) => seed({ server, rest }, spec),
    async stop() {
      await startedApp.stop();
      await startedPostgres.stop();
      await network.stop();
    },
  };
}
