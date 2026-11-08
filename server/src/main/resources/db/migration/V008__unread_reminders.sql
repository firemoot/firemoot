-- Unread-message reminders (the user.unread_message_reminder webhook).
-- reminded_seq is the channel seq a member has been reminded up to: one reminder
-- covers every unread message at or below it, so each unread burst is reminded
-- once and the next message re-arms it. A constant default makes this a
-- metadata-only change, with no table rewrite.
alter table channel_members add column reminded_seq bigint not null default 0;
