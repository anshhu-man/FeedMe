# ADMIN_PACK — Manage reviewed packs

Versioned pack recipe manifest, review evidence and store product mapping; existing purchase access survives SKU rename.

- Group: Operations; proposed phase: P1; actor: staff.
- Feature coverage: F46, F50, F51.
- Backend owner: commerce; households · packs · entitlements · purchase_events.
- Layout: Desktop operational work area with queue navigation, evidence, action controls and audit context.
- Entry data: `GET /v1/admin/packs`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Pack title *: text]
[Store product mapping *: text]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Pack title | text | Yes | Small kitchen evenings |
| Store product mapping | text | Yes | feedme.pack.small_kitchen |

## Button and action contracts

### ADMIN_PACK.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_PACK.01 — Save pack draft

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/packs` (`adminCreatePack`).
- Result: Stay / contextual return.
- Effect: Validate canonical product IDs and approved recipe revision references.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_PACK.02 — Publish reviewed pack

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/packs/{packId}/publish` (`adminPublishPack`).
- Result: Stay / contextual return.
- Effect: Require catalog approval, entitlement mapping validation and store metadata readiness.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_PACK.03 — Withdraw pack from sale

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/packs/{packId}/withdraw` (`adminWithdrawPack`).
- Result: Stay / contextual return.
- Effect: Stop new offering; preserve owned access except separately recalled content.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **ADMIN_PACK.01**: PackWrite; required body fields: title, description, recipeVersionIds; body fields available: title, description, recipeVersionIds, requiredEntitlement, storeProductIds; required operation headers: Idempotency-Key.
- **ADMIN_PACK.02**: Empty; required body fields: none; body fields available: none; required operation headers: Idempotency-Key, If-Match.
- **ADMIN_PACK.03**: StaffReason; required body fields: reasonCode, notes; body fields available: reasonCode, notes; required operation headers: Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Versioned pack recipe manifest, review evidence and store product mapping; existing purchase access survives SKU rename.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
