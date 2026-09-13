# HOUSEHOLD_PREFS — What works for this meal group

Shared equipment and serving defaults with individual requirement consent. Do not display a member’s private exclusion reasons.

- Group: Membership; proposed phase: P3; actor: member.
- Feature coverage: F44.
- Backend owner: commerce; households · packs · entitlements · purchase_events.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/households/{householdId}/preferences`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Default servings *: number]
[Shared equipment *: text]
[Use my selected requirements for household meal planning: checkbox]
[My selected requirements to share: text]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Default servings | number | Yes | 2 |
| Shared equipment | text | Yes | Stove and pan |
| Use my selected requirements for household meal planning | checkbox | No | false |
| My selected requirements to share | text | No |  |

## Button and action contracts

### HOUSEHOLD_PREFS.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: HOUSEHOLDS.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### HOUSEHOLD_PREFS.01 — Save shared defaults

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/households/{householdId}/preferences` (`updateHouseholdKitchen`).
- Result: HOUSEHOLDS.
- Effect: Update owner/admin-writable defaults; private member constraints are composed server-side for a requested meal.
- Authorization: member; Only household owner/admin may change shared equipment and servings. This does not edit another member’s private requirements.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### HOUSEHOLD_PREFS.02 — Plan a household meal

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REQUEST.
- Effect: Use current household context and revision; fall back to individual planning when membership is invalid.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### HOUSEHOLD_PREFS.03 — Save my sharing choice

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/households/{householdId}/preferences/me` (`updateHouseholdPreferences`).
- Result: Stay / contextual return.
- Effect: Actor updates only own selected requirement IDs and explicit consent for shared planning; unchecked consent withdraws sharing. Show generic group infeasibility without revealing individual causes.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- HOUSEHOLD_PREFS.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- HOUSEHOLD_PREFS.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- HOUSEHOLD_PREFS.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- HOUSEHOLD_PREFS.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- HOUSEHOLD_PREFS.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **HOUSEHOLD_PREFS.01**: HouseholdKitchenWrite; required body fields: equipmentIds, defaultServings; body fields available: equipmentIds, defaultServings; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **HOUSEHOLD_PREFS.03**: HouseholdPreferenceWrite; required body fields: sharedWithHousehold; body fields available: sharedWithHousehold, excludedIngredientIds, dietaryPatterns, dislikedIngredientIds; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Shared equipment and serving defaults with individual requirement consent. Do not display a member’s private exclusion reasons.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
