# Retained startup recovery owners

14 September 2026. M1.05d.5b.2b.3.6d.1–4 are **DONE bounded**; parent6d/task6 remain **IN_PROGRESS** for production application wiring and integrated acceptance. The first slice adds retained credential/private-data ownership, [the second existing-only work recovery](WORK_RECOVERY_OWNERS.md), and [the third/fourth owned startup and close-before-Complete](OWNED_STARTUP_RECOVERY.md). No complete authenticated product journey is claimed. The original54-feature/98-screen blueprint is preserved; [V1 includes44 features and defers10](V1_RELEASE_SCOPE.md).

The [preceding parallel batch](PARALLEL_FEATURE_FOUNDATIONS.md) added retained existing-only CONTROL ownership. Its trusted `ExistingSessionControlRecoveryStore` transfers ownership before opening and keeps the exact fixed encrypted ledger behind guarded read/CAS/close, without initialization/migration/GC or interpreting session-plan authority. Its26 JVM/12 native methods remain covered. The [current batch](PARALLEL_MEAL_STARTUP.md) adds bounded6d.3/6d.4 composition with36 session JVM/12 native methods; task7 integrated interruption and production wiring remain open.

## Why this precedes startup composition

An existing-only factory returning either a handle or a failure can lose its only cleanup reference when opening fails and closing also fails. A path reservation may block another open, but it is not a caller-accessible cleanup owner. Previously, credential closing also marked itself finished before all releases acknowledged. Those gaps must not be hidden by a successful test on already-open borrowed stores.

The new synchronous factories return purpose-fixed owners before any suspended opening. The application must retain each owner before calling `open()`, and retain it across failed opening, cancellation and failed closing. Construction never looks up Context storage, accesses Keystore or opens files. Native credential and private-data opening require API27+, exact existing private files, authenticated scope/plan and supported existing formats. No owner initializes or migrates storage, runs unrelated key garbage collection, invents an identity, returns an ordinary private-store handle or grants a lease.

## Component APIs

| Owner | Restricted operations |
| --- | --- |
| `CredentialCreateRecoveryOwner` | `open()`, full native `inspect()`, exact `abort()`, retained `close()` |
| `StateActivationRecoveryOwner` | `open()`, full native `inspect()`, sole reserved `binding()`, strict empty or exact-binding `abort(expectedBinding)`, retained `close()` |

Android entry points are `AndroidCredentialStore.createRecoveryOwner(context, scope, plan)` and `AndroidStateDatabase.createActivationRecoveryOwner(context, scope, plan)`. Both pin a defensive copy of the opaque plan. Full observations preserve native metadata fingerprints; private binding includes its exact target, schema, revision and bytes. Metadata observation is neither credential usability nor a fresh durability acknowledgement.

The common private-data factory transfers its already-owned connection synchronously into the retained manager before existing-schema initialization. The native owner retains either that manager or its not-yet-transferred connection and sibling-lock lifetime at every observable handoff. It does not discard them when initialization fails.

An admitted failed/cancelled open becomes close-only. Repeated opening never reinitializes, reopens or adopts a newer target. A cancelled rejected second opener must not invalidate the first successful owner. READY is published only after the I/O result returns to the caller dispatcher, with a close-request fence and no subsequent internal dispatcher handoff; competing operations cannot use a not-yet-published recovery owner. The caller closes the same retained owner explicitly, rather than treating open failure as evidence that nothing remains owned.

## Release acknowledgements and boundaries

Closing is non-cancellable and serialized. Private data acknowledges SQLite close before releasing its sibling lock. Native lock closing advances through lock release, channel close, descriptor close and reservation release, preserving acknowledged stages. Retryable pre-stage failures keep the owner and reservation; another close retries only unfinished stages. No failed SQLite close releases the native ownership lock.

