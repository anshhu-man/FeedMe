# Android process-separated startup recovery

14 September 2026. Composite task 7 now has focused **Android emulator process-interruption acceptance**: **seven passed recovery tests plus seven separately witnessed interrupted starts**. This is not fourteen passed tests, and it is not physical-power-loss acceptance or production sign-in/startup wiring.

The [focused immutable receipt](verification/startup-process/attempts/2026-09-13T23-00-55.783Z/report.json) passed on API 35, `arm64-v8a`, finishing at `2026-09-13T23:01:20.426Z`. It records zero recovery failures/errors/skips and all seven witnessed interruptions. The tested session instrumentation APK was 9,596,195 bytes, SHA-256 `aa0832b7d21038905ac5a036658424cb3c726abced0774ce2ca1fc29b04c8f50`. This focused receipt binds that APK and retained orchestration evidence; it is not by itself a new full-repository regression receipt.

This extends [all-owning startup recovery](OWNED_STARTUP_RECOVERY.md). That earlier package's one/two-close tests truthfully closed and reopened native owners in the same process. The tests here deliberately leave ownership retained, terminate the test process, and recover in a distinct process. The older five component-level process stages remain separate evidence and are not relabeled as these seven composite interruptions.

## Seven exact interruption points

Subsequent acceptance: the [full third-batch run](PARALLEL_UI_HTTP_PROCESS.md) passed at2026-09-13T23:30:01.566Z with these seven recovery successes and seven interrupted starts kept separate. Independent audit verified all14 distinct PIDs, exact witnesses, retained transcripts and cleanup. The focused receipt above remains historical; no broader crash, UI, provider or release claim follows.

Every pair starts with a fresh, real, sealed composite setup: authenticated native credential/data/work plans, the sole schema-2 private binding, sealed empty work origin, and original independent encrypted `PendingSetup`. Synthetic fixture credentials and a test configuration are explicit inputs; no provider or scheduler is called and no lease is published.

| Scenario | Witnessed point before forced termination | Durable CONTROL | Acknowledged subordinate closes | Fresh-process result |
| --- | --- | --- | --- | --- |
| `opening` | CONTROL, credentials and data are open; immediately before work owner open, before public READY | Unconfirmed original PendingSetup | 0 | Exact original evidence opens; a new explicit confirmation is required |
| `ready` | Public owner is READY and has been inspected, before confirmation | Unconfirmed original PendingSetup | 0 | Inspection/prepare do not confer consent; unconfirmed retry is rejected |
| `work-aborted` | Real work abort acknowledged; data and credentials remain selected | Original PendingSetup, abort requested | 0 | Explicit retry authenticates and replays the original plans |
| `data-aborted` | Real work and data aborts acknowledged; credentials remain selected | Original PendingSetup, abort requested | 0 | Explicit retry completes exact remaining cleanup |
| `work-closed` | All three aborts acknowledged; work close acknowledged; before data close | Original PendingSetup, abort requested | 1 | Fresh owners authenticate the original aborted targets and retry |
| `data-closed` | All three aborts acknowledged; work and data closes acknowledged; before credential close | Original PendingSetup, abort requested | 2 | Fresh owners authenticate the original aborted targets and retry |
| `complete` | Public confirmation returned Complete after all subordinate closes; CONTROL owner remains held | Original operation's Complete | 3 | A fresh public owner rejects Complete as abort authority and leaves native metadata unchanged |

The gate pauses are at acknowledged operation boundaries, not deliberately inside SQLite transactions. Partial-abort gates pause after the real subordinate returned success but before the coordinator can consume that result. Close gates pause before the next real close, after the outer owner has acknowledged the preceding close. No fake abort, close, plan authentication or resource observation supplies the result.

## Test and host responsibilities

The test class is [AndroidSessionSetupProcessInterruptionTest](../shared/session/src/androidInstrumentedTest/kotlin/com/feedme/session/AndroidSessionSetupProcessInterruptionTest.kt), with its dedicated [process sandbox](../shared/session/src/androidInstrumentedTest/kotlin/com/feedme/session/AndroidSessionSetupProcessSandbox.kt). All fresh recoveries use public `SessionApplicationComposition` and `AndroidSessionSetupRecovery.createOwner`. The READY and Complete interruption cases also use that public adapter. Other interruption cases use the existing internal composition-factory seam only to pause wrappers that delegate to real public native owners; no production test hook was added.

The host runner is [android-startup-process-smoke.mjs](../scripts/android-startup-process-smoke.mjs), using the pure strict [startup-process evidence parser](../scripts/startup-process-evidence.mjs) and its [regression tests](../scripts/startup-process-evidence.test.mjs). It requires an explicit local emulator selection and Android SDK path, validates the isolated session-test APK identity, and purpose-selects each method. It must not run this class as an ordinary all-tests suite.

For each pair the runner:

