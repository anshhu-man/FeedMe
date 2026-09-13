# POLL_DETAIL — Help choose dinner

Question, two meal cards, own vote and authorized aggregated results; close state uses server time.

- Group: Shared meals; proposed phase: P3; actor: member.
- Feature coverage: F37.
- Backend owner: coordination; sos · pacts · potlucks · polls · shortcuts.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/polls/{pollId}`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### POLL_DETAIL.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CIRCLE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_DETAIL.01 — Vote for A

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/polls/{pollId}/votes` (`voteInPoll`).
- Result: Stay / contextual return.
- Effect: Upsert one vote per eligible actor before server-side deadline.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_DETAIL.02 — Vote for B

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/polls/{pollId}/votes` (`voteInPoll`).
- Result: Stay / contextual return.
- Effect: Replace own vote idempotently; reject after closure.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_DETAIL.03 — Make option A mine

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADAPT.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_DETAIL.04 — Make option B mine

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADAPT.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_DETAIL.05 — Close my poll

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/polls/{pollId}/close` (`closePoll`).
- Result: Stay / contextual return.
- Effect: Owner closes once; never use client clock to decide final eligibility.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_DETAIL.06 — Remove my vote

- Visibility: When this screen and actor role are eligible.
- Trigger: `DELETE /v1/polls/{pollId}/votes/me` (`removePollVote`).
- Result: Stay / contextual return.
- Effect: Remove actor vote before server-side closing time; closed polls reject changes.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- POLL_DETAIL.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- POLL_DETAIL.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- POLL_DETAIL.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- POLL_DETAIL.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- POLL_DETAIL.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **POLL_DETAIL.01**: VoteWrite; required body fields: optionId; body fields available: optionId; required operation headers: X-Device-Session, Idempotency-Key.
- **POLL_DETAIL.02**: VoteWrite; required body fields: optionId; body fields available: optionId; required operation headers: X-Device-Session, Idempotency-Key.
- **POLL_DETAIL.05**: Empty; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key.
- **POLL_DETAIL.06**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Question, two meal cards, own vote and authorized aggregated results; close state uses server time.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
