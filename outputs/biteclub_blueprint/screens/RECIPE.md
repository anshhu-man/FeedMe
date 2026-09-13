# RECIPE — Recipe and practical details

Versioned ingredients and steps, servings, equipment, active/total time, source and reviewed status. Missing ingredients remain explicit.

- Group: Kitchen; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F01, F11, F12, F13, F19.
- Backend owner: catalog; recipe_revisions · substitutions · recalls.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/recipes/{recipeId}/versions/{recipeVersionId}`.
- Context: Recipe context may be a licensed catalog revision, immutable owned save or reviewed plan variant. Read the matching owner/rights endpoint; in picker mode return the selected reference instead of starting cooking. Third-party private copies do not imply redistribution rights.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### RECIPE.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECOMMENDATIONS.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.01 — Start cooking

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/cook-sessions` (`createCookSession`).
- Result: COOK.
- Effect: Pin recipe revision and chosen servings; ensure no active recall. Create idempotent session.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Allowed for previously cached, unrevoked pinned revision; private step events queue with revision.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.02 — Make Mine

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADAPT.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.03 — Save recipe

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/saved-recipes` (`saveRecipe`).
- Result: COOKBOOK.
- Effect: Create or return a private save from an authorized plan or reviewed recipeVersionId. Adding to a named collection is a separate command; a save alone is not positive taste feedback.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.04 — Share this meal

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CAPTURE.
- Effect: Start unpublished draft with known recipe revision; no automatic post.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.05 — View inspiration

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: POST.
- Effect: Open source only if it remains authorized; otherwise show attribution text without media.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.06 — An ingredient is unavailable

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADAPT.
- Effect: Preselect Keep the Vibe swap intent for the selected unavailable ingredient; never mutate already completed cooking steps.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.07 — Select this recipe

- Visibility: picker mode only.
- Trigger: `LOCAL pickerResult`.
- Result: Stay / contextual return; conditional: REVIEW_ATTACHMENT when attachment or request response; SOS_DETAIL when SOS suggestion; PACT_CREATE when pact source; POLL_CREATE when poll choice.
- Effect: Picker-only selection validates applicable source rights and returns the chosen reference to the requesting draft.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.08 — Cancel selection

- Visibility: picker mode only.
- Trigger: `LOCAL pickerCancel`.
- Result: Stay / contextual return; conditional: ATTACH_RECIPE when attachment or response; SOS_DETAIL when SOS suggestion; PACT_CREATE when pact source; POLL_CREATE when poll choice.
- Effect: Return to picker.cancelTo with the original source selection unchanged.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.09 — Share a shortcut

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: SHORTCUT_CREATE.
- Effect: Retain the authorized recipeVersionId and begin a separate original-text tip draft; unreviewed tips never alter reviewed steps.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE.10 — View a shared shortcut

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: SHORTCUT_DETAIL.
- Effect: Open an explicitly selected accessible shortcut reference for this recipe. If no tip is available, show the detail empty state rather than inventing a shortcut ID.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- RECIPE.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- RECIPE.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- RECIPE.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- RECIPE.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- RECIPE.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **RECIPE.01**: CookStart; required body fields: planId; body fields available: planId, deviceSequence; required operation headers: Idempotency-Key.
- **RECIPE.03**: SaveRecipeRequest; required body fields: none; body fields available: planId, recipeVersionId, title, collectionId, markMakeAgain; required operation headers: Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Versioned ingredients and steps, servings, equipment, active/total time, source and reviewed status. Missing ingredients remain explicit.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
