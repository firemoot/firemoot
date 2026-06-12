---
layout: home

hero:
  name: Firemoot
  text: Self-hosted chat backend
  tagline: A single-binary realtime chat server on Postgres. Own your messaging - no per-MAU bill, no vendor lock-in.
  actions:
    - theme: brand
      text: Quickstart
      link: /guide/quickstart
    - theme: alt
      text: Migrating from Stream
      link: /guide/migration
    - theme: alt
      text: GitHub
      link: https://github.com/firemoot/firemoot

features:
  - title: One binary, one database
    details: A single JVM service and PostgreSQL. No Redis, no Kafka, no message broker to operate. Flyway-migrated schema; boots to healthy in seconds.
  - title: Realtime, resumably
    details: WebSocket fan-out with a per-channel event log. Reconnects resume from the last seq with zero message loss or duplication - proven by a chaos test that severs the connection mid-stream.
  - title: Runs on a $7 VPS
    details: JDK 25 compact object headers + a heap-capped JVM keep RSS small. A nightly soak gates p99 delivery latency and peak memory inside a 1GB envelope.
  - title: Typed TypeScript SDK
    details: A generated transport (@firemoot/core) plus a hand-written client (@firemoot/client) with optimistic send, a channel-state reducer and read receipts. Stream-compatible naming where it is free.
  - title: Two auth surfaces
    details: HMAC-signed server requests for your backend, HS256 JWT bearer for the browser - authorised per channel operation, identity forced to the token subject.
  - title: Yours to run
    details: Apache-2.0. Docker Compose reference stack, first-class on Fly.io, Caddy for one-line TLS. S3-compatible media (MinIO, Tigris, Garage, AWS) with no code change.
---
