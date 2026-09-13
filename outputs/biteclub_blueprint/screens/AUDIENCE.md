# AUDIENCE — Who gets a seat?

Selected invited circle(s), membership preview, Keep on Plate toggle, and explicit audience context for source credit.

- Group: Publishing; proposed phase: P1; actor: member.
- Feature coverage: F31, F40.
- Backend owner: circles; circles · memberships · invitations.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/circles`, `LOCAL composerOrPostMode`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Audience *: select]
[Keep on my Plate: checkbox]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Audience | select | Yes | Only you; My kitchen circle; Selected invited circles |
| Keep on my Plate | checkbox | No | false |

## Button and action contracts

### AUDIENCE.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: EDIT_MEDIA.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUDIENCE.01 — Use this audience

- Visibility: draft mode only.
- Trigger: `PATCH /v1/post-drafts/{draftId}` (`updatePostDraft`).
- Result: PUBLISH_STATUS.
- Effect: Save audience IDs only after membership validation. Warn if source attribution/media cannot be exposed to selected recipients.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUDIENCE.02 — Manage circles

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CIRCLES.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUDIENCE.03 — Recipe save permissions

- Visibility: publishing mode only.
- Trigger: `LOCAL navigate`.
- Result: SAVE_PERMISSION.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUDIENCE.04 — Apply to existing post

- Visibility: editPost mode only; explicit confirmation required.
- Trigger: `PATCH /v1/posts/{postId}` (`updatePost`).
- Result: POST.
- Effect: Owner-only live-post mode: atomically validate current membership, replace audience and bump access epoch; revoke future media delivery for removed recipients.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUDIENCE.05 — Save as my future default

- Visibility: settings mode only.
- Trigger: `PATCH /v1/me/privacy` (`updatePrivacySettings`).
- Result: SETTINGS.
- Effect: Settings-only mode saves future default audience IDs; does not mutate an existing draft or expand existing posts.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUDIENCE.06 — Cancel audience edit

- Visibility: editPost mode only.
- Trigger: `LOCAL navigate`.
- Result: POST.
- Effect: Return without changing the owned post audience.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUDIENCE.07 — Cancel default change

- Visibility: settings mode only.
- Trigger: `LOCAL navigate`.
- Result: PRIVACY.
- Effect: Return without changing the future default.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- AUDIENCE.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- AUDIENCE.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- AUDIENCE.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- AUDIENCE.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- AUDIENCE.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **AUDIENCE.01**: PostDraftPatch; required body fields: none; body fields available: clientDraftId, caption, altText, mediaIds, attachment, removeAttachment, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, sourcePostId; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **AUDIENCE.04**: PostPatch; required body fields: none; body fields available: caption, audience, keepOnPlate, allowRecipeSaves, saveDisclosureVersion, attachment, removeAttachment; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **AUDIENCE.05**: PrivacyPatch; required body fields: none; body fields available: defaultAudience, allowCircleMemberMessages, allowRecipeRequests, analyticsConsent, allowCoordinationInvites, socialDiscoveryVisible; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Selected invited circle(s), membership preview, Keep on Plate toggle, and explicit audience context for source credit.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
