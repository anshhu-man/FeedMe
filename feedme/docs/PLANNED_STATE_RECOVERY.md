# Planned private-data recovery — M1.05d.5b.2b.2

13 September 2026. This extends the [planned activation component](PLANNED_STATE_ACTIVATION.md) with restricted existing-only inspection and explicit empty-only abort. It does not connect provider login, the composite credential/data/work setup journal or recovery UI. The full FeedMe scope remains 54 features and 98 screens.

## Small tasks

| Task | Status | Acceptance |
| --- | --- | --- |
| M1.05d.5b.2b.2 | IN_PROGRESS | Restricted existing-only opener, authenticated exact-plan observations, empty-only durable consumption before exact key deletion, repeat acknowledgement on every retry, schema migration and native VFS sync-error evidence |
| M1.05d.5b.2b.3 | TODO | One independently persisted credential/data/work setup intent before effects; empty work-origin planning, exact activation binding and final lease publication only after acknowledged completion |
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

Implementation and verification are in progress. The latest targeted run passed 315 storage JVM tests, including 40 new recovery tests, and built the isolated Android APK. Both native attempts passed all 57 regular storage tests, including 14 new recovery methods, but failed all six sync-error cases before injection could attach. Those attempts are retained at `verification/client-storage/android-attempts/2026-09-13T14-13-09.741Z/` and `2026-09-13T14-16-45.944Z/`; they are not passing sync-error evidence. No prior receipt covers the changed sources.

The native fault helper belongs only to the test APK. Runtime diagnostics established that this bundled Unix VFS does not expose `fsync` through its syscall-substitution interface. The replacement C harness has been authored at the registered VFS file-sync/deletion boundary, using the installed NDK and the same bundled SQLite engine, but is **uncompiled and unverified**. Work was paused for the user-requested public repository snapshot: Kotlin tests still need to register/select the wrapper VFS and the smoke/verifier scripts still need their old syscall labels replaced. The six sync cases are therefore incomplete and are not expected to pass at this checkpoint. The replacement must restrict errors to exact synthetic database/journal operations, delegate normal operations, restore/unregister its test wrapper after owned handles close, and run in a separate instrumentation invocation. This is injected VFS failure, not an observed operating-system `fsync` failure, replacement SQLite build or production fallback. Fresh native outcomes and artifact audits will be recorded before accepting this package.

Provider/bootstrap, composite setup, native app/recovery UI, ordinary control/work-ledger sync-error recovery, API26 credentials/recovery, Xcode/iOS, reviewed content/media, social safety, purchases, patched SQLite review, physical devices and publication approval remain separate release gates. No deployment, account enrollment, spending, upload or publication is authorized by this component.
