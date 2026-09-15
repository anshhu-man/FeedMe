# Retained manual cooking flow

## Status and boundary

**Current bounded acceptance:** the shared retained cooking host and controller passed the source-bound run at **2026-09-14T03:28:36.056Z**: **172 mealflow JVM tests**, including **43 cooking-controller tests**, **77 app JVM tests**, and **nine actual native cooking-host tests**. The combined result is **2,418 Kotlin/server/database tests, 192 Node checks and 354 native successes plus seven separately witnessed interruptions**. The host uses an explicit required `CookingFlowPolicy`, exact ephemeral consent tickets, COOK/derived MEAL_DONE presentation and cached `returnToCooking()` navigation. See the [current receipt](verification/parallel-cooking-ui-timers/verification.json), [parallel handoff](PARALLEL_COOKING_UI_TIMERS.md) and [implementation/evidence checklist](COOKING_UI_INTEGRATION_PLAN.md).

`shared/mealflow` contains the bounded production, provider-independent `CookingFlowController`, its models and encrypted record codec. The **historical controller baseline** passed at **2026-09-14T01:54:18.106Z**: **170 mealflow JVM tests**, including **41 cooking-flow protocol tests**, within **2,266 combined Kotlin/server/database tests, 181 Node checks and 345 native successes plus seven separately witnessed interruptions**. Its [receipt](verification/parallel-kitchen-cooking/verification.json), retained [2026-09-14T01-46-16.626Z attempt](verification/parallel-kitchen-cooking/attempts/2026-09-14T01-46-16.626Z/verification.json), and [batch handoff](PARALLEL_KITCHEN_COOKING.md) remain immutable historical evidence.

The cooking protocol tests use the real kitchen repositories and durable command queue with explicit synthetic transport/CAS fixtures. The current nine Android host tests use actual `MealFlowExperience.fromSession`, native encrypted stores, retained controllers and Android Back/recreation, with explicitly synthetic account/service/content inputs. Seven source-mapped PNGs were inspected for their intended states; status-bar contrast and general visual polish are **not release-accepted**. The original baseline did not include this native cooking host. Neither batch establishes deployed cooking HTTP, real identity/content providers or qualified recipe review. Earlier focused failures, the superseded controller run before the consent-projection correction, and this batch's native fixture/retry-projection failures remain historical; the current receipt accepts the corrected source.

This is a bounded F12/F13 integration, not completion of those features. A production provider-backed Android root, deployed cooking HTTP/provider acceptance, iOS host acceptance, timer UI/notifications, saved-recipe actions, optional completion follow-ups and the content-manifest contract remain separate gates. The timer foundation accepted alongside this batch does not wire timers into this cooking UI. All 44 V1 features remain in scope; the ten already-deferred features stay outside V1. See [V1 release scope](V1_RELEASE_SCOPE.md), [build status](BUILD_STATUS.md), and [private kitchen repositories](PRIVATE_KITCHEN_REPOSITORIES.md) for their current status.

## Composition and ownership

Construct the controller with:

- `AuthenticatedMealPlanningAccess.fromSession(actualPrivateSessionAccess, actualAccountTransport)`;
- the same `SessionBoundary` and serialized identity dispatcher that own that session;
- required clock, connectivity, native globally unique command UUID source and `CookingFlowPolicy`.

There is no permissive identity/provider adapter, demo engine, review default or offline server-ID generator. The real authenticated transport must implement the canonical account/guest authorization rules. Connectivity cannot upgrade an offline-private session to online access. The required profile specifies proposal age and bounded session/preferences response sizes; it must be agreed with the actual backend/transport integration, not mistaken for a global canonical API limit.

The controller borrows the session's scoped store and transport. Internally, it composes one actual `PrivateKitchenSession` and uses **that session's queue** for creation and subsequent repository actions. Its create-only execution gate cannot authorize unrelated operations; cooking progress and completion retain the repository's exact head/origin/body/ETag gates. It does not create a second cooking journal or expose that queue publicly.

`close()` invalidates this controller and releases its controller claim, but does not close, erase or retire borrowed storage, credentials, transport, the session boundary or another controller. Exact unresolved finalization/recall evidence can outlive this controller under a same-lease process guard; its own one-shot boundary subscription releases that evidence on actual lease invalidation. Closing an editor is not permission to forget an attempted command.

## Public actions and state

All actions return `PortResult<CookingFlowState>`, except `close()`, which returns `PortResult<Unit>`. Observe the read-only `states: StateFlow<CookingFlowState>`; retain the controller across presentation recreation.

