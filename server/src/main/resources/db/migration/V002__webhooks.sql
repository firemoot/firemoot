-- Outbound webhooks (M1.10). Persisted channel events are fanned out to
-- registered endpoints through a durable delivery queue consumed FOR UPDATE
-- SKIP LOCKED; rows that exhaust their retries become dead letters.

create table webhook_endpoints (
  id         text        primary key,
  url        text        not null,
  secret     text        not null,
  enabled    boolean     not null default true,
  created_at timestamptz not null default now()
);

create table webhook_deliveries (
  id              uuid        primary key default gen_random_uuid(),
  endpoint_id     text        not null references webhook_endpoints (id) on delete cascade,
  event           jsonb       not null,
  attempts        integer     not null default 0,
  status          text        not null default 'pending'
                    check (status in ('pending', 'processing', 'delivered', 'dead')),
  next_attempt_at timestamptz not null default now(),
  last_error      text,
  created_at      timestamptz not null default now()
);

-- The worker scans for due pending rows by next_attempt_at.
create index webhook_deliveries_due_idx on webhook_deliveries (next_attempt_at)
  where status = 'pending';
