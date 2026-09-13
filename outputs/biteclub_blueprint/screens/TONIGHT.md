# TONIGHT — Make a save into dinner

Saved recipe checked against current pantry confidence, time and effort; show missing ingredients before starting.

- Group: Personal; proposed phase: P2; actor: guest-or-member.
- Feature coverage: F33.
- Backend owner: planning; plan_requests · plan_variants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/saved-recipes/{savedRecipeId}`, `GET /v1/preferences`, `GET /v1/pantry/items`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Time tonight *: number]
[Effort tonight *: select]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Time tonight | number | Yes | 15 |
| Effort tonight | select | Yes | Assemble only; A little cooking; Happy to cook |

## Button and action contracts

### TONIGHT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: COOKBOOK.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TONIGHT.01 — Check tonight’s version

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/plans` (`createPlan`).
- Result: VARIANT.
- Effect: Create plan from authorized saved recipe and fresh constraints; respect recall and current exclusions.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TONIGHT.02 — Confirm ingredients

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: PANTRY.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TONIGHT.03 — Back to my saves

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: COOKBOOK.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- TONIGHT.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- TONIGHT.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- TONIGHT.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- TONIGHT.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- TONIGHT.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **TONIGHT.01**: PlanRequest; required body fields: mode, constraints; body fields available: mode, sourceRecipeVersionId, sourcePostId, savedRecipeId, constraints, preferenceVersion, naturalLanguage, confirmedInterpretation, intent, baseMeal; required operation headers: Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Saved recipe checked against current pantry confidence, time and effort; show missing ingredients before starting.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
