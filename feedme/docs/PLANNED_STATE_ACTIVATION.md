# Planned private-data activation — M1.05d.5b.2b.1

13 September 2026. This is a bounded storage-selection component for FeedMe's Android and shared Kotlin foundation. It does not complete session setup, provide a login/record handle, repair arbitrary orphan keys, implement recovery UI or clear release gates. All 54 features and 98 screens remain in scope.

## Small tasks and acceptance

| Task | Status | Done when |
| --- | --- | --- |
| M1.05d.5b.2b.1 | DONE | Authenticated exact predecessor/key planning without writes; exact key/owner selection, no replacement of selected missing keys, no unrelated GC, shared/real-SQLite/native regression evidence |
| M1.05d.5b.2b.2 | DONE | [Planned state recovery](PLANNED_STATE_RECOVERY.md): bounded existing-only inspection/empty-only abort, changed V2 receipt COMMIT before exact key deletion; 40 new JVM, 14 native recovery and six injected-VFS tests pass in the full source-bound run |
| M1.05d.5b.2b.3 | TODO | [Seven-task composite setup package](COMPOSITE_SESSION_SETUP.md): independent control/work acknowledgement acceptance, empty work-origin planning, retained exact plans through binding persistence and acknowledged completion before lease activation |
| M1.05d.5b.2c / b.3 / d.5c | TODO | Separate credential refresh recovery, first-factory/process-kill/power-loss/platform drills, and actual provider/recovery UI integration |

## How the component works

`EncryptedStateDatabase.planActivation(scope)` reads one exact owner on the database's serialized dispatcher. It accepts an absent owner or a fully retired inactive predecessor. Any record belonging to an absent/inactive owner—including tombstones—is corrupt evidence, not an empty setup. An inactive predecessor's old key must already be absent and its GC marker cleared; this method never finishes an earlier retirement automatically.

The optional native `PlannedStateVault` selects a fresh CSPRNG key ID without creating a key or reserving a database row. The database verifies that no active/inactive owner, GC marker, historical activation-abort receipt or native alias already uses it. Same-owner historical receipts also prevent key reuse. It binds the exact prior generation and prior key ID, a derived next generation, the proposed key ID and the opaque owner tag under the existing install/namespace HMAC. Planning returns an opaque `StateActivationPlan`, not credentials or an activation lease.

The trusted session owner must independently persist this plan before invoking `commitPlannedActivation(scope, plan)`. The commit authenticates the native MAC and exact scope before effects, then checks the same absent/retired predecessor under a write transaction. It creates only the planned key and selects only the planned owner generation. An already existing usable planned key may be reused after the original operation created it but failed to select the owner. It is never replaced or automatically deleted after an error or cancellation.

Selected replay accepts only the exact active generation/key pair, no competing owner reference and no GC marker for that key. It requires the existing native key; missing selected key material never causes regeneration. Existing records are preserved without decryption. Its `Unit` result acknowledges selection only: no `PrivateStateStore`, general retirement target, identity grant or record-integrity claim is returned. Verified resume and actual lease publication remain distinct operations.

## Durable control and recovery integration gates

The current runtime still uses its existing unplanned private-data activation path. This new primitive must not be presented as an integrated setup guarantee until b.2b.3 persists and enforces one exact pending plan before any key/owner writes. A random candidate ID is not a reservation. Two low-level plans can share one predecessor; abandoning a partially written first plan and committing a second can leave the first key orphaned. The component refuses changed owner state and preserves that key rather than guessing authority to delete it. The composite journal must prevent that replacement, and restricted recovery must resolve the original intent.

A selected replay's transaction with no row mutation is not a fresh filesystem synchronization acknowledgement. The fault tests distinguish an exception injected after a successful real COMMIT from a failure before COMMIT: the former loses the application's receipt, not the already completed SQLite operation. With DELETE + synchronous=EXTRA, a successful COMMIT already includes the configured database/journal synchronization and journal-directory synchronization. A further synchronization is not automatically required solely because a test wrapper subsequently throws. This relies on SQLite's documented storage assumptions, not a physical-device power-loss test. [SQLite synchronous settings](https://www.sqlite.org/pragma.html#pragma_synchronous).

Genuine `SQLITE_IOERR_FSYNC` or `SQLITE_IOERR_DIR_FSYNC` means a persistence operation failed; an I/O error can undo a statement or the entire transaction. Before generic unknown-outcome handling authorizes irreversible cleanup or clears the composite journal, verify the actual Android driver's exception provenance, connection/rollback state and recovery behavior for journal, database and directory synchronization failures. The same gate applies to the independent control/work ledgers. [SQLite I/O result codes](https://www.sqlite.org/rescode.html#ioerr_fsync), [transaction error handling](https://www.sqlite.org/lang_transaction.html#response_to_errors_within_a_transaction).