The new native data factory also guards entry into the bundled SQLite driver's close. The installed driver marks itself closed before its native close returns; blindly calling it again after an error could return a no-op. An exception after actual driver-close admission is therefore terminal in the retained wrapper. Only an explicit test fault before admission is retryable. The wrapper is retained for both the not-yet-transferred connection and the initialized common manager. Common factory callers must supply this truthful close contract; the older unguarded value-returning factories are not covered by this fix.

Reservations retain the actual sibling-lock lifetime object, not just a pathname. The old value-returning factories still do not expose retained failed-open cleanup to their caller; a failed common SQLite opener can also lose its manager reference. Those legacy paths remain a process-only repair boundary, not seamless retained startup recovery. The new retained factories are the path that keeps both manager and native lifetime accessible.

A real platform close error after its descriptor/channel has become invalid or closed is ambiguous. It is not promoted to success because a later close would be a no-op. A retained terminal failure keeps the process reservation, never retries a saved numeric file descriptor, and requires explicit process repair. Tests can simulate that error immediately after a real descriptor close; that is not evidence of an actual platform EIO or physical power loss.

The application must not clear its recovery journal merely because these two owners closed. The [new owned composition](OWNED_STARTUP_RECOVERY.md) also owns/closes the work-recovery handle and preserves independent control ownership through the final acknowledged Complete write. Its verified protocol is not yet wired into the product roots.

## Small remaining tasks

| Slice | Acceptance |
| --- | --- |
| 6d.1 — DONE bounded | Credential/data retained owners; exact observations; failed-open and failed-close retention; caller-handoff cancellation and truthful native/driver release tests. Verification below covers this component only. |
| 6d.2 — DONE bounded | Existing-only fixed work owner plus original-plan public session facade; exact native proof, sole ledger and status/revision observation; changed-CAS abort/replay; retained failed-open/failed-close ownership. No initialization, normal resume, migration, GC, signing, lease or scheduler. Added30 storage JVM,28 session common,12 lower native and7 real public-facade tests. Full1,650 Kotlin/161 Node/299 native run passed. [Evidence and integration limits](WORK_RECOVERY_OWNERS.md). |
| 6d.3 — DONE bounded | Actual application reservation before factories and retained four-owner startup; exact control/configuration/lifecycle preflight; authenticated original plans, explicit consent and ordered abort; partial-open cleanup and close-only retries. Root invalidation clears private leases synchronously but retains ownership; failed logical runtime admission releases exactly. [Current proof](OWNED_STARTUP_RECOVERY.md). Product wiring remains separate. |
| 6d.4 — DONE bounded | Retain exact ALL-ABORTED checkpoint before subordinate close; acknowledge each close before fresh changed Complete, without reinspecting closed resources or reconstructing consent from persisted Complete. Cancellation/close/CAS/lifecycle and real same-process close/reopen tests pass. [Current proof](OWNED_STARTUP_RECOVERY.md); task7 process-separated integration is not claimed. |
| Composite task7 — TODO | Integrated native process-separated interruption acceptance, distinct from component hooks and controlled reopen. Public journal recovery, hard-kill and physical-power-loss coverage remain explicit gates. |

The existing [6c coordinator](COMPOSITE_SETUP_ABORT.md) still borrows resources and intentionally closes none by default. The new explicitly owned variant retains the ALL-ABORTED checkpoint and defers Complete to the owner; it does not silently transfer ownership in the borrowed path.

## Current verification

The [current combined source-bound receipt](verification/parallel-meal-startup/verification.json) passed at **2026-09-13T22:19:20.584Z**, covering all preceding owner regressions and bounded6d.3/6d.4:1,890 Kotlin/server/database,161 Node and323 native tests with seven clean Android libraries. [Exact inventory, independent audits, failed history and cleanup](PARALLEL_MEAL_STARTUP.md). Use `node scripts/verify-parallel-meal-startup.mjs` for this source tree;6d.1/6d.2 runners below have historical fixed inventories. Application wiring, task7 integrated interruption and release gates remain open.

## Historical 6d.1 verification

