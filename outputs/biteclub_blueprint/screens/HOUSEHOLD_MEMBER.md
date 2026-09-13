# HOUSEHOLD_MEMBER — Household members

Invitations and membership management with private personal dietary settings protected.

- Group: Membership; proposed phase: P3; actor: member.
- Feature coverage: F44.
- Backend owner: commerce; households · packs · entitlements · purchase_events.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/households/{householdId}/members`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### HOUSEHOLD_MEMBER.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: HOUSEHOLDS.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### HOUSEHOLD_MEMBER.01 — Create member invitation

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/invitations` (`createInvitation`).
- Result: Stay / contextual return.
- Effect: Create limited role-scoped token; invitee must accept explicitly.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### HOUSEHOLD_MEMBER.02 — Share invitation

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OS.shareSheet`.
- Result: Stay / contextual return.
- Effect: User chooses destination; no automatic message send.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### HOUSEHOLD_MEMBER.03 — Accept household invitation

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/invitations/accept` (`acceptInvitation`).
- Result: HOUSEHOLD_PREFS.
- Effect: Validate token, owner entitlement and seat limit transactionally; do not import private data without consent.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### HOUSEHOLD_MEMBER.04 — Remove selected member

- Visibility: When this screen and actor role are eligible.
- Trigger: `DELETE /v1/households/{householdId}/members/{userId}` (`removeHouseholdMember`).
- Result: Stay / contextual return.
- Effect: Owner removes shared access; member retains their private saves and preferences.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- HOUSEHOLD_MEMBER.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- HOUSEHOLD_MEMBER.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- HOUSEHOLD_MEMBER.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- HOUSEHOLD_MEMBER.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- HOUSEHOLD_MEMBER.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **HOUSEHOLD_MEMBER.01**: InvitationRequest; required body fields: targetType, targetId; body fields available: targetType, targetId, expiresInHours, maxUses; required operation headers: X-Device-Session, Idempotency-Key.
- **HOUSEHOLD_MEMBER.03**: InviteAccept; required body fields: token; body fields available: token; required operation headers: X-Device-Session, Idempotency-Key.
- **HOUSEHOLD_MEMBER.04**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Invitations and membership management with private personal dietary settings protected.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
