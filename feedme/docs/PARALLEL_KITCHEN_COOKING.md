# FeedMe — fifth parallel component batch

14 September 2026. **Verified as bounded components** at **2026-09-14T01:54:18.106Z**. The active goal continues milestone by milestone. This accepts the fifth batch's frozen component implementation, not complete features, native cooking UI, a production journey or a ship-ready release.

## Work and ownership

| Worker | Bounded package | Current evidence |
| --- | --- | --- |
| Lead | Six canonical kitchen HTTP operations; real Ktor/CIO + PostgreSQL integration, including actual persisted inputs feeding the planning test adapter | 21 HTTP unit and20 HTTP/database methods pass in focused execution |
| Nash | Owned preference/pantry persistence, explicit provisioning, V004, exact command/replay/tombstone/cursor/outbox guarantees | 10 unit and28 PostgreSQL methods pass in focused execution; previous migration bytes preserved |
| Averroes | Exact private-write acknowledgements, lease-scoped recall lifetime, read-only cooking observations and optional create-only queue gate | All171 kitchen JVM methods pass in focused execution |
| McClintock | Actual shared cooking start/retry/download/progress/completion controller using the existing private kitchen queue | Final170-method mealflow run passes, including41 new cooking methods; fresh Android library build/lint and independent review pass |

The API/store focused run passes31 unit and48 database methods. Identity, recipe/editorial and catalog policies are explicitly synthetic test adapters. This does not establish a live identity provider or a complete app-to-server cooking journey.

The new runner is [verify-parallel-kitchen-cooking.mjs](../scripts/verify-parallel-kitchen-cooking.mjs). Its fixed inventories match the final frozen source declarations. The previous runner/receipts remain untouched. The historical demo APK is not replaced by these components.

## Accepted frozen verification

The [current receipt](verification/parallel-kitchen-cooking/verification.json) and [immutable final attempt](verification/parallel-kitchen-cooking/attempts/2026-09-14T01-46-16.626Z/verification.json) record **2,266 Kotlin/server/database tests, 181 Node checks and 345 native successes**, plus **seven separately witnessed interrupted starts**. Shared Kotlin totals1,932: core54, contracts123, transport72, storage544, sync108, kitchen171, session593, planning40, mealflow170 and app57. Server141 and isolated PostgreSQL193 are separate. All have zero failures, errors or skips. Eight libraries have zero-issue lint; all518 scheduled Gradle tasks executed, including40 required acceptance tasks.

The receipt binds424 source inputs,116 exact JVM XML classes,20 artifacts and309 retained evidence files. Source manifest SHA-256: `d5d2aaf4f3c6af0edb5b728ff627dfe30bb0655e3269e91adab816ffa9956c33`. Receipt SHA-256: `95b14f508973eb4d130edad7e6cd679f70ea67745682e9f3379d925ff5807479`. Root and independent recursive audits match all1,284 size/hash records across926 paths and identical current/immutable/last-attempt receipts. Independent source/JVM review matches424 input hashes and2,266 exact methods across116 fresh XML classes, with retained XML bytes identical. All181 Node declarations across10 files match unique TAP identities and contiguous pass ordinals, with no canceled/skipped/todo results. All19 rebuilt artifacts and eight ordered successful verification commands are fresh; the twentieth artifact is the unchanged historical demo.

Independent native review matches345 unique source-bound start/success pairs in38 successful invocations, plus seven separate start-only witnessed interruptions (45 invocations total). All14 witness/recovery PIDs, seven force-stop/disappearance/recovery/cleanup chains, five earlier controlled process stages,39 VFS labels,157 original/retained copy pairs and70 native source hashes agree. All11 fresh1080×1920 PNGs were structurally and visually reviewed. Four-ABI native helper bytes and actual ELF identity match; the helper is absent from eight AARs, the session/app test APKs and the historical demo. Only API35 ARM64 executes; other ABIs are packaging evidence. Notification delivery is unavailable, both hardlink branches were denied13 and remain unexercised, and process termination is not physical-power-loss proof.

The owned read-only emulator exited0. Storage test fixtures are empty, session retains only its three WorkManager database files, and the app retains only11 evidence images in two directories. All251 retained synthetic PostgreSQL clusters have no PID files; port8789 has no listener. No retained cluster directory or user database was deleted. Full Xcode remains absent; iOS is uncompiled. The clean public export remains at `4b9a5ac0b2bef352d3b617082d0bb62b31ec966c`, with no new push or deployment. Historical demo APK SHA remains `bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805`.

### First full run — historical, before the confirmation projection correction

Attempt [2026-09-14T01:31:35.635Z](verification/parallel-kitchen-cooking/attempts/2026-09-14T01-31-35.635Z/verification.json) passed at01:39:43.133Z with2,265 Kotlin/server/database tests,181 Node checks and345 native successes plus seven separately witnessed interrupted starts. Its423-source manifest SHA is `e6768ddb269d087a91029f7ec1038d131e2dc5972b6fe8d79690ca18b7f50e71`; receipt SHA is `a63269c5dc2de1072c9644b3338bbfbf4464d90e21ea58c42e6a10772ea8a0ff`. It binds116 JVM XML classes,20 artifacts and309 evidence files. Independent/root audits matched1,283 recursive hash records across925 paths, all exact test identities and identical receipt copies. All518 scheduled Gradle tasks executed, including40 required acceptance tasks; eight libraries had zero-issue lint.

