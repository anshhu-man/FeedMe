# Foreground cooking timer UI: next integration package

## Status and scope

This document preserves the integration design written during the seventh checkpoint. The [eighth package](PARALLEL_COOKBOOK_TIMER_MEDIA.md) implements the shared TIMER screen, actual progress-host composition and native tests; its complete source-frozen run passed at2026-09-14T06:41:28.831Z. Follow that tracker for the final audit and bounded evidence. The design-time descriptions below are not a current implementation inventory or release acceptance.

Use the existing [CookingTimerReducer](../shared/kitchen/src/commonMain/kotlin/com/feedme/kitchen/CookingTimerReducer.kt), [SessionCookingTimers](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/timers/SessionCookingTimers.kt), and [Android foreground adapter](../shared/mealflow/src/androidMain/kotlin/com/feedme/mealflow/timers/AndroidForegroundCookingTimerAdapter.kt). Do not add another persisted timer model, reducer, scheduler default, account verifier, or server alarm. The Android adapter provides foreground Handler callbacks only; background alarms, notifications, permission UX, process-death delivery, and iOS remain separate gates.

Authority is [F12 Guided Cooking](../../outputs/biteclub_blueprint/features/F12.md), the [COOK/TIMER registry actions](../../outputs/biteclub_blueprint/registry/screen_registry.json), the [TimerState/CookPatch contract](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json), and [F13 pinned-content rules](../../outputs/biteclub_blueprint/features/F13.md). A timer is an estimate, never readiness certification or permission to advance/complete a step.

## What is actually available

- `SessionCookingTimers.change(sessionId, expectedLocalRevision, commandId, action)` already acknowledges canonical timer progress, immutable cooking update intent, and private desired mapping atomically before installation. It uses actual runtime `install`, `cancel`, and `runLocalEffect` gates.
- `inspect(sessionId)` returns the union of canonical timers and private mappings. Missing mappings remain visible without alert authority. `cancelAlert(sessionId, timerId)` reconciles the exact retained alert ticket without changing canonical timer progress or installing a replacement.
- `CookingTimerView` separates timing (`RUNNING`, `PAUSED`, `DUE`, `UNCERTAIN`), private mapping phase, and this facade's installation acknowledgement. None means a notification was delivered.
- [AndroidProcessCookingTimerClock](../shared/mealflow/src/androidMain/kotlin/com/feedme/mealflow/timers/AndroidProcessCookingTimerClock.kt) reads wall time and elapsed realtime with process-only continuity. Handler wakeups use uptime and may be late; wakeups recheck elapsed time. A new process cannot reuse the old continuity claim.
- The adapter's `bind` creates its sole facade from the exact runtime/current access, cancellation port, clock, policy, store, boundary, and origin composition. `setForeground` is synchronous on Main and defaults to off. `close` fences callbacks; exact downstream cancellation remains available afterward.
- Policy read-bracket retries are bounded to three successful advancing-revision observations. Generic failures, unknown work commits, and invoked effects are never automatically retried.

At design time, the experience had no timer owner/route/actions and the progress host supplied rejecting cancellation/execution ports. The eighth implementation changes those together: [MealFlowExperience](../shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/MealFlowExperience.kt) retains the timer page, and [ProgressSessionOwner](../apps/android/src/progress/kotlin/com/feedme/development/progress/ProgressSessionOwner.kt) constructs the real timer-only cancellation/policy/adapter before opening the runtime. Duration copy is now a suggestion, not the inaccurate claim that no timer has started. Native and full-source acceptance remain separate from these source changes.

## Exact action mapping

| Registry action | Next UI behavior and existing domain operation |
| --- | --- |
| `COOK.03` | Open TIMER with the exact selected cooking session and pinned step. Navigation allocates no command/ticket and starts nothing. |
| `TIMER.01` | Explicit Start validates an integral duration and pinned step, then calls `change(..., Start(...))` with one retained command ID and captured local revision. |
| `TIMER.02` | `Pause(timerId)`: persist remaining duration/non-running intent before exact alert cancellation. Do not report complete cancellation on failure. |
| `TIMER.03` | `Reset(timerId)`: restore full chosen duration in paused form, cancel the old alert, and do not start a replacement. |
| `TIMER.05` | `Resume(timerId)`: use persisted remaining duration and a fresh desired generation after required prior cancellation. Never silently restart the original full duration. |
| `TIMER.06` | `Cancel(timerId)`: remove only the selected canonical timer and cancel its exact alert; retain siblings and cooking progress. Return to cooking only after truthful result handling. |
| `TIMER.back`, `TIMER.04` | Return to cooking without pausing, canceling, submitting, discarding, or completing. |