| Action | Implemented behavior |
|---|---|
| `restore()` | Reads local flow, selected cooking pin and queue observations. No HTTP or automatic send. |
| `prepareStart(planId)` | Validates the current selected manual meal and fresh backend context; stores a proposal without POSTing. |
| `confirmStart()` | Explicitly admits the prepared original create command and attempts dispatch. |
| `retryStart()` | Explicitly retries/reconciles the retained original create command, never a new key/body. |
| `discardUnsentStart()` | Discards only a prepared proposal or a queue command with zero attempts. Never discards attempted uncertainty. |
| `open(sessionId)` | Selects an already-downloaded owned bundle; it is not an arbitrary download or an origin-rebinding grant. |
| `refresh()` | Explicitly downloads the selected owned CookSession and its exact materialized Plan. A refresh is not a pending command receipt. |
| `moveTo(stepId)`, `markStepComplete(stepId)` | Persist local repository intent/progress with the actual local revision and a unique command ID. No automatic send or completion. |
| `pause()`, `resume()`, `abandon()` | Explicit status edits. Back never invokes them. |
| `complete()` | Explicit completion with canonical `makeAgain: false`; no implicit save, feedback, share or timer action. |
| `synchronize()` | Materializes and synchronizes only the selected session's pending head, then applies its exact receipt. Later local actions remain ordered. |
| `backToRecipe()` | Immediate cached navigation, generation-fenced with no store/queue/HTTP mutation. Preserves pending bytes and finalization proof. |
| `returnToCooking()` | Symmetric cached navigation to the observed selected pin only; rejects missing or mismatched/PREPARED/QUEUED pins. No store/HTTP effects, selection, acknowledgement or authority reconstruction. |

The state distinguishes idle, proposal confirmation, pending start, cooking, paused, completed, abandoned, conflict, offline, error and unavailable outcomes. `screen` distinguishes recipe and cooking presentation. A local completed status may still have unsynchronized commands; it is not a server completion acknowledgement.

`pending` exposes safe command references, phases, attempts, retry timing and whether an explicit retry or unsent discard is available. Repository-local actions are also represented by `cooking.pendingCommandIds`, including actions not yet materialized into the queue. `canEdit`, `canStop`, and `canComplete` are advisory UI projections; every action rechecks the actual prerequisites.

`historical` and `serverAcknowledged` are deliberately separate. Opening/restoring another cached pin does not inherit an acknowledgement from a previously selected session. Historical downloaded content is not a fresh rights/recall/safety assertion, and local integrity hashes are not a signed content manifest. Recalled, incomplete and personal-unreviewed instruction snapshots are withheld by this controller's projection.

For a PREPARED start, `state.plan` is the exact proposed Plan, or null when blocked; it never falls back to a previously selected cooking pin. `state.cooking` may still observe that older pin. The accepted two-distinct-Plan/recall-blocked regression covers this consent distinction. The new host uses that exact proposal and a unique form/selection-bound ticket, never the older pin, for explicit confirmation.

## Current-plan start protocol

The only start input is a Plan ID, not a caller-supplied Plan or recipe body. It must be the current selection in the same owner's acknowledged `mealflow.v1` record. The controller requires:

1. No unresolved planning command; a retained manual draft and preferences matching the selected Plan request.
2. No pending stricter preference draft/command or process-only failed-write preference fence.
3. Exact current meal-record revision and original immutable Plan/preferences bodies.
4. Fresh canonical `getPreferences → getPlan → getPreferences` responses, with operation/status/content/schema binding, owned IDs, exact ETag numbers and matching context.
5. A ready Plan with its complete reviewed **published** materialized recipe, matching version reference, and no inferred community source grant.
6. No known recall in mealflow history, the shared kitchen recall repository or the controller's one-way evidence.

Planning a start is read-only toward the backend. The private `mealflow.cooking.v1` record retains the original generated command ID, exact Plan/preferences bytes, source revision and creation time. The proposal-age policy is checked when `confirmStart()` is invoked. Clock rollback, expired proposals or changed source/context require explicit reconciliation/new preparation; they are not silently repaired.

Confirmation commits the original `createCookSession` intent and its queued domain pointer atomically through the real queue. The canonical request is `{planId, deviceSequence: 0}`, with the original `Idempotency-Key` and **no If-Match**. The create-only gate repeats current context and exact intent checks. The queue's acknowledged reservation precedes dispatch. Failed/unknown or malformed acknowledgements are never upgraded by matching readback.

A canonical **201** alone does not enable cooking. The controller correlates the original queue intent, validates the returned session/Plan relationship and initial state, then downloads the returned owned CookSession and its exact materialized Plan through `CookingRepository.download`. The downloaded bodies, actual origin and availability must match; a catalog RecipeVersion is never substituted for a scaled/materialized Plan. Finally, the controller atomically attaches the selected server session and archives the interpreted create receipt.

