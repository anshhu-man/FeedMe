# Explicit composite setup abort

14 September 2026. M1.05d.5b.2b.3.6c is **DONE as a bounded already-open component**. This connects [read-only inspection](INTERRUPTED_SETUP_INSPECTION.md) and [exact cleanup primitives](SETUP_ABORT_PRIMITIVES.md) through the actual native runtime. It does not implement recovery screens, provider login, startup recovery factories or the full cross-process interruption matrix. [Composite task6](COMPOSITE_SESSION_SETUP.md) and the full FeedMe release remain incomplete.

## Runtime API and ownership

The shared `PrivateSessionRuntime` exposes three explicit operations:

| Operation | Behavior |
| --- | --- |
| `preparePendingSetupAbort()` | Read-only native preflight returning an opaque `PreparedSessionSetupAbort`. No confirmation, cleanup, provider call or ID allocation. |
| `confirmPendingSetupAbort(proposal)` | Trusted UI integration calls this only after the user confirms this exact proposal. Freshly compare all evidence, acknowledge the original pending intent with its abort flag, then run ordered cleanup. |
| `retryPendingSetupAbort()` | Retry an already-confirmed original intent with fresh acknowledgements. Unconfirmed, legacy, unrelated or arbitrary Complete records do not supply abort authority. |

The runtime owns serialization on its existing identity dispatcher and mutex. Proposals are bound to the runtime instance, lifecycle generation, immutable configuration and exact observed evidence. They expose no public owner, plan, operation ID, payload, native handle, token or user-supplied deletion predicate. Cancellation, close, active/newer lease or a changed lifecycle invalidates the operation, including a cancelled queued caller and a native call that returns non-cooperatively.

These APIs are production coordination code, but confirmation in tests is programmatic. An actual confirmation screen must show the interrupted setup action and call `confirmPendingSetupAbort` only in response to that explicit choice; a screen render, inspection result or crash must never invoke it automatically.

All credential/data/work resources are already-open borrowed owners. The coordinator opens and closes none. Parent stores retain their normal lifetime ownership. Startup factories and retained failed-open/failed-close owners belong to task6d; this integration never substitutes ordinary open/resume/GC as recovery.

## Exact evidence and confirmation

The internal inspector retains detached evidence rather than using its public advisory report as authority: exact independent control revision/raw payload, the original canonical setup plan, native credential/data statuses and metadata fingerprints, work status/actual revision, and the sole binding's full native target, schema, revision and bytes when present. It reads resources forwards and backwards inside exact control and process-retirement checks.

Preparation only accepts coherent, unconfirmed composite setup with the optional native credential-abort capability available. Confirmation repeats the whole bracket and compares every field against the proposal. Even a same-payload control/work write or same-content binding rewrite changes its revision and requires a new proposal. Missing/poisoned owners, wrong configuration, unrelated plans, extra rows/tombstones, invalid binding or contradictory stage ordering stop before confirmation or cleanup.

The confirmation write is `PendingSetup(originalPlan, abortRequested=true)` through a genuinely changed control CAS, its exact successful receipt and readback. A failed/unknown receipt stops all lower effects, even when matching true intent is visible. An explicit retry of that retained true intent obtains a new acknowledgement first. Credential-only legacy PendingCreate recovery remains separate; it cannot extract a nested plan from composite control.

## Cleanup sequence and retry

1. Recollect all native evidence after confirmation acknowledgement; that acknowledgement does not freeze the other stores. Require the exact acknowledged control and unchanged original resources.
2. Abort the exact work plan. Require successful changed acknowledgement, ABORTED status and exactly the next work revision. Credential and data evidence must remain unchanged.
3. Derive the full canonical schema2 binding from original operation, scope, configuration, credential incarnation, work origin and the authenticated native data target. Abort the exact data plan with those expected bytes, or strict empty-only mode when no binding exists. Require ABORTED, no binding and a changed native receipt fingerprint. Work and credential evidence must remain unchanged.
4. Abort the exact credential plan and scope through the optional native capability. Require ABORTED while work/data evidence remains unchanged. No credential payload is read or used as identity proof.
5. Recollect final evidence and require all three exact aborted resources, no binding, no active/newer lease and unchanged pending control. Acknowledge `Complete(originalOperation)` only now, and bracket final resources/control again before returning success.

Control, process-retirement and lifecycle checks surround every awaited boundary and precede each effect. These independent stores are not one atomic transaction; each native operation also authenticates its own exact target under its own guard. If a later component fails, earlier consumed fences remain. Retry never broadens the target set.

Every **pending-intent retry** re-acknowledges true control and all three native aborts, even when they already report ABORTED. Matching enum/readback evidence alone is never a new durability receipt. Reverse cleanup progress is rejected: data cannot be aborting/aborted before work is aborted, and credentials cannot be aborting/aborted before data and work are aborted.

## Lost final acknowledgement

