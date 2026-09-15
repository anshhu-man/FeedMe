# FeedMe — authenticated ingredient picker and pantry view

14 September 2026. This slice adds a GET-only ingredient/pantry controller to `:shared:mealflow`, plus safe local Back navigation for the existing meal-request controller. It supplies actual catalog IDs and labels to the new retained meal-flow host. It does not configure a provider, enable a backend route, edit pantry stock, submit a meal automatically, or complete F03/F53 end to end. The [44-feature V1 and 10 deferrals](V1_RELEASE_SCOPE.md) remain unchanged.

Focused run `32204` passed **82 mealflow common/JVM methods**: 52 meal-request methods, including five new Back regressions, and 30 new picker methods. It also passed 31 app common/JVM methods, 88 server unit methods and 119 PostgreSQL methods; app/session native test APK compilation succeeded. These are focused results, **not a final source-bound combined receipt or a passing native UI run**. The earlier [parallel-meal-startup receipt](verification/parallel-meal-startup/verification.json) is historical and does not verify these additions. See [BUILD_STATUS.md](BUILD_STATUS.md) for subsequent acceptance.

## Required composition and public API

Subsequent acceptance: the [full third-batch run](PARALLEL_UI_HTTP_PROCESS.md) passed at2026-09-13T23:30:01.566Z, including all82 mealflow tests, the now34 app tests and6 native presentation checks. The focused compilation-only discussion below is historical; actual authenticated provider/catalog/native-host integration remains open.

Current acceptance: the [fourth frozen batch](PARALLEL_KITCHEN_SOCIAL_HOST.md) includes these30 picker methods in129 mealflow tests, plus57 app JVM methods and nine actual retained-host native methods. The separate pantry/preferences editor is described in [KITCHEN_INPUT_EDITING.md](KITCHEN_INPUT_EDITING.md); it does not turn this picker into a mutation authority. Native account/transport remain synthetic, while provider/catalog/client-server and whole-feature gates remain open.

[IngredientPickerController](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/IngredientPickerController.kt) requires `AuthenticatedMealPlanningAccess`, the actual `SessionBoundary`, the same serialized identity dispatcher, an `EpochClock`, `ConnectivityPort`, and explicit `IngredientPickerPolicy`. The public access factory accepts a real `PrivateSessionAccess` and required `AccountTransport`; it borrows the session's exact lease, owner-scoped store, origin and online/offline-private mode. There is no signing-in, owner activation, catalog issuer, encryption, clock or policy fallback.

The controller exposes read-only `states: StateFlow<IngredientPickerState>` and these explicit suspending actions:

| Action | Implemented behavior |
| --- | --- |
| `restore()` | Restore eligible downloaded labels and the meal-request record's existing pantry page. No backend request. Explicitly prune expired persisted labels when necessary. |
| `search(query)` | Replace the prior search generation. Online, call canonical `searchIngredients`; offline, match only downloaded names and aliases. |
| `nextSearchPage()` | Fetch the current search's exact opaque cursor and query, within the configured page bound. |
| `refreshPantry()` | Fetch a fresh first pantry page into this controller's ephemeral view. No stock or request mutation. |
| `nextPantryPage()` | Fetch the current pantry cursor, retaining each row's historical/fetch disclosure. Unfetched rows are not known to be absent. |
| `close()` | Redact current state, detach the subscription and release this controller's process claim. Borrowed native stores, transport and session remain open. |

[Models](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/IngredientPickerModels.kt) expose `knownIngredients`, `searchResults`, `pantryItems`, `searchQuery`, separate `searchPhase`/`pantryPhase`, `searchHasMore`/`pantryHasMore`, `issue`, `failureReason` and optional `retryAfterSeconds`. Phases are `IDLE`, `LOADING`, `READY`, `EMPTY`, `OFFLINE`, `ERROR` and `UNAVAILABLE`. Collecting state starts no I/O. Lists are detached and diagnostic strings redact private details.

