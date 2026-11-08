---
"@firemoot/stream-compat": minor
---

`normalizeWebhookEvent()` now rebuilds the server's new `user.unread_message_reminder` webhook in Stream's shape: `user`, plus `channels` keyed by cid, each with the member list nested in `channel` and its newest messages carrying `user.id`. A reminder handler written against Stream reads it unchanged.
