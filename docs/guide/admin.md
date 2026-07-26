# Admin dashboard

Firemoot serves a small operator dashboard from the same binary at **`/admin`** -
no extra service, no extra process. It is a single page: usage charts, API key
management, and the webhook dead-letter queue.

## Logging in

Set `FIREMOOT_ADMIN_PASSWORD` and restart. There is no default, so until you set
one the dashboard cannot be logged into at all. On boot the password is hashed
with Argon2id into the database; setting the variable again on a later boot
resets it.

There is no username - one password, one operator. Signing in sets an
`httpOnly`, `SameSite=Strict` session cookie carrying a 12-hour JWT, plus a
readable CSRF cookie that the page echoes back on every mutating request. The
session cookie is marked `secure` unless `FIREMOOT_DEV_DEMO` is on, so **serve
`/admin` over HTTPS** or the browser will drop it. There is no logout; close the
tab or wait out the 12 hours.

The page is `noindex`, but it is still a password prompt on your public origin.
If you would rather it were not reachable at all, block `/admin` at your reverse
proxy and reach it over a tunnel.

## At a glance

Seven tiles across the top, computed live from the base tables:

- **Online now** - concurrent WebSocket connections right now
- **DAU / WAU / MAU** - distinct active users over the trailing 1, 7 and 30 days
- **Messages today** - with a per-channel-type breakdown underneath
- **Media stored** - total bytes of uploaded objects
- **Database** - on-disk size of the database

## Charts

Four charts underneath, drawn from rollup tables rather than recomputed on each
load:

| Chart | Window | Shows |
| --- | --- | --- |
| Active users | 90 days | Daily DAU, WAU and MAU lines. |
| Messages per day | 90 days | Stacked by channel type. |
| Concurrent connections | 168 hours | Hourly p95 and max. |
| Storage | 90 days | Media bytes and database size. |

A background worker writes the daily rollups every 10 minutes and samples the
connection count every 60 seconds, so a brand new instance shows "No data yet"
for a while - that is expected, not a fault. Raw activity is kept for 35 days,
connection samples for 2 days, hourly rows for 8 days; the rolled-up daily series
is what survives long-term.

The same numbers are available to a scraper at `/metrics` in Prometheus format.
The dashboard does not read that endpoint - it has its own API - so you can
restrict one without breaking the other.

## API keys

Create and revoke the database-backed server API keys your backend signs with.
The table lists key id, creation date and status; **the secret is shown exactly
once**, on creation, and is never recoverable afterwards. New keys work
immediately, with no restart.

The bootstrap key from `FIREMOOT_API_KEY_ID` / `FIREMOOT_API_SECRET` is not
listed here and cannot be revoked here - it lives in config, resolved before the
database. To rotate away from it, create a DB-backed key, cut your backend over,
then change the config value.

## Webhook dead-letters

Deliveries that exhausted their retry ladder, most recent first (up to 100). Each
row shows the event type, the endpoint id, the attempt count, the last error and
when the delivery was enqueued.

**Replay** requeues one: status back to pending, attempts back to zero, next
attempt now. It gets the full ladder again, so if the consumer is still broken it
will dead-letter a second time rather than hammering the endpoint. Fix the
consumer first, then replay.

Replay is the only action here. Registering and deleting endpoints is a REST
operation - see [webhooks](./webhooks).
