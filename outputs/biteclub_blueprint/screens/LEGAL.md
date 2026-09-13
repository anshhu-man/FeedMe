# LEGAL — Terms and privacy

Versioned readable Terms, Privacy, community policy and launch eligibility. Returning does not imply consent.

- Group: Access; proposed phase: P1; actor: public.
- Feature coverage: F47, F49.
- Backend owner: profile; profiles · preferences · account_jobs.
- Layout: Single-column entry flow; title, short explanation, labelled inputs, primary action and recovery choices.
- Entry data: `GET /v1/config`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Document: select]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Document | select | No | Privacy; Terms; Community rules |

## Button and action contracts

### LEGAL.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: AUTH_WELCOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### LEGAL.01 — Open selected document

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL update`.
- Result: Stay / contextual return.
- Effect: Load the published legal document version in an accessible native web view. External links require explicit user action.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### LEGAL.02 — Copy document link

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL update`.
- Result: Stay / contextual return.
- Effect: Copy a public legal document URL without identity or session data.
- Authorization: public; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.


## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Versioned readable Terms, Privacy, community policy and launch eligibility. Returning does not imply consent.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
