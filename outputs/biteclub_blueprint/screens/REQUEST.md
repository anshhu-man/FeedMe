# REQUEST — Tell us what you have

Short natural-language request and explicit meal mode. Show only additional questions needed for the selected plan.

- Group: Kitchen; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F02, F03, F07.
- Backend owner: planning; plan_requests · plan_variants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/preferences`, `GET /v1/pantry/items`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Your situation *: textarea]
[Meal mode *: select]
[Meal already prepared (Improve mode): text]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Your situation | textarea | Yes | Rotis, yogurt, cucumber. Ten minutes. Very little energy. |
| Meal mode | select | Yes | Cook; Assemble; Improve an existing meal |
| Meal already prepared (Improve mode) | text | No | Noodles |

## Button and action contracts

### REQUEST.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: HOME.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REQUEST.01 — Check ingredients

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: PANTRY.
- Effect: Parse draft into candidate ingredient IDs for user confirmation; unknown terms remain unresolved.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REQUEST.02 — Set time and effort

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: EFFORT.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REQUEST.03 — Find a meal

- Visibility: When this screen and actor role are eligible.
- Trigger: `POST /v1/plans` (`createPlan`).
- Result: RECOMMENDATIONS; conditional: PANTRY when unknown ingredients need confirmation; EFFORT when incompatible/unclear budget; UNAVAILABLE when no supported match.
- Effect: Interpret text server-side into a strict constraint schema, confirm ambiguity, filter reviewed revisions then rank. Save an immutable plan receipt.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### REQUEST.04 — Choose a taste

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: TASTE.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- REQUEST.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- REQUEST.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- REQUEST.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- REQUEST.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- REQUEST.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **REQUEST.03**: PlanRequest; required body fields: mode, constraints; body fields available: mode, sourceRecipeVersionId, sourcePostId, savedRecipeId, constraints, preferenceVersion, naturalLanguage, confirmedInterpretation, intent, baseMeal; required operation headers: Idempotency-Key.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Short natural-language request and explicit meal mode. Show only additional questions needed for the selected plan.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
