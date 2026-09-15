# FeedMe — meal flow, planning persistence and owned startup

14 September 2026. The second parallel component batch is verified. These are accepted implementation packages within the approved 44-feature V1, not complete features, connected production journeys or a ship-ready app. The 10 deferred features remain excluded.

## Delivered packages

| Owner | Implemented and verified | Integration still required |
| --- | --- | --- |
| McClintock — cooking | [Durable meal-request flow](MEAL_REQUEST_FLOW.md): canonical manual inputs, encrypted owner-scoped draft/history, acknowledged dispatch admission, exact-key/body uncertain-outcome retries, ancestry-only alternatives and synchronous private-state redaction | Native REQUEST → RECOMMENDATIONS → Plan-context RECIPE screens, actual authenticated transport and approved reviewed catalog; blocked-command reconciliation and application-wide recall |
| Nash — backend | [PostgreSQL planning persistence](SERVER_PLANNING_PERSISTENCE.md): immutable Plans/evidence, transactional command receipts/outbox/lineage, current principal/input/catalog authority, durable alternatives and explanation cursors | Real authority/input/catalog adapters, canonical HTTP ingress and lifecycle writers sharing the required transaction locks; no fixture policy may enable a route |
| Averroes — platform | [Owned startup recovery](OWNED_STARTUP_RECOVERY.md): application reservation before native factories, retained four-owner recovery, explicit exact-plan consent, ALL-ABORTED checkpoint, truthful subordinate closes before fresh Complete | Production app composition and confirmation UI, composite task7 integrated process-interruption acceptance, real identity/configuration, general orphan/journal/refresh repair |
| Lead — integration | Synchronous one-shot session invalidation and exact failed-runtime registration release; [conditional Plan.mode alignment](PLAN_MODE_CONTRACT_ALIGNMENT.md), central cross-review/builds and evidence audits | Real account/guest bootstrap, native journey acceptance, iOS and release gates |

No new production UI or deployed route is claimed. Existing demo roots still inject `DemoKitchenRuntime`; a historical demo artifact must not be presented as the new manual flow. All 54 designs and all 44 V1 feature owners remain on the [team board](AI_TEAM_PLAN.md).

## Combined acceptance

The [source-bound run](verification/parallel-meal-startup/verification.json) started at **2026-09-13T22:15:13.316Z** and passed at **22:19:20.584Z** (14 September in India).

| Check | Result |
| --- | --- |
| Shared Kotlin | 1,718: core54, contracts123, transport72, storage544, sync103, kitchen142, session593, planning40, mealflow47 |
| Server / real isolated PostgreSQL | 71 unit + 101 database tests; combined Kotlin total1,890 |
| Tooling | 161 Node tests across nine files; 44-included/10-deferred structural scope check and Gradle scope gate |
| Android native | 323: storage164 + session159, across29 isolated runner invocations on API35 ARM64 |
| Libraries | Seven fresh JARs and seven Android AARs; all seven lint reports contain zero issues |
| Retained proof | 354 source inputs,17 artifacts,173 evidence files,98 JVM XML files |

Independent source/result audit matched every unique declared Kotlin method to the fresh retained XML identities, and all161 exact Node names to Subtest/ok pairs; no failures, errors, skips or duplicate identities. All33 required test/JAR/AAR/lint/scope task lines ran freshly. Current and retained XML/lint bytes agree. Root recursive audit matched673 hash records across596 unique paths, the initial/final source manifest, and identical immutable/current/last-attempt receipt bytes.

Source manifest SHA-256: `17dd7bddb1b9ec3ebb09813b72b2d4c42b6b78997032711fb16f03db33a6bef4`.

Receipt SHA-256: `c04a13b6956dd3bb51f71d60a5b1597b50092974317b98a269e0a9867bd14afa`.

Native coverage retains39 actual bundled-SQLite VFS fault labels and five controlled process stages. The new12 owned-startup native methods include real same-process close/reopen after one/two subordinate closes; they are not new process-kill or physical-power-loss stages. Four ABIs have actual ELF packaging evidence; runtime execution is API35 ARM64 only. iOS is uncompiled, and hardlink creation remains platform-denied/unexercised. The test-only C VFS helper is absent from seven current AARs, the session test APK and the unchanged historical demo; this does not claim every Kotlin fault seam is absent from bytecode.

