# PACT_DETAIL — Your Dinner Pact

Participant decisions, shared context and each user’s private variant; progress sharing is optional.

- Group: Shared meals; proposed phase: P3; actor: member.
- Feature coverage: F34.
- Backend owner: coordination; sos · pacts · potlucks · polls · shortcuts.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/pacts/{pactId}`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Proposed meal time: datetime-local]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Proposed meal time | datetime-local | No | 2026-09-14T19:30 |

## Button and action contracts

### PACT_DETAIL.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: INBOX.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.01 — I’m in

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/pacts/{pactId}/participation` (`respondToPact`).
- Result: Stay / contextual return.
- Effect: Set own response accepted with revision; never accept for another participant.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.02 — Pass this time

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/pacts/{pactId}/participation` (`respondToPact`).
- Result: Stay / contextual return.
- Effect: Set own response declined without streak or public penalty.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.03 — Make my version

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: ADAPT.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.04 — Chat about dinner

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: THREAD.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.05 — Share a progress photo

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CAPTURE.
- Effect: Open an optional composer with self-only initial audience. Select a supported self/circle audience explicitly; pact participation does not authorize a new audience type.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.06 — Cancel my pact

- Visibility: When this screen and actor role are eligible.
- Trigger: `DELETE /v1/pacts/{pactId}` (`cancelPact`).
- Result: Stay / contextual return.
- Effect: Creator cancels coordination; preserve independently saved recipes according to grants.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.07 — Leave this pact

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/pacts/{pactId}/participation` (`respondToPact`).
- Result: Stay / contextual return.
- Effect: Set own participation=left; exclude future thread sends/notifications as policy specifies while retaining private variants.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.08 — Propose a different time

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/pacts/{pactId}` (`reschedulePact`).
- Result: Stay / contextual return.
- Effect: Create updated schedule revision; affected accepted participants must reconfirm rather than auto-commit.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_DETAIL.09 — Confirm changed plan

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/pacts/{pactId}/participation` (`respondToPact`).
- Result: Stay / contextual return.
- Effect: Accept the displayed pact revision; stale confirmation returns conflict.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- PACT_DETAIL.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- PACT_DETAIL.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- PACT_DETAIL.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- PACT_DETAIL.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- PACT_DETAIL.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **PACT_DETAIL.01**: Participation; required body fields: decision; body fields available: decision, planId, acknowledgedScheduleVersion; required operation headers: X-Device-Session, Idempotency-Key.
- **PACT_DETAIL.02**: Participation; required body fields: decision; body fields available: decision, planId, acknowledgedScheduleVersion; required operation headers: X-Device-Session, Idempotency-Key.
- **PACT_DETAIL.06**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **PACT_DETAIL.07**: Participation; required body fields: decision; body fields available: decision, planId, acknowledgedScheduleVersion; required operation headers: X-Device-Session, Idempotency-Key.
- **PACT_DETAIL.08**: PactScheduleWrite; required body fields: scheduledAt, timeZone; body fields available: scheduledAt, timeZone, reason; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **PACT_DETAIL.09**: Participation; required body fields: decision; body fields available: decision, planId, acknowledgedScheduleVersion; required operation headers: X-Device-Session, Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Participant decisions, shared context and each user’s private variant; progress sharing is optional.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
