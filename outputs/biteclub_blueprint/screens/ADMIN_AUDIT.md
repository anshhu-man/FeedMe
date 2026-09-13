# ADMIN_AUDIT — Audit trail

Append-only records with actor/purpose/action/target and redacted payload metadata; read access itself audited.

- Group: Operations; proposed phase: P1; actor: staff.
- Feature coverage: F49, F51, F52, F54.
- Backend owner: safety; reports · blocks · mutes · reviews · audit_log.
- Layout: Desktop operational work area with queue navigation, evidence, action controls and audit context.
- Entry data: `GET /v1/admin/audit`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Target or trace ID: text]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Target or trace ID | text | No |  |

## Button and action contracts

### ADMIN_AUDIT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_AUDIT.01 — Find records

- Visibility: When this screen and actor role are eligible.
- Trigger: `GET /v1/admin/audit` (`adminListAudit`).
- Result: Stay / contextual return.
- Effect: Cursor-based role-scoped query; no raw access tokens, passwords or food-profile payloads.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Read only eligible cache; show staleness and do not infer current permissions.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_AUDIT.02 — Request scoped export

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/audit/exports` (`adminExportAudit`).
- Result: Stay / contextual return.
- Effect: Require approved operational purpose and elevated role; encrypt short-lived output.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **ADMIN_AUDIT.01**: No JSON body; required body fields: none; body fields available: none; required operation headers: see security contract.
- **ADMIN_AUDIT.02**: AuditExportWrite; required body fields: from, to, reason; body fields available: from, to, targetType, reason; required operation headers: Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Append-only records with actor/purpose/action/target and redacted payload metadata; read access itself audited.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
