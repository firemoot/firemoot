# Self-hosting

Firemoot holds long-lived WebSocket connections. That one fact decides where it
can run: anywhere a process can stay up and keep sockets open. A $7 VPS, a Fly
machine, a box under your desk - all fine. Serverless is not.

You need exactly two things: somewhere to run the container, and a Postgres.

## Docker Compose (the reference stack)

`deploy/compose/docker-compose.yml` is the reference deployment and the exact
stack the project's own CI boots: `firemoot` + `postgres:17`, with an optional
MinIO for media. Postgres and MinIO publish no host ports - only Firemoot's
`6668` is exposed.

```sh
cd deploy/compose
export FIREMOOT_API_SECRET=$(openssl rand -hex 32)
export FIREMOOT_ADMIN_PASSWORD='choose-something'
docker compose up -d

curl localhost:6668/readyz
```

The stack pulls the published `ghcr.io/firemoot/firemoot` image. To run a
locally-built server instead, `mise exec -- sbt "server/Docker/publishLocal"`
(tags `firemoot:latest`) and set `FIREMOOT_IMAGE=firemoot:latest`.

Both of those are read from your environment with development defaults behind
them, so **set `FIREMOOT_API_SECRET` before you expose the box** - the fallback is
`change-me`. Leaving `FIREMOOT_ADMIN_PASSWORD` unset simply keeps the dashboard
locked.

The image bakes in `-XX:+UseCompactObjectHeaders -XX:+UseG1GC
-XX:MaxRAMPercentage=75`, so the heap follows whatever memory limit you give the
container. 1GB is the tested envelope; 2GB is comfortable. See
[sizing](./sizing).

### TLS and WebSockets with Caddy

Put [Caddy](https://github.com/firemoot/firemoot/tree/main/deploy/caddy) in front
for automatic HTTPS. The whole reverse-proxy config is:

```text
chat.example.com {
	reverse_proxy localhost:6668
}
```

Caddy fetches and renews the certificate itself and upgrades the WebSocket on
`/v1/ws` transparently - there is no special WebSocket directive to add. Point
clients at `wss://chat.example.com/v1/ws`.

## Fly.io

Fly is a first-class target, and `deploy/fly/fly.toml` is a working config. The
walkthrough below assumes you start from that directory.

### 1. Create the app

```sh
cd deploy/fly
fly launch --copy-config --no-deploy   # keeps the committed fly.toml
```

The committed config sets `auto_stop_machines = "off"`,
`auto_start_machines = false` and `min_machines_running = 1`. **Leave those
alone.** Fly's default scale-to-zero is built for stateless HTTP; applied to a
chat node it silently drops every connected client the moment traffic goes quiet.
It also scales on concurrent *connections* rather than requests, which is the
right axis for a service holding thousands of idle sockets.

### 2. Postgres

```sh
fly postgres create --name firemoot-db --region lhr
fly postgres attach firemoot-db --app firemoot
```

`attach` hands you a `DATABASE_URL`; Firemoot wants the parts separately:

```sh
fly secrets set \
  FIREMOOT_DB_HOST=firemoot-db.flycast \
  FIREMOOT_DB_USER=firemoot \
  FIREMOOT_DB_PASSWORD=<from the attach output> \
  FIREMOOT_DB_SSLMODE=disable
```

`FIREMOOT_DB_SSLMODE=disable` is **not optional** over `flycast`. The proxy
accepts the TLS request and then breaks the handshake rather than refusing it, so
pgjdbc's default `prefer` never falls back to plaintext and the boot dies in
Flyway with a connection error that looks nothing like a TLS problem. The traffic
stays inside your private WireGuard network either way.

A managed provider that terminates TLS properly - Neon, for instance - needs no
such flag; use a pooled, non-idling endpoint, since Firemoot keeps a small
persistent connection pool.

### 3. Media with Tigris (optional)

```sh
fly storage create        # provisions a Tigris bucket and sets AWS_* secrets

fly secrets set \
  FIREMOOT_S3_ENDPOINT=https://fly.storage.tigris.dev \
  FIREMOOT_S3_BUCKET=<bucket> \
  FIREMOOT_S3_ACCESS_KEY=<AWS_ACCESS_KEY_ID> \
  FIREMOOT_S3_SECRET_KEY=<AWS_SECRET_ACCESS_KEY> \
  FIREMOOT_S3_FORCE_PATH_STYLE=false \
  FIREMOOT_S3_PUBLIC_URL=https://<bucket>.fly.storage.tigris.dev
```

The last two lines are the Tigris-specific bit. Tigris serves public objects
**virtual-host style only** - a path-style public read returns `403` - so
path-style addressing has to come off, and the public base URL has to be the
bucket subdomain, or presigned PUTs and public GETs end up on different origins
and images 403 in the browser after uploading fine.

Nothing else about media is vendor-specific: Firemoot only ever presigns, gets
and puts, so MinIO, Garage, SeaweedFS or AWS swap straight in. Leave
`FIREMOOT_S3_ENDPOINT` unset to run with media disabled (uploads return `501`).

### 4. App secrets

```sh
fly secrets set \
  FIREMOOT_API_SECRET=$(openssl rand -hex 32) \
  FIREMOOT_ADMIN_PASSWORD=<your admin password>
```

### 5. Deploy

```sh
fly deploy --ha=false
```

`--ha=false` matters. `fly deploy` provisions **two** machines by default for
high availability, and v1's realtime backplane is in-process - the second machine
shares no state with the first, so two users in the same channel can land on
different machines and never see each other's messages. One machine, until the
Postgres `LISTEN`/`NOTIFY` backplane lands (see below).

### 6. Verify

```sh
fly status                          # one machine, running, not stopped
curl https://<app>.fly.dev/healthz
curl https://<app>.fly.dev/readyz   # also checks Postgres
```

Then point a client at `wss://<app>.fly.dev/v1/ws`; Fly's proxy upgrades the
WebSocket transparently over the `force_https` listener.

## v1 is single-node

The realtime backplane lives in the process, so a Firemoot deployment is one
node. That is a deliberate v1 boundary rather than a permanent one: the backplane
sits behind an interface, and the first step to multi-node is a Postgres
`LISTEN`/`NOTIFY` implementation of it, with Redis pub/sub as a later option. It
is on the roadmap.

Until then, scale vertically - raise the container's memory and the JVM heap
follows. One well-fed node serves a lot of chat; see [sizing](./sizing) for the
measured envelope.

## Not a host: serverless platforms

Vercel, Netlify, AWS Lambda and App Runner cannot hold long-lived WebSocket
connections, so they cannot **host** Firemoot. They are excellent **clients** of
it: your serverless app talks to Firemoot over HTTPS with the server SDK - minting
tokens, provisioning, sending - exactly as it would talk to a hosted chat vendor.
The socket lives between the browser and your Firemoot node, not between the
browser and your functions.
