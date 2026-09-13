# Session recovery diagnostics — M1.05d.5a

13 September 2026. This slice adds read-only, bounded recovery evidence to the shared private-session runtime. It is not a repair implementation, identity provider, account reset or permission to delete data. FeedMe's 54 features and 98 screens remain unchanged; the application still uses its explicitly labeled demo composition.

## Delivery packages

| Package | Status | Required proof |
| --- | --- | --- |
| M1.05d.5a | DONE | Bounded already-open component: 18 real-SQLite inspection tests, 22 runtime diagnostic tests and two actual Android diagnostic integration tests pass; no key GC/credential reads/mutations or stale-report publication |
| M1.05d.5b | IN_PROGRESS | [Empty setup discard](EMPTY_SETUP_DISCARD.md) b.1, [planned credential CREATE](PLANNED_CREDENTIAL_CREATE.md) b.2a and [low-level data selection](PLANNED_STATE_ACTIVATION.md) b.2b.1 are complete bounded components; [existing-only data recovery](PLANNED_STATE_RECOVERY.md) is complete as a bounded component, and composite data/work journal, refresh repair and crash proof remain absent |
| M1.05d.5c | TODO | Native factory failure diagnostics, actual platform recovery UI and physical-device/process-loss/restore drills; no reset fallback |

M1.05d.5 remains in progress. No complete authentication, offline, recovery screen or release requirement is accepted by these components.

## Integration and trust boundary

The application-lifetime identity owner may call `PrivateSessionRuntime.inspectRecovery()` only while STARTUP, SIGNED_OUT, RESTORE_REQUIRED or RECOVERY_REQUIRED, with no current lease. The method rejects ACTIVE, VERIFYING, COMPOSING and RETIRING before diagnostic I/O; CLOSED also fails. It captures a lifecycle generation before waiting for the owner mutex and rechecks it after every asynchronous observation. A later close or superseding lifecycle operation cannot publish an old diagnostic as current evidence.

Inspection never changes runtime phase, creates a lease, obtains credentials, calls a verifier, generates a UUID, commits a control/work/private record, cancels a timer/worker, retires an account or invokes a repair. It does not call `recover()` or `restorationAllowed()`: the former performs cleanup, and the latter can install a process retirement latch. A separate internal query observes an existing process latch without creating one.

`SessionRecoveryReport` contains only finite finding/component/next-step enums and an optional sanitized failure-reason enum. It carries no account ID, scope, UUID, revision, configuration digest, path, key alias, token, private record, native ticket or raw exception. Suggested next steps are advisory presentation routing, not executable commands or transferable authority. Every real action must use the corresponding trusted lifecycle API and freshly validate its own evidence.

## What the report means

| Finding | Observed evidence | Permitted interpretation / next step |
| --- | --- | --- |
| RETIREMENT_PENDING | Existing process retirement latch or valid logout/setup-discard control intent | Keep access closed; explicit retry of the existing retirement workflow, which independently validates exact targets |
| EVIDENCE_UNAVAILABLE | Missing/malformed control or a failed metadata/binding observation | Preserve state and recheck the named component; a generic storage failure does not identify corruption, lock state or a lost key |
| EVIDENCE_CHANGED | Control/credential/work/binding evidence differs across observations | Discard the old diagnosis and recheck; no automatic action based on mixed snapshots |
| METADATA_EMPTY | No selected credential owner and idle work metadata | Not a globally empty installation; owner data or orphan keys may remain. Startup and verified create still perform their own checks |
| PARTIAL_STATE | Distinct pending credential CREATE, only credential or work selection, or work retiring without a valid independent retirement barrier | Preserve for the corresponding exact recovery workflow; do not infer abort confirmation, fill in stores or fabricate cleanup authority |
| SCOPE_MISMATCH | Selected credential and work scopes differ | Do not join identities, merge guests or choose one store as authoritative |
| DATA_MISSING | No active owner for the exact selected scope | Not proof of successful erasure or permission to recreate the account |
| BINDING_MISSING / BINDING_INVALID | Active owner lacks the exact record, or schema/codec validation fails | Preserve for repair; matching account strings cannot recreate an activation binding |
| CONFIGURATION_CHANGED | Bound trusted configuration differs | Do not hand old secrets to a differently configured provider/API |
| BINDING_MISMATCH | Scope, credential incarnation, work origin or current authenticated data target differs | Preserve exact separate evidence; no rebind or silent reset |
| VERIFICATION_REQUIRED | Exact local metadata and activation binding agree | Still not signed in or offline-authorized; real credential read and provider/owner policy are required |

