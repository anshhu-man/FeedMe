# REVIEW_ATTACHMENT — Check your recipe card

Original recipe and explicitly chosen changes; author confirms actual ingredients/steps and saveability.

- Group: Publishing; proposed phase: P1; actor: member.
- Feature coverage: F24, F25.
- Backend owner: social; post_drafts · posts · media · save_grants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/post-drafts/{draftId}`, `LOCAL confirmedAttachment`.
- Context: Resolve exactly one mode: draft reads the owned post draft; editPost reads the owned post and selected recipe version; fulfillRequest reads the authorized recipe request and selected recipe version. Skip draft hydration entirely in fulfillRequest/editPost. Confirmation creates only the matching typed draft, post edit or private response selection; it never switches those resource scopes.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[These details match what I made *: checkbox]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| These details match what I made | checkbox | Yes | false |

## Button and action contracts

### REVIEW_ATTACHMENT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ATTACH_RECIPE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REVIEW_ATTACHMENT.01 — Confirm attachment

- Visibility: draft mode only.
- Trigger: `PATCH /v1/post-drafts/{draftId}` (`updatePostDraft`).
- Result: SAVE_PERMISSION.
- Effect: Save versioned recipe attachment and source references, mark author confirmation timestamp.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REVIEW_ATTACHMENT.02 — Edit recipe details

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ATTACH_RECIPE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REVIEW_ATTACHMENT.03 — Remove attachment

- Visibility: draft mode only.
- Trigger: `LOCAL navigate`.
- Result: AUDIENCE.
- Effect: Clear draft attachment only; source recipe and cook session remain intact.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REVIEW_ATTACHMENT.04 — Replace on existing post

- Visibility: editPost mode only; explicit confirmation required.
- Trigger: `PATCH /v1/posts/{postId}` (`updatePost`).
- Result: POST.
- Effect: Owner sends the confirmed attachment and expected post version. Atomically replace version-bound future save grant; existing permitted copies follow lifecycle policy.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REVIEW_ATTACHMENT.05 — Back to this post

- Visibility: editPost mode only.
- Trigger: `LOCAL navigate`.
- Result: POST.
- Effect: Leave the existing attachment unchanged; discard only the uncommitted replacement selection.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REVIEW_ATTACHMENT.06 — Use this recipe in my response

- Visibility: fulfillRequest mode only.
- Trigger: `LOCAL update`.
- Result: RECIPE_REQUEST.
- Effect: Confirm the selected eligible recipeVersionId and its rights for this request. Return a typed response selection without saving a post draft, widening an audience, or sending a response.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REVIEW_ATTACHMENT.07 — Cancel recipe response selection

- Visibility: fulfillRequest mode only.
- Trigger: `LOCAL navigate`.
- Result: RECIPE_REQUEST.
- Effect: Return to the request with no confirmed response and no publication changes.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- REVIEW_ATTACHMENT.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- REVIEW_ATTACHMENT.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- REVIEW_ATTACHMENT.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- REVIEW_ATTACHMENT.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- REVIEW_ATTACHMENT.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **REVIEW_ATTACHMENT.01**: PostDraftPatch; required body fields: none; body fields available: clientDraftId, caption, altText, mediaIds, attachment, removeAttachment, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, sourcePostId; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **REVIEW_ATTACHMENT.04**: PostPatch; required body fields: none; body fields available: caption, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, attachment, removeAttachment; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Original recipe and explicitly chosen changes; author confirms actual ingredients/steps and saveability.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
