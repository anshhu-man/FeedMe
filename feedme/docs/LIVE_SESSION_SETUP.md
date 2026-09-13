# Ordered live session setup — retained selection stage

13 September 2026. **DONE — bounded selection component**, verified at **2026-09-13T16:39:46.240Z**. Task M1.05d.5b.2b.3.4 in [composite session setup](COMPOSITE_SESSION_SETUP.md). This document preserves the selection-stage contract and its historical evidence. The subsequent [binding, sealing and late-publication implementation](SESSION_SETUP_PUBLICATION.md) now replaces the runtime setup path and is DONE bounded, verified at **2026-09-13T17:13:06.803Z**; it does not supply a real provider or sign-in screen.

## Production role and ordering

`LiveSessionSetupCoordinator` is internal to the trusted native session owner. `begin(verifiedCredentials)` must follow real provider challenge verification and FeedMe account/guest bootstrap under immutable approved configuration. No application default supplies synthetic identity. Its native adapter delegates directly to the real planned credential store, encrypted private database and work registry; it never calls ordinary activate/resume or creates a temporary lease.

The parent must exclusively serialize those stores on its identity dispatcher, supply a distinct independent control store, and supply its current-owner and process-retirement checks. Per-component mutexes are not a cross-store transaction or permission for another caller to mutate the same resources.

The implemented `begin`/`retry` selection order is:

1. Reject active/stale owners, pending retirement/setup, occupied credential/work slots, invalid configuration or incomplete account bootstrap.
2. Capture the original control revision/bytes and prepare credential, private-data and work-origin plans without selecting resources. Validate exact plan structure, expected revisions and scope, then bracket native data/work observations and credential/control metadata.
3. Retain the exact plan and verified credentials in this live attempt before the first control CAS. Persist `PendingSetup(plan, false)` with a successful changed revision and exact readback. A failed, malformed, missing or unknown acknowledgement cannot authorize selection.
4. Select the exact credential plan; compare the entire returned canonical credential snapshot, not just its owner. Then select the exact data plan, then the exact setup-only work origin. Recheck control, current owner, cancellation and resource state between operations. Nonempty data—including tombstones—blocks continuation.
5. Leave the original composite payload pending. Return only Unit. No activation binding, session lease, record/transport handle, work binding, scheduling, confirmation or journal clearing is returned or performed.

An operation UUID equal to the previous completed control operation is rejected. Revisions cannot wrap or consume the last revision needed for later completion. Native issuers remain responsible for authenticating their opaque plans; canonical decoding alone is not identity, configuration approval or a MAC check.

## Retry, cancellation and interruption

`retry()` accepts no arguments. It can use only the same coordinator's retained verified credentials and exact plans, never a new identity, a newly generated candidate or arbitrary stored bytes. Before any selection it requires either the exact original untouched control record or its exact pending payload at a later revision, then obtains a fresh changed control acknowledgement. Abort-requested, replaced, malformed, advanced-terminal or stale records fail closed. Every retry replays the original selection operations and checks their results; it does not infer completion from progress flags.

`cancel()` and `close()` invalidate the live generation without waiting behind a suspended native callback. Coroutine cancellation, including the dispatcher return handoff, drops the owned live attempt. Late operations may already have completed a native effect, but they cannot continue into another effect after the fence. The exact durable journal is preserved without deletion or an inferred abort request. A cancelled caller still queued behind another operation does not own that other operation's live credentials. Close is permanent; caller-owned stores are closed separately.

A fresh coordinator cannot recover verified credentials from a persisted plan. After restart, cancellation or native credential-manager poisoning, explicit recovery remains required. The existing Android recovery handle is abort-only; partial key/blob/temp inventory cannot be bypassed with ordinary reopening or a fabricated sign-in result.

## Read-only private-data preflight

`EncryptedStateDatabase.inspectPlannedActivation(scope, plan)` authenticates the exact plan/scope and observes its predecessor/selection/abort metadata in a read transaction. It uses no ordinary opener, garbage collection, key allocation/deletion, record decryption or scoped handle. PREPARED/PARTIAL/SELECTED_EMPTY are the only eligible live selection observations. SELECTED_NONEMPTY, ABORTING, ABORTED, changed generations and competing references are rejected by the coordinator.

This inspection is not proof of a usable key, intact ciphertext or a successful disk synchronization. The selected-empty status can still describe damaged selected key material; the actual selection call must validate usability and stop on failure.

