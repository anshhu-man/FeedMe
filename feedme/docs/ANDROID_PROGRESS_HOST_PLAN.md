# Android retained-progress host plan

14 September 2026. **Implemented source; installed verification in progress.** The [seventh-batch handoff](PARALLEL_PROGRESS_COOKBOOK_TIMERS.md) records current build/test evidence and failed attempts. The five tasks below remain the acceptance criteria, not a claim that every criterion has passed. The sixth receipt is historical. The existing visible demo and release scope remain unchanged.

The user-visible outcome is an interactive development build of the actual request, recommendations, recipe, explicit cooking confirmation, cooking and completion screens, with real encrypted retained state. It is not another visual prototype or a claim of live authentication, reviewed recipes or production service availability.

## Current entry points

The existing [Android launcher](../apps/android/src/main/kotlin/com/feedme/development/MainActivity.kt) constructs `DemoKitchenRuntime` and renders `FeedMeApp`. Its `com.feedme.development` identity and “LOCAL DEMO · SAMPLE DATA · NO LIVE SHARING” banner describe an in-memory demo. Leave that source and the currently installed/running preview unchanged.

The new shared UI is [FeedMeMealFlow](../shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/FeedMeMealFlow.kt), composed by [MealFlowExperience.fromSession](../shared/app/src/commonMain/kotlin/com/feedme/app/mealflow/MealFlowExperience.kt). It accepts an actual `PrivateSessionAccess`, `AccountTransport`, `SessionBoundary`, serialized dispatcher, clock, connectivity, IDs, explicit policies and configured ingredient/equipment choices. It owns the meal, picker, kitchen-input and cooking controllers while borrowing the underlying native access.

The current native demonstration of this composition is `NativeMealFlowTestActivity`, under `shared/app/src/androidInstrumentedTest`. It requires a test-retained owner before launch and is not a standalone application entry point. The proposed host must import **no AndroidTest source, instrumentation owner, test-only JNI helper or instrumentation-runner API**. Its development inputs must not become defaults for real production identities.

## Five implementation tasks

### 1. Isolate a launchable progress variant

Add a `progress` build type/source set to the existing `apps/android` module, with debug dependency fallbacks and a distinct application ID such as `com.feedme.development.progress`. Add only the necessary progress-scoped session/storage dependencies; do not add a second application module or change the existing demo launcher. Proposed files are `apps/android/src/progress/AndroidManifest.xml`, `ProgressApplication.kt` and `ProgressActivity.kt` in the development package, plus the narrow variant configuration in `apps/android/build.gradle.kts`.

The manifest overlay must replace the progress variant's launcher intent, not expose two indistinguishable launchers. Use a distinct development label and a permanent banner: **SYNTHETIC ACCOUNT / CONTENT / SERVICE · REAL NATIVE STORAGE**. Keep network permission absent while the service is entirely local. The variant is neither a release artifact nor a new route around release-scope checks.

Acceptance: both application IDs can be installed side by side; launching progress never changes the existing demo's files, UID, Keystore namespace or running activity. APK inspection proves the progress identity, launcher, permissions and absence of test-only dependencies. Existing `FeedMeTheme` and shared visual components are reused.

### 2. Retain the actual native session composition

Add `ProgressSessionOwner.kt` and a small owner-status model under the progress source set. Retain one owner at application lifetime on `Dispatchers.Main.immediate`; activity recreation borrows it rather than opening another lifetime. Use `SessionApplicationComposition.create(boundary, dispatcher, configurationBinding)` and `reserve()` before native construction or I/O. Retain each successful owner before cancellation can lose its returned handle.

Use the real `AndroidSessionControlStore`, `AndroidSessionWorkStore`, `AndroidStateDatabase` and `AndroidCredentialStore`, then `PrivateSessionRuntime.openReserved(...)`. Keep the immutable configuration binding explicit and bound to the development configuration. The runtime owns lease activation; the host must not forge `PrivateSessionAccess` or activate a boundary as an authentication shortcut. Call `recover()`, expose its actual phase, and require explicit user actions for `create()` or `restore()` as appropriate. Obtain `currentAccess()` and call `MealFlowExperience.fromSession(...)` with the required explicit policy/clock/ID inputs. Unsupported API levels remain visibly unavailable; current native prerequisites include API27+.

Acceptance: a second window/recreation cannot duplicate native ownership; cancelled or failed opening cannot publish a usable experience. Failed acquisition/close remains retained and visibly blocked. The host installs no scheduler, alarm, worker or permissive execution policy; unexpected work remains `NOT_CONFIGURED`.

### 3. Supply explicit development authority and canonical service behavior

Add `ProgressIdentity.kt` and `ProgressCanonicalService.kt`, confined to the isolated variant. The synthetic verifier must accept only the exact configured development environment, account, credential values and configuration. It must reject real or foreign credentials. The UI offers “Start synthetic preview,” not a simulated successful live login. These inputs are fixtures declared by the development build, not substitutes for provider, bootstrap or review approval.

