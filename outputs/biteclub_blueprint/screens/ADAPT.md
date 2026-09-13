# ADAPT — Make Mine

Compare source ingredients against your confirmed ingredients and explicit constraints. Hard exclusions remain fixed.

- Group: Kitchen; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F01, F05, F10.
- Backend owner: planning; plan_requests · plan_variants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/plans/{planId}`, `GET /v1/preferences`, `GET /v1/pantry/items`.
- Context: If opened from a social/catalog recipe with no planId, retain authorized source reference and collect constraints. The Make my version command first creates a source-bound plan via POST /v1/plans, then calls adaptations with the returned planId (or consumes the already adapted response). Do not GET an invented plan ID.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[What should change? *: textarea]
[Time available *: number]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| What should change? | textarea | Yes | Use what I have; keep it easy. |
| Time available | number | Yes | 10 |

## Button and action contracts

### ADAPT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECIPE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADAPT.01 — Make my version

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/plans/{planId}/adaptations` (`adaptPlan`).
- Result: VARIANT; conditional: UNAVAILABLE when no reviewed compatible variant.
- Effect: Validate source access and revision, run deterministic eligibility and substitutions; return change list and reasons.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADAPT.02 — Edit ingredients

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: PANTRY.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADAPT.03 — Edit preferences

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: FOOD_PREFS.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADAPT.04 — Keep original

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECIPE.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADAPT.05 — Change effort

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: EFFORT.
- Effect: Edit the adaptation draft and return to ADAPT with constraints preserved.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADAPT.06 — Change equipment

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: EQUIPMENT.
- Effect: Edit current equipment/defaults explicitly; return to ADAPT and recompute affected eligibility.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADAPT.07 — Change taste

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: TASTE.
- Effect: Edit optional sensory intent; maintain all hard constraints.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- ADAPT.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- ADAPT.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- ADAPT.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- ADAPT.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- ADAPT.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **ADAPT.01**: AdaptRequest; required body fields: constraints, reason; body fields available: constraints, replaceIngredientId, requestedReplacementId, retainTasteTag, reason, simplificationGoal, allowDifferentMeal, excludeRecipeVersionIds, continuationCursor; required operation headers: Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Compare source ingredients against your confirmed ingredients and explicit constraints. Hard exclusions remain fixed.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
