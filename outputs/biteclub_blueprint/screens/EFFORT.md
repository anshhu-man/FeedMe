# EFFORT — What fits today?

Total time, active preparation and effort chips; cleanup preference and equipment explain feasibility.

- Group: Kitchen; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F05, F08, F11.
- Backend owner: planning; plan_requests · plan_variants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/preferences`, `LOCAL requestDraft`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Maximum total minutes *: number]
[Effort *: select]
[Cleanup preference: select]
[Maximum active preparation minutes: number]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Maximum total minutes | number | Yes | 10 |
| Effort | select | Yes | Assemble only; A little cooking; Happy to cook |
| Cleanup preference | select | No | Any; One bowl; One pan |
| Maximum active preparation minutes | number | No | 5 |

## Button and action contracts

### EFFORT.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REQUEST.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### EFFORT.01 — Apply to this meal

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL update`.
- Result: Stay / contextual return.
- Effect: Validate positive time and compatible mode; update only the request draft, not permanent preferences.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### EFFORT.02 — Find my meal

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REQUEST.
- Effect: Return with the explicit budget and revision; request must be submitted to get a plan.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### EFFORT.03 — Change equipment

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: EQUIPMENT.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- EFFORT.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- EFFORT.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- EFFORT.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- EFFORT.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- EFFORT.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.


## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Total time, active preparation and effort chips; cleanup preference and equipment explain feasibility.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
