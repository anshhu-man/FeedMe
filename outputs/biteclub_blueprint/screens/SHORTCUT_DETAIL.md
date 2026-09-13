# SHORTCUT_DETAIL — Shortcut Swap

Tip, recipe/source, community label and save/report actions.

- Group: Shared meals; proposed phase: P3; actor: member.
- Feature coverage: F36.
- Backend owner: coordination; sos · pacts · potlucks · polls · shortcuts.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/shortcuts/{shortcutId}`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]

[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

## Button and action contracts

### SHORTCUT_DETAIL.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECIPE.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SHORTCUT_DETAIL.01 — Save this tip

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/shortcuts/{shortcutId}/saves` (`saveShortcut`).
- Result: Stay / contextual return.
- Effect: Save permitted private tip reference with attribution; recheck revocation before display.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SHORTCUT_DETAIL.02 — Helpful

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/shortcuts/{shortcutId}/helpful` (`setShortcutHelpful`).
- Result: Stay / contextual return.
- Effect: One actor state per tip; no inference that popularity means safety review.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SHORTCUT_DETAIL.03 — Open recipe

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: RECIPE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SHORTCUT_DETAIL.04 — Report tip

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REPORT.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SHORTCUT_DETAIL.05 — Share my shortcut

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: SHORTCUT_CREATE.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SHORTCUT_DETAIL.06 — Withdraw my shortcut

- Visibility: When this screen and actor role are eligible; explicit confirmation required.
- Trigger: `DELETE /v1/shortcuts/{shortcutId}` (`deleteShortcut`).
- Result: RECIPE.
- Effect: Author removes tip from future social display and flags private references unavailable; never edit reviewed recipe steps.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### SHORTCUT_DETAIL.07 — Keep as my cooking note

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/cook-sessions/{sessionId}` (`updateCookSession`).
- Result: COOK.
- Effect: Attach explicit community tip text as a private personal note; reviewed steps stay unchanged and the note retains its unreviewed label.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- SHORTCUT_DETAIL.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- SHORTCUT_DETAIL.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- SHORTCUT_DETAIL.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- SHORTCUT_DETAIL.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- SHORTCUT_DETAIL.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **SHORTCUT_DETAIL.01**: Empty; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key.
- **SHORTCUT_DETAIL.02**: HelpfulWrite; required body fields: helpful; body fields available: helpful; required operation headers: X-Device-Session, Idempotency-Key.
- **SHORTCUT_DETAIL.06**: No JSON body; required body fields: none; body fields available: none; required operation headers: X-Device-Session, Idempotency-Key, If-Match.
- **SHORTCUT_DETAIL.07**: CookPatch; required body fields: deviceSequence; body fields available: status, currentStepId, completedStepIds, deviceSequence, timers, personalNotes; required operation headers: Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Tip, recipe/source, community label and save/report actions.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
