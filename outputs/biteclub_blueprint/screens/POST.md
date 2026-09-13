# POST — Meal post

Retained or currently accessible meal with attachment status, source credit, reactions and actions.

- Group: Social; proposed phase: P1; actor: member.
- Feature coverage: F22, F24, F25, F26, F27, F30.
- Backend owner: social; post_drafts · posts · media · save_grants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/posts/{postId}`, `POST /v1/media/{mediaId}/access`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Edit my caption (owner only): textarea]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Edit my caption (owner only) | textarea | No | Made this work. |

## Button and action contracts

### POST.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: PROFILE_PLATE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.01 — Make Mine

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADAPT; conditional: RECIPE_REQUEST when attachment lacks supported details.
- Effect: Start from supported attached recipe revision; unavailable recipe opens Ask for recipe.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.02 — Save recipe

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/posts/{postId}/recipe-saves` (`savePostRecipe`).
- Result: COOKBOOK.
- Effect: Authorize immutable recipe-only copy at commit time.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.03 — React

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/posts/{postId}/reactions` (`setReaction`).
- Result: Stay / contextual return.
- Effect: Idempotent per-actor reaction update.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.04 — Reply

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: THREAD.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.05 — View Your Takes

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REMIX_TRAIL.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.06 — Make a Dinner Pact

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: PACT_CREATE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.07 — Share my version

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CAPTURE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.08 — Keep on my Plate

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/posts/{postId}` (`updatePost`).
- Result: Stay / contextual return.
- Effect: Owner-only placement update; keep flag never expands original audience.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.09 — Delete my post

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: DELETE_POST.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.10 — Report this post

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REPORT.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.11 — Remove my reaction

- Visibility: When this screen and actor role are eligible.
- Trigger: `DELETE /v1/posts/{postId}/reactions/me` (`removeReaction`).
- Result: Stay / contextual return.
- Effect: Idempotently remove actor reaction; do not notify author of a removed reaction.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.12 — Edit audience

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: AUDIENCE.
- Effect: Owner-only live-post edit mode: use PATCH /v1/posts/{postId} after confirmation, never change an unsent draft instead.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.13 — Change recipe-save permission

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: SAVE_PERMISSION.
- Effect: Owner-only live-post edit mode; disclose prior allowed recipe copies persist while future grants change.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.14 — Remove from my Plate

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `PATCH /v1/posts/{postId}` (`updatePost`).
- Result: Stay / contextual return.
- Effect: Owner sets keepOnPlate=false; still-active Today placement remains until expiry. If neither placement remains, normal source access ends.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.15 — Edit caption

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/posts/{postId}` (`updatePost`).
- Result: Stay / contextual return.
- Effect: Owner edits bounded caption/alt text with revision; content policy applies. Recipe revisions require a new confirmed attachment workflow.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.16 — Replace recipe attachment

- Visibility: post owner only.
- Trigger: `LOCAL navigate`.
- Result: ATTACH_RECIPE.
- Effect: Owner-only edit: choose a permitted revision, review it, then explicitly commit to this post. Do not edit a composer draft instead.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POST.17 — Remove recipe attachment

- Visibility: post owner only; explicit confirmation required.
- Trigger: `PATCH /v1/posts/{postId}` (`updatePost`).
- Result: Stay / contextual return.
- Effect: Owner sends removeAttachment=true with expected version. Revoke future grants while preserving previously authorized copies under the lifecycle policy.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- POST.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- POST.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- POST.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- POST.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- POST.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **POST.02**: SavePostRecipeRequest; required body fields: recipeVersionId, grantPolicyVersion; body fields available: recipeVersionId, grantPolicyVersion, collectionId, markMakeAgain; required operation headers: X-Device-Session, Idempotency-Key.
- **POST.03**: ReactionWrite; required body fields: kind; body fields available: kind; required operation headers: X-Device-Session, Idempotency-Key.
- **POST.08**: PostPatch; required body fields: none; body fields available: caption, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, attachment, removeAttachment; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **POST.11**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **POST.14**: PostPatch; required body fields: none; body fields available: caption, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, attachment, removeAttachment; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **POST.15**: PostPatch; required body fields: none; body fields available: caption, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, attachment, removeAttachment; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **POST.17**: PostPatch; required body fields: none; body fields available: caption, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, attachment, removeAttachment; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Retained or currently accessible meal with attachment status, source credit, reactions and actions.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
