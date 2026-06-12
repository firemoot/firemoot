# Firemoot via Docker Compose

The reference deployment (SPEC.md §11) and the exact stack downstream CI runs:
`firemoot` + `postgres:17` (+ an optional `pgsty/minio` for media). Suitable for a
$7 Hetzner-class VPS.

## Quickstart (about 5 minutes)

1. Build the image (until it is published to Docker Hub, §12):
   ```sh
   sbt "server/Docker/publishLocal"     # tags firemoot:latest
   ```
2. Set the credentials you must not ship with defaults, then boot:
   ```sh
   cd deploy/compose
   export FIREMOOT_API_SECRET=$(openssl rand -hex 32)
   docker compose up -d
   ```
   Override `FIREMOOT_API_SECRET` (and `FIREMOOT_ADMIN_PASSWORD`, to unlock the
   dashboard) in the compose file or your environment before exposing the box.
3. Check it:
   ```sh
   curl localhost:6668/healthz      # liveness
   curl localhost:6668/readyz       # liveness + Postgres ping
   ```

The JVM runs with `-XX:+UseCompactObjectHeaders -XX:+UseG1GC
-XX:MaxRAMPercentage=75` (baked into the image) - the RSS/$7-VPS envelope. 2GB
RAM is comfortable.

## TLS + WebSockets with Caddy

Put [Caddy](../caddy/Caddyfile) in front for automatic HTTPS. The entire config is:

```caddyfile
chat.example.com {
	reverse_proxy localhost:6668
}
```

Caddy fetches and renews the certificate itself and upgrades the WebSocket on
`/v1/ws` transparently - there is no special WebSocket directive to add. Point
clients at `wss://chat.example.com/v1/ws`.

## Enabling media (MinIO or any S3)

Media is off until an S3 endpoint is configured (uploads return `501`). The
compose file ships a `pgsty/minio` service; enable it by setting these on the
`firemoot` service and creating the bucket once (`mc mb local/firemoot`):

```yaml
FIREMOOT_S3_ENDPOINT: http://minio:9000
FIREMOOT_S3_BUCKET: firemoot
FIREMOOT_S3_ACCESS_KEY: firemoot
FIREMOOT_S3_SECRET_KEY: firemoot-secret
```

The code path is generic-S3 only (presign/get/put, never vendor admin APIs), so
Garage / SeaweedFS / Tigris / AWS swap in unchanged.

## Not a host for serverless platforms

Vercel, Netlify, AWS Lambda and App Runner cannot hold the long-lived WebSocket
connections Firemoot needs. Your serverless app is a *client* of Firemoot (via the
server SDK over HTTPS), exactly as it would be a client of a hosted chat vendor.
For a managed long-running target, see [the Fly.io guide](../fly/README.md).
