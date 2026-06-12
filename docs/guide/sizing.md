# Sizing & performance

Firemoot is built to run small. A single JVM service plus Postgres, with the heap
capped and the GC chosen for low resident memory.

## Memory

The image runs the JVM with:

```
-XX:+UseCompactObjectHeaders -XX:+UseG1GC -XX:MaxRAMPercentage=75
```

Compact object headers (JDK 25) trim heap by ~10-22%; G1 keeps RSS predictable
(ZGC is incompatible with compact headers). `MaxRAMPercentage=75` caps the heap at
three-quarters of the container's memory limit, so giving the container 1GB yields
a ~768MB heap ceiling with headroom for the rest of the process.

A nightly soak runs the server in a **1GB-capped** container and gates on peak
container RSS as well as delivery latency - the "$7 VPS" envelope is a tested
constraint, not a slogan.

## Latency and throughput

The same soak drives N idle WebSocket subscribers plus a steady REST send rate,
stamping each message so subscribers measure true end-to-end **delivery latency**.
The p99 gate is part of CI. On a modest box, p95 delivery sits in the tens of
milliseconds at the default load.

Throughput is bounded by Postgres: every message is one transaction that
allocates a per-channel seq, writes the message and appends to the channel event
log atomically. Idle WebSocket connections are cheap (the JVM/Netty stack handles
many thousands per node).

## Recommended starting point

- **Small community / launch:** 1 vCPU, 1-2GB RAM, a small Postgres. Comfortable.
- **Growing:** raise the container memory; the heap follows via
  `MaxRAMPercentage`. Watch RSS the way the soak does.

## Scaling out

v1 is **single node**: the realtime backplane is in-process. The designed first
step to multi-node is the Postgres `LISTEN`/`NOTIFY` backplane (the interface is
already a seam), Redis pub/sub after that. Until then, scale vertically - one
well-fed node serves a lot of chat.
