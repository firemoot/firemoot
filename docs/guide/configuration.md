# Configuration

Firemoot is configured entirely by environment variables - there is no config
file. Everything has a working default except the credentials you must not ship
with defaults, so a bare `docker run` against a Postgres boots.

A variable that is present but **blank** counts as absent. That matters for the
optional ones: Compose's `${VAR:-}` syntax passes an empty string rather than
leaving the variable unset, and without that rule an "unset" `FIREMOOT_S3_ENDPOINT`
would look configured and then fail fast on its missing siblings.

## HTTP

| Variable | Default | What it does |
| --- | --- | --- |
| `FIREMOOT_HTTP_HOST` | `0.0.0.0` | Bind address. |
| `FIREMOOT_HTTP_PORT` | `6668` | Bind port. Serves the API, `/v1/ws`, `/admin` and the health endpoints. |

## Database

| Variable | Default | What it does |
| --- | --- | --- |
| `FIREMOOT_DB_HOST` | `localhost` | Postgres host. |
| `FIREMOOT_DB_PORT` | `5432` | Postgres port. |
| `FIREMOOT_DB_NAME` | `firemoot` | Database name. |
| `FIREMOOT_DB_USER` | `firemoot` | Role to connect as. |
| `FIREMOOT_DB_PASSWORD` | `firemoot` | Password. Change it. |
| `FIREMOOT_DB_MAX_CONNECTIONS` | `10` | Size of the connection pool. |
| `FIREMOOT_DB_SSLMODE` | `prefer` | JDBC `sslmode` for the boot-time Flyway connection. |

`FIREMOOT_DB_SSLMODE` exists for one specific failure. `prefer` (pgjdbc's own
default) asks for TLS and falls back to plaintext if the server *refuses* it -
but some proxies accept the SSL request and then break the handshake, which
`prefer` cannot recover from, so the migration step dies on boot. Fly's unmanaged
Postgres over `flycast` behaves this way; set `FIREMOOT_DB_SSLMODE=disable` there
(the traffic is already inside a private network). Anywhere your Postgres speaks
TLS properly, leave it alone.

## Server credentials

| Variable | Default | What it does |
| --- | --- | --- |
| `FIREMOOT_API_KEY_ID` | `firemoot` | The bootstrap server API key id. |
| `FIREMOOT_API_SECRET` | `dev-secret` | The one credential that matters - see below. |
| `FIREMOOT_ADMIN_PASSWORD` | *(unset)* | Unlocks the admin dashboard. |

`FIREMOOT_API_SECRET` does two jobs: it is the HMAC key your backend signs server
requests with, **and** the HS256 key that signs and verifies end-user JWTs. Rotate
it deliberately - doing so invalidates every live user token. Generate one with
`openssl rand -hex 32` and keep it out of the browser.

The bootstrap key pair is resolved before the database, so it always works even
on an empty schema. Additional keys created from the admin dashboard live in the
database and take effect without a restart; the bootstrap key is not one of them
and cannot be revoked from the UI.

`FIREMOOT_ADMIN_PASSWORD` has no default, so the dashboard stays locked until you
set one. On boot it is hashed with Argon2id into the `settings` table - setting
the variable again on a later boot resets the password.

## Media (S3)

Media is **off** until `FIREMOOT_S3_ENDPOINT` is set; uploads return `501` until
then. Once it is set, the rest of the block loads and a half-configured store
fails fast at boot rather than at the first upload.

| Variable | Default | What it does |
| --- | --- | --- |
| `FIREMOOT_S3_ENDPOINT` | *(unset)* | The S3 endpoint. Setting it enables media. |
| `FIREMOOT_S3_BUCKET` | `firemoot` | Bucket name. Create it yourself; Firemoot never calls vendor admin APIs. |
| `FIREMOOT_S3_REGION` | `us-east-1` | Region for request signing. |
| `FIREMOOT_S3_ACCESS_KEY` | *(required)* | Access key id. |
| `FIREMOOT_S3_SECRET_KEY` | *(required)* | Secret access key. |
| `FIREMOOT_S3_PUBLIC_URL` | *(unset)* | Base URL for public object reads. Defaults to `<endpoint>/<bucket>`. |
| `FIREMOOT_S3_FORCE_PATH_STYLE` | `true` | Path-style (`endpoint/bucket/key`) vs virtual-host (`bucket.endpoint/key`) addressing. |
| `FIREMOOT_S3_PRESIGN_EXPIRY_SECONDS` | `900` | Lifetime of a presigned upload URL. |
| `FIREMOOT_MEDIA_MAX_IMAGE_BYTES` | `10485760` (10 MiB) | Per-image upload cap. |
| `FIREMOOT_MEDIA_MAX_FILE_BYTES` | `52428800` (50 MiB) | Per-file upload cap. |
| `FIREMOOT_MEDIA_ALLOWED_MIME` | see below | Comma-separated MIME allowlist. |

The default allowlist is
`image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain`.

`FIREMOOT_S3_FORCE_PATH_STYLE` decides how object URLs are built, and it needs to
match how your store serves *public* reads. Path style suits MinIO and friends
and is the default. Stores that only serve public objects virtual-host style -
Tigris being the one you are most likely to meet, where a path-style public read
returns `403` - need it set to `false` so presigned PUTs and public GETs share
the same bucket-subdomain origin. Pair that with an explicit
`FIREMOOT_S3_PUBLIC_URL` on the same origin.

## Development

| Variable | Default | What it does |
| --- | --- | --- |
| `FIREMOOT_DEV_DEMO` | `false` | Serves a demo page at `/demo` and drops the `secure` flag from the admin session cookie. Never enable it in production. |

## JVM flags

The published image already runs with
`-XX:+UseCompactObjectHeaders -XX:+UseG1GC -XX:MaxRAMPercentage=75`, so the heap
tracks whatever memory limit you give the container. You do not need to set
`-Xmx`. See [sizing](./sizing) for what those flags buy.

## Operational endpoints

| Path | Auth | Returns |
| --- | --- | --- |
| `GET /healthz` | none | `200 {"status":"ok"}`. Liveness; never touches the database. |
| `GET /readyz` | none | `200 {"status":"ready"}`, or `503` when Postgres is unreachable. |
| `GET /metrics` | **none** | Prometheus text exposition. |
| `GET /v1/openapi.json` | none | The generated OpenAPI document. |

`/metrics` is deliberately unauthenticated so a scraper needs no credential, and
it is deliberately cheap - the gauges (`firemoot_ccu`, `firemoot_dau`,
`firemoot_wau`, `firemoot_mau`, `firemoot_messages{channel_type=…}`,
`firemoot_media_bytes`, `firemoot_db_size_bytes`) are computed on demand. It does
leak usage volumes, so firewall it or restrict it at your reverse proxy if the
box is public.
