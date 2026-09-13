# POTLUCK_DETAIL — One shared dinner

Volunteered contributions, confirmed guest requirements, assignment status and current plan revision.

- Group: Shared meals; proposed phase: P3; actor: member.
- Feature coverage: F35.
- Backend owner: coordination; sos · pacts · potlucks · polls · shortcuts.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/potlucks/{potluckId}`, `GET /v1/potlucks/{potluckId}/contributions`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### POTLUCK_DETAIL.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CIRCLE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.01 — Bring an ingredient

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CONTRIBUTION.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.02 — Find a meal we can make

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/potlucks/{potluckId}/plan` (`planPotluck`).
- Result: RECOMMENDATIONS.
- Effect: Check accepted participants and required private exclusion constraints; output feasible plan without revealing whose exclusion caused a filter.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.03 — Claim selected task

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/potlucks/{potluckId}/claims` (`claimPotluckContribution`).
- Result: Stay / contextual return.
- Effect: Unique assignment plus revision prevents two people claiming the same exclusive contribution.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.04 — Open meal conversation

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: THREAD.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.05 — Lock this meal plan

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/potlucks/{potluckId}/confirm` (`confirmPotluck`).
- Result: Stay / contextual return.
- Effect: Owner confirms expected plan/contribution revision; notify only participants. Later changes require reconfirmation.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.06 — Join shared meal

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/potlucks/{potluckId}/participation` (`respondToPotluck`).
- Result: Stay / contextual return.
- Effect: Accept invitation and explicit sharing scope for this event.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.07 — Leave shared meal

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `POST /v1/potlucks/{potluckId}/participation` (`respondToPotluck`).
- Result: Stay / contextual return.
- Effect: Withdraw own participation/contributions and invalidate confirmed plan; owner must transfer/cancel where necessary.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.08 — Cancel shared meal

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `DELETE /v1/potlucks/{potluckId}` (`cancelPotluck`).
- Result: CIRCLE.
- Effect: Owner cancels event, releases claims and prevents new activity; independently saved recipes persist.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POTLUCK_DETAIL.09 — Update shared meal details

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/potlucks/{potluckId}` (`updatePotluck`).
- Result: Stay / contextual return.
- Effect: Owner changes servings/equipment/time with revision and requires plan reconfirmation.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- POTLUCK_DETAIL.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- POTLUCK_DETAIL.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- POTLUCK_DETAIL.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- POTLUCK_DETAIL.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- POTLUCK_DETAIL.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **POTLUCK_DETAIL.02**: PotluckPlanRequest; required body fields: constraints; body fields available: constraints; required operation headers: X-Device-Session, Idempotency-Key.
- **POTLUCK_DETAIL.03**: PotluckClaim; required body fields: contributionId, expectedVersion, action; body fields available: contributionId, expectedVersion, action; required operation headers: X-Device-Session, Idempotency-Key.
- **POTLUCK_DETAIL.05**: PotluckConfirm; required body fields: recipeVersionId, acknowledgedContributionVersion; body fields available: recipeVersionId, acknowledgedContributionVersion; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **POTLUCK_DETAIL.06**: Participation; required body fields: decision; body fields available: decision, planId, acknowledgedScheduleVersion; required operation headers: X-Device-Session, Idempotency-Key.
- **POTLUCK_DETAIL.07**: Participation; required body fields: decision; body fields available: decision, planId, acknowledgedScheduleVersion; required operation headers: X-Device-Session, Idempotency-Key.
- **POTLUCK_DETAIL.08**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **POTLUCK_DETAIL.09**: PotluckWrite; required body fields: title, scheduledAt, inviteeIds; body fields available: title, scheduledAt, inviteeIds, selectedRecipeVersionId; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Volunteered contributions, confirmed guest requirements, assignment status and current plan revision.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