The full [source-bound receipt](verification/startup-recovery-owners/verification.json) passed at **2026-09-13T19:31:47.969Z** (14 September in India): **1,592 Kotlin/server/database tests,142 Node checks and280 isolated Android tests**, with five freshly built Android libraries and zero-issue lint. Shared Kotlin totals1,501: core48, contracts119, transport72, storage/integration488, sync103, kitchen142 and session529; server46 and isolated PostgreSQL45 remain separate. Native coverage is140 storage/140 session on the API35 arm64 emulator.

New coverage comprises22 real-SQLite JVM methods,15 native private-data methods and12 native credential methods. Cases cover zero-I/O construction, exact native scope/plan/full observations, absent/foreign/poisoned inventory, partial acquisition, failed initialization plus failed close, retryable release stages, exact abort/binding delegation, queued/rejected open and cancellation/close during the caller-return handoff. A separate real bundled connection proves the close-admission wrapper refuses later no-op success after a simulated post-close error. No terminal native owner is bypassed to manufacture a successful cleanup. These hooks are not actual OS EIO, new VFS faults or physical power loss.

The first full attempt at `verification/startup-recovery-owners/attempts/2026-09-13T19-22-20.784Z/` failed six credential-test descriptor counts. The test helper compared Context's absolute path alias rather than the canonical path used by the opener. Only that test source changed; every exact1/0 assertion was preserved. A focused12-test native rerun passed, followed by the complete fresh run above. The failed attempt remains retained. Production compilation, the initial22-test JVM run and the final488-test storage/two-APK build also passed.

The passing receipt binds280 source inputs,13 artifacts and148 retained evidence files. Source SHA-256: `48a7c137a6f3b63d2b92733897809b7b363c39a16a55adc932c52906536f90e0`. Receipt SHA-256: `ccdea74d1b3b70e5e5a0910c8f242a3ae885c00f3cf3b771d6945325da3169c1`. Root audit matched all556 recursive size/hash records (488 unique descriptors), the source aggregate and exact current/immutable/last-attempt receipt copies.

Independent audits matched all1,592 unique source-declared JUnit methods across83 fresh retained/current XMLs and142 exact TAP name/result pairs. Native audit matched280 unique start/success pairs across25 invocations, all39 VFS labels (26 code1034/13 code1290), five controlled process stages,41 original/retained evidence copies and actual four-ABI ELF bytes. No discrepancy was found. Existing hardlink branches remain unexercised because creation was denied with errno13; exact delivered-notification cancellation passed.

The owned emulator exited normally. Storage fixtures are empty; only the three expected WorkManager database files remain in the session test app. Test notification permission remained granted. No port8789 listener or PostgreSQL PID files remain across134 retained synthetic clusters, none of which were deleted. The public snapshot remains clean and unchanged; no new source was pushed.

Reproduce with `node scripts/verify-startup-recovery-owners.mjs`, JDK17, Android SDK36/NDK28.2.13676358, local PostgreSQL via `FEEDME_POSTGRES_BIN` and a booted API35 emulator selected by `FEEDME_TEST_DEVICE`. The run retains all previous native coverage,39 VFS labels and five controlled process stages. The test-only C VFS helper remains isolated to four storage-test APK ABIs and absent from five AARs, the session test APK and the unchanged demo. Prior [6c evidence](verification/composite-setup-abort/verification.json) remains historical for the changed source tree; older fixed-inventory verifiers are not current-source commands.

## Integration and release limits

Public private-data recovery continues to reject rollback journals, WAL/SHM, absent stores and unsupported schemas. Native credential partial/poisoned inventory rules remain authoritative; an authenticated plan cannot adopt unknown files or foreign keys. These APIs do not select providers, configure app identities, deliver a confirmation UI, schedule domain work or implement refresh. API26 credentials/recovery, full Xcode/iOS, physical-device tests, reviewed content, backend/social/billing and release gates remain separately tracked.

The public GitHub repository remains its audited earlier snapshot. This local work neither republishes source nor deploys or submits FeedMe.
