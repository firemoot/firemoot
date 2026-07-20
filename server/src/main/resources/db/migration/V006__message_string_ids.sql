-- Client-supplied message ids (Stream parity). Message ids become caller-supplied
-- text (an arbitrary string like "cmrxyz_first"), not server-only UUIDs. Existing
-- rows are preserved via a uuid::text cast; server-minted ids stay UUIDv7 strings.
--
-- Every column referencing messages.id must change type in lockstep, so the
-- foreign keys are dropped, all four columns retyped, then the keys re-added
-- (identical cascade semantics). The messages.id primary key and the partial
-- thread index on parent_message_id are rebuilt automatically by the retype.

alter table reactions drop constraint reactions_message_id_fkey;
alter table message_flags drop constraint message_flags_message_id_fkey;
alter table messages drop constraint messages_parent_message_id_fkey;

alter table messages alter column id type text using id::text;
alter table messages alter column parent_message_id type text using parent_message_id::text;
alter table reactions alter column message_id type text using message_id::text;
alter table message_flags alter column message_id type text using message_id::text;

alter table messages
  add constraint messages_parent_message_id_fkey
  foreign key (parent_message_id) references messages (id) on delete cascade;
alter table reactions
  add constraint reactions_message_id_fkey
  foreign key (message_id) references messages (id) on delete cascade;
alter table message_flags
  add constraint message_flags_message_id_fkey
  foreign key (message_id) references messages (id) on delete cascade;
