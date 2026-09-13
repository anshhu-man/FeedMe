# RECIPE_REQUEST — Ask for the recipe

Prefilled private request with optional edit; author can answer with known recipe or details.

- Group: Social; proposed phase: P1; actor: member.
- Feature coverage: F29.
- Backend owner: conversations; threads · messages · reactions · notifications.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/posts/{postId}`, `GET /v1/recipe-requests/{recipeRequestId}`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Request *: textarea]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Request | textarea | Yes | Could you share the recipe? |

## Button and action contracts

### RECIPE_REQUEST.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: POST.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE_REQUEST.01 — Send request

- Visibility: requester mode only.
- Trigger: `POST /v1/recipe-requests` (`requestRecipe`).
- Result: THREAD.
- Effect: Check post access and block rules, coalesce duplicate pending request, create/find private thread.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE_REQUEST.02 — Browse related reviewed meals

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REQUEST.
- Effect: Use explicit dish text as inspiration; do not claim reproduction of photographed dish.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE_REQUEST.03 — Cancel

- Visibility: requester mode only.
- Trigger: `LOCAL navigate`.
- Result: POST.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE_REQUEST.04 — Answer with a recipe

- Visibility: fulfillRequest mode only.
- Trigger: `LOCAL navigate`.
- Result: ATTACH_RECIPE.
- Effect: Select a redistributable existing recipe for this private request. Preserve recipeRequestId and return from attachment review before an explicit response is sent.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE_REQUEST.05 — Send recipe response

- Visibility: fulfillRequest confirmed mode only.
- Trigger: `POST /v1/recipe-requests/{recipeRequestId}/response` (`respondToRecipeRequest`).
- Result: THREAD.
- Effect: Author resolves request with decision=fulfill and the explicitly reviewed eligible recipeVersionId; recheck requester eligibility and redistribution rights. Direct viewing never creates a recipe-copy grant.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE_REQUEST.06 — Decline request

- Visibility: fulfillRequest mode only.
- Trigger: `POST /v1/recipe-requests/{recipeRequestId}/response` (`respondToRecipeRequest`).
- Result: THREAD.
- Effect: Author sends decision=decline, closing the request privately without obligation to explain.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE_REQUEST.07 — Cancel my request

- Visibility: requester mode only.
- Trigger: `DELETE /v1/recipe-requests/{recipeRequestId}` (`cancelRecipeRequest`).
- Result: THREAD.
- Effect: Requester cancels only their own pending request.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### RECIPE_REQUEST.08 — Back to conversation

- Visibility: fulfillRequest mode only.
- Trigger: `LOCAL navigate`.
- Result: THREAD.
- Effect: Preserve the pending request and discard only the unsubmitted response selection; send nothing.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- RECIPE_REQUEST.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- RECIPE_REQUEST.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- RECIPE_REQUEST.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- RECIPE_REQUEST.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- RECIPE_REQUEST.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **RECIPE_REQUEST.01**: RecipeRequest; required body fields: postId; body fields available: postId, message; required operation headers: X-Device-Session, Idempotency-Key.
- **RECIPE_REQUEST.05**: RecipeRequestResponse; required body fields: decision; body fields available: decision, recipeVersionId, message; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **RECIPE_REQUEST.06**: RecipeRequestResponse; required body fields: decision; body fields available: decision, recipeVersionId, message; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **RECIPE_REQUEST.07**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Prefilled private request with optional edit; author can answer with known recipe or details.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
