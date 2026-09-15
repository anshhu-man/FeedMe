# FeedMe — retained manual meal-flow host

14 September 2026. The shared app now contains a real controller-backed host for manual REQUEST → RECOMMENDATIONS → plan-context RECIPE. It is a production composition component, **not a sign-in flow, a configured production backend, or a completed native journey**. Existing demo entry points remain separate. The [44-feature V1 scope and 10 deferrals](V1_RELEASE_SCOPE.md) are unchanged.

## Components and required ownership

Subsequent acceptance: the [full third-batch run](PARALLEL_UI_HTTP_PROCESS.md) passed at2026-09-13T23:30:01.566Z, including all34 app and82 mealflow JVM tests and6 native presentation tests. All five corrected final native screenshots were visually reviewed. The focused evidence discussion below is historical; this acceptance still excludes actual login, the retained host/dialog/system-Back journey, iOS and release certification.

Current acceptance: the [fourth frozen batch](PARALLEL_KITCHEN_SOCIAL_HOST.md) passes129 mealflow/57 app JVM tests and nine additional actual retained-host native methods, covering system Back, dialog choices, native drafts, recreation and pantry/preferences pages. Synthetic account/transport and real native storage are distinguished in [RETAINED_MEAL_HOST.md](RETAINED_MEAL_HOST.md). The older host-test exclusion above describes the third checkpoint, not the additional fourth-batch evidence; live provider/client-server, cooking/save/share and release gates remain open.

[MealFlowExperience](../shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/MealFlowExperience.kt) has a private constructor. Its public `fromSession` factory requires actual `PrivateSessionAccess`, an `AccountTransport`, the actual `SessionBoundary`, the same serialized identity/UI dispatcher, clock, connectivity, native globally unique meal-operation IDs, meal/picker/kitchen-input policies, and configured equipment/taste choices. It requires a current lease and creates [MealRequestController](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/MealRequestController.kt), [IngredientPickerController](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/IngredientPickerController.kt) and [KitchenInputController](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/KitchenInputController.kt) from the same borrowed access. The required kitchen policy and third controller were added in the [fourth batch](PARALLEL_KITCHEN_SOCIAL_HOST.md); the historical third-batch receipt above does not certify those changes.

This factory does not accept a demo state as a session, mint credentials, activate storage, fabricate ingredient IDs, approve a recipe or upgrade offline-private access. Required transport/backend adapters still own canonical authorization and current catalog/input policy. Equipment/taste choices are supplied explicitly; there is no reviewed-catalog fallback.

[FeedMeMealFlow](../shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/FeedMeMealFlow.kt) requires that retained experience, `onExit`, and a **required** composable `platformBackHandler` bridge. There is no silent no-op Back default. Supplying this parameter is not proof of correct Android/iOS system-Back integration.

The application must retain the same experience across ordinary UI recreation, and close the old experience on actual journey/session disposal before dropping or replacing it. Construction and synchronous form/search edits must run on the same serialized identity/UI dispatcher as the boundary. The host collects current meal, picker, form, kitchen-input and child-page state and invokes local `restore()` when attached. Kitchen drafts restore before meal context. Attachment does not fetch context, search, submit a plan, retry a command or close native stores.

`MealFlowExperience.close()` redacts its form and closes the kitchen-input, picker and meal controllers under non-cancellable dispatcher ownership. The underlying private store, transport and native session are **borrowed**: this host neither closes/erases them nor clears a newer lease. Closing the editor does not release its same-lease pending-preference safety fence; actual invalidation redacts/releases it. External disposal must not treat a detached composable as ownership of native logout or recovery.

## Manual controls and explicit actions

The request screen offers explicit auto/cook/assemble/improve mode, effort, exact servings text, optional total/active minutes, configured equipment/taste choices, and Improve-mode preparation description/state. No numeric rounding or automatic text interpretation is performed. Existing-meal description and available ingredients do not invent a confirmed base composition.

Ingredient lookup renders actual downloaded catalog names and labels historical results. An explicit checkbox changes only the unsaved current-meal form. An explicit exclusion action changes only the form's exclusion overlay; saved hard exclusions still apply at request building. Unknown labels remain visibly unresolved. Pantry reports retain presence/confirmation distinctions and do not select ingredients, infer quantities or establish freshness/allergen safety. Separate [pantry/preferences child pages](KITCHEN_INPUT_EDITING.md) now provide local patches and original-command saves; whole-feature editing/conflict/authority acceptance remains open.

| Visible action | Controller behavior |
| --- | --- |
| Search / More ingredient matches | Explicit canonical search/pagination, or bounded offline label matching. The host requires nonblank search text. |
| Check pantry reports / More pantry reports | Explicit ephemeral pantry retrieval/pagination; not a stock mutation or second persisted pantry authority. |
| Edit pantry / Food preferences | Open a retained child page without I/O. Child Back returns to the original meal journey without discarding its form. |
| Save on a kitchen child page | Admit the exact canonical draft to the existing queue, then explicitly synchronize only that command when allowed. Rendering, restoration and opening a page never dispatch it. |
| Refresh preferences and pantry | Explicit meal-context refresh; no plan submission. |
| Save draft on this device | Validate and persist only the captured manual draft through the meal controller's acknowledged encrypted CAS. |
| Find a meal | First acknowledge the current form as a draft, then invoke explicit manual submission. Offline/uncertain outcomes remain distinct. |
| Retry original request | Retry the retained command's original key/body. Unsaved edits never replace that command. |
| Show another option | Use the saved request's eligible alternatives. Disabled and rejected while the form has unsaved edits. |
| Previous option | Navigate to an earlier retained plan, not a taste-dislike action. |
| View recipe | Open the selected historical Plan snapshot. Disabled/rejected for unsaved form changes and unavailable recipe content. |
| Back | Return from RECIPE to the same recommendation; otherwise return to REQUEST, or call the owning app's exit callback from REQUEST. |