A valid binding does not prove that the credential blob/key is usable, tokens are valid, the account/device is unrevoked, membership is current or no new recipe recall exists. RESERVED/CANCELLING native work is not automatically a broken login: a real verified restore already reconciles exact incomplete tickets before publishing access. A generic 401 or refresh failure does not imply logout or authorize deleting private cooking progress.

The inspector observes independent control first, then credential metadata, work metadata and only the exact activation record. It rereads successful observations in reverse order and encloses them in matching control revision/bytes. An existing/new process retirement latch takes precedence, including when durable control is unavailable. Separate databases are not one transaction; matching observations remain a diagnostic snapshot, never a durable activation or repair grant.

All control gates recognize the distinct `PendingCreate` intent. Diagnostics classify it as PARTIAL_STATE without reading credential/work/data evidence; ordinary retirement recovery and empty-only discard refuse it rather than falling back to their cleanup protocols. The separate `CredentialCreateCoordinator` may inspect its opaque pending proposal and replay an already-confirmed abort; a newly observed plan requires explicit confirmation. Neither a diagnostic nor an initial ABORTED native observation clears that durable gate.

## Storage primitive: genuinely separate from resume

`EncryptedStateDatabase.inspectRecord(scope, key)` reads an exact requested active owner's authenticated current target and decrypted record in one SQLite read transaction. It returns a redacted `StateRecordInspection` only to trusted native composition, not UI. A null target means that requested owner is absent/inactive; a non-null target with no record means the requested record is absent/deleted. It does not enumerate other owners or return a write-capable private-store handle.

Unlike `resume()`, inspection never calls `cleanupKeys()`. It does not initialize schema, create/recreate an owner/key, run pending key GC, modify a retirement fence or perform repair. It requires the current existing key and rejects locally retired, corrupt or unrepresentable owner state. The target and record share one read transaction; malformed/ciphertext-invalid records fail instead of producing a deceptively valid target-only result. The existing strict record decryption helper is shared with ordinary reads.

The target is an authenticated retirement capability and the record is private plaintext: keep the storage-level result out of UI/logs. Only the bounded runtime report is presentation-safe. Read errors may make an already-failed database handle unusable according to its existing transaction safety rules; no recoverability guarantee is inferred from a diagnostic failure.

## Native factory and repair limits

This API operates only on already-open resources. Ordinary native factory opens are not read-only probes: they can create files/keys, initialize or migrate schema, recover journals and execute pending key cleanup. Never reopen them merely to obtain a diagnostic. Failed opening must remain a separate platform recovery state. The later plan-authenticated existing-only data opener is a distinct restricted capability, not a replacement diagnostic probe or permission to guess a plan.

Interrupted credential refresh can leave an extra old/new blob or orphan key that deliberately prevents the credential factory from opening. The runtime therefore cannot diagnose those layouts through this API. Most Android factory errors currently collapse to STORAGE_FAILURE; these do not distinguish corruption, missing key, unsafe path, lock contention or device availability. API26 credential factory rejection is separately NOT_CONFIGURED. Do not invent an unlock/erase instruction from a generic error.

Conversely, a selected credential key/blob can be missing, or its ciphertext damaged, while the authenticated slot metadata remains readable. A diagnostic must still say VERIFICATION_REQUIRED if the binding agrees; actual credential read/verification remains the necessary next gate, not something diagnostics silently perform.

