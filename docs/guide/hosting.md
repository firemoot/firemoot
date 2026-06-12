# Hosting

Firemoot holds long-lived WebSocket connections. That one fact decides where it
can run: anywhere that lets a process stay up and keep sockets open.

## Docker Compose (reference)

The reference stack is `firemoot` + `postgres:17` (+ optional S3-compatible media).
It suits a $7 Hetzner-class VPS and is exactly what the project's own CI runs. See
the [compose README](https://github.com/firemoot/firemoot/tree/main/deploy/compose)
for the 5-minute path.

### TLS and WebSockets with Caddy

Put [Caddy](https://github.com/firemoot/firemoot/tree/main/deploy/caddy) in front
for automatic HTTPS. The entire reverse-proxy config is:

```text
chat.example.com {
	reverse_proxy localhost:6668
}
```

Caddy fetches and renews the certificate itself and upgrades the WebSocket on
`/v1/ws` transparently - there is no special WebSocket directive. Point clients at
`wss://chat.example.com/v1/ws`.

## Fly.io (first-class)

Firemoot runs well on Fly with **one rule that overrides every default**: never
let the proxy idle-stop a machine that holds live sockets. The provided `fly.toml`
sets `auto_stop_machines = "off"` and `min_machines_running = 1`. Pair it with Fly
Postgres or Neon, and Tigris for S3-compatible media (zero code change). Full
walkthrough in the
[Fly guide](https://github.com/firemoot/firemoot/tree/main/deploy/fly).

## Media storage

Media is off until an S3 endpoint is configured (uploads return `501`). The code
path is **generic S3 only** - it presigns, gets and puts, and never calls a vendor
admin API - so MinIO, Tigris, Garage, SeaweedFS or AWS S3 all swap in unchanged.

## Not a host: serverless platforms

Vercel, Netlify, AWS Lambda and AWS App Runner cannot hold long-lived WebSocket
connections, so they cannot **host** Firemoot. They are first-class **clients** of
it: your serverless app talks to Firemoot with the server SDK over HTTPS (minting
tokens, provisioning, sending) exactly as it would talk to a hosted chat vendor.
The realtime socket lives between the browser and your Firemoot node, not between
the browser and your serverless functions.
