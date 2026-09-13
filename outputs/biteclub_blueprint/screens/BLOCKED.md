# BLOCKED — Blocked accounts

Private list of blocked accounts with explicit unblock action; no block notification to target.

- Group: Settings; proposed phase: P1; actor: member.
- Feature coverage: F42.
- Backend owner: safety; reports · blocks · mutes · reviews · audit_log.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/blocks`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### BLOCKED.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: PRIVACY.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### BLOCKED.01 — Unblock selected account

- Visibility: When this screen and actor role are eligible.
- Trigger: `DELETE /v1/blocks/{userId}` (`unblockUser`).
- Result: Stay / contextual return.
- Effect: Remove block without rejoining circles, restoring old messages, or sending invitations automatically.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### BLOCKED.02 — Block selected account

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/blocks` (`blockUser`).
- Result: Stay / contextual return.
- Effect: Block interactions and future authorized media issuance; omit notifications to target, update dependent read visibility.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- BLOCKED.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- BLOCKED.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- BLOCKED.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- BLOCKED.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- BLOCKED.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **BLOCKED.01**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **BLOCKED.02**: BlockWrite; required body fields: targetUserId; body fields available: targetUserId; required operation headers: X-Device-Session, Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Private list of blocked accounts with explicit unblock action; no block notification to target.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