Readback can use SQLite or operating-system caches. Therefore, as an engineering inference, a matching row alone is not evidence that a genuinely failed synchronization has been repaired. The historical selection tests below do not establish that this driver exposes such a matching-but-undurable state; they also do not rule it out. The [recovery continuation](PLANNED_STATE_RECOVERY.md) verifies changed-row acknowledgement with six separately invoked injected-VFS tests in the actual bundled SQLite engine. The forwarding VFS injects return codes before delegated xSync, or after exact journal unlink without directory sync; it does not inject an OS errno or prove physical power-loss behavior. Those direct owned-connection fixtures do not prove that the strict public recovery factory accepts journals. Independent control/work-ledger acknowledgement remains a gate in the [composite setup design](COMPOSITE_SESSION_SETUP.md). No additional-descriptor redesign is selected by this component. [SQLite cache retention](https://www.sqlite.org/atomiccommit.html#cache_retained_between_transactions).

Normal database opening, `activate()` and `resume()` retain their initialization/cleanup roles; normal opening now atomically upgrades only the exact V1 schema to V2. Planning and planned commit themselves never run `cleanupKeys()`. The new API27+ existing-only recovery opener requires V2 and existing private files, lock and index authentication. It rejects V1, existing rollback journals, WAL/SHM and unknown children without initialization, migration or GC. Opening a hot rollback journal could itself modify storage, so that case remains blocked rather than described as byte-read-only inspection.

Recovery exposes only plan-bound `inspect`, `abort` and `close`, never private records, an activation/retirement handle or a lease. Every abort—including an already ABORTED retry—changes `feedme_activation_aborts.revision` and commits the exact consumed owner/generation/key before deletion. A first-transaction error or cancellation never proceeds to deletion; a second locked check rejects new rows or references before deleting only the planned key. Matching or foreign historical receipt references cannot be reused as new candidates. Returned-handle close is retryable and retains native ownership until SQLite close succeeds. If construction fails and its cleanup close also fails, no retry handle is returned; ownership remains fenced until process restart. Runtime data-plan wiring, independent composite setup journaling and recovery UI are still absent.

## Encoding and native keys

The canonical 170-byte plan contains version, explicit absent/retired discriminator, 64-lowercase-hex owner tag, big-endian prior generation, exact prior key or canonical zero padding, 32-lowercase-hex proposed key ID and a 32-byte MAC. Two generation increments are reserved: selected and later consumed. The MAC uses the distinct `feedme.activation-plan.v1` domain plus NUL and all 138 unsigned bytes. The plan contains no raw owner ID, recipe record, token or key material.

Construction bounds and copies bytes; structural decoding is not MAC authentication. All representations and errors are redacted. Android's exact key creation rejects every existing alias, including invalidated/unusable entries. It retains AES-256-GCM, provider-generated nonces and nonexportable Android Keystore material. No new dependency, software production vault or permanent app namespace is introduced.

## Verification

Current source-bound verification, exact test totals and the latest receipt are indexed in [Build status — current verification](BUILD_STATUS.md#current-verification). The [work-origin planning component](WORK_ORIGIN_PLANNING.md) adds lease-free native-owner-bound preparation and a setup-selected state; it does not complete composite setup, private binding, explicit setup abort or provider/UI integration. Earlier component counts and hashes below are historical evidence, not current build outputs.

Historical completed snapshot: [planned state recovery](PLANNED_STATE_RECOVERY.md), **DONE for bounded M1.05d.5b.2b.2**, finished **2026-09-13T14:53:52.071Z**. The [historical receipt](verification/planned-state-recovery/verification.json) passes **1,109 Kotlin/server/isolated-PostgreSQL tests, 92 Node checks and 122 native checks**, with zero failures/errors/skips and five clean Android library lint reports. Shared tests total 1,018 (storage 315/session 219); server 46 and PostgreSQL 45 are additional. Native tests comprise 65 storage (57 regular, six separately invoked injected-VFS cases and two separate-process stages) and 57 session. VFS error injection is not an OS errno, physical power-loss or public-factory journal-recovery test. Runtime data-plan wiring, recovery UI and the [seven-task composite setup package](COMPOSITE_SESSION_SETUP.md) remain TODO; no whole milestone or release is accepted.

The independent read-only audit of that historical snapshot rehashed all 211 inputs, 13 artifacts and 110 retained evidence files without mismatch. All 1,109 unique JUnit methods in 64 XML files, 92 TAP names and 122 native start/success pairs matched the source declarations at that time. All 24 native evidence/metadata copy pairs agreed; its latest, immutable and last-attempt receipts were byte-identical. Five lint reports contained no issues. The four-ABI test injector was present only in the storage test APK, absent from production AARs, the session test APK and unchanged demo. Actual APK inspection confirmed storage min26/target26 and session min26/target36. Delivered-notification cancellation passed; both hardlink constructions were platform-denied, leaving their existing-link guard branches unexercised.

Historical source-manifest SHA-256: `acd9db05a3bb002fbbd7ac3ac39e6c87892d1f6769f1c500d369c6716ed7146c`; historical receipt SHA-256: `38027d1b50027b58702b9b4a50631cd857e34a02d65edf2f07687b5f9d89d26f`. The immutable attempt is `verification/planned-state-recovery/attempts/2026-09-13T14-52-30.343Z/`.

The historical frozen-source selection run passed at **2026-09-13T13:35:54.272Z**. Its [machine-readable receipt](verification/planned-state-activation/verification.json) retains the commands, environments, hashes and attempt evidence. All following counts/hashes describe that earlier snapshot; the preceding planned-credential-create receipt is also historical.

| Check | Result and limits |
| --- | --- |
| Kotlin/server/database | 1,069 passed, no failures/errors/skips: 978 shared (48 core, 119 contracts, 72 transport, 275 storage, 103 sync, 142 kitchen, 219 session), 46 server and 45 real PostgreSQL tests |
| New shared/real-SQLite cases | 16 canonical-codec tests and 26 planned-activation JVM tests; strict formats/MAC/scope, no-write planning, exact key replay, absent/retired predecessors, records/tombstones, conflicting references, cancellation and before/after-COMMIT faults |
| Node regressions | 92 passed; tracking/contract/backup checks, not native feature acceptance |
| Android isolated tests | 102 passed on API35 arm64: 45 storage (43 ordinary tests, including 14 new planned-activation cases, plus 2 separate-process stages) and 57 session (47 ordinary plus 10 integration tests) |
| Libraries/lint | Five Android AAR builds and lint reports passed with zero issues; storage and session test APKs freshly rebuilt |
| Independent audit | All 204 source inputs, 13 artifacts and 96 retained evidence files match their hashes; 63 JUnit XML files contain exactly 1,069 unique source-declared tests, with 92 TAP pairs and 102 native identities independently checked |

The new Android cases use real SQLite and Keystore. Fault injection is confined to internal test connection/vault wrappers; the direct fault fixture opens a previously initialized database and does not prove public-factory first-open locking/recovery. Public factory cases separately exercise ordinary creation/reopen. Existing process-separated stages close their writer before restarting; neither those stages nor injected COMMIT failures claim hard-kill or power-loss coverage. The session integration tests retain synthetic identity verification and programmatic confirmation, not login or app recovery UI. Session tests target SDK36; storage tests target26. Credentials remain API27+ only.

Exact delivered-notification cancellation passed. Both credential hardlink-construction attempts were denied by the platform (`EACCES`, 13), so the existing-hardlink branch remains unexercised. The initially granted isolated test-notification permission was preserved. The owned emulator exited successfully; the local test listener stopped and none of the 82 retained synthetic PostgreSQL test directories has a live postmaster PID file. Existing FeedMe demo data were not reset; synthetic test fixtures and their exact key namespaces were cleaned by the tests. The demo APK was not rebuilt or changed.

| Receipt/artifact | SHA-256 |
| --- | --- |
| Source manifest (204 inputs) | `61818301682fa20a88f7a05494c237f57171d783a3968be25b4e9ddb83822d8b` |
| Verification receipt | `002334f350e72827ba06a1f8ccd1dc13c9d58458743d9ee8b2a055214dca512a` |
| Storage JVM JAR | `4af38bc62756b7881b40bbc529aae370a15eeffcc868f053b1a3f0eda27d6254` |
| Storage AAR | `dfee23287df8cb76b3115b5d5e1dd4256b5d6728eb7029ca7ea7be1a6bd9cb64` |
| Storage test APK | `49ddbde04b177ed785c51de52fa703145516fa42cddccaff71136a470bc14d47` |

For the current bounded recovery run, use the reproduction command in [planned state recovery](PLANNED_STATE_RECOVERY.md). The following command reproduces the historical selection package with JDK17, the installed Android SDK, PostgreSQL 15 binaries and a booted API35 emulator:

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ANDROID_HOME=/Users/LOCAL_USER/Library/Android/sdk FEEDME_POSTGRES_BIN=/opt/homebrew/opt/postgresql@15/bin FEEDME_TEST_DEVICE=emulator-5554 node scripts/verify-planned-state-activation.mjs
```

Real providers/bootstrap, composed setup, API26 credentials, full Xcode/iOS, reviewed recipes and media, social safety, actual purchases, native app UI, patched SQLite review, physical devices and publication approval remain separate gates. No service was deployed, account created, money spent or artifact uploaded by this package.
