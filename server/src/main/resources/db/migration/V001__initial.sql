-- Firemoot initial schema (M0.3). Covers the core chat surface from SPEC.md §3.
-- Webhooks, uploads, metrics and settings tables land in later milestone migrations.

-- Users: caller-supplied text ids (Stream parity).
create table users (
  id             text primary key,
  name           text,
  image          text,
  role           text        not null default 'user',
  custom         jsonb       not null default '{}'::jsonb,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  last_active_at timestamptz,
  deleted_at     timestamptz
);

-- Channels: cid = "type:id". current_seq is the per-channel monotonic counter.
create table channels (
  cid             text primary key,
  type            text        not null,
  id              text        not null,
  created_by      text        references users (id) on delete set null,
  custom          jsonb       not null default '{}'::jsonb,
  frozen          boolean     not null default false,
  archived        boolean     not null default false,
  current_seq     bigint      not null default 0,
  last_message_at timestamptz,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz,
  unique (type, id)
);

-- Membership with role and the per-member read pointer.
create table channel_members (
  cid           text        not null references channels (cid) on delete cascade,
  user_id       text        not null references users (id) on delete cascade,
  role          text        not null default 'member'
                  check (role in ('owner', 'moderator', 'member')),
  last_read_seq bigint      not null default 0,
  created_at    timestamptz not null default now(),
  primary key (cid, user_id)
);
create index channel_members_user_idx on channel_members (user_id);

-- Messages. seq is allocated from channels.current_seq in the same transaction.
create table messages (
  id                uuid        primary key,
  cid               text        not null references channels (cid) on delete cascade,
  seq               bigint      not null,
  user_id           text        references users (id) on delete set null,
  type              text        not null default 'regular'
                      check (type in ('regular', 'system')),
  text              text,
  custom            jsonb       not null default '{}'::jsonb,
  attachments       jsonb       not null default '[]'::jsonb,
  parent_message_id uuid        references messages (id) on delete cascade,
  reply_count       integer     not null default 0,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  deleted_at        timestamptz,
  text_search       tsvector generated always as (to_tsvector('simple', coalesce(text, ''))) stored,
  unique (cid, seq)
);
create index messages_thread_idx on messages (parent_message_id)
  where parent_message_id is not null;
create index messages_text_search_idx on messages using gin (text_search);

-- Persisted event log: the replay source for WebSocket resume (seq > last_seen).
create table channel_events (
  cid        text        not null references channels (cid) on delete cascade,
  seq        bigint      not null,
  type       text        not null,
  payload    jsonb       not null,
  created_at timestamptz not null default now(),
  primary key (cid, seq)
);

-- Reactions.
create table reactions (
  message_id uuid        not null references messages (id) on delete cascade,
  user_id    text        not null references users (id) on delete cascade,
  type       text        not null,
  created_at timestamptz not null default now(),
  primary key (message_id, user_id, type)
);

-- Server SDK API key + secret pairs.
create table api_keys (
  id         text        primary key,
  secret     text        not null,
  created_at timestamptz not null default now(),
  revoked_at timestamptz
);
