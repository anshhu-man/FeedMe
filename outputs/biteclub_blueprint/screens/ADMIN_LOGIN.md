# ADMIN_LOGIN — FeedMe operations sign-in

Separate staff client, workforce SSO/MFA and role claims; no user-social account elevation.

- Group: Operations; proposed phase: P1; actor: staff.
- Feature coverage: F47, F51, F52.
- Backend owner: identity; Cognito · accounts · sessions.
- Layout: Desktop operational work area with queue navigation, evidence, action controls and audit context.
- Entry data: `EXTERNAL WorkforceOIDC.configuration`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

## Button and action contracts

### ADMIN_LOGIN.01 — Sign in with staff SSO

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL WorkforceOIDC.authorizePKCE`.
- Result: ADMIN_HOME.
- Effect: Complete enterprise identity plus mandatory MFA; API independently authorizes staff role for each command.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ADMIN_LOGIN.02 — Return to member app

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: AUTH_WELCOME.
- Effect: Preserve the current draft and open the destination.
- Authorization: staff; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.


## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Separate staff client, workforce SSO/MFA and role claims; no user-social account elevation.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