The later [empty-setup discard](EMPTY_SETUP_DISCARD.md) component supplies a distinct durable protocol only for explicit discard of readable, selected empty fragments. [Planned credential creation](PLANNED_CREDENTIAL_CREATE.md) adds authenticated CREATE ownership before native writes and restricted existing-only recovery from its independent journal. [Planned private-data activation](PLANNED_STATE_ACTIVATION.md) supplies low-level exact selection; its `Unit` acknowledgment is not a diagnostic, lease, private handle, retirement target or durable setup-journal resolution. [Planned state recovery](PLANNED_STATE_RECOVERY.md) now implements a separate plan-bound inspect/abort/close component, with completed source-bound shared/native verification. Every empty-only abort commits an actually changed V2 receipt and consumed generation before exact key deletion, even after an ABORTED observation; historical same/foreign-owner receipt references prevent candidate-key reuse.

The API27+ recovery factory requires existing V2/files/lock/index, rejects existing journals and unknown children, and performs no initialization, migration or GC. Normal opening alone can atomically migrate exact V1 to V2 and retains ordinary cleanup behavior. Returned-handle close retries; failed construction whose cleanup close also fails retains ownership until process restart. The runtime still uses unplanned data activation and has no composite work/setup journal or data-recovery UI. None of these components authorizes a general factory probe or arbitrary orphan deletion. Refresh recovery, missing install authentication, confirmation UX, physical devices, app hard-kill, power loss, iOS, API26 compatibility and patched SQLite remain release gates. No provider setup, deployment, spending, store upload or publication is part of these slices.

## Verification

