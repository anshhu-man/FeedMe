# Cooking UI integration plan

The five behavioral integration tasks now have an **implemented and bounded-accepted shared Compose host**. The source-bound run passed at **2026-09-14T03:28:36.056Z**, from attempt **2026-09-14T03-16-38.009Z**: **172 mealflow JVM tests**, including **43 cooking-controller tests**, **77 app JVM tests** (20 added here), and **nine actual Android cooking-host tests**. Combined acceptance is **2,418 Kotlin/server/database tests, 192 Node checks and 354 native successes plus seven separately witnessed interruptions**. See the [current receipt](verification/parallel-cooking-ui-timers/verification.json) and [parallel handoff](PARALLEL_COOKING_UI_TIMERS.md). This reuses the existing visual system; it is not a redesign, production provider entry point or visual-release certification.

The preceding accepted controller baseline is historical for this changed source: **170 mealflow JVM tests, including 41 cooking tests**, within **2,266 combined Kotlin/server/database tests, 181 Node checks and 345 native successes plus seven separately witnessed interruptions**. That run passed at **2026-09-14T01:54:18.106Z**, from attempt **2026-09-14T01-46-16.626Z**. See the [prior receipt](verification/parallel-kitchen-cooking/verification.json), [batch handoff](PARALLEL_KITCHEN_COOKING.md) and current [BUILD_STATUS](BUILD_STATUS.md). Those native totals do not certify the new cooking host.

The earlier 169-test/40-cooking checkpoint and the first full pass precede the consent-projection correction described below. They remain historical, along with the documented focused failures and fixes; current acceptance uses the corrected source, not those earlier checkpoints.

Keep the existing `FeedMeTheme`, colors, typography, cards, spacing, safe insets and Compose controls. The production starting point is `shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/MealFlowExperience.kt` and `FeedMeMealFlow.kt`, backed by [CookingFlowController](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/CookingFlowController.kt). The demo `App.kt`/`FeedMeScreens.kt` route names are useful vocabulary, **not** production state or action authority: their sample step-completion, Save and Share handlers must not be connected to this flow.

## Implemented source and verification checklist

`MealFlowExperience.fromSession` now requires an explicit `CookingFlowPolicy` and owns one borrowed `CookingFlowController`. `CookingUiOwner` retains only ephemeral navigation and unique consent tickets; actual boundary invalidation clears them synchronously. `CookingFlowPresentation` projects the actual immutable controller observation, and `CookingFlowScreen` renders it without a parallel progress reducer. `FeedMeMealFlow` wires the explicit actions and Android Back bridge.

The native selector is `com.feedme.app.mealflow.AndroidCookingFlowHostTest` (nine methods). It uses the existing actual `NativeMealFlowTestSession`, retained `NativeMealFlowTestActivity` and encrypted store, with a deliberately synthetic canonical transport. The [retained native report](verification/parallel-cooking-ui-timers/attempts/2026-09-14T03-16-38.009Z/android-cooking-host/report.json) records nine unique ordered start/success pairs, zero failures/errors/skips and seven 1080×1920 PNGs. Their bytes/hashes and enclosing source-method mappings were checked; all seven captures were visually inspected. The host tests cover retained 201/download uncertainty, not a newly injected native final-attachment-ACK failure; exact finalization proof continues to have the controller's separate protocol tests.

The original five task descriptions below remain the behavioral acceptance checklist. Their bounded common/native and combined evidence is now accepted; unimplemented production-root/provider/iOS and release-polish gates remain explicit.

### Native captures, final assertions and retained failures

All capture paths below are inside the final attempt's `android-cooking-host` directory. Each PNG records a particular asserted stage, not necessarily the method's final state or every control in its scrollable view.

| PNG | Exact source method | Observed stage and subsequent method outcome |
|---|---|---|
| `cooking-confirmation.png` | `exactPreparedConsentSurvivesRecreationAndBackNeverStartsCooking` | Exact synthetic Plan A/1.5000 servings, explicit confirmation and Not now; zero POST before consent. |
| `cooking-progress.png` | `exactPreparedConsentSurvivesRecreationAndBackNeverStartsCooking` | COOK, exact Plan A and current first step after one original create/download; stale ticket rejected. |
| `cooking-pending.png` | `retainedCreatedReceiptRetriesDownloadWithoutSecondPost` | OFFLINE/unacknowledged download and withheld instructions; after recreation, the original receipt retry reaches cooking with no second POST. The retry control is below this captured viewport, not visually proven by this frame alone. |
| `cooking-done-pending.png` | `explicitOfflineProgressAndCompletionUseHeadOnlySyncAndNeverAutoFinishLastStep` | “Finished on this device · waiting to sync,” historical disclosure and three retained actions. |
| `cooking-done-acknowledged.png` | `explicitOfflineProgressAndCompletionUseHeadOnlySyncAndNeverAutoFinishLastStep` | Completion acknowledged after three explicit ordered head synchronizations; completion uses no If-Match and `makeAgain: false`. |
| `cooking-unavailable.png` | `invalidationRedactsConsentAndNoncooperativeCreateCannotRevivePrivateScreen` | UNAVAILABLE, account/private-content redaction and no retained recipe or confirmation revival after the delayed callback. |
| `cooking-retained-recipe.png` | `immediateBackDuringDownloadAndReopenedOwnerKeepOriginalReceiptAndPinnedRecipe` | Exact pinned Plan A and Return to cooking; subsequent controller replacement restores the same pin historically without a new POST. |