`IngredientOption` carries the actual catalog ID, name, aliases, category, complete canonical document, local check time and historical flag. `PantryOption` preserves the canonical row ID, ingredient ID, presence, confirmation status/time including missing versus null, staple flag, complete document, optional resolved label, optional local check time and historical flag. A missing label remains unresolved; neither the controller nor host invents a name or ingredient-ID mapping.

The app's [MealFlowExperience](../shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/MealFlowExperience.kt) creates the picker and meal controllers from the same actual session access; the [fourth batch](PARALLEL_KITCHEN_SOCIAL_HOST.md) adds a separate KitchenInputController without changing this picker's GET-only authority. The application owns that experience across UI recreation and closes it on actual disposal. The [FeedMeMealFlow](../shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/FeedMeMealFlow.kt) host collects current controller/form state. Its existence does not replace the application's demo entry points or supply the missing trusted composition.

## Canonical requests and bounded pagination

[04_API_Contract.json](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json) defines `searchIngredients` as `GET /v1/ingredients` and `listPantry` as `GET /v1/pantry/items`. Both permit genuinely authorized guest and account principals. This component does not create guest credentials or remove the transport's account device-session requirements.

Search sends exact query parameters `q`, `limit`, and, for continuation, `cursor`. Pantry sends `limit` and optional `cursor`. There is no request body, idempotency key, `If-Match`, pantry-write operation or planning operation. Empty `q` is schema-valid and the controller sends it explicitly; the current host requires nonblank search text. The controller rejects malformed Unicode, control characters and queries exceeding 100 Unicode scalar values rather than replacing them with guessed input. Returned cursors remain opaque; locally, blank/control-character cursors and cursors exceeding 2,048 UTF-16 code units are rejected.

`IngredientPickerPolicy` requires page size 1–50, at most 1–10 pages per search/pantry sequence, 1–512 retained catalog labels, and a label-retention interval from one millisecond through 30 days. These are resource limits, not approved product defaults. Successful page documents are bounded to 262,144 bytes and depth 16; each cached ingredient document is independently bounded to 32,768 bytes and depth 12. Oversized replies are refused, never truncated into apparently complete choices.

The canonical request validator runs for the actual principal. Response binding validates operation, status, content type, schema and Problem correlation before choices are accepted. Repeated cursors and duplicate ingredient/row identities within or across pages fail closed instead of mixing snapshots. A changed or expired backend cursor remains an explicit failure; this controller cannot refresh its authority locally. Page limits disable further local pagination, not prove that the catalog/pantry has no additional items.

Port retry delays are exposed, with no automatic retry. The current picker forwards `ApiReply.retryAfterSeconds`/port delay; it does not yet combine a body-only canonical `Problem.retryAfterSeconds` hint. This display limitation is separate from the meal-request controller's more complete mutation-retry policy.

## One label cache, not a second pantry authority

[IngredientPickerCache](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/IngredientPickerCache.kt) stores a schema-1 record at `RecordKey("mealflow.ingredients.v1", origin)` through the borrowed encrypted owner-scoped store. Its exact keys are `schema`, `origin`, `clock`, and `items`; each item holds its canonical document text and local `checked` time. The record is bounded to 524,288 bytes. Count/encoded-byte eviction removes older downloaded labels; it does not remove unresolved meal commands or manipulate the separate meal-flow record.

No query text, pantry stock, selected ingredients, credentials or meal-request intent is persisted in this label record. Every write requires the exact expected-revision CAS, a successful strictly newer revision receipt, and an exact revision/schema/payload readback. Failure or `OUTCOME_UNKNOWN` never publishes fresh search success from matching readback. A later explicit restore may expose a successfully written but previously unacknowledged GET cache as **historical downloaded metadata**; that observation grants no command, selection or stock authority.

The cache rejects wrong origin/schema, malformed or duplicate data, lower catalog versions and changed metadata at the same version. Version comparisons preserve exact integer values without floating-point rounding. Higher-version catalog metadata may replace an older retained label. Cache operations reject observed clock rollback/future timestamps. Expired labels are excluded from subsequent state publications, including when only the pantry pane is refreshed; explicit restore can prune their persisted entries. There is no autonomous expiry worker or continuously advancing freshness claim. The device clock and historical flag do not establish trusted server time or ingredient freshness.

