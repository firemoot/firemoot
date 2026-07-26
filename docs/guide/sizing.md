# Sizing & performance

Firemoot is built to run small: a single JVM service plus Postgres, with the heap
capped and the GC chosen for low resident memory.

## Memory

The image runs the JVM with:

```
-XX:+UseCompactObjectHeaders -XX:+UseG1GC -XX:MaxRAMPercentage=75
```

Compact object headers (JDK 25) trim heap by roughly 10-22%; G1 keeps RSS
predictable, and ZGC is not an option here because it is incompatible with
compact headers. `MaxRAMPercentage=75` caps the heap at three-quarters of the
container's memory limit, so a 1GB container yields a ~768MB heap ceiling with
headroom for the rest of the process. You should not need to set `-Xmx`.

## What the nightly soak measures

A scheduled job runs the server in a **1GiB-capped** container - the "$7 VPS"
envelope - and drives it with k6: 50 idle WebSocket subscribers plus a steady
10 messages/second for 60 seconds, through the real auth paths (the script mints
its own HS256 tokens and HMAC signatures). Every message is stamped on send so
subscribers measure true end-to-end **delivery latency**, not request duration.

It fails the build on p99 delivery latency over 1000ms, peak container RSS over
800MiB, or an OOM kill.

Recent nightly runs, on a shared GitHub-hosted runner:

| | |
| --- | --- |
| Delivery latency | median ~17ms, p95 43-48ms, p99 194ms |
| Peak container RSS | 285-290MiB |
| Failed checks | 0 |

Those are honest numbers from a contended CI runner rather than a tuned
benchmark box, and the load is modest by design - the point of the gate is to
catch a regression in the memory envelope, not to publish a throughput record.
Dedicated hardware does better.

## Throughput

Throughput is bounded by Postgres. Every message is one transaction that
allocates the per-channel seq, writes the message and appends to the channel
event log atomically - that atomicity is what makes gapless resume work, and it
is the cost you pay for it. Idle WebSocket connections, by contrast, are cheap:
the JVM/Netty stack holds many thousands per node.

## Recommended starting point

- **Small community or launch:** 1 vCPU, 1-2GB RAM, a small Postgres. Comfortable.
- **Growing:** raise the container's memory and the heap follows via
  `MaxRAMPercentage`. Watch RSS the way the soak does, and watch Postgres first -
  it will be the bottleneck before the JVM is.

## Scaling out

v1 is **single node**: the realtime backplane is in-process. The designed first
step to multi-node is a Postgres `LISTEN`/`NOTIFY` backplane (the interface is
already a seam), with Redis pub/sub as a later option. Until then, scale
vertically - one well-fed node serves a lot of chat. See
[self-hosting](./hosting) for what that means on Fly in particular.