The other four passing methods verify exact Plan B/new-ticket consent after rejecting the stale ticket, original-key/body retry after a lost create response, stricter-preference blocking with explicit safe stop, and missing-provider/exact-recall rejection with no eligible preview or create. They are part of the nine source-matched results, not inferred from the seven PNGs.

The captures show weak white status-bar-icon contrast against the light background, dense development copy/raw UUID wrapping, and scroll-dependent controls. They establish the stated behavioral views, **not finished visual polish, contrast/accessibility certification or release acceptance**. No image was substituted or regenerated.

Earlier native attempts remain retained. The initial attempt failed all nine methods before UI because the helper created `no_backup` with an unsupported mode; the helper was corrected to inspect an absent/existing owned parent without creating or chmodding it. The next attempt passed six and failed three: the real retained-201 download path lacked an immediate retry projection, the synthetic recall Problem omitted required `traceId`, and an identical-text dialog replacement could leave a stale composed callback in the test. The controller now retains only the validated receipt command observation before download; the fixture supplies the canonical trace ID; and the consent test observes dismissal/new presentation and awaits exact Plan B/two-create state. Guards were not weakened. Those three methods passed focused rerun, then all nine passed the final combined run.

### 1. Add one retained cooking owner to the existing experience

**Files:** extend `MealFlowExperience.kt`; narrowly extend cooking state/projection only as needed; add focused common experience/projection tests.

Add `val cooking: CookingFlowController` to the private experience constructor. `fromSession` creates it from the **same** `AuthenticatedMealPlanningAccess`, actual boundary, serialized dispatcher, clock, connectivity and globally unique ID source as the other controllers, with a required `CookingFlowPolicy`. Do not introduce another native owner, store, queue, transport provider or persisted UI cooking model. The controller already composes the real `PrivateKitchenSession` and its queue.

`restore()` should restore cooking locally after the existing preference/meal restoration, without automatic network, start, confirmation, retry or synchronization. UI recreation keeps the same experience; restoration is observation, not authority reconstruction. `close()` must also close/redact the cooking controller while continuing to leave all native resources borrowed. Actual session invalidation hides every meal, cooking and dialog payload synchronously.

The controller's consent projection was corrected during this review and passed final verification: a PREPARED proposal may coexist with an older selected cooking pin, but `CookingFlowState.plan` refers to the exact prepared Plan, or null when that proposal is blocked. `cooking` can still observe the older selected pin. The accepted two-distinct-Plan/blocked-proposal regression covers this distinction. UI confirmation must consume `state.plan`, never fall back to `state.cooking.plan`, read the private record codec from `shared/app`, or infer the proposal from a title/history position.

**Acceptance:** one retained cooking controller; no construction/restore transport; disposal does not erase/close borrowed resources; same-lease finalization survives controller replacement as supported by the protocol; foreign/stale leases expose no private payload; exact proposal and previous pin cannot be confused.

### 2. Wire RECIPE to explicit preparation and confirmation

**Files:** extend `MealFlowExperience.kt`, `MealScreenActions`/recipe branch in `FeedMeMealFlow.kt`; add a small confirmation presentation/action type if useful.

Add experience actions that use the existing `perform` admission gate for `prepareStart`, `confirmStart`, original retry and explicit unsent discard. A new start must also reject dirty ephemeral form state; saving/dispatching that form is a separate user action. Capture the currently displayed retained Plan ID and the form ticket on the serialized owner, then call `cooking.prepareStart(id)`. Never pass a copied recipe body, a catalog candidate or the demo `StartCooking` action.

Only a successful, current START_CONFIRMATION proposal opens the start confirmation. Show that exact Plan, servings and the fact that confirming creates a server cooking session. Use a unique dialog intent tied to the same experience/current selection/form generation. Clear it on dismissal, navigation, replacement or lease invalidation; recheck it **inside the admitted action** before calling argument-free `confirmStart()`. A delayed coroutine from an old dialog must not confirm a newer proposal. The controller's own context/lease checks remain mandatory.

Distinguish three actions:

