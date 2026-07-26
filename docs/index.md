---
layout: home

hero:
  name: Firemoot
  text: Self-hosted chat backend
  tagline: Stream Chat's developer experience, your infrastructure.
  image:
    src: /brand/firemoot-blaze.png
    alt: FIREMOOT ablaze
  actions:
    - theme: brand
      text: Get started
      link: /guide/quickstart
    - theme: alt
      text: Migrating from Stream
      link: /guide/migration
    - theme: alt
      text: GitHub
      link: https://github.com/firemoot/firemoot

features:
  - title: A Stream-shaped API surface
    details: Your backend mints user tokens, the browser talks straight to the chat backend, the backend authorises every operation. Channels, members, reactions, read receipts, typing, threads, flags - the same model, the same method names where they were free to keep.
  - title: One JVM service and Postgres
    details: No Redis, no Kafka, no broker to babysit. The event log, the webhook queue and the metrics rollups all live in Postgres, migrated by Flyway on boot.
  - title: Boots in CI in seconds
    details: "@firemoot/test starts the real image plus Postgres through Testcontainers, seeds a fixture and hands back a driver. Your integration tests run against the actual server, not a mock."
  - title: Realtime with gapless resume
    details: Subscribe with the last seq you saw and the server replays everything after it, then splices in live events by seq. No gap, no duplicate - proven by a chaos test that severs the TCP connection mid-stream.
  - title: TypeScript SDKs from OpenAPI
    details: "@firemoot/core is generated from the tapir endpoints and CI fails on drift, so the types cannot lie. @firemoot/client adds optimistic send, a channel-state reducer and read receipts on top."
  - title: An admin dashboard included
    details: Active users, messages per day by channel type, concurrent connections and storage growth, plus API key rotation and one-click replay of dead-lettered webhooks. Served from the same binary at /admin.
  - title: Webhooks with replay
    details: Every persisted channel event is fanned out to your endpoints, HMAC-signed and retried on a 1m/5m/30m/2h ladder. What still fails is dead-lettered rather than dropped, and you can requeue it from the dashboard.
  - title: S3 media with thumbnails
    details: Presigned uploads against any S3-compatible store - MinIO, Tigris, Garage, SeaweedFS, AWS. Thumbnails are generated in the background and patched onto the attachment via message.updated.
  - title: Runs on a $7 VPS
    details: The nightly soak holds the container to a 1GiB cap and gates on both latency and memory. Latest run - p95 delivery latency 48ms at 290MiB peak RSS, with zero failed checks.
---
