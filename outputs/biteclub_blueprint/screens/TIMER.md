# TIMER — Step timer

Named timer with duration and absolute local deadline. OS notifications are best effort; foreground remaining time derives from deadline.

- Group: Kitchen; proposed phase: P1; actor: guest-or-member.
- Feature coverage: F12, F53.
- Backend owner: cooking; cook_sessions · step_events.
- Layout: Focused full-screen task with persistent context and a clear close/back action.
- Entry data: `LOCAL timerDeadline`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Duration in seconds *: number]
[Active timer *: select]
[Primary action and supporting controls below]
[Focused exit / native or operational context]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Duration in seconds | number | Yes | 120 |
| Active timer | select | Yes | Current step; Another active timer |

## Button and action contracts

### TIMER.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: COOK.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TIMER.01 — Start timer

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OS.scheduleLocalTimer`.
- Result: Stay / contextual return.
- Effect: Persist timer ID and deadline, request context-specific local notification permission if needed; no server alarm dependency.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TIMER.02 — Pause timer

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL update`.
- Result: Stay / contextual return.
- Effect: Persist remaining duration and cancel pending OS notification.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TIMER.03 — Reset timer

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL update`.
- Result: Stay / contextual return.
- Effect: Cancel current notification and restore chosen duration; no cook-step advancement.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TIMER.04 — Back to cooking

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: COOK.
- Effect: Preserve the current draft and open the destination.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TIMER.05 — Resume timer

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OS.scheduleLocalTimer`.
- Result: Stay / contextual return.
- Effect: Use persisted remaining duration to create a fresh deadline and best-effort OS alert; never restart full duration silently.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### TIMER.06 — Cancel timer

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OS.cancelLocalTimer`.
- Result: COOK.
- Effect: Cancel the selected timer/notification and retain other active timers and cooking progress.
- Authorization: guest-or-member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.


## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Named timer with duration and absolute local deadline. OS notifications are best effort; foreground remaining time derives from deadline.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
