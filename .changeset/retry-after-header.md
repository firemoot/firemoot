---
"@firemoot/core": patch
---

`Problem` gains an optional `retryAfter` (whole seconds), set on `429` responses alongside the new `Retry-After` header.
