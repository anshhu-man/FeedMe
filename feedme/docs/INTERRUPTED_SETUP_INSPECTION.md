# Interrupted setup — read-only inspection

13 September 2026. M1.05d.5b.2b.3.6a is **DONE as a bounded component**. This is the already-open diagnostic part of [composite session setup](COMPOSITE_SESSION_SETUP.md), not complete startup recovery, an abort proposal, or a user-facing recovery screen. The original FeedMe scope and public repository snapshot are unchanged.

Subsequent [6b exact abort primitives](SETUP_ABORT_PRIMITIVES.md) are also DONE bounded and add authenticated ABORTED work observations only when the retained control intent has its abort flag. Inspection itself remains read-only. [Build status](BUILD_STATUS.md#current-verification) indexes current-source evidence; the counts and hashes below preserve the earlier 6a checkpoint.

## Production entry point

`PrivateSessionRuntime.inspectInterruptedSetup()` runs on the existing identity owner and under its mutex. It requires an inactive session in an inspectable runtime phase. Coroutine cancellation, close, a new lifecycle generation and an active/newer lease invalidate the call, including when a lower operation returns non-cooperatively after cancellation. It does not change the runtime phase or reopen any store.

The returned `InterruptedSetupReport` contains advisory enums only: finding, affected component, sanitized failure reason and, only after matching evidence, component stages. It exposes no owner identifier, plan, scope, fingerprint, revision, credential, native handle, confirmation token or `canAbort` flag. `SELECTED` does not mean credentials are usable; `BOUND` does not mean setup is durably complete.

## Exact read bracket

1. Check the process retirement latch and current owner/coroutine. Read the independent control record with a positive revision and strict codec.
2. Idle/Complete yield `NONE`; legacy PendingCreate remains a separate recovery path; retirement wins. Decode a composite PendingSetup and compare its exact configuration before consulting lower stores.
3. Authenticate the original credential plan and scope using the optional native metadata capability. Capture status and a private comparison fingerprint over the canonical manifest, plan and artifact-name/key inventory. Do not read token/blob contents or test key usability.
4. Authenticate the original private-data plan and capture status plus a private fingerprint over owner, plan, key presence and consumed-plan receipt revision. Do not run key GC or decrypt application records.
5. If the selected data is nonempty, accept only the one reserved, non-tombstoned activation binding. Require positive revision, schema2 and byte-exact canonical content containing the original operation, scope, configuration, credential incarnation, work origin and native data target. Other rows and tombstones are rejected by the native binding inspector.
6. Authenticate the work-origin plan against its exact predecessor or selected/sealed canonical record; capture its positive ledger revision. Reject consumed or nonempty ordinary work.
7. Repeat the component observations and compare status, fingerprints, actual work revision and the binding's full target/revision/schema/payload. Re-read exact control revision/payload and the process latch, including after a lower-store failure. Retirement or changed evidence overrides an earlier partial result.

This is a bounded read bracket, not one atomic cross-store transaction. Repeated matching observations are neither write acknowledgements nor guarantees that another actor will not change state later. Every future operation must revalidate and obtain its own required acknowledgements.

## Findings and integration

| Finding | Meaning / safe next action |
| --- | --- |
| NONE | No composite pending setup in the observed control record. Recheck normal startup; do not infer successful authentication. |
| UNCONFIRMED_SETUP | Original native resources agree with a pending setup and valid live-selection ordering. Preserve for explicit repair. |
| ABORT_REQUESTED | The existing exact journal carries its abort flag. This report itself grants no cleanup authority. |
| OTHER_CONTROL_PENDING | Legacy pending credential creation remains on its separate path. Never extract a nested composite plan into legacy abort. |
| RETIREMENT_PENDING | Existing retirement takes priority. Retry only its already-authorized protocol. |
| CONFIGURATION_CHANGED | Current configuration differs before lower-store reads. Preserve the original resources. |
| RESOURCE_MISMATCH | Authenticated resources, stage ordering or the exact binding disagree. No partial stages are returned. |
| EVIDENCE_UNAVAILABLE / EVIDENCE_CHANGED | A capability/read failed or the bracket changed. Recheck evidence; no token read, fallback open or mutation occurs. |

For unconfirmed setup, credentials must be selected before data advances; work cannot advance until data is selected; binding requires selected/sealed work. Abort states without the retained abort flag are contradictory. With an existing abort flag, authenticated partial abort stages may be reported. [6c confirmed-abort handling](COMPOSITE_SETUP_ABORT.md) now operates on borrowed already-open stores; all-owning startup recovery remains unfinished.

## Deliberate limitations

- Only already-open, parent-owned stores are inspected. Closed, poisoned, missing, partial-inventory or factory-blocked stores remain unavailable. There is no initialization, migration, recovery-factory fallback or hidden handle to close.
- Metadata fingerprints are private comparison values, not authentication/cleanup capabilities. Credential blob content and key usability are deliberately excluded; missing presence changes fingerprints, content corruption need not.
- The original 6a work observations added revision evidence without changing the work lifecycle. Subsequent 6b adds an authenticated aborted marker; observing it does not execute abort or grant confirmation. A same-payload acknowledged write still changes the observed revision.
- Data fingerprints include abort receipt revision, so a fresh acknowledged consumed-plan replay is detectable even when the enum stays ABORTED. Binding content/revision is inspected separately; ordinary payloads are not fingerprinted.
- No confirmation proposal, persisted confirmation, cleanup, provider verification, lease, ID allocation, scheduler registration, native UI, factory repair or new publication is implemented here.
- API26 credential/recovery, full Xcode/iOS, physical devices/power loss, public hot-journal recovery and failed-open/failed-close ownership remain separate gates.

## Remaining task6 slices

| Slice | Required acceptance |
| --- | --- |
| 6a — DONE bounded | Already-open, redacted, no-effects inspection with exact native metadata and control brackets. |
| 6b — DONE bounded | [Native exact abort primitives](SETUP_ABORT_PRIMITIVES.md): authenticated work-aborted marker with changed acknowledgement on every retry; binding-aware data abort permits zero rows or the sole schema2 binding matching trusted expected bytes and deletes no domain row/tombstone. Session identity derivation and explicit consent remain the coordinator's responsibility. |
| 6c — DONE bounded | [Generation-bound explicit confirmation and coordinator](COMPOSITE_SETUP_ABORT.md): preflight all original resources, acknowledge exact abort intent before ordered work/data/credential cleanup, and freshly re-acknowledge pending retries. Actual runtime integration borrows already-open stores and closes none; startup-owned handles and their pre-Complete close acknowledgements remain6d. |
| 6d — IN_PROGRESS | [Retained credential/data owners and remaining startup slices](STARTUP_RECOVERY_OWNERS.md). Existing-only work recovery, the all-owning startup composition and owned-close-before-Complete still require integration. Never route through ordinary open/resume as a diagnostic fallback. |

Task7 separately covers the integrated failure/process-separation matrix. Task6 as a whole remains incomplete until the confirmation, cleanup and startup ownership gates pass; primitive tests alone cannot close it.

## Historical 6a verification

The full [source-bound receipt](verification/interrupted-setup-inspection/verification.json) passed at **2026-09-13T17:46:00.346Z**: **1,461 Kotlin/server/database tests, 122 Node checks and 203 isolated Android tests**, five freshly built Android libraries and zero-issue lint. Shared Kotlin totals 1,370 (core48, contracts119, transport72, storage/integration424, sync103, kitchen142, session462), plus server46 and isolated PostgreSQL45. Native coverage is storage97/session106.

New coverage comprises 32 common inspector, 12 common work-observation, 18 real-SQLite runtime, 10 real-SQLite data-observation, 12 native credential-observation and 9 native runtime-inspection tests. All prior 27 VFS labels and five controlled process stages remain included; injected engine failures and controlled reopen are not physical power-loss or hard-kill acceptance. The Android session tests use a synthetic verifier, not provider authentication.

The receipt binds **258 source inputs, 13 artifacts and 136 evidence files**. Source aggregate SHA-256: `81ca28615c158ab04129a499a333398add88562bb509b81ee27324d699e325e2`. Receipt SHA-256: `83b7b6933974d5fa49f05e9844bde2eb5ea83025f4adcfea53b1440c14f108fa`. The historical demo is unchanged; fault-injection code remains isolated to the storage test APK. Root audit verified all 506 recursively referenced size/hash records and exact current/immutable/last-attempt receipt copies. The owned emulator exited normally, its notification permission remained granted, and no test-server listener or PostgreSQL PID files remained across 110 retained synthetic clusters.

Independent audits matched 1,461 unique executed JUnit methods across 79 XML files bijectively to current source, 122 exact TAP name/result pairs, 203 native paired start/success identities across 17 invocations, all 27 VFS labels, five process stages, 33 original/retained native copy pairs and four actual ELF ABI headers/bytes. No discrepancy was found. Existing-hardlink branches remain honestly unexercised because the platform denied creation with errno13; exact delivered-notification cancellation passed.

Focused production compilation and both focused JVM modules also passed. One earlier focused test compilation failed on a fixture variable-shadowing error; the fixture was corrected before the successful focused and full runs. No product defect was concealed or marked passing by weakening an assertion.

For current sources, use `node scripts/verify-startup-recovery-owners.mjs` in the existing JDK17/SDK36/local PostgreSQL/emulator environment. The following command reproduced the historical 6a snapshot only; its fixed inventory must not be run against newer sources:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home \
ANDROID_HOME=/Users/LOCAL_USER/Library/Android/sdk \
FEEDME_POSTGRES_BIN=/opt/homebrew/opt/postgresql@15/bin \
FEEDME_TEST_DEVICE=emulator-5554 \
node scripts/verify-interrupted-setup-inspection.mjs
```

The preceding [publication receipt](verification/session-setup-publication/verification.json) remains historical. No historical runner or receipt was overwritten, no new sources were pushed, and no feature, full milestone or release gate is accepted by this component result.
