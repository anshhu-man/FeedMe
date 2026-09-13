# VIDEO_EDIT — A little food clip

Short clip trim, caption and accessible text, with duration/size limits visible. Processing states block publication until ready.

- Group: Publishing; proposed phase: P3; actor: member.
- Feature coverage: F38.
- Backend owner: social; post_drafts · posts · media · save_grants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `LOCAL videoDraft`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Caption: textarea]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Caption | textarea | No | Made this work. |

## Button and action contracts

### VIDEO_EDIT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CAPTURE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### VIDEO_EDIT.01 — Choose clip

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OS.photoPickerVideo`.
- Result: Stay / contextual return.
- Effect: Use limited picker; enforce configured duration/size/codec bounds before upload.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### VIDEO_EDIT.02 — Trim clip

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL update`.
- Result: Stay / contextual return.
- Effect: Save non-destructive trim range locally; show final duration and upload estimate.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### VIDEO_EDIT.03 — Upload and process

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/media` (`prepareMediaUpload`).
- Result: PUBLISH_STATUS.
- Effect: Obtain intent, upload into quarantine, complete intent and transcode/scan asynchronously with stable media ID.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### VIDEO_EDIT.04 — Attach recipe

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ATTACH_RECIPE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### VIDEO_EDIT.05 — Use a photo instead

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CAPTURE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- VIDEO_EDIT.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- VIDEO_EDIT.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- VIDEO_EDIT.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- VIDEO_EDIT.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- VIDEO_EDIT.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **VIDEO_EDIT.03**: MediaPrepare; required body fields: kind, contentType, bytes, sha256, clientDraftId; body fields available: kind, contentType, bytes, sha256, durationSeconds, clientDraftId; required operation headers: X-Device-Session, Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Short clip trim, caption and accessible text, with duration/size limits visible. Processing states block publication until ready.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
