# SAVE_PERMISSION — May friends save the recipe?

Explain persistent private recipe copies separately from post media. Revocation stops future saves; prior authorized copies follow declared retention.

- Group: Publishing; proposed phase: P1; actor: member.
- Feature coverage: F30, F31.
- Backend owner: social; post_drafts · posts · media · save_grants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `LOCAL exactRecipeGrantContext`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Allow private recipe saves: checkbox]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Allow private recipe saves | checkbox | No | false |

## Button and action contracts

### SAVE_PERMISSION.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REVIEW_ATTACHMENT.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SAVE_PERMISSION.01 — Confirm permission

- Visibility: draft mode only.
- Trigger: `PATCH /v1/post-drafts/{draftId}` (`updatePostDraft`).
- Result: AUDIENCE.
- Effect: Bind grant policy to exact confirmed recipe revision; never grant source image/comment copying.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SAVE_PERMISSION.02 — Back to recipe details

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REVIEW_ATTACHMENT.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SAVE_PERMISSION.03 — Update existing post

- Visibility: editPost mode only; explicit confirmation required.
- Trigger: `PATCH /v1/posts/{postId}` (`updatePost`).
- Result: POST.
- Effect: Change future recipe saving grant for an owned post with expected revision. Existing permitted private copies follow published retention policy.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SAVE_PERMISSION.04 — Cancel permission edit

- Visibility: editPost mode only.
- Trigger: `LOCAL navigate`.
- Result: POST.
- Effect: Keep the existing source grant policy unchanged.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- SAVE_PERMISSION.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- SAVE_PERMISSION.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- SAVE_PERMISSION.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- SAVE_PERMISSION.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- SAVE_PERMISSION.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **SAVE_PERMISSION.01**: PostDraftPatch; required body fields: none; body fields available: clientDraftId, caption, altText, mediaIds, attachment, removeAttachment, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, sourcePostId; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **SAVE_PERMISSION.03**: PostPatch; required body fields: none; body fields available: caption, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, attachment, removeAttachment; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Explain persistent private recipe copies separately from post media. Revocation stops future saves; prior authorized copies follow declared retention.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
