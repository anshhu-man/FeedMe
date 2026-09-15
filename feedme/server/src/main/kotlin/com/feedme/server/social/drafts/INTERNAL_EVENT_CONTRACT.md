# Internal PostDraft change event

`social.post_draft.changed.v1` is a purpose-fixed transactional outbox fact produced by
`PostDraftStore`, aggregate type `post-draft`, schema version 1. Its aggregate ID and version
are the exact draft ID and committed version. It is not a published post, media readiness,
audience grant, notification, feed item or client consent.

The data object has exactly five required fields: `environment` (configured environment),
`ownerId` (verified account UUID), `draftId` (owned draft UUID), `version` (positive integer),
and `state` (`draft` or `discarded`). Identity is internal routing metadata, not public/log data.
No caption, attachment, media identity/URL, circle IDs, credentials or bearer fields are allowed.
Create and each acknowledged edit/discard commit one event alongside the domain row and command
receipt. Exact command replay adds none. A future consumer must use a durable inbox and current
authority; no consumer, publishing adapter or background expiry implementation is enabled here.

Explicit owned discard also clears the draft's private content to the sole internal terminal
format `{}`; only owner/client/version/deletion correlation and exact media cleanup evidence
remain in its tombstone. GET is unavailable, not a fabricated empty PostDraft response. Original
successful command receipts still follow the existing seven-day receipt retention protocol:
this is not a claim of instant erasure of all retained database/backup copies.