Current source-bound verification, exact test totals and the latest receipt are indexed in [Build status — current verification](BUILD_STATUS.md#current-verification). The [work-origin planning component](WORK_ORIGIN_PLANNING.md) adds lease-free native-owner-bound preparation and a setup-selected state; it does not complete composite setup, private binding, explicit setup abort or provider/UI integration. Earlier component counts and hashes below are historical evidence, not current build outputs.

Historical completed snapshot: [planned state recovery](PLANNED_STATE_RECOVERY.md), **DONE for bounded M1.05d.5b.2b.2**, finished **2026-09-13T14:53:52.071Z**. The [historical receipt](verification/planned-state-recovery/verification.json) passes **1,109 Kotlin/server/isolated-PostgreSQL tests, 92 Node checks and 122 native checks**, with zero failures/errors/skips and five clean Android library lint reports. Shared tests total 1,018 (storage 315/session 219); server 46 and PostgreSQL 45 are additional. Native tests comprise 65 storage (57 regular, six separately invoked injected-VFS cases and two separate-process stages) and 57 session. VFS error injection is not an OS errno, physical power-loss or public-factory journal-recovery test. Runtime data-plan wiring, recovery UI and the [seven-task composite setup package](COMPOSITE_SESSION_SETUP.md) remain TODO; no whole milestone or release is accepted.

The previous complete [planned private-data activation](PLANNED_STATE_ACTIVATION.md) run finished **2026-09-13T13:35:54.272Z** with **1,069 Kotlin/server/PG, 92 Node and 102 native checks**, shared 978 (storage 275/session 219) and native 45 storage/57 session. Those totals and the diagnostic receipt/counts/hashes below are historical. Lost application receipt after acknowledged COMMIT remains distinct from actual filesystem-sync failure; neither proves physical power-loss safety or justifies a universal extra-fsync rule.

The [historical recovery-diagnostics receipt](verification/recovery-diagnostics/verification.json), completed **2026-09-13T11:54:29.621Z**, passes **896 Kotlin/server/isolated-PostgreSQL tests, 92 Node checks and 66 Android checks** with no failures, errors or skips. Shared Kotlin totals 805: core 48, contracts 119, transport 72, storage/integration 172, sync 103, kitchen 142 and session 149; server 46 and PostgreSQL 45. All five Android libraries build and pass lint with zero issues. Existing Kotlin warnings remain distinct from clean Android lint.

Forty new JVM tests cover the exact inspection and runtime diagnostics. The 18 storage tests use actual encrypted SQLite with a test JCA vault, including a real failed-delete key-GC row that remains untouched by inspection, a rejected interleaving writer, strict malformed-record/key failures and cancellation. The 22 runtime tests use real encrypted data/control/work stores with test credential/verifier/native-effect ports. They cover all findings, process-only and durable retirement priority, reverse-read changes/failures, no secret or mutation calls, guest/unselected-owner preservation, busy/close/cancel/queued-generation races and bounded report fields.

On the API35 arm64 emulator, 29 regular storage tests and two separate storage-process stages pass. The session test package passes 29 credential/cancellation tests plus six separately invoked native integration tests. The two new diagnostic tests use real public factories and preserve files, aliases and native job identities across repeated inspections. One loops through a missing selected key, missing blob and tampered ciphertext: authenticated metadata still agrees, inspection never reads credentials, and a separate explicit credential read fails. These tests do not represent real provider verification or an app recovery screen.

Source-manifest SHA-256: `701b9ae51c9e312886002e77866c53ea096316e4776fa980234a2ff8913c68ab` (187 inputs). The immutable attempt `verification/recovery-diagnostics/attempts/2026-09-13T11-53-33.757Z/` retains 91 evidence files and 13 artifact hashes. Receipt SHA-256: `0c510df4d45794734beaba1808293f77e58cca756f54e072e1ed3fbf6e3abe7e`.

Session test APK: 8,394,103 bytes, SHA-256 `9a8f7fc1296c61d0984c99b5c515c1b68907e50bd22243eef904bfed8f2ca2ba`. Storage test APK: 6,273,871 bytes, SHA-256 `e0d54e19e5f7a2687de6f2d00f331781930e9fc4fcab51190527da66c6a70e75`. Session tests target SDK36; storage tests target26, both executed on API35. The demo APK is unchanged and not rebuilt or connected to the new diagnostics.

An independent read-only audit rehashed all 187 current sources, 13 artifacts and 91 retained evidence files with no mismatch. All 896 actual JUnit cases in 58 XML files, 92 TAP names and 66 native start/success pairs match current source declarations with zero failures/errors/skips. Nineteen native evidence copy pairs and two metadata pairs match; all four commands exited successfully and five current/retained lint reports have zero issues. Immutable receipt, pointer and last-attempt bytes agree. Actual APK inspection confirms the stated SDK levels.

Delivered-notification cancellation passed after a temporary grant only to the isolated test package; its original denied permission was restored and confirmed afterward. Both hardlink fixtures were platform-denied, so execution of the existing-hardlink guards remains unproven. Controlled close/reopen is not app hard-kill, power-loss, physical-device or iOS evidence.

The owned headless emulator exited successfully after verification. None of the 66 retained synthetic PostgreSQL fixture directories has a `postmaster.pid`, and no test-server listener remains on port 8789. Exact test-owned fixture cleanup passed; unrelated apps and FeedMe demo data were not reset. Delivery tracking and its 14 tests pass, preserving 10 milestones, 72 milestone tasks, 54 features, 162 unchecked feature work packages and 98 screens. These checks validate tracking structure, not implementation acceptance.

Reproduce with installed JDK17, Android SDK, local PostgreSQL binaries and an owned booted local emulator:

```sh
FEEDME_TEST_DEVICE=emulator-5554 node scripts/verify-recovery-diagnostics.mjs
```

Set `JAVA_HOME`, `ANDROID_HOME` and `FEEDME_POSTGRES_BIN` to installed paths. The runner binds fresh source/artifact/evidence hashes and exact native source-method start/success identities, with explicit permission/hardlink branches. It does not change notification permissions. Previous receipts remain historical and do not cover the new diagnostics. No whole feature, milestone or release gate is accepted by this component verification.
