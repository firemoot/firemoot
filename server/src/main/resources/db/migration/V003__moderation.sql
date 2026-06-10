-- Moderation queue (M1.11). A flag records a report against a message; the
-- queue is surfaced in the admin dashboard (M3). Flagging also emits a
-- user.flagged webhook event, so external moderation tooling is notified.

create table message_flags (
  id         uuid        primary key default gen_random_uuid(),
  message_id uuid        not null references messages (id) on delete cascade,
  cid        text        not null references channels (cid) on delete cascade,
  flagged_by text        not null,
  reason     text,
  status     text        not null default 'open'
               check (status in ('open', 'reviewed', 'dismissed')),
  created_at timestamptz not null default now()
);

create index message_flags_status_idx on message_flags (status, created_at);
