# FEEDBACK — Worth an encore?

Optional taste and actual effort feedback; plain language and a clear skip.

- Group: Kitchen; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F15, F16.
- Backend owner: memory; feedback · memories · collections · recipe_copies.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `LOCAL sessionFeedbackDraft`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[How was it?: select]
[Optional note: textarea]
[What is this feedback about?: select]
[Selected ingredient or texture: text]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| How was it? | select | No | Loved it; Too much prep; Not my taste |
| Optional note | textarea | No |  |
| What is this feedback about? | select | No | Whole meal; Ingredient; Texture; Preparation effort |
| Selected ingredient or texture | text | No |  |

## Button and action contracts

### FEEDBACK.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: MEAL_DONE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### FEEDBACK.01 — Save feedback

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/feedback` (`createFeedback`).
- Result: MEAL_DONE.
- Effect: Upsert explicit feedback for the user and session; create reviewable memory candidates with provenance.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Queue private feedback with client operation ID.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### FEEDBACK.02 — Skip

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: MEAL_DONE.
- Effect: Create no feedback record or negative preference.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### FEEDBACK.03 — Edit previous feedback

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/feedback/{feedbackId}` (`updateFeedback`).
- Result: Stay / contextual return.
- Effect: Revise owned explicit feedback; update derived memory provenance and ranking revision.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### FEEDBACK.04 — Remove my feedback

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `DELETE /v1/feedback/{feedbackId}` (`deleteFeedback`).
- Result: MEAL_DONE.
- Effect: Retract source feedback and resulting unsupported memory influences; retain only legally required audit metadata.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- FEEDBACK.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- FEEDBACK.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- FEEDBACK.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- FEEDBACK.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- FEEDBACK.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **FEEDBACK.01**: FeedbackWrite; required body fields: none; body fields available: cookSessionId, taste, effort, makeAgain, note, target; required operation headers: Idempotency-Key.
- **FEEDBACK.03**: FeedbackWrite; required body fields: none; body fields available: cookSessionId, taste, effort, makeAgain, note, target; required operation headers: Idempotency-Key, If-Match.
- **FEEDBACK.04**: No JSON body; required body fields: none; body fields available: none; required operation headers: Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Optional taste and actual effort feedback; plain language and a clear skip.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
