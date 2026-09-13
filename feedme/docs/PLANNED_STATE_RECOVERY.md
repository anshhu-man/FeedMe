# Planned private-data recovery — M1.05d.5b.2b.2

13 September 2026. This extends the [planned activation component](PLANNED_STATE_ACTIVATION.md) with restricted existing-only inspection and explicit empty-only abort. The subsequent [session setup publication](SESSION_SETUP_PUBLICATION.md) implementation connects the journal, selection, binding and work seal to late runtime access; it is DONE bounded, verified at **2026-09-13T17:13:06.803Z**. It is not provider login, confirmed composite abort or recovery UI. The full FeedMe scope remains 54 features and 98 screens.

## Small tasks

| Task | Status | Acceptance |
| --- | --- | --- |
| M1.05d.5b.2b.2 | DONE — bounded component | Restricted existing-only opener, authenticated exact-plan observations, empty-only durable consumption before exact key deletion, repeat acknowledgement on every retry, schema migration and native injected-VFS sync-error evidence |
| M1.05d.5b.2b.3 | IN_PROGRESS | The first four [composite setup tasks](COMPOSITE_SESSION_SETUP.md) are DONE bounded; [task 5 publication](SESSION_SETUP_PUBLICATION.md) is DONE bounded, verified at **2026-09-13T17:13:06.803Z**. Tasks 6 and 7 remain: confirmed startup/abort and integrated failure acceptance |
| M1.05d.5b.2c / b.3 / d.5c | TODO | Separate refresh recovery, first-factory/process-kill/power-loss/platform evidence and actual provider/recovery UI |

## Production integration contract

The trusted session owner must retain the exact authenticated activation plan and persist explicit abort intent independently before requesting deletion. `AndroidStateDatabase.openActivationRecovery(context, scope, plan)` returns only a `StateActivationRecoveryHandle`, exposing `inspect`, `abort` and `close`. It never returns an ordinary database manager, private-data store, retirement target, credential or session lease. Inspection is an observation, not permission to discard newly written data.

| Observed state | Meaning |
| --- | --- |
| PREPARED | Exact absent/retired predecessor and no planned alias |
| PARTIAL | Exact predecessor with the planned alias already present |
| SELECTED_EMPTY | Exact active planned owner/generation, with zero private rows; missing key material does not become a replacement key |
| SELECTED_NONEMPTY | Exact active planned owner has at least one private row, including tombstones or unknown/corrupt payloads; abort refuses |
| ABORTING | Exact inactive consumed generation and matching abort receipt, with planned alias still present |
| ABORTED | Same consumed owner/receipt, and planned alias absent; inspection alone is not a new durability acknowledgement |

Each operation authenticates the scope/plan and rechecks current ownership, all rows and competing owner/GC/abort-receipt references. Changed or newer incarnations remain fenced. A previous readable-empty observation cannot authorize erasing a later record. No record decryption, new alias, arbitrary inventory deletion or unrelated key GC occurs. Android alias-presence inspection allows explicitly cleaning unusable owned key material without replacing it.

## Durable consumed-plan acknowledgement

Every abort attempt, including an observed ABORTED replay, first performs one real write transaction: select the exact inactive consumed generation `prior + 2` and write/increment the matching abort receipt. The counter must change; exhaustion fails closed. Only a successfully returned COMMIT permits proceeding. A visible consumed row after an exception, cancellation or genuine sync error never substitutes for that acknowledgement.

After acknowledgement, a second write lock rechecks the exact consumed state and absence of records/competing references before deleting only the planned alias. Caller cancellation is checked at entry, after the first commit and immediately before deletion. A later failure does not undo the earlier acknowledged generation fence. Retry repeats the changing receipt transaction; it never recreates the key or advances to another incarnation.