Pantry refresh/pagination results are **ephemeral**. `restore()` can read the existing sole `RecordKey("mealflow.v1", origin)` pantry page maintained by [MealRequestController](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/MealRequestController.kt); it does not write or duplicate that pantry page. Restored pantry rows are historical and have no invented local fetch time. `usuallyHave`, `uncertain`, `low`, `out` and confirmation fields remain distinct. A label match or pantry report never selects a meal ingredient, confirms essential availability, infers quantity, or certifies storage/allergen safety. Actual selection is an explicit form action; acknowledged manual-draft persistence remains the meal-request controller's responsibility.

## Cancellation, invalidation and Back

The picker subscribes to actual boundary invalidation before I/O. Logout, account switch and same-account lease replacement synchronously clear its current query, choices, pantry rows and cached presentation references, and release its process claim. Lease/lifecycle checks and coroutine cancellation checks surround suspending operations and storage acknowledgements. Search and pantry have separate generations; a superseding search cannot publish its predecessor's late reply. One serialized mutex protects cache/paging operations. No cancelled or stale callback may turn a stored label into a fresh displayed success or erase another identity's state.

Immutable snapshots already handed to callers are not revocable capabilities. The host must render current state and must not retain an independent private-screen cache after invalidation. Closing this controller performs no scope erasure, native work cancellation or newer-lease clear.

Meal-request navigation now distinguishes:

- `returnToRecommendations()`: return from RECIPE to the **same selected recommendation**, not the previous alternative.
- `backToDraft()`: return to REQUEST while preserving the selected plan and any unresolved original command.
- `previousPlan()`: explicitly select an earlier retained plan; this is not Back.

Both Back actions are generation-fenced, read-only navigation. If validated/acknowledged cached state exists, they do not wait behind suspended transport or perform storage I/O. Without that cache they perform a validated read. They preserve visible issue/failure information, exact pending key/body and selected plan; they never promote an unacknowledged draft write. Late success/cancellation from the superseded request cannot reopen another screen or discard its durable unresolved command. Actual lease invalidation always takes priority. Recipe navigation remains a historical preview, not authorization to cook, save or post.

The host keeps unsaved partial form text distinct from the saved draft and from an already dispatched command. Dirty form state disables new alternatives/recipe entry; retrying the original unresolved request remains a separate action. Neither Back nor ingredient lookup sends that command automatically.

## Evidence and remaining acceptance

The [30 picker tests](../shared/mealflow/src/commonTest/kotlin/com/feedme/mealflow/IngredientPickerControllerTest.kt) cover canonical guest/account calls, query/paging bounds, exact metadata, uncertain pantry facts, offline recreation, corruption/version/clock checks, cache CAS/readback failures, supersession/cancellation, close and lease redaction. The [52 meal-request tests](../shared/mealflow/src/commonTest/kotlin/com/feedme/mealflow/MealRequestControllerTest.kt) include the five Back regressions. Their detached store and transport fixtures prove protocol behavior, **not native cryptography or provider authorization**.

The app's 31 common tests verify form acknowledgement/redaction and exact plan presentation. Six compiled Android presentation-test methods exercise synthetic Compose rendering/events, not real identity, authenticated search, native encrypted label storage or the complete `FeedMeMealFlow`/`MealFlowExperience` journey. Their runtime result must be established separately; this document does not infer it from compilation.

[F03 ingredient input](../../outputs/biteclub_blueprint/features/F03.md), [F09 alternatives](../../outputs/biteclub_blueprint/features/F09.md) and [F53 navigation/offline](../../outputs/biteclub_blueprint/features/F53.md) gain bounded implemented components here. Remaining acceptance includes trusted identity/backend and reviewed catalog composition, actual native controller-to-backend journeys, host dirty-exit/platform-Back behavior, lifecycle/recreation testing, and full pantry editing/confirmation flows. [USER_ACTIONS.md](USER_ACTIONS.md) provider/configuration/content-rights gates remain unresolved. No HTTP activation, deployment, purchase, provider choice, P2/P3 feature, or public repository refresh is authorized by this handoff.
