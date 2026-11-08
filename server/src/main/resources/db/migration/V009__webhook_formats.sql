-- Per-endpoint webhook body format. 'stream' sends getstream.io's own webhook
-- payloads, so a handler written against Stream needs no translation layer;
-- 'firemoot' is the original camelCase envelope ({ type, cid, seq, data }),
-- deprecated and kept only so existing endpoints can migrate. Existing rows keep
-- 'firemoot'; new endpoints default to 'stream'.
alter table webhook_endpoints
  add column format text not null default 'firemoot'
    check (format in ('stream', 'firemoot'));
alter table webhook_endpoints alter column format set default 'stream';
