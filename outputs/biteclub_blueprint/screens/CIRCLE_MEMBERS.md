# CIRCLE_MEMBERS — Circle members

Member roles and owner actions. Removing a member revokes future access and affects shared coordination eligibility.

- Group: Social; proposed phase: P1; actor: member.
- Feature coverage: F23, F40.
- Backend owner: circles; circles · memberships · invitations.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/circles/{circleId}/members`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### CIRCLE_MEMBERS.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CIRCLE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### CIRCLE_MEMBERS.01 — Remove selected member

- Visibility: When this screen and actor role are eligible.
- Trigger: `DELETE /v1/circles/{circleId}/members/{userId}` (`removeCircleMember`).
- Result: Stay / contextual return.
- Effect: Owner/moderator permission, If-Match membership version and last-owner invariant enforced.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### CIRCLE_MEMBERS.02 — Transfer ownership

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `POST /v1/circles/{circleId}/ownership` (`transferCircleOwnership`).
- Result: Stay / contextual return.
- Effect: Only current owner may transfer; require explicit confirmation and audit.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### CIRCLE_MEMBERS.03 — Invite someone

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: INVITE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### CIRCLE_MEMBERS.04 — Report a member

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REPORT.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- CIRCLE_MEMBERS.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- CIRCLE_MEMBERS.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- CIRCLE_MEMBERS.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- CIRCLE_MEMBERS.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- CIRCLE_MEMBERS.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **CIRCLE_MEMBERS.01**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **CIRCLE_MEMBERS.02**: OwnershipTransfer; required body fields: newOwnerUserId, confirmed; body fields available: newOwnerUserId, confirmed; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Member roles and owner actions. Removing a member revokes future access and affects shared coordination eligibility.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
