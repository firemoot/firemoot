# Deploying Firemoot on Fly.io

Firemoot is a stateful WebSocket backend, which makes Fly a first-class target -
with **one rule that overrides every default**:

> **Never let the proxy idle-stop a machine that holds live sockets.**
> `fly.toml` sets `auto_stop_machines = "off"` and `min_machines_running = 1`.
> Fly's out-of-the-box scale-to-zero is designed for stateless HTTP; for a chat
> node it silently drops every connected client. Leave these as they are.

## 1. Database

Firemoot needs one Postgres. Either works:

- **Fly Postgres** (managed cluster in your org):
  ```sh
  fly postgres create --name firemoot-db --region lhr
  fly postgres attach firemoot-db --app firemoot
  ```
  `attach` sets a `DATABASE_URL`; translate it into Firemoot's discrete vars:
  ```sh
  fly secrets set \
    FIREMOOT_DB_HOST=firemoot-db.flycast \
    FIREMOOT_DB_USER=firemoot \
    FIREMOOT_DB_PASSWORD=<from attach output> \
    FIREMOOT_DB_SSLMODE=disable
  ```
  `FIREMOOT_DB_SSLMODE=disable` is not optional over `flycast`: the proxy accepts
  the SSL request and then breaks the handshake instead of refusing it, so
  pgjdbc's default `prefer` never falls back to plaintext and boot dies in
  Flyway. The traffic stays inside your private WireGuard network.
- **Neon** (serverless Postgres): create a database, then set the same three
  `FIREMOOT_DB_*` secrets from its connection string. Use a pooled, non-idling
  endpoint - Firemoot keeps a small persistent skunk pool.

## 2. Media (optional) - Tigris

Tigris is S3-compatible and runs on Fly with zero code change (Firemoot only ever
presigns/gets/puts - never vendor admin APIs):

```sh
fly storage create            # provisions a Tigris bucket, sets AWS_* secrets
fly secrets set \
  FIREMOOT_S3_ENDPOINT=https://fly.storage.tigris.dev \
  FIREMOOT_S3_BUCKET=<bucket> \
  FIREMOOT_S3_ACCESS_KEY=<AWS_ACCESS_KEY_ID> \
  FIREMOOT_S3_SECRET_KEY=<AWS_SECRET_ACCESS_KEY>
```

Leave the `FIREMOOT_S3_*` secrets unset to run with media disabled (uploads 501).

## 3. App secrets

```sh
fly secrets set \
  FIREMOOT_API_SECRET=$(openssl rand -hex 32) \
  FIREMOOT_ADMIN_PASSWORD=<your admin password>
```

`FIREMOOT_API_SECRET` signs server HMAC requests *and* the end-user JWTs the
client connects with - keep it secret and stable. `FIREMOOT_ADMIN_PASSWORD` is
hashed (Argon2id) into the DB on first boot; the dashboard stays locked until set.

## 4. Deploy

`fly.toml` pins `image = "firemoot/firemoot:latest"`, so once that image exists on
Docker Hub (PLAN §12) the deploy is just:

```sh
fly apps create firemoot        # once; or `fly launch --copy-config --no-deploy`
fly deploy --ha=false
```

`--ha=false` matters. `fly deploy` provisions **two** machines by default for high
availability, and with the v1 in-process backplane the second node shares no state
with the first - two users on the same channel can land on different machines and
never see each other. One machine until the Postgres `LISTEN`/`NOTIFY` backplane
lands.

### Interim: before Docker Hub

Firemoot's image is built by sbt-native-packager (a runtime-only image that COPYs
pre-staged artifacts), so Fly's remote builder cannot build it from a fresh
checkout - build locally and push to Fly's registry:

```sh
sbt "server/Docker/publishLocal"                 # tags firemoot:latest
fly auth docker
docker tag firemoot:latest registry.fly.io/firemoot:latest
docker push registry.fly.io/firemoot:latest
fly deploy --ha=false --image registry.fly.io/firemoot:latest
```

(or point `[build] image` at any registry Fly can pull from).

## 5. Verify

```sh
fly status                       # one machine, running, not stopped
curl https://<app>.fly.dev/healthz
curl https://<app>.fly.dev/readyz   # checks the Postgres connection
```

Then point a client at `wss://<app>.fly.dev/v1/ws` - Fly's proxy upgrades
WebSockets transparently over the `force_https` listener.

## Scaling notes

- Single region, single machine is the v1 story (the backplane is in-process).
  Multi-node needs the Postgres `LISTEN`/`NOTIFY` backplane (post-v1, SPEC §2).
- To grow vertically, raise `[[vm]] memory`; the JVM tracks it via
  `-XX:MaxRAMPercentage=75` (baked into the image). Watch RSS the way the nightly
  soak does.
