# POLL_CREATE — Dinner Vote

Two actionable recipe options, an audience and closing time; each option must be accessible to that audience.

- Group: Shared meals; proposed phase: P3; actor: member.
- Feature coverage: F37.
- Backend owner: coordination; sos · pacts · potlucks · polls · shortcuts.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/circles`, `LOCAL selectedOptions`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Question *: text]
[Closes in minutes *: number]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Question | text | Yes | What should I make tonight? |
| Closes in minutes | number | Yes | 30 |

## Button and action contracts

### POLL_CREATE.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CIRCLE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_CREATE.01 — Choose option A

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: COOKBOOK.
- Effect: Open a scoped recipe picker for poll option A. On selection return to POLL_CREATE with a permitted recipe/source reference; cancel returns to POLL_CREATE with its previous draft unchanged. Preserve draft/editPost/fulfillRequest mode throughout. Private-only saved source content cannot be reattached as redistributable text.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_CREATE.02 — Choose option B

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: COOKBOOK.
- Effect: Open a scoped recipe picker for poll option B. On selection return to POLL_CREATE with a permitted recipe/source reference; cancel returns to POLL_CREATE with its previous draft unchanged. Preserve draft/editPost/fulfillRequest mode throughout. Private-only saved source content cannot be reattached as redistributable text.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### POLL_CREATE.03 — Ask my circle

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/polls` (`createPoll`).
- Result: POLL_DETAIL.
- Effect: Validate two distinct supported choices, close time and membership; create poll and outbox once.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- POLL_CREATE.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- POLL_CREATE.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- POLL_CREATE.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- POLL_CREATE.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- POLL_CREATE.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **POLL_CREATE.03**: PollWrite; required body fields: question, options, audience, closesAt; body fields available: question, options, audience, closesAt; required operation headers: X-Device-Session, Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Two actionable recipe options, an audience and closing time; each option must be accessible to that audience.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
