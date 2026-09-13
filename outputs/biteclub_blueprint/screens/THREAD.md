# THREAD — Meal conversation

Private author reply thread or authorized coordination thread. Message context does not grant post/media access.

- Group: Social; proposed phase: P1; actor: member.
- Feature coverage: F28, F29.
- Backend owner: conversations; threads · messages · reactions · notifications.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/threads/{threadId}`, `GET /v1/threads/{threadId}/messages`.
- Context: Opening Reply from a post creates/finds a scoped private thread via POST /v1/threads before message reads. Coordination threads use server-provided IDs and participant membership.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Message *: textarea]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Message | textarea | Yes | That looks doable! |

## Button and action contracts

### THREAD.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: INBOX.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### THREAD.01 — Send reply

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/threads/{threadId}/messages` (`sendMessage`).
- Result: Stay / contextual return.
- Effect: Authorize membership and blocks, validate bounded text, persist message and outbox once by clientMessageId.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### THREAD.02 — Open shared recipe

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECIPE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### THREAD.03 — Ask for recipe

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECIPE_REQUEST.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### THREAD.04 — Report message

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REPORT.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### THREAD.05 — Mute thread

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/mutes` (`muteTarget`).
- Result: Stay / contextual return.
- Effect: Suppress eligible notification delivery without changing message visibility.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### THREAD.06 — Unmute thread

- Visibility: When this screen and actor role are eligible.
- Trigger: `DELETE /v1/mutes/{muteId}` (`unmuteTarget`).
- Result: Stay / contextual return.
- Effect: Remove chosen mute record; future delivery still obeys notification settings, block and audience checks.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### THREAD.07 — Review incoming recipe request

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECIPE_REQUEST.
- Effect: Author context: resolve the authorized pending recipeRequestId and retain the thread return destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- THREAD.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- THREAD.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- THREAD.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- THREAD.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- THREAD.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **THREAD.01**: MessageWrite; required body fields: kind, text, clientMessageId; body fields available: kind, text, postId, recipeVersionId, clientMessageId; required operation headers: X-Device-Session, Idempotency-Key.
- **THREAD.05**: MuteWrite; required body fields: targetType, targetId; body fields available: targetType, targetId; required operation headers: X-Device-Session, Idempotency-Key.
- **THREAD.06**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Private author reply thread or authorized coordination thread. Message context does not grant post/media access.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
