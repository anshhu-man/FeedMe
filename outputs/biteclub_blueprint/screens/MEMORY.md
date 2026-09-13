# MEMORY — What works for you

Readable explicit preferences with their source and last change; filter by taste or preparation.

- Group: Personal; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F16, F18, F39.
- Backend owner: memory; feedback · memories · collections · recipe_copies.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/memories`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### MEMORY.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: SETTINGS.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### MEMORY.01 — Inspect a memory

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: MEMORY_DETAIL.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### MEMORY.02 — Edit food preferences

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: FOOD_PREFS.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### MEMORY.03 — Pause personalization

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/preferences` (`updatePreferences`).
- Result: Stay / contextual return.
- Effect: Disable learned ranking while keeping explicit exclusions active; explain retained records.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### MEMORY.04 — Return to saved meals

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

- MEMORY.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- MEMORY.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- MEMORY.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- MEMORY.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- MEMORY.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **MEMORY.03**: PreferencePatch; required body fields: none; body fields available: hardExcludedIngredientIds, dietaryPatterns, dislikedIngredientIds, equipmentIds, preferredTasteTags, defaultEnergy, consentVersion, defaultServings; required operation headers: Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Readable explicit preferences with their source and last change; filter by taste or preparation.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