1. Stops only any lingering `com.feedme.session.test` process and verifies absence before the stage; it does not clear app data.
2. Starts the exact interruption selector with a fresh lower-case UUID `startupRunId`, its fixed `startupScenario`, and `startupAction=interrupt`.
3. Waits for the exact canonical checkpoint and ownership marker, matches their run/scenario/PID to the one live test-package PID, and requires exactly the matching AndroidJUnitRunner start event with no finish or timeout failure.
4. Retains the pre-stop transcript and checkpoint, invokes `am force-stop com.feedme.session.test`, retains the command result and interrupted transcript, and verifies that the process PID disappeared.
5. Invokes only the matching recovery selector with the same run/scenario and `startupAction=recover`; requires an exact start/success pair, one `OK (1 test)`, successful instrumentation completion, a different PID, and exact final result fields.
6. Verifies that successful recovery removed that run's owned fixture and retains every stage's logs in the attempt directory.

An interrupted instrumentation shell may itself return exit code zero. That is never accepted as a passed test: the interruption parser requires only the exact start event and rejects a success event, `OK`, assertion failure or timeout. The report explicitly marks each interruption `passedTest=false`. Recovery is a distinct invocation with its own verified success identity.

The fixture holds for at most 60 seconds after its checkpoint. Failure to interrupt in time fails the method and preserves the fixture. The host has a shorter checkpoint deadline. A failed stage is not skipped or converted into a recovery success.

## Exact witness, authority and cleanup

The private witness root is `no_backup/retirement-integration-process-<runId>/`. Its `ownership.json` contains only version, run ID, scenario and creator PID. Its canonical, bounded `checkpoint.json` contains only version, run ID, scenario, PID, the fixed control-state label and acknowledged-close count. Files are forced, renamed and the containing directories synchronized before the gate waits. Witnesses contain no token, scope, configuration digest, plan bytes, key alias or private record. A witness is host/test orchestration evidence, **not production erasure consent**.

Initial fixture creation refuses existing fixed native namespaces and any prior process fixture. Only successful creation after that preflight establishes test ownership. The application reservation is acquired before any production factory I/O. Recovery opens the exact existing UUID root after checking the private ownership marker and distinct prior PID; it does not call the ordinary initialization factories used during initial fixture setup.

The original credential/data/work plans remain inside independent encrypted CONTROL. Public retained owners authenticate them through the existing native stores. Every pending recovery verifies the original operation ID and abort-request flag, rejects a changed trusted configuration without native mutation, and exercises permanent root invalidation and a stale reservation. Snapshots and alias inventories must remain unchanged across those denials and advisory operations. There is no credential-token read, provider/bootstrap request, new identity allocation, ordinary resume, garbage collection, work install/cancel or lease publication during recovery.

Unconfirmed PendingSetup is not auto-confirmed after process death: retry is rejected until this test explicitly prepares and confirms a fresh proposal. Confirmed PendingSetup permits only an explicit retry of its original authenticated intent. The memory-only ALL-ABORTED close checkpoint does not survive the kill; a fresh owner instead authenticates the original durable PendingSetup and the exact component states again. Final Complete follows fresh component acknowledgements, subordinate close acknowledgements and a fresh independent CONTROL acknowledgement.

Persisted Complete is deliberately different: it cannot reconstruct either the original pending plans or user consent. The final pair checks that fresh open, prepare and retry do not create authority or mutate the completed state. Its successful test result means that rejection was correct, not that a new abort occurred.

Successful test cleanup runs only after every exact owner, CONTROL and the composition reservation acknowledge close. It verifies UID/private modes, no symlinks, single-link regular files and exact allowed inventory. It additionally requires no remaining private-data or credential owner key and exactly the authenticated CONTROL/WORK owners plus the five expected install aliases. Unknown files, aliases, newer owners or failed closes preserve the fixture; there is no generic namespace reset, blanket orphan sweep or production-data deletion. Lock files are checked by metadata without opening a second descriptor for their lifetime-lock inode. The host independently checks removal of the exact run directory; it does not remove unknown fixtures to make the next pair pass.

## Boundaries still open

These are actual forced deaths of the isolated instrumentation process on an Android emulator. They do **not** establish physical power-loss durability, arbitrary instruction-level crash safety, a kill during a SQLite transaction, corrupt/hot rollback-journal recovery, general orphan/refresh repair or successful retry of an ambiguous platform close. Existing-only factories still refuse unsupported or unknown native inventory and preserve their missing-key/poison/close-failure gates.

The fixtures do not prove hostile same-UID concurrency resistance beyond the trusted single application-composition contract. Permission and hard-link checks in their accepted ordinary paths are not new evidence that a platform-denied hard-link attack branch was exercised. These tests add no VFS sync-injection labels, physical-device or additional-ABI execution, iOS/Xcode acceptance, real provider authentication, confirmation UI, remote revocation, deployment or app-store release claim. V1 feature deferrals are unchanged.
