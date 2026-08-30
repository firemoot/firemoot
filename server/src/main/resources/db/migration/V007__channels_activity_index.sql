-- Top-N "recent activity" ordering for channel queries. Without it the
-- filter-agnostic channels statement seq-scans and sorts the whole table on
-- every call - O(total channels), ~534ms at 63k rows on the CI instance.
-- `if not exists` because the index was created concurrently on the live CI
-- database ahead of this migration shipping.
create index if not exists channels_activity_idx
  on channels (coalesce(last_message_at, created_at) desc, cid desc)
  where deleted_at is null;
