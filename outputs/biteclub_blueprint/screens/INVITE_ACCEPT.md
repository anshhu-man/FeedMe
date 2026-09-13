# INVITE_ACCEPT — Join a kitchen circle

Show invitation preview only after token validation; show inviter and circle without exposing member meal data.

- Group: Access; proposed phase: P1; actor: member.
- Feature coverage: F23, F47, F53.
- Backend owner: circles; circles · memberships · invitations.
- Layout: Single-column entry flow; title, short explanation, labelled inputs, primary action and recovery choices.
- Entry data: `GET /v1/invitations/preview`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

## Button and action contracts

### INVITE_ACCEPT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### INVITE_ACCEPT.01 — Accept invitation

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/invitations/accept` (`acceptInvitation`).
- Result: CIRCLE; conditional: AUTH_WELCOME when guest; preserve invite return; UNAVAILABLE when expired/revoked/full.
- Effect: Atomically validate token expiry, account, block rules and membership cap; consume single-use invite if applicable.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### INVITE_ACCEPT.02 — Decline

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: HOME.
- Effect: Discard local invite context; do not notify sender by default.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **INVITE_ACCEPT.01**: InviteAccept; required body fields: token; body fields available: token; required operation headers: X-Device-Session, Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Show invitation preview only after token validation; show inviter and circle without exposing member meal data.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
