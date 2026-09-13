# AUTH_RESET_CONFIRM — Choose a new password

Reset code and new password; secret fields are never cached or included in analytics.

- Group: Access; proposed phase: P1; actor: public.
- Feature coverage: F47.
- Backend owner: identity; Cognito · accounts · sessions.
- Layout: Single-column entry flow; title, short explanation, labelled inputs, primary action and recovery choices.
- Entry data: `LOCAL recoveryState`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Reset code *: text]
[New password *: password]
[Confirm password *: password]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Reset code | text | Yes | 123456 |
| New password | password | Yes | New-example-123! |
| Confirm password | password | Yes | New-example-123! |

## Button and action contracts

### AUTH_RESET_CONFIRM.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: AUTH_RESET.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUTH_RESET_CONFIRM.01 — Save new password

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL Cognito.confirmForgotPassword`.
- Result: AUTH_LOGIN.
- Effect: Validate code and password; clear recovery state. Require a fresh login and revoke sessions according to identity policy.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### AUTH_RESET_CONFIRM.02 — Resend reset code

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL Cognito.forgotPassword`.
- Result: Stay / contextual return.
- Effect: Respect cooldown and generic identity response.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.


## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Reset code and new password; secret fields are never cached or included in analytics.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
