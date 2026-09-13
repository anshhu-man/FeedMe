# ATTACH_RECIPE — Add the useful details

Choose a known cooked/saved recipe or provide explicit dish information. No inferred nutrient/allergen facts from media.

- Group: Publishing; proposed phase: P1; actor: member.
- Feature coverage: F24, F29.
- Backend owner: social; post_drafts · posts · media · save_grants.
- Layout: Mobile stack: app bar, current context, labelled fields/content, primary action, secondary actions, bottom navigation.
- Entry data: `LOCAL recipePickerContext`.
- Context: Existing-source mode requires an eligible authorized recipeVersionId. Manual community-recipe mode requires canonical ingredient quantities/units, structured steps, servings, equipment, active/total effort and source-rights attestation before review; visible entry labels are serialized with Attachment.personalRecipe schema, never directly as form state. A recipe request response accepts only an existing permitted recipe reference; manual details cannot be smuggled into its strict DTO.

## Wireframe and fields

```text
[App bar / title / back]
[Current meal, post, identity or operation context]
[Dish name: text]
[Recipe source link: url]
[Ingredients I can confirm: textarea]
[My preparation steps (for entered recipes): textarea]
[Recipe servings: number]
[My estimated total minutes: number]
[My estimated active minutes: number]
[Equipment used: text]
[Primary action and supporting controls below]
[Today | Cook | Cookbook | Inbox | My Plate]
```

| Field | Control | Required | Example/options |
| --- | --- | --- | --- |
| Dish name | text | No | My wrap |
| Recipe source link | url | No |  |
| Ingredients I can confirm | textarea | No |  |
| My preparation steps (for entered recipes) | textarea | No |  |
| Recipe servings | number | No | 1 |
| My estimated total minutes | number | No | 10 |
| My estimated active minutes | number | No | 5 |
| Equipment used | text | No | Bowl |

## Button and action contracts

### ATTACH_RECIPE.back — Back

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: EDIT_MEDIA.
- Effect: Return to previous allowed screen; retain draft/session state. Unsaved destructive exits ask before discarding.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ATTACH_RECIPE.01 — Choose from my cookbook

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: COOKBOOK.
- Effect: Open a scoped recipe picker for recipe attachment. On selection return to REVIEW_ATTACHMENT with a permitted recipe/source reference; cancel returns to ATTACH_RECIPE with its previous draft unchanged. Preserve draft/editPost/fulfillRequest mode throughout. Private-only saved source content cannot be reattached as redistributable text.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ATTACH_RECIPE.02 — Use my recent cook

- Visibility: When this screen and actor role are eligible.
- Trigger: `LOCAL navigate`.
- Result: REVIEW_ATTACHMENT.
- Effect: Load pinned session revision and changes for creator confirmation.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ATTACH_RECIPE.03 — Review entered details

- Visibility: publishing mode only.
- Trigger: `LOCAL navigate`.
- Result: REVIEW_ATTACHMENT.
- Effect: Treat user-entered recipe as community content until separately reviewed.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

### ATTACH_RECIPE.04 — Post without a recipe

- Visibility: draft mode only.
- Trigger: `LOCAL navigate`.
- Result: AUDIENCE.
- Effect: Attachment remains absent; viewers receive Ask for recipe rather than a fabricated Make Mine result.
- Authorization: member; Validate authentication scope, feature flag, resource ownership/membership, current revision and required field constraints on the server for network operations.
- Failure: 401 expired session → login with safe return; 403/404 unavailable → remove restricted content; 409/412 conflict → refresh and review; 422 invalid → inline correction; 429/503 → bounded retry with input retained.
- Offline: Available locally; reauthorize any destination data on access.
- Retry/concurrency: No durable command; provider/native-specific replay protection applies.
- Event: action_result with screen/action/feature IDs and redacted outcome only.

## Inherited bottom navigation

- ATTACH_RECIPE.nav0: **Today** → TODAY. Preserve draft where applicable; destination data is independently authorized.
- ATTACH_RECIPE.nav1: **Cook** → HOME. Preserve draft where applicable; destination data is independently authorized.
- ATTACH_RECIPE.nav2: **Cookbook** → COOKBOOK. Preserve draft where applicable; destination data is independently authorized.
- ATTACH_RECIPE.nav3: **Inbox** → INBOX. Preserve draft where applicable; destination data is independently authorized.
- ATTACH_RECIPE.nav4: **My Plate** → PROFILE_PLATE. Preserve draft where applicable; destination data is independently authorized.

## Serialization contracts

UI labels and example fixtures are not wire fields. Typed use cases resolve selected labels to canonical IDs, convert display enums and combine validated draft data with authorized route context. Generated request types must follow the linked OpenAPI schema; never serialize arbitrary form state. User authentication also requires the registered X-Device-Session header per the API.


## States and design acceptance

- loading: Preserve known context and reserve content geometry; disable only conflicting actions.
- empty: Choose a known cooked/saved recipe or provide explicit dish information. No inferred nutrient/allergen facts from media.
- error: Show the typed action error next to its trigger, retain non-secret input and expose recovery.
- offline: Use eligible private cache or a clear reconnect state; never simulate publication, payment, or authorization success.
- Validate default and conditional destinations, form errors, current role, lost responses, keyboard/text scaling, safe Back and applicable offline behavior on both native platforms.
