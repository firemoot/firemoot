-- Metrics and admin settings (M3). Raw facts feed MAU/DAU/WAU and are pruned
-- after rollup; daily/hourly rollups retain the dashboard's history; settings
-- holds install metadata and the admin password hash (M3.4).

-- One row per user per day they were active (idempotent capture).
create table activity_facts (
  day     date not null,
  user_id text not null,
  primary key (day, user_id)
);

-- Concurrent-connection samples (every 60s); rolled up to hourly p95/max, pruned.
create table ccu_samples (
  ts    timestamptz not null primary key,
  value integer     not null
);

create table metrics_hourly (
  ts     timestamptz      not null,
  metric text             not null,
  labels jsonb            not null default '{}'::jsonb,
  value  double precision not null,
  primary key (ts, metric, labels)
);

create table metrics_daily (
  day    date             not null,
  metric text             not null,
  labels jsonb            not null default '{}'::jsonb,
  value  double precision not null,
  primary key (day, metric, labels)
);

create table settings (
  key   text  primary key,
  value jsonb not null
);