The coordinator retains exact final component evidence and the predicted next Complete record immediately before attempting the final control CAS, only after all lower acknowledgements succeeded. If that write became visible but its result was lost, the **same runtime generation** can retry only the final control receipt: require that exact predicted revision/payload, unchanged original aborted resources, then obtain a fresh Complete acknowledgement and final bracket. This finalization-only retry does not repeat already-acknowledged cleanup.

It cannot follow a later same-operation revision, changed native evidence or an arbitrary Complete. The retained proof is consumed after successful return and is unavailable after runtime replacement, close or lifecycle-generation change. A restarted coordinator cannot infer it from a stored operation ID. Normal startup may inspect a completed, empty state without treating it as new abort authority. A successful abort returns the runtime to STARTUP; normal recovery and a fresh verified create remain separate actions.

## Native credential seam

`CredentialCreatePlanAbort.abortPlannedCreate(scope, plan)` is an optional capability on the already-open Android owner. Native scope binding, plan authentication, exact slot revision and inventory are checked inside the same guarded mutex acquisition as the existing abort body. It preserves poison/closed-owner barriers and neither opens a recovery handle nor closes its parent.

The existing legacy recovery handle and new seam share exact cleanup, synchronization and key/file rules. Selected missing or damaged credential material can be erased only through authenticated exact metadata; it is never decrypted or reconstructed. Foreign/newer selections and unknown inventory remain untouched. Every replay obtains the existing native synchronization acknowledgement. Ordinary credential retirement is not a substitute.

## Historical 6c verification

The full [source-bound receipt](verification/composite-setup-abort/verification.json) passed at **2026-09-13T18:50:03.131Z** (14 September in India): **1,570 Kotlin/server/database tests, 142 Node checks and 253 isolated Android tests**, five freshly built Android libraries and zero-issue lint. Shared Kotlin totals 1,479: core48, contracts119, transport72, storage/integration466, sync103, kitchen142 and session529; server46 and isolated PostgreSQL45 remain separate. Native coverage is storage125/session128. Production compilation, the initial storage464 run and final focused storage466/session529/native test-APK build also passed.

New coverage comprises 22 real-SQLite runtime integration tests, 40 independent common protocol tests, 12 native credential-capability tests and 10 real-native runtime tests. Runtime tests cover queued stale/cancelled callers and cancellation at the final dispatcher return, including preservation of a newer external lease. Independent review also tightened typed latch-error handling and consumption of retained finalization proof after success. No failing build/test attempt was hidden or waived.

The receipt binds **274 source inputs, 13 artifacts and 145 retained evidence files**. Source aggregate SHA-256: `23d828e5d1d1a90163c6a1005466a187dad8017b765ad37547998b475174119a`. Receipt SHA-256: `f424a5e0af6f5c5498165908e8211654b779d51e3b38049a3fa9af3207276564`. Root audit verified all543 recursively referenced size/hash records (477 unique descriptors), source aggregate and exact current/immutable/last-attempt receipt copies. The owned emulator exited normally, test notification permission remained granted, and no port8789 listener or PostgreSQL PID files remained across126 retained synthetic clusters. None of those clusters was deleted.

Independent audits matched all1,570 unique JUnit methods across82 XML files and142 exact TAP pairs to current source declarations and fresh command timestamps, plus253 unique native start/success pairs across23 invocations. All39 VFS labels, five controlled process stages,39 native copy pairs and actual four-ABI ELF bytes matched. The test helper is absent from five AARs, the session test APK and the unchanged demo. Storage fixtures were removed; only three expected WorkManager database files remain in the session test app. No discrepancy was found. Existing-hardlink branches remain unexercised because the platform denied creation; exact delivered-notification cancellation passed.

Earlier [primitive evidence](verification/setup-abort-primitives/verification.json) remains historical for the changed sources; old fixed-inventory verifiers must not be run against this newer tree. The historical demo APK and clean public export checkout remain unchanged, and no new source was pushed.

The dedicated historical6c verifier was `node scripts/verify-composite-setup-abort.mjs`; its fixed inventory must not be run against newer sources. Current source verification uses `node scripts/verify-startup-recovery-owners.mjs`, indexed in [Build status](BUILD_STATUS.md#current-verification), using the existing JDK17, Android SDK36/NDK28.2.13676358, local PostgreSQL binaries and a booted API35 emulator selected by `FEEDME_TEST_DEVICE`. It preserves all previous native cases,39 VFS labels/five controlled process stages, JUnit/TAP identities, artifact hashes and four-ABI test-helper isolation. Confirmation/control/work response-loss wrappers are application acknowledgement failures, not new native engine faults or physical power loss.

Task6d startup factory/close ownership, task7 integrated process-separation acceptance, native UI/provider/bootstrap, API26/iOS, physical devices/power loss, public hot-journal recovery, domain/server/social/billing integration and all release gates remain open. No app identifiers, spending, deployment, submission or new GitHub publication is authorized by this implementation. The original 54 features and98 designed screens remain intact.