- **Confirm start** calls `confirmStart()` once; it does not create a new proposal/key.
- **Not now / Back** dismisses the view and returns to the recipe without erasing or dispatching the retained proposal. Offer an explicit way to review that same proposal again.
- **Discard unsent start** calls `discardUnsentStart()` only when that action is available. It is never offered as discard/reset for an attempted unknown outcome.

A current owned historical Plan is not sufficient: the controller still checks current preferences/source revision, published reviewed content, recall and real backend authorization. Offline/provider-unavailable/context-changed responses remain visible; no sample-provider fallback or optimistic cooking transition.

**Acceptance:** render/recompose/dismiss/Back cause zero POSTs; double tap cannot enqueue two creates; stale dialog, dirty form and changed Plan cannot confirm; the actual POST occurs only after explicit confirmation and retains the exact original key/body through uncertainty.

### 3. Render COOK from the real retained snapshot and wire only explicit actions

**Files:** new `CookingFlowPresentation.kt` and `CookingFlowScreen.kt` under `shared/app/.../mealflow`; extend the retained host's state collection and experience action wrappers. A small move of existing private `Hero`, `InfoCard`, `Primary`, `Pill` or section helpers into a shared internal component file is sufficient; do not redesign the visual system.

Collect `experience.cooking.states`. Render only its permitted Plan/CookingSnapshot projection. Resolve step IDs against the immutable materialized Plan; show the actual `currentStepId`, `completedStepIds` and exact instruction/quantity text. Do not create a parallel `viewedStep`/completed-set/`isDone` authority or flatten the Plan into demo `MealUi`. Distinguish navigation to a step from marking it completed; a missing step is an unavailable/integrity state, not index clamping to a guessed step.

| Visible action | Exact controller call | Required UI behavior |
|---|---|---|
| Previous/choose step | `moveTo(stepId)` | Use actual Plan step IDs; enable only with `canEdit`. |
| Mark this step complete | `markStepComplete(stepId)` | Keep it separate from automatic advancement/completion. |
| Pause / resume | `pause()` / `resume()` | Respect `canStop` / `canEdit`; show locally retained versus synced state. |
| Stop this session | `abandon()` after explicit stop confirmation | Never invoke from Back, UI disposal or session replacement. |
| Sync this session | `synchronize()` | Explicitly process only the selected session's head; do not drain unrelated commands. |
| Refresh downloaded session | `refresh()` | Explicit network action; not an acknowledgement of a pending command. |
| Finish cooking | `complete()` after an explicit completion action | Do not invoke merely because the last step is viewed/marked. |

Surface pending queue rows **and** `cooking.pendingCommandIds` for not-yet-materialized local actions. Do not label local progress or a terminal-looking queue row as server success. Show retry timing and typed reconciliation/unavailable status without inventing a new-key retry. `FINALIZATION_REQUIRED` uses the retained original start's `retryStart()`; it is not ordinary step synchronization or an acknowledged APPLIED receipt.

Keep known-recall/unreviewed/incomplete instructions hidden. Pending stricter preferences disable continued cooking/resume/completion and unsafe dispatch; explicit safe pause/abandon still follow the controller's availability checks. Conflicting remote state is read-only reconciliation, not an automatic merge or choice of whichever version looks newer. Missing provider/authentication is an unavailable state owned by the app/session root.

Durations may be displayed with **“no timer started”**. No timer button, notification permission prompt, running countdown or restoration claim belongs to this package.

**Acceptance:** a local edit updates only after repository acknowledgement; a failed/superseded action cannot overwrite displayed progress; the head's actual received ETag is used by the controller; last-step handling cannot send completion automatically; stopped/recalled/foreign-origin views do not expose enabled cooking actions.

### 4. Derive MEAL_DONE and Back behavior without a second route authority

**Files:** host routing/projection in `FeedMeMealFlow.kt` and the new cooking presentation; narrow experience navigation wrappers and dialog tests.

`CookingFlowScreen` currently has RECIPE/COOK, not a persisted MEAL_DONE route. Derive the completion presentation from the controller's completed cooking state rather than changing the demo route reducer or persisting a new `done` flag. A completed local action with pending commands should say **“Finished on this device · waiting to sync”**. An acknowledged completion and a historical restored completion need distinct copy; `historical`/`serverAcknowledged` must not be collapsed. Conflict/unavailable/recalled state overrides celebratory success.

Route Back from actual current state, not the screen captured before an await:

