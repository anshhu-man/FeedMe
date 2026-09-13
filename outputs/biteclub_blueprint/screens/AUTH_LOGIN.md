# AUTH_LOGIN — Welcome back

Login form with password recovery and provider choices. Server selects onboarding or app destination after authentication.

- Group: Access; proposed phase: P1; actor: public.
- Feature coverage: F47.
- Backend owner: identity; Cognito · accounts · sessions.
- Layout: Single-column entry flow; title, short explanation, labelled inputs, primary action and recovery choices.
- Entry data: `GET /v1/config`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Email *: email]
[Password *: password]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Email | email | Yes | you@example.com |
| Password | password | Yes | Example-password-123! |

## Button and action contracts

### AUTH_LOGIN.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: AUTH_WELCOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUTH_LOGIN.01 — Log in

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL Cognito.signIn`.
- Result: HOME; conditional: PROFILE_SETUP when profile incomplete; AUTH_VERIFY when email unverified; UNAVAILABLE when account suspended.
- Effect: Complete provider authentication, call account bootstrap, and route by onboarding state. Restore an authorized deferred link.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUTH_LOGIN.02 — Forgot password

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: AUTH_RESET.
- Effect: Preserve the current draft and open the destination.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUTH_LOGIN.03 — Create account

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: AUTH_SIGNUP.
- Effect: Preserve the current draft and open the destination.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUTH_LOGIN.04 — Continue with Apple

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OIDC.authorizeApplePKCE`.
- Result: AUTH_CALLBACK.
- Effect: Launch native browser provider flow.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUTH_LOGIN.05 — Continue with Google

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OIDC.authorizeGooglePKCE`.
- Result: AUTH_CALLBACK.
- Effect: Launch native browser provider flow.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.


## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Login form with password recovery and provider choices. Server selects onboarding or app destination after authentication.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
