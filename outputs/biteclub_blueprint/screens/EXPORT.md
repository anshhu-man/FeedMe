# EXPORT — Export your FeedMe data

Recent-auth export request with private-data scope and status. Package excludes other people’s private media/messages beyond policy.

- Group: Settings; proposed phase: P1; actor: member.
- Feature coverage: F49.
- Backend owner: profile; profiles · preferences · account_jobs.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/jobs/{jobId}`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### EXPORT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: PRIVACY.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### EXPORT.01 — Request export

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/account/exports` (`requestAccountExport`).
- Result: Stay / contextual return.
- Effect: Create one active export job; snapshot owned data, encrypt output and expire download URL.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### EXPORT.02 — Check export status

- Visibility: When this screen and actor role are eligible.
- Trigger: `GET /v1/jobs/{jobId}` (`getJob`).
- Result: Stay / contextual return.
- Effect: Return authorized job state and bounded download capability when ready.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Read only eligible cache; show staleness and do not infer current permissions.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### EXPORT.03 — Download my export

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OS.downloadAuthorizedExport`.
- Result: Stay / contextual return.
- Effect: Obtain fresh short-lived export URL after reauth and save via native user file picker.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- EXPORT.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- EXPORT.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- EXPORT.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- EXPORT.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- EXPORT.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **EXPORT.01**: ExportRequest; required body fields: format, includeMedia; body fields available: format, includeMedia; required operation headers: X-Device-Session, Idempotency-Key.
- **EXPORT.02**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Recent-auth export request with private-data scope and status. Package excludes other people’s private media/messages beyond policy.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
