# PEOPLE_PICKER — Choose your people

Purpose-scoped member selector. Show only eligible mutual circle members or accepted event participants; selection is private until the user sends the invitation.

- Group: Social; proposed phase: P1; actor: member.
- Feature coverage: F23, F28, F34, F35.
- Backend owner: circles; circles · memberships · invitations.
- Layout: Focused full-screen task with persistent context and a clear close/back action.
- Entry data: `GET /v1/circles/{circleId}/members`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Selected people *: text]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Selected people | text | Yes | Meera |

## Button and action contracts

### PEOPLE_PICKER.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: CIRCLES.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PEOPLE_PICKER.01 — Use selected people

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL pickerResult`.
- Result: Stay / contextual return; conditional: PACT_CREATE when Dinner Pact; POTLUCK_CREATE when Bring a Bit; INVITE when circle role selection.
- Effect: Return selected eligible account IDs to the requesting draft; server checks eligibility again at invitation creation.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PEOPLE_PICKER.02 — Cancel selection

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL pickerCancel`.
- Result: Stay / contextual return; conditional: PACT_CREATE when pact; POTLUCK_CREATE when potluck; INVITE when circle.
- Effect: Return to requesting draft with previous recipients unchanged.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.


## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Purpose-scoped member selector. Show only eligible mutual circle members or accepted event participants; selection is private until the user sends the invitation.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
