# ADMIN_FLAGS — Feature rollout controls

Server feature flags, platform/version eligibility and rollout percentage; client hiding is never authorization.

- Group: Operations; proposed phase: P1; actor: staff.
- Feature coverage: F38, F44, F50, F54.
- Backend owner: platform; outbox · idempotency · telemetry · flags.
- Layout: Desktop operational work area with queue navigation, evidence, action controls and audit context.
- Entry data: `GET /v1/admin/flags`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Feature ID *: text]
[Rollout percentage *: number]
[Change reason *: text]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Feature ID | text | Yes | F32 |
| Rollout percentage | number | Yes | 5 |
| Change reason | text | Yes | Canary rollout after gates pass |

## Button and action contracts

### ADMIN_FLAGS.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_FLAGS.01 — Stage flag change

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/flags/changes` (`adminProposeFlagChange`).
- Result: Stay / contextual return.
- Effect: Validate dependency gates and bounds; create proposed version requiring configured approver.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_FLAGS.02 — Apply approved change

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/flags/changes/{changeId}/apply` (`adminApplyFlagChange`).
- Result: Stay / contextual return.
- Effect: Atomic flag revision update with audit and rollback pointer.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_FLAGS.03 — Disable selected feature

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/admin/flags/{flagKey}` (`adminUpdateFlag`).
- Result: Stay / contextual return.
- Effect: Kill new entry points and commands while preserving readable existing data and safe exit paths.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **ADMIN_FLAGS.01**: FlagChangeWrite; required body fields: flagKey, baseRevision, enabled, rolloutPercent, reason; body fields available: flagKey, baseRevision, enabled, rolloutPercent, reason; required operation headers: Idempotency-Key.
- **ADMIN_FLAGS.02**: FlagApply; required body fields: approve, reason; body fields available: approve, reason; required operation headers: Idempotency-Key, If-Match.
- **ADMIN_FLAGS.03**: FlagWrite; required body fields: enabled, rolloutPercent, reason; body fields available: enabled, rolloutPercent, reason; required operation headers: Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Server feature flags, platform/version eligibility and rollout percentage; client hiding is never authorization.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