The wire statuses remain only `running`, `paused`, and `done`; cancellation does not invent a `cancelled` status. Current application bounds are 32 timers and 1–86,400 seconds, not unstated canonical maxima. Preserve exact integral values, upward pause rounding, overflow checks, and the reducer's uncertainty rules. A pinned step's duration may prefill an editable draft only when actually present; it is not a start instruction. Missing labels/context are unavailable, not guessed. F12's prose “clear expired” still needs a reviewed mapping to the existing actions before adding another control.

## Ordered implementation work

### 1. Complete trusted native composition before enabling controls

Files: `apps/android/src/progress/.../ProgressSessionOwner.kt`, `ProgressApplication.kt`, `ProgressActivity.kt`, and the progress manifest; changes require a new source freeze and verification.

Retain the composition root/reservation before native initialization. After positive startup routing, construct and retain one clock, one deny-until-bound `CookingTimerExecutionPolicy`, and one actual foreground adapter before `PrivateSessionRuntime.openReserved`. Pass that same adapter as the runtime cancellation port and that same policy as its execution policy. After verified current access exists, bind exactly once and retain the returned facade. A replacement runtime/access needs new objects; never rebind a closed policy.

There is a concrete dependency gate: [AndroidNativeWorkCancellation.create](../shared/session/src/androidMain/kotlin/com/feedme/session/AndroidNativeWorkCancellation.kt) currently requires an explicit non-exported app-owned receiver and an already trusted-initialized WorkManager. The progress manifest intentionally removes WorkManager auto-initialization and currently supplies no such receiver. Do not copy the instrumentation receiver/initializer, add a no-op accepting delegate, or initialize WorkManager before the startup reservation/recovery decision. First approve the real cancellation composition and its initialization order, or a narrowly reviewed timer-only cancellation separation. Until that gate is met, timer observations can be read-only and controls must clearly say unavailable.

Use one retained foreground-owner lifecycle on Main. Activity recreation must not replace the application-owned adapter. Activity stop calls `setForeground(false)` synchronously; foreground re-entry may resume only its retained callbacks. Old Activity callbacks must not fence a newly attached foreground host: test exact host identity or a retained attachment token. Process restart starts foreground delivery disabled, with no reconstructed lease or installed callback.

### 2. Add a small shared presentation/action owner, not a new timer engine

Proposed new files: `shared/mealflow/src/commonMain/.../timers/CookingTimerFlowController.kt` and a detached UI-state file. Integrate it through `MealFlowExperience`; UI must not receive raw storage, native tickets, or private ledger JSON.

Two narrow existing-API gaps must be resolved explicitly:

1. `inspect` does not return the cooking local revision or pinned step context needed by `change`. Add a purpose-fixed coherent observation derived from the facade's existing loaded snapshot plus timer views, or bracket the real repository observation before exposing a captured action revision. Do not combine an old recipe/revision with a new timer list. Current `CookingFlowController` owns a generation-guarded private repository wrapper; exposing it or stripping that guard is not an acceptable shortcut. A correctly paired repository on the same actual access uses the same persisted records, not a second timer model.
2. Android dispatch observations and `onDue` currently identify an opaque native ticket, while shared views deliberately expose only timer IDs. Add a trusted narrow correlation inside the facade/adapter boundary so UI receives the exact cooking-session/timer display identity and detached delivery phase. Do not parse the private proof string, expose ticket IDs in UI, or reconstruct an “installed” receipt from persisted ARMED rows. The final short synchronous effect must still execute inside the real runtime gate.

Capture session, step/timer identity, command ID, local revision, and presentation generation on each explicit action. Preserve the original command after an unknown result; never allocate a new ID merely because a button was tapped again. Re-read retained state and use exact `cancelAlert` reconciliation where supported; ambiguous domain writes are not a general replay API. Reuse existing cooking selection, pending-preference, recall, origin, and queue restrictions before enabling action entry; do not treat an old `canEdit` display flag as authority after suspension. Starting/resuming from a PREPARED proposal or an old selected pin is forbidden.

After an acknowledged timer mutation, refresh the cooking controller through its read-only observation path so `localPendingCount` and explicit Sync refer to the actual queued `updateCookSession`. Do not send an extra PATCH, fabricate an ETag, bypass FIFO, or mark server acknowledgement from local timer success. Resolve how existing cooking preference/action gates are invoked before wiring the UI; do not install an accepting replacement policy.

