# SOS_CREATE — Fridge SOS

Explicitly shared ingredient subset, time/effort and one chosen circle; private pantry is never attached wholesale.

- Group: Shared meals; proposed phase: P2; actor: member.
- Feature coverage: F32.
- Backend owner: coordination; sos · pacts · potlucks · polls · shortcuts.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/circles`, `LOCAL voluntaryIngredientSubset`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Ingredients to share *: text]
[Time available *: number]
[Effort *: select]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Ingredients to share | text | Yes | Bread, yogurt, cucumber |
| Time available | number | Yes | 10 |
| Effort | select | Yes | Assemble only; A little cooking; Happy to cook |

## Button and action contracts

### SOS_CREATE.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SOS_CREATE.01 — Ask my circle

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/sos` (`createSOS`).
- Result: SOS_DETAIL.
- Effect: Check selected audience and create expiring request with minimal chosen context; notify opted-in eligible circle members.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SOS_CREATE.02 — Give me an idea now

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REQUEST.
- Effect: Copy the same explicit constraints to private helper; do not wait for social reply.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SOS_CREATE.03 — Choose circle

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CIRCLES.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- SOS_CREATE.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- SOS_CREATE.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- SOS_CREATE.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- SOS_CREATE.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- SOS_CREATE.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **SOS_CREATE.01**: SOSWrite; required body fields: ingredientIds, caption, audience; body fields available: ingredientIds, sharedConstraints, caption, audience; required operation headers: X-Device-Session, Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Explicitly shared ingredient subset, time/effort and one chosen circle; private pantry is never attached wholesale.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