The AndroidX 2.7.1 wrapper exposes the SQLite numeric error only in message text. Production recovery does not parse that text to authorize cleanup: any failed commit stops deletion. Test-only code retains numeric codes, not raw private SQL/provider messages. Injected native VFS sync-error tests are separate from wrapper exceptions injected after successful COMMIT and from physical storage failure or power-loss proof. SQLite defines synchronization and deletion at these VFS interfaces. [File methods](https://www.sqlite.org/c3ref/io_methods.html), [VFS interface](https://www.sqlite.org/c3ref/vfs.html).

## Schema and native ownership

Normal opening explicitly migrates the exact v1 schema to v2 in one transaction, adding `feedme_activation_aborts(owner_tag, generation, key_id, revision)`. Owner/record ciphertext and existing key material are preserved; the bounded metadata reveals no raw identity or key material. A new abort replaces only that owner's historical receipt. Candidate IDs cannot collide with another retained receipt. Existing-only recovery accepts v2 only and never migrates, initializes missing storage or runs ordinary cleanup. Legacy v1 recovery requires the separately supported normal migration path, not a silent recovery reset.

The native recovery opener requires the existing private directory, database, empty lock file and install HMAC key. It rejects missing components, foreign schema, unexpected children, symlinks, hardlinks, unsafe modes and nonempty locks. It authenticates the plan before SQLite opens. It shares the ordinary opener's process-local reservation and lifetime lock, opens without CREATE, and retains ownership until SQLite close acknowledges. Returned-handle close failures are retryable.

Explicit remaining factory/platform limits: recovery is API27+, while ordinary storage still supports API26. Any preexisting rollback journal/WAL/SHM is preserved and rejected by this bounded public opener; the internal native fault fixture may allow SQLite's own journal recovery and does not prove public-factory support. If construction/cancellation itself encounters an unacknowledged SQLite close, ownership stays fail-closed until process exit; no returned handle exists to retry that close. A dedicated retained failed-open owner/recovery protocol is still needed before claiming seamless construction-failure recovery.

## Verification

The historical frozen-source verification below passed at **2026-09-13T14:53:52.071Z**. The [machine-readable receipt](verification/planned-state-recovery/verification.json) retains its original commands, hashes and evidence. [Build status — current verification](BUILD_STATUS.md#current-verification) indexes current source evidence. [Session setup publication](SESSION_SETUP_PUBLICATION.md) now replaces the old runtime setup path and requires changed binding, work-seal and control acknowledgements before access; it is DONE bounded, verified at **2026-09-13T17:13:06.803Z**. Journal decoding remains structural, not a native MAC check or trusted configuration approval; real provider verification must be supplied externally. Selected-data replay alone is still not a fresh durability acknowledgement. Composite tasks 6 and 7 remain open.

| Check | Result and limits |
| --- | --- |
| Shared Kotlin | 1,018 passed: 48 core, 119 contracts, 72 transport, 315 storage, 103 sync, 142 kitchen and 219 session; no failures/errors/skips |
| Server and database | 46 server and 45 real isolated PostgreSQL tests passed; no provider/product-service connection |
| Node | 92 contract/backup/tracking regressions passed |
| Native Android | 122 passed on API35 arm64: 65 storage (57 regular, six separate-process injected-VFS cases, two process-separated persistence stages) plus 57 session (47 regular, ten integration) |
| Libraries and lint | Five freshly built Android AARs and zero-issue lint; both isolated test APKs rebuilt |
| Packaging | All four test-helper ELF ABIs inspected in the storage test APK; helper absent from all five production AARs, session test APK and unchanged historical demo |
| Independent integrity audit | 211 source inputs, 13 artifacts and 110 retained evidence files match their hashes; 1,109 unique source-declared JUnit cases in 64 XML files, 92 TAP identities and 122 native start/success pairs match actual retained output |

The native fault helper belongs only to the test APK. The bundled Unix VFS does not expose `fsync` through its syscall-substitution interface, so the replacement uses a separately registered, non-default, explicitly URI-selected forwarding VFS with the same bundled SQLite engine. Four cases return `SQLITE_IOERR_FSYNC` (1034) before the exact file's sync delegation; two return `SQLITE_IOERR_DIR_FSYNC` (1290) after delegating exact journal unlink without directory sync. The actual native exception code and one injected-failure counter are required. Each failure leaves key-deletion count zero; the tests then require a fresh successful changed-receipt abort, exact final state and cleanup. A sibling sandbox proves the fault is target-bound. Every normal operation delegates, faults are disarmed, owned handles close and the wrapper unregisters. This is injected VFS failure, not actual OS errno, a replacement SQLite build, public-factory hot-journal recovery or physical power-loss proof.

Two earlier attempts passed the 57 regular tests but failed all six cases before the unavailable syscall hook attached. They remain at `verification/client-storage/android-attempts/2026-09-13T14-13-09.741Z/` and `2026-09-13T14-16-45.944Z/`; they are not passing sync evidence. The focused replacement run passed at 14:51:52.791Z; the full run above owns its historical rebuilt APK. Current artifacts belong to the current source-bound receipt indexed in BUILD_STATUS.md. The published GitHub snapshot captured the earlier unfinished checkpoint and remains unchanged; local continuations require a deliberate publication refresh.

Delivered-notification cancellation passed; both credential hardlink-construction attempts were platform-denied (`EACCES`, 13), so those existing-hardlink branches remain unexercised. The initially granted test-notification permission was not granted/revoked by this continuation. Test fixtures were cleaned, the owned emulator exited successfully, no test server listens on port 8789, and none of the 86 retained synthetic PostgreSQL cluster directories has a postmaster PID file. Existing FeedMe demo data were not reset.

| Receipt/artifact | SHA-256 |
| --- | --- |
| Source manifest (211 inputs) | `acd9db05a3bb002fbbd7ac3ac39e6c87892d1f6769f1c500d369c6716ed7146c` |
| Verification receipt | `38027d1b50027b58702b9b4a50631cd857e34a02d65edf2f07687b5f9d89d26f` |
| Storage JVM JAR | `3b5dd50c8a69945cf168e5f07dccc555798cda64676ed2aab21fda15b5001f98` |
| Storage AAR | `1bcca82bf9788b70e21ed1bc1da95b9492a2522a741dcc828a882e9fab565ee7` |
| Storage test APK (6,438,060 bytes) | `ed54ba7d42fc2d9b998f4d331506ef94e8acad84b2289be6bb34e4060984caf6` |

For the current tree, reproduce with JDK17, Android SDK36, the pinned installed NDK28.2.13676358, PostgreSQL15 and a booted emulator. The older runner retains its historical inventory expectations.

```sh
node scripts/verify-startup-recovery-owners.mjs
```

Set `JAVA_HOME`, `ANDROID_HOME`, `FEEDME_POSTGRES_BIN` and `FEEDME_TEST_DEVICE` for the local environment. The command does not build or publish a release app.

Provider/bootstrap, startup recovery factory/close ownership and integrated cross-store interruption acceptance, native app/recovery UI, API26 credentials/recovery, Xcode/iOS, reviewed content/media, social safety, purchases, patched SQLite review, physical devices and publication approval remain separate release gates. Subsequent [already-open confirmed abort](COMPOSITE_SETUP_ABORT.md) is DONE bounded; the historical tests here do not establish its acceptance or startup/cross-store crash recovery. No deployment, account enrollment, spending, upload or publication is authorized by this component.
