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

### Media on the same box

On a single VPS, the simplest media setup is the compose stack's MinIO served
from the **same hostname** as the API, path-style, under a path prefix that is
the bucket name:

```text
chat.example.com {
	handle /attachments/* {
		reverse_proxy minio:9000
	}
	handle {
		reverse_proxy firemoot:6668
	}
}
```

```sh
FIREMOOT_S3_ENDPOINT=https://chat.example.com
FIREMOOT_S3_BUCKET=attachments
FIREMOOT_S3_ACCESS_KEY=<a MinIO user scoped to that bucket>
FIREMOOT_S3_SECRET_KEY=<its secret>
```

Path-style addressing is the default, so object URLs come out as
`https://chat.example.com/attachments/<key>`. Make the bucket publicly readable
(`mc anonymous set download local/attachments`). Give Firemoot a MinIO user whose
policy covers only that bucket, not the root credentials.

This works because Caddy passes the `Host` header through unchanged, so the
signatures on presigned PUTs still verify at MinIO. It also means the API, browser
uploads and image reads all share one origin: a client's Content-Security-Policy
and image allow-list need exactly one entry, and no extra DNS record is involved.

If the Caddyfile is a single-file bind mount, an edit that replaces the file (an
editor's atomic save, or `mv`) leaves the running container reading the old
inode, and `caddy reload` reloads the old config. Recreate the Caddy container
after such an edit, or mount the containing directory instead.

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

Point any external uptime monitor at `/readyz`, not `/healthz`. `/healthz` never
touches the database, so it stays green while a starved Postgres stalls every
real request.

### Size for sustained load, not bursts

Fly's `shared-cpu-Nx` machines guarantee only **1/16 of a core per vCPU**. Above
that they spend burst credits. When the credits run out, the kernel clamps the
machine back to that baseline until load drops and credit rebuilds. JVM cold
starts and query-heavy bursts (a CI suite running in parallel shards, say) drain
credit fast.

The usual casualty is Postgres on `shared-cpu-1x`. Queries that normally take
milliseconds stretch to seconds while `/healthz` stays fast. Throttling has a
tell: the machine gets slower under *less* load, because the clamp tracks
cumulative burn, not current traffic.

Check Fly's metrics before blaming the code. `fly_instance_cpu_balance` near
zero and a non-zero `fly_instance_cpu_throttle` mean the machine is being
clamped. The remedy is more shared vCPUs (each adds baseline and credit) or a
`performance` machine.

`fly deploy` re-applies the `[[vm]]` size in `fly.toml`. A machine resized with
`fly machine update` or `fly scale vm` silently reverts on the next deploy unless
you copy the new size into `fly.toml` too.

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