Independent native audit matched323 unique source/start/success identities across29 invocations, all12 new startup names, all39 raw VFS label mappings (26 code1034/13 code1290 with required zero-effect assertions), five exact process-stage selectors and45 original/retained evidence-copy pairs. Actual four-ABI ELF bytes/headers and APK metadata agree. Exact delivered-notification cancellation passed. No integrity discrepancy was found; the descriptive undercount below is the only receipt wording issue.

Receipt erratum: the inherited `nativeInjectionPackaging.scope` text says “six current Android AARs.” Its actual exclusion list covers **seven** AARs plus both named APKs (nine archives); verified coverage is seven. Preserve the frozen runner/receipt bytes and correct that descriptive string when the next source batch opens.

## Review corrections and failed evidence

- Central schema review made mode optional only for `needsConfirmation`/`noMatch`, never `ready`/`recalled`. Explicit shared/server matrices replace two obsolete unconditional-required mutation expectations (8,423 →8,421); null/auto remain invalid. Historical contract receipts were not repinned.
- Meal-flow review added durable dispatch admission, conservative uncertain-response handling, seven-day issued-key capacity and a pre-dispatch worst-case record budget. A too-small negative test fixture initially failed; its escaped input was increased with explicit boundary assertions, without relaxing production limits. The final47-test focused run and full rerun passed.
- Server review added current normalized-taxonomy checks, monotonic lifecycle validation and retired historical-read versus current-ready-replay distinction. All28 new PostgreSQL and15 new server unit tests passed in the final run.
- The first owned-startup native diagnostic passed prior147 +11/12 new methods. The remaining test incorrectly assumed PendingSetup inspection installed the retirement latch. Its setup now uses a real captured binding and actual retirement request with the first control CAS failing; unchanged zero-factory/no-effect assertions passed in the complete rerun. The failed transcript remains retained under `verification/native-credentials/android-attempts/2026-09-13T22-06-27.128Z/`.

Focused failures and earlier receipts remain historical evidence, not overwritten success. The blueprint's regenerated22-scenario/98-screen/900-handler browser run is synthetic navigation proof only; see the contract-alignment document.

## Cleanup and publication boundary

The exact owned read-only emulator (PID24891/session31454) exited0; the device list is empty. Before shutdown the storage test-private directory was empty and the session test app retained only three expected WorkManager files. The disposable emulator overlay initially lacked the session test package; permission was granted only to the newly installed instrumentation package to exercise exact delivered-notification cancellation. No saved AVD state or real FeedMe permission was changed. All181 retained synthetic PostgreSQL clusters have no postmaster PID files, and port8789 has no listener. No clusters were deleted.

The public export remains clean and unchanged at `4b9a5ac0b2bef352d3b617082d0bb62b31ec966c`; these changes are local. No deployment, purchase, account selection, CI provisioning, publication or store submission occurred. The active goal and existing needs-you follow-up remain; unanswered [user actions](USER_ACTIONS.md) are not silently resolved.

## Next team assignments

1. Cooking/UI: connect shared Kotlin REQUEST → RECOMMENDATIONS → owned Plan-context RECIPE to the accepted controller. Preserve exact canonical quantities and nonready/offline/resolving states; retain the labelled demo separately. Pantry carries ingredient IDs, so integrate authenticated `searchIngredients` and bounded pantry pagination without fabricated ID/name mappings. Cooking/save require fresh authority, not a historical Plan.
2. Backend: add canonical planning ingress behind mandatory verified principal/current input/catalog adapters; test actual HTTP denial/replay/ETags and transactional lifecycle races. Unconfigured providers must remain unavailable, not fall back to fixtures.
3. Platform: exercise composite task7 process-separated interruption and application-root lifetime integration. Preserve pending evidence, retained close ownership, terminal ambiguity and the public journal refusal boundary.
4. Lead: integrate, review native layout/accessibility/navigation against the approved visual system, and rerun source-bound acceptance. Rotate onto remaining V1 packages when their dependencies are usable; all whole-feature and release gates remain open.

Reproduce with `node scripts/verify-parallel-meal-startup.mjs`, JDK17, Android SDK36/NDK28.2.13676358, local PostgreSQL through `FEEDME_POSTGRES_BIN`, and a booted API35 emulator through `FEEDME_TEST_DEVICE`. Older fixed-inventory runners/receipts are historical. Component test totals are not a percentage-complete or deadline estimate.
