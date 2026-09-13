# ADMIN_SUBSTITUTION — Reviewed substitutions

Directed ingredient/step transformation with recipe-context limits, equipment/timing effects and review evidence.

- Group: Operations; proposed phase: P1; actor: staff.
- Feature coverage: F10, F13, F51.
- Backend owner: catalog; recipe_revisions · substitutions · recalls.
- Layout: Desktop operational work area with queue navigation, evidence, action controls and audit context.
- Entry data: `GET /v1/admin/substitutions`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Substitution rule *: textarea]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Substitution rule | textarea | Yes | Explicit source, target, limits and changed steps. |

## Button and action contracts

### ADMIN_SUBSTITUTION.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADMIN_HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_SUBSTITUTION.01 — Save substitution draft

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/substitutions` (`adminCreateSubstitution`).
- Result: Stay / contextual return.
- Effect: Version rule and eligibility assertions; no automatic publication.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_SUBSTITUTION.02 — Approve and activate

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/substitutions/{substitutionId}/reviews` (`adminReviewSubstitution`).
- Result: Stay / contextual return.
- Effect: Require qualified independent review, passing constraint fixtures and content hash match.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_SUBSTITUTION.03 — Disable substitution

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/admin/substitutions/{substitutionId}/reviews` (`adminReviewSubstitution`).
- Result: Stay / contextual return.
- Effect: Stop new planning usage; evaluate affected plans and recall independently if required.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **ADMIN_SUBSTITUTION.01**: SubstitutionWrite; required body fields: fromIngredientId, toIngredientId, recipeVersionIds, ratio, preservedTags, requiredStepChanges; body fields available: fromIngredientId, toIngredientId, recipeVersionIds, ratio, preservedTags, requiredStepChanges; required operation headers: Idempotency-Key.
- **ADMIN_SUBSTITUTION.02**: ReviewWrite; required body fields: decision, notes, checks; body fields available: decision, notes, checks; required operation headers: Idempotency-Key, If-Match.
- **ADMIN_SUBSTITUTION.03**: ReviewWrite; required body fields: decision, notes, checks; body fields available: decision, notes, checks; required operation headers: Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Directed ingredient/step transformation with recipe-context limits, equipment/timing effects and review evidence.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
