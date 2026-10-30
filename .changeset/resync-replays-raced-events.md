---
"@firemoot/client": patch
---

`Channel.watch()` and `resync()` now resume from the older of the channel snapshot and the newest loaded message. Previously an event that landed between the two parallel requests - another member's read receipt, a member change, a channel update - could be overwritten by the rebuilt state and then never replayed, leaving that receipt or member list stale until its next change.