All UI operations use the actual experience methods rather than fixtures or raw transport calls. The experience serializes ordinary actions and exposes busy state. Back is deliberately not queued behind a suspended network action. Existing issues/failures remain visible; unresolved original-command messaging is separate from edited form state. Cook, Save and Share are disclosed as unconnected separately authorized workflows, not success buttons.

## Unsaved form, acknowledgement and navigation

`MealFormOwner` retains partial text only in its current in-memory state. The host does not put the form/search payload in SavedState, Bundle, preferences or logs. Valid explicit saves use the separate meal controller; invalid partial text remains editable and dirty. Diagnostic strings redact private values, and published form collections are detached.

A form-generation ticket identifies the exact edit being saved. An older successful storage operation cannot mark newer edits saved. The owner checks current lease and generation **after** invoking an external edit transform, so a reentrant edit, close, logout or lease replacement cannot republish an obsolete private form. The save helper rejects a stale acknowledgement; it does not infer success from a matching local object.

Back with dirty form displays an explicit save/discard dialog. Save-and-go-back captures both the form ticket and a unique dialog intent. After the suspended save returns, navigation requires the same current intent, current lease/owner, unchanged form ticket and acknowledged non-dirty state. Dismissal or discard withdraws the prior intent. New edits, account replacement or close prevent the older receipt from authorizing exit. The final navigation decision reads the controller's current screen rather than an older captured screen projection.

Discard removes only the unsaved form overlay, restoring the controller's last saved draft. It does not discard an unresolved network command or erase private records. Back navigation itself preserves exact pending key/body, selected plan and failure information; a late response cannot reopen a superseded screen. The underlying controller behavior and byte/replay limits are described in [MEAL_REQUEST_FLOW.md](MEAL_REQUEST_FLOW.md) and the current [INGREDIENT_PICKER_FLOW.md](INGREDIENT_PICKER_FLOW.md).

The dialog token is not a new account capability or an owner-close substitute. The integrating application remains responsible for closing a replaced experience; `remember(experience)` alone does not cancel every coroutine holding an older object. Previously handed-out immutable values are not revocable: callers must render current state and must not retain an independent private-screen cache after invalidation.

## Presentation without a parallel recipe contract

[MealPlanPresentation](../shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/MealPlanPresentation.kt) uses the complete canonical `PlanWire` and pinned `RecipeVersionWire`, not `DemoKitchenRuntime`/flattened recipe models. Quantities, units, servings and duration tokens retain their exact wire spelling. Missing labels are explicitly marked; no name, reviewer, equipment mapping or source grant is generated.

The recipe preview shows actual ingredient preparation, equipment, full steps, mandatory safety flags, timing/estimate notes, recorded changes/reasons and catalog/version context. Displaying a duration starts no timer. Historical notices explain that retained content does not prove current rights, recall, availability or permission to cook/save. Needs-confirmation/no-match preserve an absent unresolved mode rather than inventing a concrete mode. Unavailable identity state hides private plan/form/picker details and leaves Back available.

## Evidence and limits

Focused run `32204` passed 82 mealflow, 31 app common/JVM, 88 server unit and 119 PostgreSQL methods, and compiled app/session native test APKs. Three subsequent pure form-owner regressions bring the current app source total to **34** methods; their corrected source requires its subsequent coordinated run. This document does not turn the earlier 31-method result into proof of those later edits. See [BUILD_STATUS.md](BUILD_STATUS.md) for later verified status.

The app common suites test exact presentation, form isolation/redaction, stale acknowledgements, reentrant transformations and saved-form exit checks. Mealflow's separate tests cover real controller sequencing through synthetic authenticated transport/detached store ports, including pending command preservation and stale/cancelled Back behavior. They do not prove native encryption or provider identity.

The six [AndroidMealFlowPresentationTest](../shared/app/src/androidInstrumentedTest/kotlin/com/feedme/app/mealflow/AndroidMealFlowPresentationTest.kt) methods use an instrumentation-only [MealFlowUiTestActivity](../shared/app/src/androidInstrumentedTest/kotlin/com/feedme/app/mealflow/MealFlowUiTestActivity.kt). It renders `MealJourneyScreen` with clearly labelled synthetic data and action counters. This exercises Compose layout/accessibility/rendered actions, **not** actual `fromSession`, the full `FeedMeMealFlow` dialog, real authenticated search, native encrypted draft storage or a live backend meal journey. A focused six-method event run passed at 2026-09-13T23:15:56.044Z. Subsequent screenshot-settling/inset corrections require a fresh run: neither final screenshot approval nor source-bound combined acceptance is claimed here. Compilation, partial attempts and earlier frames are not substitutes for that verification.

Remaining gates include actual app-root identity/backend composition, required native Back/dirty-dialog and recreation/background acceptance, the complete original-command reconciliation journey, approved catalog rights/review, cooking/save/recall integration and native platform privacy/accessibility acceptance. The app's normal demo entry points have not been silently replaced with fixture-backed “production” access. [USER_ACTIONS.md](USER_ACTIONS.md) provider/configuration/content gates remain explicit; no deployment, provider choice, purchase, P2/P3 enablement or public snapshot refresh follows from this implementation.