### 3. Add COOK/TIMER presentation and honest delivery copy

Files: `shared/app/src/commonMain/.../mealflow/CookingFlowScreen.kt`, `CookingFlowPresentation.kt`, `FeedMeMealFlow.kt`, `MealFlowExperience.kt`, and a new `CookingTimerScreen.kt` with presentation tests.

Reuse FeedMe's existing styling. Add the COOK timer entry only for an actual selected pin, and a TIMER page with an explicit duration draft and multiple timer rows labeled from pinned steps. Keep the current `key(screen, sessionId)` scroll behavior and add timer selection to the timer page's own navigation state. Back is immediate navigation, even during a pending action, without authorizing another mutation or erasing its retained intent.

Use bounded, lifecycle-owned read-only observation updates while visible. Coalesce overlapping refreshes; ticks must cause no command, database mutation, transport, rescheduling, or stored countdown decrement. Reuse reducer observations rather than duplicating elapsed/clock-jump arithmetic in Compose. Redact on invalidation and reject late observation returns after controller close or navigation replacement.

Keep these labels distinct:

- canonical progress retained / local action awaiting server sync;
- foreground callback installed in this exact owner;
- foreground suspended, blocked, clock uncertain, or delivery outcome unknown;
- foreground due indication actually acknowledged by the gated local effect;
- exact cancellation pending/unknown versus acknowledged cleanup.

`alertAcknowledgedInThisOwner` is not delivered. `DUE` is not an emitted sound, notification, or automatic `done` write. Unknown effect acknowledgement must never cause another due effect on foreground re-entry. The due effect can update a retained foreground banner/accessibility status; it cannot suspend, call HTTP/runtime recursively, advance steps, or complete the meal. Avoid announcing every countdown tick to accessibility services.

### 4. Make close, retirement, recall, and restoration explicit

The adapter/facade are borrowed by UI. Navigation does not close them. Application close/invalidation fences their callbacks before losing the active runtime; preserve the same cancellation owner through exact native cleanup and actual runtime/store close acknowledgements. Reset uses existing verified retirement, including installed timer tickets, not a broad alarm/notification sweep. A failed close/cancellation retains the exact owner and identities.

Fresh-process restored canonical timers are visible, but old monotonic anchors are uncertain and old mappings are cleanup-only. Do not reinstall them automatically or report prior ARMED rows as this owner's acknowledgement. Exact cleanup may remain possible when recipe/timer data is no longer usable; a later explicit timer action needs its own valid current context and supported reducer transition. Existing facade mutation on recalled content remains denied; do not weaken that rule to make a Pause button appear successful.

A stopped Activity may later resume a callback only within the same retained process/owner and after all current gates pass. That is different from verified startup after process death. Leaving foreground does not imply canonical timer pause or cancellation. Explain these limits before the user relies on alerts.

## Acceptance for the next package

- Common presentation/action tests: exact registry actions, integer bounds, missing step labels/mapping, multiple timers, original command/revision retention, no writes on ticks/Back, and local-versus-server-versus-delivery copy.
- Real-SQLite tests: UI action through the actual facade, atomic domain/queue/mapping ordering, pending preference/queue restrictions, changed pin, lost domain/install/cancel acknowledgement, and no replacement identity after unknown outcome.
- Native retained-host tests: real runtime/adapter pairing, visible Start/Pause/Resume/Reset/Cancel, sibling preservation while another timer changes, due banner through the actual gate, Activity recreation/stop/start, invalidation, and exact reset/close cleanup. Exercise the UI, not only direct facade calls.
- Fresh-process test: retained wire timers survive; old callback/continuity/receipt does not revive; explicit permitted reconciliation keeps original identities. Count witnessed interruptions separately from passed recoveries.
- Failure tests: recall, clock jump/continuity loss, canceled/late reads, failed work CAS, final-effect/foreground race, unavailable cancellation configuration, and denied notification permission without fabricated notification success.
- Packaging/evidence: actual progress APK/manifest, no test-only initializer/receiver/JNI leak, exact native identity pairs, source-bound screenshots, and owned-device cleanup. Do not change the user's preview emulator during verification.

This design document itself supplies no verification receipt. The eighth tracker records implementation and bounded foreground tests. No new permission grant, background service/receiver delivery, real provider, platform scheduling guarantee, full F12 acceptance or iOS acceptance follows. Future notification UX must follow the real platform capability state; foreground display does not require claiming notification permission, and permission denial must not be relabeled as an alert receipt.
