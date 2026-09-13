# PACT_CREATE — Dinner Pact

Invite a selected friend with a recipe reference and optional time; no automatic scheduling or food profile disclosure.

- Group: Shared meals; proposed phase: P3; actor: member.
- Feature coverage: F34.
- Backend owner: coordination; sos · pacts · potlucks · polls · shortcuts.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `LOCAL selectedShareableRecipe`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Invitation *: text]
[Optional time: text]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Invitation | text | Yes | Making this tonight? |
| Optional time | text | No | Tonight |

## Button and action contracts

### PACT_CREATE.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: POST.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_CREATE.01 — Send Dinner Pact

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/pacts` (`createPact`).
- Result: PACT_DETAIL.
- Effect: Validate relationship, source shareability and selected recipient; create pending participant invitations once.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_CREATE.02 — Choose a recipe

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: COOKBOOK.
- Effect: Open a scoped recipe picker for pact source. On selection return to PACT_CREATE with a permitted recipe/source reference; cancel returns to PACT_CREATE with its previous draft unchanged. Preserve draft/editPost/fulfillRequest mode throughout. Private-only saved source content cannot be reattached as redistributable text.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_CREATE.03 — Cancel

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: POST.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### PACT_CREATE.04 — Choose a friend

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: PEOPLE_PICKER.
- Effect: Open an eligible-recipient picker and return selected IDs to this event draft.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- PACT_CREATE.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- PACT_CREATE.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- PACT_CREATE.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- PACT_CREATE.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- PACT_CREATE.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **PACT_CREATE.01**: PactWrite; required body fields: title, sourceRecipeVersionId, scheduledAt, timeZone, inviteeIds; body fields available: title, sourceRecipeVersionId, scheduledAt, timeZone, inviteeIds; required operation headers: X-Device-Session, Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Invite a selected friend with a recipe reference and optional time; no automatic scheduling or food profile disclosure.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