Subsequent integration review found a missing regression: with an earlier selected cooking session A and a newly prepared meal B, the controller's generic Plan projection could still expose A although confirmation starts B. Native cooking consent UI is not connected, but the state contract itself needed correction before acceptance. The phase-specific exact prepared Plan/null projection and one grouped A/B, blocked-preview and discard-alignment regression are now implemented. Focused25331 passes all170 mealflow methods, including41 cooking tests. This first full run is historical and does not certify the later source; the accepted fresh complete run above verifies the correction and also binds the F12 Guided Cooking specification. No test failure or known gap is hidden by the first run's passing counts.

## Focused failures and corrections

1. The first server integration compile found test access to an internal cursor encoder and a missing JsonObject projection. The tests now independently sign their own synthetic expired cursor and access the typed object. No production test-only API was added.
2. One HTTP input assertion expected a response validator error even though StoredReply rejects a body on204 during construction. It now asserts that constructor invariant; the validator's unexpected-ETag regression remains.
3. Independent review aligned search cursor length with Unicode scalar count, preserving strict UTF-8/control rejection.
4. The first48-method PostgreSQL run passed43 and failed five root integration assertions. Three assumed JSONB object key order was a response guarantee; whole parsed values, status and exact ETag are now checked. Two supplied a noMatch plan where the test intended to exercise new ready-selection authority. They now use a clearly labelled synthetic reviewed candidate. No production planning policy changed. The next focused run passes all48.
5. Cooking review identified exact attachment archive proof, caller/controller lifetime fences, per-selection acknowledgement reset, prepared-action projection and learned recall preview gaps. The first hardened169-method mealflow run compiled but failed four tests: invalid pending-plan fixture history, an assertion inconsistent with the queue's unresolved malformed-response state, lifecycle loss reported as storage failure and a recall observation lost before a repository rejected changed same-version bytes. Fixture expectations/history and production lifecycle/recall handling were corrected; new starts now require published content while existing owned-retired continuation policy remains separate. Focused rerun69687 passes all169. No failed test is counted as a pass.

Focused attempts are recorded in the task's tool results; regenerated build XML is not an immutable history of every focused failure. The combined runner separately retains immutable attempt evidence.

## Integration boundaries

- [Kitchen HTTP/persistence](KITCHEN_HTTP_INGRESS.md) requires actual verified account/device or bounded guest identity, current principal lifecycle and reviewed catalog/search adapters. No GET initializes preferences. The default Main does not enable product operations.
- [Private acknowledgements](KITCHEN_ACKNOWLEDGEMENTS.md) are integrity/lifecycle guarantees, not server authorization, recipe approval or physical-power-loss proof.
- The shared cooking controller is a production-oriented component, not yet a delivered native cooking screen/server service. Native UI/timers, cooking routes/current source authorization, reviewed content, save/share and real account journeys remain separate work.
- The planning integration fixture reads actual preference/pantry rows under the same exclusive principal lock. It rejects unsupported dietary presets and never infers confirmed stock. Its recipe/reviewer/taxonomy objects are synthetic; this is not a production catalog adapter.
- iOS remains uncompiled without full Xcode. API26/physical devices, real provider integration, signing/store review and all whole-feature/release gates remain open.

## Next small tasks

1. **DONE bounded:** resolve focused failures, freeze source/test inventories and complete independent delta review, including exact prepared-plan consent projection.
2. **DONE bounded:** run combined JVM/database/library/lint/Android/Node acceptance against unchanged source.
3. **DONE bounded:** independently audit identities, immutable receipts, hashes and native evidence; record component acceptance only.
4. **TODO — Nash/backend:** implement [cooking persistence and ingress](COOKING_BACKEND_PLAN.md), in five small tasks with explicit current source/pin and completion policy.
5. **TODO — McClintock/native cooking:** implement [RECIPE → COOK → MEAL_DONE composition](COOKING_UI_INTEGRATION_PLAN.md), in five small tasks preserving explicit completion, exact confirmation and Back-without-abandon.
6. **TODO — Averroes/platform:** implement [session-owned timers](COOKING_TIMER_PLAN.md), in six small tasks preserving clock uncertainty, cancellation and truthful platform acknowledgements. Basic cookbook save remains a separately acknowledged integration; source/manifest and optional makeAgain semantics must be resolved before enabling them.

V1 still includes44 P1 features and defers10. No TasteEcho, new scope cut, provider, spend, deployment, public push or publication is inferred. Existing unanswered user inputs remain in [USER_ACTIONS.md](USER_ACTIONS.md); no duplicate alarm or unchanged question was created.
