# NOTIFICATIONS — Choose your updates

Per-event opt-in preferences, quiet hours/time zone and OS permission status. Food/diet data stays out of lock-screen payloads.

- Group: Settings; proposed phase: P1; actor: member.
- Feature coverage: F41.
- Backend owner: conversations; threads · messages · reactions · notifications.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `GET /v1/notification-settings`, `EXTERNAL OS.notificationAuthorization`.
- Context: Resolve typed resource IDs from authorized navigation context; no URL or client ID is trusted as proof of access. New/create modes skip nonexistent-resource GET and use local drafts. Empty/missing picker context returns to the documented parent.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Replies: checkbox]
[Invitations: checkbox]
[Dinner Pacts: checkbox]
[Quiet hours: text]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Replies | checkbox | No | true |
| Invitations | checkbox | No | true |
| Dinner Pacts | checkbox | No | false |
| Quiet hours | text | No | 22:00–08:00 |

## Button and action contracts

### NOTIFICATIONS.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: SETTINGS.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### NOTIFICATIONS.01 — Save notification choices

- Visibility: When this screen and actor role are eligible.
- Trigger: `PATCH /v1/notification-settings` (`updateNotificationSettings`).
- Result: SETTINGS.
- Effect: Update user event preferences; worker rechecks current preference/membership immediately before send.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: Stable client operation ID for durable command; server idempotency record and transaction receipt. If-Match where versioned.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### NOTIFICATIONS.02 — Enable on this device

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: NOTIFICATION_PERMISSION.
- Effect: Preserve the current draft and open the destination.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### NOTIFICATIONS.03 — Open device notification settings

- Visibility: When this screen and actor role are eligible.
- Trigger: `EXTERNAL OS.openAppSettings`.
- Result: Stay / contextual return.
- Effect: OS permission and server preferences remain independent.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Retain input and retry online; do not show a durable success before server receipt.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- NOTIFICATIONS.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- NOTIFICATIONS.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- NOTIFICATIONS.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- NOTIFICATIONS.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- NOTIFICATIONS.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.

- **NOTIFICATIONS.01**: NotificationSettingsPatch; required body fields: none; body fields available: replies, reactions, invitations, remixes, cookingReminders, quietStartLocal, quietEndLocal, timeZone; required operation headers: X-Device-Session, Idempotency-Key, If-Match.

## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Per-event opt-in preferences, quiet hours/time zone and OS permission status. Food/diet data stays out of lock-screen payloads.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