- Confirmation Back dismisses that dialog only and never confirms/discards a dispatched command.
- COOK/MEAL_DONE Back invokes `cooking.backToRecipe()` directly, outside the experience's busy action gate. It is cached, immediate and non-mutating; it must preserve finalization proof and in-flight original command bytes.
- Returning from the pinned recipe uses the new symmetric `returnToCooking()` cached navigation, not `open(id)` or another record write. It rejects a missing pin and PREPARED/QUEUED old-pin/new-proposal joins. Leaving the cooking recipe also fences a suspended cooking operation, without discarding its original command.
- The resulting cooking recipe view refers to that session's exact pinned Plan, even if a newer meal request has selected another Plan. **Return to the meal journey** is a separate presentation navigation event; do not silently substitute `meals.states.value.plan` for the cooking pin.
- Once back in the ordinary meal journey, use its existing RECIPE → RECOMMENDATIONS → REQUEST behavior and exact dirty-save ticket dialog. “Previous option” is never Back from cooking.
- Preserve the required `platformBackHandler` bridge. Android system Back, visible Back and dialog dismissal must exercise the same handler precedence; Back remains available during loading/errors. The host's `onExit` still delegates final experience/session ownership to the app root.

Keep Save and Make Again disabled with explicit unavailable copy until their separate production controller/HTTP workflows are implemented. Do not connect demo callbacks, label `complete(makeAgain=false)` as Save, reissue `createCookSession` against an old Plan as Make Again, or treat the separate social workflow as cooking completion authority. Feedback/share also remain separate explicit actions; none occurs automatically on MEAL_DONE.

**Acceptance:** completion never auto-saves/shares/restarts; Back never completes, abandons, discards or rewrites pending records; a suspended old completion cannot navigate a newer experience; backing out of a proof-required state keeps the original-ID finalizer available.

### 5. Verify the real retained host, then retain honest native release gates

**Files:** common app tests for the new projections/experience admission; extend the actual `AndroidMealFlowHostTest` fixture or add a focused `AndroidCookingFlowHostTest` with its own exact selector/evidence. Pure presentation tests supplement, not replace, actual `MealFlowExperience.fromSession` tests.

Use the existing test pattern: real public native session/store, retained experience on its serialized dispatcher and explicitly synthetic authenticated transport/reviewed bodies. Add canonical create/get/update/complete fixture operations with exact request counters/keys; do not make a permissive fake SPI or presentation callback the success path. Reuse source-owned cleanup that retains failed native ownership rather than deleting unknown state. Root-owned verification must capture actual method identities and fresh app test-APK evidence before acceptance.

Minimum behavioral matrix:

- RECIPE → prepared confirmation → one explicit create → exact download → COOK; wrong/missing provider/context, published/retired/recall gates, dirty form and distinct prior/new Plan confirmation.
- Lost create reply, failed download, malformed reply and lost attachment ACK: original-ID retry, visible finalization-required state, no new POST after a retained 201, no discard/reset for attempted outcomes.
- Offline edits, sequential head synchronization, pause/abandon versus stricter preferences, explicit completion, and local-pending versus acknowledged/historical MEAL_DONE copy.
- Noncooperative network/store callbacks, actual boundary invalidation while a dialog or render is visible, older caller-return cancellation versus a newer action, and no late private content or navigation.
- Actual Android system Back during confirmation, queued start, COOK, completion, error and dirty meal form; visible Back and dialog precedence must match. Assert no hidden complete/abandon/Save/Make Again/Share/timer effects.
- Activity/Compose recreation with the same experience preserves real controller state and unsent/unknown intent without auto-confirming or resending. If detachment cancels an action, the next screen shows its retained reconciliation state. A new trusted owner can restore acknowledged local records; process-only finalization proof must not be reconstructed from a persisted APPLIED row.
- Settled-frame visual inspection and accessibility assertions for the reused components, scroll reachability, disabled actions, loading/error/recall copy and safe insets. Pixel captures must correspond to the final accessible state, not an earlier transition frame.

The actual production Android root still needs an authorized session/provider entry point to compose this host; `NativeMealFlowTestActivity` is not that root. iOS compilation/shared source availability is not native acceptance: a retained iOS owner, native Back/navigation/lifecycle handling, secure persistence and device-level behavior remain separate release gates. Do not claim signed content-manifest, timer/native work or real provider/backend acceptance from synthetic host tests.

## Delivery boundary

These five tasks wire existing deterministic/protocol logic to the existing shared Compose presentation. They do not select a provider, approve recipe content, prove deployed cooking HTTP, add a new storage/session authority or widen V1 to the ten deferred features. [COOKING_FLOW](COOKING_FLOW.md) describes the controller; [MEAL_FLOW_UI](MEAL_FLOW_UI.md) describes the pre-cooking host baseline. The current receipt accepts the bounded nine-method native host and seven exact captures, not a shipping entry point or polished release UI. The separately accepted timer foundation does not add timers to this cooking UI. Timer UI/notifications, Save, Make Again, feedback/share, signed content manifests and a real provider-backed Android/iOS application root remain explicit separate gates. The separate proposed [Android progress host](ANDROID_PROGRESS_HOST_PLAN.md) is not yet that production root.