The service adapter implements the existing `AccountTransport` contract and canonical operation schemas used by the controllers. Reuse fixture response *shapes* as a reference, without importing test sources. Preserve exact IDs, versions, ETags, idempotency keys, request bodies and original operation outcomes. Supply configured synthetic ingredient/equipment labels only; never invent a production ID/name mapping or published reviewer authority. Include deliberate unavailable/offline behavior rather than fake success for unsupported operations.

The existing test transport keeps Plans, sessions and receipts in process memory. Copying it verbatim would make process-restart replay misleading. To support interactive service continuation after restart, give the synthetic service a separately namespaced, bounded encrypted service-state/receipt record with acknowledged CAS and exact-key/body replay. This models the *synthetic server's* state; it must not replace or mutate the client's existing queue/domain authority. If that ledger is not implemented, restarted service operations must remain explicitly unavailable while eligible local retained state stays readable. Never recreate an uncertain command outcome using fresh fixture IDs.

Acceptance: an uncertain create retried after restart uses its original key/body and yields its original synthetic result, or visibly remains unresolved if service persistence is absent. Foreign identities and unsupported operations fail closed. There is still only the actual existing client command queue used by the cooking controller, not a second preview queue.

The implementation now requires `ProgressCanonicalService.open(allowInitialize)` explicitly. Only a newly acknowledged native create/retry-create passes true; identity restore passes false. A missing service ledger after restore is a repair gate, not permission to create fresh server history. The exact verified identity may still be explicitly retired through Reset. The separate encrypted service ledger retains exact request fingerprints and resource witnesses; a matching read alone does not acknowledge a lost commit or permit a stale receipt to replace newer state. Nineteen local ledger tests cover these boundaries; native process acceptance is tracked separately.

### 4. Wire the retained screens and truthful lifecycle actions

Render `FeedMeMealFlow(experience, onExit, platformBackHandler)` with the actual Android Back bridge. The existing shared controller and experience remain the authority for selection, pending actions, form dirtiness, confirmation tickets and progress. Add no parallel cooking model in the activity. Activity attachment restores eligible local state only; it never submits a meal, confirms a start, retries a command or marks a meal complete.

Expose explicit local resume and exit behavior. Close the experience first; its controller closes redact state but do not close borrowed native stores. Then acknowledge runtime and native-owner closes, preserving failed stages and releasing the reservation last. Ordinary Back, backgrounding and rotation neither retire the account nor discard original commands. A destructive preview reset needs separate explicit consent and the actual runtime retirement protocol; never sweep matching aliases/files or clear application data as hidden cleanup.

For an original interrupted setup, use the existing retained `AndroidSessionSetupRecovery.createOwner(context, reservation)` protocol only after prior owners are genuinely released: advisory inspection, explicit preparation/confirmation, original-intent retry and acknowledged close. A `CONFLICT` from that recovery owner is **not** proof that ordinary opening is safe. Missing, ambiguous or unsupported startup evidence must remain a repair gate. This task does not add a generic startup classifier, hot-journal recovery or new erasure authority; if the existing API cannot establish the required route, report the exact blocked state rather than guessing.

Acceptance: the user can navigate REQUEST → RECOMMENDATIONS → RECIPE → exact prepared-plan confirmation → COOK → explicitly derived MEAL_DONE. Plan B consent never uses a prior cooking pin A. Pending preferences, recall, missing provider/service capability and unknown commands remain visible. No automatic start, completion, abandonment or send occurs. Timers, Save, Share and MakeAgain remain unconnected.

### 5. Verify the installed host, including interruption boundaries

Add progress-variant owner unit tests and installed-host Android tests, with the variant/test task wiring reviewed before execution. Tests must launch the actual progress activity and real native owners, not an instrumentation-only substitute for the host. Source-controlled synthetic inputs remain labeled in screenshots and transcripts.

Verify recreation retains the same experience and consent ticket; stale ticket rejection; exact A/B selection; Back during suspended download; local restoration without transport; uncertain create and retained-201 download retry without a second POST; pending stricter preferences; learned recall redaction; lease invalidation and late-callback fencing; close failure retention; explicit retirement/reset and no unknown-key cleanup. Verify original command identities across a controlled process restart separately from same-process close/reopen. Do not claim physical power-loss or hot-journal recovery from those tests.

Acceptance: fresh progress APK metadata, exact test identities, screenshots and cleanup results are retained in a new verification package. The currently visible emulator is not repurposed for testing. A failed stage remains a failed result, not a silent fallback to the old demo or a successful synthetic service response.

## Boundaries that remain

This host provides real interaction with existing production-shaped session, storage, controller and Compose components. It does not provide real OAuth/device authentication, approved provider/bootstrap configuration, live HTTP service wiring, published reviewed catalog rights or safety approval, signed snapshot manifests, native timer scheduling, production reset policy, release signing, iOS acceptance or physical-device coverage. The current 44-feature V1 inclusion and ten deferred features are unchanged; an interactive developer preview is not acceptance of all V1 features.

Existing ordinary native value-returning factories still do not expose retained failed-acquisition owners. Public existing-only recovery refuses unsupported journal/file/schema conditions. Preserve those limitations explicitly. The purpose of this bounded package is a useful, honest interactive progress build—not broader recovery behavior or a second application architecture.
