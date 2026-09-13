# ADMIN_INCIDENT — Service and incident controls

Read current service health and runbook references. High-impact commands require on-call role, reason and approval policy.

- Group: Operations; proposed phase: P1; actor: staff.
- Feature coverage: F54.
- Backend owner: platform; outbox · idempotency · telemetry · flags.
- Layout: Desktop operational work area with queue navigation, evidence, action controls and audit context.
- Entry data: `GET /v1/admin/health`, `GET /v1/admin/incidents`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Operational reason *: textarea]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Operational reason | textarea | Yes | Incident reference and scoped action. |

## Button and action contracts

### ADMIN_INCIDENT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_INCIDENT.01 — Refresh service health

- Visibility: When this screen and actor role are eligible.
- Trigger: `GET /v1/admin/health` (`adminGetHealth`).
- Result: Stay / contextual return.
- Effect: Return aggregated dependency/queue/reconciliation status with no sensitive user payloads.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Read only eligible cache; show staleness and do not infer current permissions.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_INCIDENT.02 — Pause media publication

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/operations/media-pause` (`adminPauseMedia`).
- Result: Stay / contextual return.
- Effect: Set audited kill switch for new publication; retain user drafts and safe status UI.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_INCIDENT.03 — Request outbox replay

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/operations/replays` (`adminReplayEvents`).
- Result: Stay / contextual return.
- Effect: Replay bounded event range using consumer idempotency; never blindly re-send notifications or charges.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_INCIDENT.04 — Open release flags

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_FLAGS.
- Effect: Preserve the current draft and open the destination.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_INCIDENT.05 — Inspect audit

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_AUDIT.
- Effect: Preserve the current draft and open the destination.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **ADMIN_INCIDENT.01**: No JSON body; required body fields: none; body fields available: none; required operation headers: see security contract.
- **ADMIN_INCIDENT.02**: MediaPause; required body fields: paused, reason; body fields available: paused, reason; required operation headers: Idempotency-Key.
- **ADMIN_INCIDENT.03**: ReplayRequest; required body fields: consumer, eventIds, dryRun, reason; body fields available: consumer, eventIds, dryRun, reason; required operation headers: Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Read current service health and runbook references. High-impact commands require on-call role, reason and approval policy.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
