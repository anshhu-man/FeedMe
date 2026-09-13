# PUBLISH_STATUS — Review and share

Preflight summary, explicit publish button, upload/transcode/moderation progress, and final server receipt. Draft is never mistaken for a live post.

- Group: Publishing; proposed phase: P1; actor: member.
- Feature coverage: F21, F24, F31, F38.
- Backend owner: social; post_drafts · posts · media · save_grants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/post-drafts/{draftId}`, `GET /v1/media/{mediaId}`.
- Context: Unpublished draft starts review mode. Upload pipeline: POST /v1/media → signed native upload → POST /v1/media/{mediaId}/complete → GET status with bounded backoff → explicit POST /v1/posts. No upload or status callback auto-publishes.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### PUBLISH_STATUS.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: EDIT_MEDIA.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PUBLISH_STATUS.01 — Publish to selected audience

- Visibility: draft mode only.
- Trigger: `POST /v1/posts` (`publishPost`).
- Result: POST; conditional: PUBLISH_STATUS when media processing or review pending; UNAVAILABLE when source/audience permission revoked.
- Effect: Require ready sanitized media and validated draft; publish once using stable idempotency key, create placements/grant/outbox atomically.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PUBLISH_STATUS.02 — Retry upload

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/media` (`prepareMediaUpload`).
- Result: Stay / contextual return.
- Effect: Reuse draft asset checksum and upload intent; obtain bounded signed upload URL. Complete and poll media state before enabling publish.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PUBLISH_STATUS.03 — Check media status

- Visibility: When this screen and actor role are eligible.
- Trigger: `GET /v1/media/{mediaId}` (`getMediaStatus`).
- Result: Stay / contextual return.
- Effect: Refresh processing state with bounded backoff, then reveal eligible publish action.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Read only eligible cache; show staleness and do not infer current permissions.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PUBLISH_STATUS.04 — Edit before sharing

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: EDIT_MEDIA.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PUBLISH_STATUS.05 — Save draft and leave

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: HOME.
- Effect: Keep unpublished state and resume card; no background auto-publication.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- PUBLISH_STATUS.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- PUBLISH_STATUS.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- PUBLISH_STATUS.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- PUBLISH_STATUS.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- PUBLISH_STATUS.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **PUBLISH_STATUS.01**: PostWrite; required body fields: caption, mediaIds, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, clientDraftId; body fields available: caption, mediaIds, audience, keepOnPlate, attachment, allowRecipeSaves, saveDisclosureVersion, sourcePostId, clientDraftId, draftId, draftVersion, altText; required operation headers: X-Device-Session, Idempotency-Key.
- **PUBLISH_STATUS.02**: MediaPrepare; required body fields: kind, contentType, bytes, sha256, clientDraftId; body fields available: kind, contentType, bytes, sha256, durationSeconds, clientDraftId; required operation headers: X-Device-Session, Idempotency-Key.
- **PUBLISH_STATUS.03**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Preflight summary, explicit publish button, upload/transcode/moderation progress, and final server receipt. Draft is never mistaken for a live post.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
