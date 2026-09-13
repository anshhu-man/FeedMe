# MEMORY_DETAIL — A remembered preference

Show source feedback, affected ingredient/texture/preparation and examples of why it may recur.

- Group: Personal; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F16, F17, F18.
- Backend owner: memory; feedback · memories · collections · recipe_copies.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/memories/{memoryId}`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Preference *: select]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Preference | select | Yes | Prefer; Neutral; Avoid as a taste preference |

## Button and action contracts

### MEMORY_DETAIL.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: MEMORY.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### MEMORY_DETAIL.01 — Update memory

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/memories/{memoryId}` (`updateMemory`).
- Result: MEMORY.
- Effect: Revise user-owned memory with optimistic concurrency and invalidate personalized ranking cache.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### MEMORY_DETAIL.02 — Forget this

- Visibility: When this screen and actor role are eligible.
- Trigger: `DELETE /v1/memories/{memoryId}` (`deleteMemory`).
- Result: MEMORY.
- Effect: Delete memory and suppression provenance so old feedback cannot silently recreate it.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### MEMORY_DETAIL.03 — View source meal

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECIPE.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- MEMORY_DETAIL.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- MEMORY_DETAIL.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- MEMORY_DETAIL.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- MEMORY_DETAIL.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- MEMORY_DETAIL.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **MEMORY_DETAIL.01**: MemoryPatch; required body fields: none; body fields available: enabled, value, context; required operation headers: Idempotency-Key, If-Match.
- **MEMORY_DETAIL.02**: No JSON body; required body fields: none; body fields available: none; required operation headers: Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Show source feedback, affected ingredient/texture/preparation and examples of why it may recur.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