New starts require published content. Existing downloaded cooking can preserve retired content under the repository's historical continuation policy; that does not authorize a new selection/start. Retirement and recall are not interchangeable: known recalls block cooking.

## Uncertain outcomes and finalization

Offline preparation/confirmation does not invent a server session or schedule an automatic create. Once dispatch may have happened, the original ID/body remains retained across cancellation, controller recreation and explicit retry. Retry-After/backoff and the existing queue receipt horizon are not bypassed by tapping retry. Attempted conflicts/rejections that the queue cannot safely resume remain explicit reconciliation states; this slice has no new-key rebase or conflict-supersession authority.

If 201 was retained but the owned download fails, retry can resume that receipt/download without POSTing again. Before the download await, the controller retains the already validated original receipt's command observation so this retry is immediately visible even when downloading fails. This is not a selected pin, applied receipt or server-completion acknowledgement; retry re-reads actual queue/domain state. A different or advanced downloaded body is not silently treated as the original creation receipt.

A possibly committed final local attachment needs more than an `APPLIED` observation. Before the atomic apply, a same-lease process guard captures the exact intended domain payload and the **actual queue-generated archive mutation**. Explicit original-ID finalization requires exact resulting domain/archive bytes, revisions, original plan/session receipt and the unchanged owned pin; it then obtains a fresh changed domain CAS and exact readback, with archive checks before and after. `FINALIZATION_REQUIRED` is not an acknowledged-applied success. Reads, Back, discard and a new proposal cannot release this proof. Back does not rewrite the payload being reconciled.

This retained finalization proof survives controller close/reopen within the same actual lease, not arbitrary process death or identity replacement. An arbitrary terminal archive does not reconstruct retry authority. After a genuine restart, an existing attached bundle can be observed historically through the repository; this does not manufacture a missing final-ack proof or resend the old POST.

## Progress, preferences, recall and lifecycle

Offline actions use the downloaded session's real server ID and immutable materialized plan. Local progress and its domain action are committed atomically. Only the head action obtains a queue request: `updateCookSession` uses the actual acknowledged server ETag; `completeCookSession` follows the canonical contract without If-Match. Subsequent actions do not guess a future ETag. Completion is explicit; reaching/marking the last step is not completion.

Stricter pending preferences block start, continued cooking edits/resume, completion and unsafe progress dispatch. Explicit pause/abandon can still be retained/synchronized without granting cooking permission. They do not erase pending commands or bypass the repository's own origin/recall/availability checks. No pantry row is interpreted as confirmation of ingredients available today.

Canonical matching recall Problems and successful recalled Plan observations install one-way negative evidence before old instructions can be projected, even if a later version comparison or local acknowledgement fails. The shared recipe-recall query is blocking evidence only: `false` is not a safety/rights grant. Foreign-origin bundles remain historical/read-only, and recalled content cannot be revived by a stale ready response.

All repository store/transport awaits are enclosed by controller-generation and actual lease checks, including noncooperative callbacks and the final dispatcher return handoff. Synchronous boundary invalidation redacts cached private state immediately. A cancelled older operation cannot invalidate a newer admitted operation. No controller action clears a newer lease, erases a scope, starts native work or requests notification permission.

## Remaining integration work

- Compose a production provider/session Android entry point and complete visual/accessibility release qualification. The nine actual Android cooking-host tests accept the bounded shared-host behavior, not a shipping application root; native iOS composition remains unaccepted. The proposed isolated interactive development host is described in [ANDROID_PROGRESS_HOST_PLAN](ANDROID_PROGRESS_HOST_PLAN.md).
- Configure/deploy and verify the actual cooking HTTP service with approved identities/content. The implemented HTTP and database integration tests are not backend deployment evidence, and the native host's synthetic transport is not a live service.
- Finish the canonical content-manifest/complete-download agreement; current local hashes do not certify provenance or review.
- Integrate native timers separately through the session work/permission/lifecycle controls, without claiming timer restoration or notification delivery here.
- Wire Save, Make Again, feedback/share and optional completion choices separately. This controller's `makeAgain: false` is not a saved-recipe operation or a decision to remove that required product feature.
- Supply the still-required real provider/backend and licensed, qualified reviewed content. No provider, issuer, catalog rights or review authority was selected by this implementation.

Relevant implementation: `shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/CookingFlowController.kt`, `CookingFlowModels.kt`, `CookingFlowRecords.kt`; existing `shared/kitchen` repositories and `shared/sync`'s `DurableCommandQueue.kt`. The workspace's canonical operations and DTOs remain defined in `../outputs/biteclub_blueprint/architecture/04_API_Contract.json`; F12/F13 remain the feature contract, not this adapter's completion claim.
