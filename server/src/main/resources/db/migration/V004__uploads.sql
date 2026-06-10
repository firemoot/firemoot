-- Media uploads (M2.1). A row is created when an upload is presigned (pending),
-- advanced to stored once the client confirms the PUT, and thumbnailed after the
-- worker generates a thumbnail (M2.3). Generic S3 only - no vendor admin APIs.

create table uploads (
  id         uuid        primary key default gen_random_uuid(),
  user_id    text        references users (id) on delete set null,
  object_key text        not null,
  mime       text        not null,
  size_bytes bigint      not null,
  status     text        not null default 'pending'
               check (status in ('pending', 'stored', 'thumbnailed')),
  thumb_key  text,
  created_at timestamptz not null default now()
);

create index uploads_status_idx on uploads (status);
