# ADMIN_CASE — Resolve a moderation case

Evidence, rule mapping, prior decisions and appeal history. Moderator decisions include justification and reversible content actions where possible.

- Group: Operations; proposed phase: P1; actor: staff.
- Feature coverage: F42, F52.
- Backend owner: safety; reports · blocks · mutes · reviews · audit_log.
- Layout: Desktop operational work area with queue navigation, evidence, action controls and audit context.
- Entry data: `GET /v1/admin/cases/{caseId}`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Decision reason *: textarea]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Decision reason | textarea | Yes | Applicable rule and evidence. |

## Button and action contracts

### ADMIN_CASE.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_REPORTS.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_CASE.01 — Remove reported content

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `POST /v1/admin/reports/{reportId}/actions` (`adminActOnReport`).
- Result: Stay / contextual return.
- Effect: Apply documented action enum with evidence, target version and audit; revoke new delivery and enqueue purge.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_CASE.02 — Dismiss report

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/reports/{reportId}/actions` (`adminActOnReport`).
- Result: Stay / contextual return.
- Effect: Record no-action decision with reason; disclose only permitted outcome to reporter.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_CASE.03 — Restrict account

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `POST /v1/admin/accounts/{accountId}/restrictions` (`adminRestrictAccount`).
- Result: Stay / contextual return.
- Effect: Enforce high-impact permission/separate approver threshold and audit.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_CASE.04 — Review appeal

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/reports/{reportId}/appeals` (`adminRecordAppeal`).
- Result: Stay / contextual return.
- Effect: Assign to a different authorized reviewer; preserve original evidence and outcome history.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_CASE.05 — Back to queue

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_REPORTS.
- Effect: Preserve the current draft and open the destination.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **ADMIN_CASE.01**: CaseWrite; required body fields: action, reasonCode, notes; body fields available: action, reasonCode, notes, assigneeId; required operation headers: Idempotency-Key.
- **ADMIN_CASE.02**: CaseWrite; required body fields: action, reasonCode, notes; body fields available: action, reasonCode, notes, assigneeId; required operation headers: Idempotency-Key.
- **ADMIN_CASE.03**: RestrictionWrite; required body fields: restriction, reasonCode, notes; body fields available: restriction, expiresAt, reasonCode, notes; required operation headers: Idempotency-Key.
- **ADMIN_CASE.04**: AppealWrite; required body fields: reason; body fields available: reason, evidenceText; required operation headers: Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Evidence, rule mapping, prior decisions and appeal history. Moderator decisions include justification and reversible content actions where possible.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
