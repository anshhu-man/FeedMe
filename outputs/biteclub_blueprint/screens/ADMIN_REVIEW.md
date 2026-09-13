# ADMIN_REVIEW — Review and publish

Review checklist, evidence and immutable diff; reviewer identity, credentials and separation of duties are recorded.

- Group: Operations; proposed phase: P1; actor: staff.
- Feature coverage: F13, F51.
- Backend owner: catalog; recipe_revisions · substitutions · recalls.
- Layout: Desktop operational work area with queue navigation, evidence, action controls and audit context.
- Entry data: `GET /v1/admin/recipes/{recipeId}/versions/{recipeVersionId}`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Review decision *: select]
[Review notes *: textarea]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Review decision | select | Yes | Approve; Request changes; Reject |
| Review notes | textarea | Yes | Evidence and tested preparation results. |

## Button and action contracts

### ADMIN_REVIEW.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_REVIEW.01 — Record review decision

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/recipes/{recipeId}/versions/{recipeVersionId}/reviews` (`adminReviewRecipe`).
- Result: Stay / contextual return.
- Effect: Authorize reviewer independent from author; bind decision to immutable revision hash and scope.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_REVIEW.02 — Publish approved revision

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/recipes/{recipeId}/versions/{recipeVersionId}/publish` (`adminPublishRecipe`).
- Result: Stay / contextual return.
- Effect: Publisher checks required approvals, version and embargo; transaction publishes catalog event.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_REVIEW.03 — Recall revision

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `POST /v1/admin/recipes/{recipeId}/versions/{recipeVersionId}/recall` (`adminRecallRecipe`).
- Result: Stay / contextual return.
- Effect: Privileged confirmed action deactivates selection, invalidates dependent plans and emits recall events for saved/cached use.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_REVIEW.04 — Return to draft

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_RECIPE.
- Effect: Preserve the current draft and open the destination.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **ADMIN_REVIEW.01**: ReviewWrite; required body fields: decision, notes, checks; body fields available: decision, notes, checks; required operation headers: Idempotency-Key, If-Match.
- **ADMIN_REVIEW.02**: Empty; required body fields: none; body fields available: none; required operation headers: Idempotency-Key, If-Match.
- **ADMIN_REVIEW.03**: ReviewWrite; required body fields: decision, notes, checks; body fields available: decision, notes, checks; required operation headers: Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Review checklist, evidence and immutable diff; reviewer identity, credentials and separation of duties are recorded.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