In particular, the data component's already-selected replay makes no changed SQL write. It is **not a fresh durability acknowledgement** after a previous sync failure. Successful `begin`/`retry` calls are therefore selection-only, never durable setup completion. The separate [completion/publication path](SESSION_SETUP_PUBLICATION.md) now requires a genuinely changed private binding, work seal and final control acknowledgement before access; it is DONE bounded, verified at **2026-09-13T17:13:06.803Z**. Credential replay separately synchronizes its directory; work selection replay already performs a fresh changed CAS. These different guarantees must not be conflated.

## Verification and open gates

Current source-bound verification is indexed in [Build status](BUILD_STATUS.md#current-verification) and uses `node scripts/verify-startup-recovery-owners.mjs` in the established JDK17/Android SDK/NDK/local PostgreSQL/emulator environment. The historical selection run used `node scripts/verify-live-session-setup.mjs`; its [passing receipt](verification/live-session-setup/verification.json) retains the immutable attempt at `verification/live-session-setup/attempts/2026-09-13T16-38-02.519Z/`. The counts and hashes below describe that snapshot. Protocol fixtures test ordering and lifecycle failures; real SQLite tests cover the inspection; separate Android tests execute selection against real credential/data/work/control stores. Subsequent [read-only composite inspection](INTERRUPTED_SETUP_INSPECTION.md) and [exact abort primitives](SETUP_ABORT_PRIMITIVES.md) are DONE bounded; [confirmed already-open coordination](COMPOSITE_SETUP_ABORT.md) is now DONE bounded, while startup ownership and integrated interruption acceptance remain open.

| Check | Verified result |
| --- | --- |
| Shared Kotlin | 1,205 passed: 48 core, 119 contracts, 72 transport, 370 storage/integration, 103 sync, 142 kitchen, 351 session |
| Server / isolated PostgreSQL | 46 + 45 passed; Kotlin/server/database total 1,296 |
| Node | 112 passed |
| Native Android | 169 passed on API35 arm64: storage94 and session75, including 11 new live-selection tests |
| Android artifacts | Five fresh AARs with zero-issue lint and both rebuilt test APKs; no rebuilt app/release claim |
| Evidence | 241 source inputs, 13 artifacts and 126 retained evidence files; zero failures/errors/skips |

The new work adds 29 common protocol tests, 14 real-SQLite inspection tests and 11 Android tests. Source-manifest SHA-256: `07aeeb2918aa492bf45d23656883f530759f8512f29a2d5af9f564a584aa8e6c`. Receipt SHA-256: `3fbc6d0c525cf6e82f7c84b4ab92edf357e2c66dea43f8dcdcb74d8aa94937f2`.

The storage test APK is 8,705,652 bytes (`2cbdab31183191e7e5c83816c9e21cf68bcd3ea348835f44dc45cc89aa34a63f`); the session test APK is 8,656,247 bytes (`efad58d292f689ea09bf78d43d87dbad8d37a34207e2eade929c17994cc027e9`). All four test-helper ABIs are confined to the storage test APK, absent from the five libraries, session test APK and unchanged historical demo. Existing-hardlink branches remain platform-denied and unexercised; exact delivered-notification cancellation was verified in its separate prior suite.

Native wrapper-injected lost acknowledgements describe an application losing a response after a real store call, not a kernel sync failure or hard kill. Prior actual-engine VFS and controlled distinct-process tests remain separately covered by the full runner. Controlled close/reopen is not physical power-loss acceptance.

Independent read-only audits matched every listed source/artifact/evidence hash and immutable receipt copy, 1,296 unique JUnit identities across 73 XML files to then-current declared methods, 112 paired TAP successes and 169 exact native start/success pairs. All 21 prior VFS labels and five process stages were preserved, with exact APK metadata/copies and actual four-ABI helper bytes. No discrepancies were found.

Owned native fixture directories were checked clean. The task-owned emulator exited successfully; notification permission remained granted as before. No port8789 listener or PID files remained across 102 retained synthetic PostgreSQL clusters. Diagnostic clusters and historical receipts were retained, not deleted.

Task 5's [durable binding/work seal/late runtime publication](SESSION_SETUP_PUBLICATION.md) is DONE bounded, verified at **2026-09-13T17:13:06.803Z**. Redacted startup/confirmed exact abort and integrated interruption acceptance remain tasks 6 and 7. Approved provider/bootstrap/typed configuration, API26 credentials/recovery, public journals/failed-open ownership, physical devices, full Xcode/iOS, product routes/cooking/social/safety/purchases and store/Shipaton gates remain open. The full 54-feature/98-screen FeedMe scope is unchanged. The historical demo and public GitHub snapshot are unchanged; no deployment/publication is implied.
