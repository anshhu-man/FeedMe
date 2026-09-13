# FOOD_PREFS — Food preferences

Editable food choices, exclusions and dislikes. Explain these filter suggestions without guaranteeing preparation safety.

- Group: Kitchen; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F04, F39, F48.
- Backend owner: profile; profiles · preferences · account_jobs.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/preferences`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Eating preference: select]
[Ingredient exclusions: text]
[Dislikes: text]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Eating preference | select | No | Flexible; Vegetarian; Vegan |
| Ingredient exclusions | text | No |  |
| Dislikes | text | No |  |

## Button and action contracts

### FOOD_PREFS.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: SETTINGS.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### FOOD_PREFS.01 — Save preferences

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/preferences` (`updatePreferences`).
- Result: EQUIPMENT; conditional: SETTINGS when editing outside onboarding; EQUIPMENT when first setup.
- Effect: Persist structured IDs with preference revision. Invalidate stale plans and ask before replacing a currently cooking plan.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### FOOD_PREFS.02 — Skip for now

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: HOME.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- FOOD_PREFS.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- FOOD_PREFS.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- FOOD_PREFS.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- FOOD_PREFS.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- FOOD_PREFS.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **FOOD_PREFS.01**: PreferencePatch; required body fields: none; body fields available: hardExcludedIngredientIds, dietaryPatterns, dislikedIngredientIds, equipmentIds, preferredTasteTags, defaultEnergy, consentVersion, defaultServings; required operation headers: Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Editable food choices, exclusions and dislikes. Explain these filter suggestions without guaranteeing preparation safety.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
