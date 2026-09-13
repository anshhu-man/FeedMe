# SQLite storage engine audit

Inspected 2026-09-13. This records dependency and API evidence for the durable owner-scoped store. It is an implementation review input, not a claim that Android or iOS runtime checks have passed. No builds were run for this audit.

## Decision and engine limitation

`androidx.sqlite:sqlite-bundled:2.7.1` is the current stable AndroidX release, published September 9, 2026. Non-web KMP projects retain synchronous SQLite APIs in common code; the new web APIs do not require this Android/JVM/iOS project to use suspending driver interfaces. The 2.7.1 release note concerns transaction cancellation on web and suspending drivers. [AndroidX releases](https://developer.android.com/jetpack/androidx/releases/sqlite), [KMP setup](https://developer.android.com/kotlin/multiplatform/sqlite).

The AndroidX wrapper version is distinct from the embedded SQLite engine version. The exact 2.7.1 Android arm64, JVM macOS arm64, and iOS arm64 binary artifacts inspected here identify SQLite **3.50.1**, compiled with `THREADSAFE=2`, and this source ID:

```text
2025-06-06 14:52:32 b77dc5e0f596d2140d9ac682b2893ff65d3a4140aa86067a3efebe29dc914c95
```

This matches SQLite's [3.50.1 release](https://www.sqlite.org/releaselog/3_50_1.html). No evidence of a later WAL fix backport was found in the inspected artifacts or release notes. Verify `SELECT sqlite_version(), sqlite_source_id()` in the actual runtime as part of release evidence.

SQLite documents a rare WAL-reset corruption race affecting versions through 3.51.2; fixes are in 3.51.3 and later, with backports including 3.50.7 and 3.44.6. It requires concurrent connections to the same WAL file, potentially across processes, with overlapping write/checkpoint activity. A mutex local to one store instance and `BEGIN IMMEDIATE` do not establish that this precondition is absent. [SQLite WAL-reset advisory](https://www.sqlite.org/wal.html#walreset).

The bounded foundation uses **`journal_mode=DELETE` with `synchronous=EXTRA`**, fixed application SQL, no FTS tables, no arbitrary database imports, and an exclusive application-owned lifetime lock in the platform factories. DELETE removes the WAL-reset advisory's WAL-mode precondition; this conclusion follows from the advisory's explicit scope. Initialization rejects an existing WAL database before changing its journal mode. It does not attempt conversion or delete sidecars. The returned DELETE mode is verified before exposing a new or accepted database.

The lifetime lock is acquired before opening SQLite and released only after statements and connection are closed. A second same-file manager must fail to open, including in another process. A process-local registry alone is insufficient. Use a dedicated stable sibling lock file, with canonical database-path identity and no symlink/hard-link aliases permitted by the factory. Do not unlink the lock file after releasing it: another process can already hold its old inode while a new inode permits a conflicting lock. Locking is advisory and only coordinates cooperating application code; it is not a defense against arbitrary code already running with database-file access. These are application design requirements, separate from transaction-level owner fencing.

This journal choice does not patch SQLite 3.50.1's other defects. Keep the embedded-engine version/security gate visible, and do not introduce WAL, database imports, untrusted SQL, virtual tables, or extra connections without a new engine audit.

## Published target compatibility

Declare `implementation("androidx.sqlite:sqlite-bundled:2.7.1")` in `commonMain`, with `google()` in dependency repositories. It exposes `androidx.sqlite:sqlite:2.7.1` transitively. An explicit matching `sqlite` dependency is optional when using its public interfaces directly. Keep the AndroidX SQLite atomic group aligned at 2.7.1. A JVM-only storage module used by Android does not exercise the published Android AAR; an Android KMP target should be declared for actual Android variant verification.

| Project setting | Exact artifact evidence | Assessment |
| --- | --- | --- |
| Kotlin 2.3.21 | Gradle metadata requires stdlib 2.1.20; inspected iOS cinterop KLIB declares compiler 2.3.20, ABI/metadata 2.3.0 | No indicated compiler upgrade; native link remains required |
| Android minSdk 26, compileSdk 36, AGP 8.13.2 | Published AAR manifest minSdk 23; AAR metadata minCompileSdk 34 and minAGP 8.1.1 | Project settings exceed the published floors |
| JVM toolchain 17 | Inspected driver class major version 55, Java 11 bytecode | JVM 17 supports this bytecode |
| iOS deployment 16 | `ios_arm64` and `ios_simulator_arm64` variants published; inspected device static archive `minos 14.0`, SDK 18.5 | Device binary floor fits iOS 16; native linking/runtime still unverified |

The root module resolves target artifacts `sqlite-bundled-android`, `sqlite-bundled-jvm`, `sqlite-bundled-iosarm64`, and `sqlite-bundled-iossimulatorarm64`. The iOS artifacts also carry a cinterop static-library dependency and use `sqlite-framework` implementation classes under the bundled alias; this does not mean the bundled engine was replaced with the operating system's SQLite.

Primary metadata: [root](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled/2.7.1/sqlite-bundled-2.7.1.module), [Android](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-android/2.7.1/sqlite-bundled-android-2.7.1.module), [JVM](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-jvm/2.7.1/sqlite-bundled-jvm-2.7.1.module), [iOS device](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-iosarm64/2.7.1/sqlite-bundled-iosarm64-2.7.1.module), [iOS simulator](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-iossimulatorarm64/2.7.1/sqlite-bundled-iossimulatorarm64-2.7.1.module).

Inspection inputs were the published [Android AAR](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-android/2.7.1/sqlite-bundled-android-2.7.1.aar), [JVM JAR](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-jvm/2.7.1/sqlite-bundled-jvm-2.7.1.jar), and [iOS cinterop KLIB](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-iosarm64/2.7.1/sqlite-bundled-iosarm64-2.7.1-cinterop-androidXBundledSqlite.klib). Archive manifests, class headers, Mach-O load commands, and embedded version strings were read without executing the native libraries.

## Exact synchronous API and resource ownership

The published 2.7.1 source archives contain these relevant interfaces in `androidx.sqlite`; `SQLiteConnection` and `SQLiteStatement` extend `AutoCloseable`:

```kotlin
// SQLiteDriver
fun open(fileName: String): SQLiteConnection
val hasConnectionPool: Boolean

// SQLiteConnection
fun prepare(sql: String): SQLiteStatement
fun inTransaction(): Boolean
fun close()

// SQLiteStatement: parameter indexes start at 1; column indexes start at 0.
fun bindBlob(index: Int, value: ByteArray)
fun bindLong(index: Int, value: Long)
fun bindText(index: Int, value: String)
fun bindNull(index: Int)
fun getBlob(index: Int): ByteArray
fun getLong(index: Int): Long
fun getText(index: Int): String
fun isNull(index: Int): Boolean
fun getColumnType(index: Int): Int
fun step(): Boolean
fun reset()
fun clearBindings()
fun close()
```

`step()` returns true for an available row and false at completion. A false result for an INSERT/UPDATE/DELETE is normal completion, not failure. These methods throw on failure. Resetting a statement retains bindings; clear or rebind every parameter before reuse. Check `isNull` before nullable reads, and check actual column type/range where silent SQLite conversion would hide invalid persisted data. Prefer `Long` reads before validating narrowing conversions.

`BundledSQLiteDriver` is in `androidx.sqlite.driver.bundled`. Its `open(fileName: String, flags: Int): SQLiteConnection` overload accepts constants in the same package, including `SQLITE_OPEN_READWRITE`, `SQLITE_OPEN_CREATE`, `SQLITE_OPEN_FULLMUTEX`, `SQLITE_OPEN_NOFOLLOW`, and `SQLITE_OPEN_EXRESCODE`. The default `open(fileName)` enables read/write and create. Do not enable `SQLITE_OPEN_URI` for ordinary application-owned paths. Register no dynamic extensions.

Connections from the default bundled driver must not be used concurrently. Serialize the complete operation: open, initialization, prepare/bind/step/read/close, transaction completion, and connection close. `FULLMUTEX` can serialize individual SQLite calls but cannot prevent two coroutines from interleaving transaction statements; retain an application-level transaction lock. Avoid suspensions in the synchronous transaction body, and run blocking disk work off the UI thread. [Bundled driver threading contract](https://developer.android.com/reference/kotlin/androidx/sqlite/driver/bundled/BundledSQLiteDriver), [SQLite threading modes](https://www.sqlite.org/threadsafe.html).

Close statements before completing transactions, including on error. Do not expose raw connections, statements, SQL text execution, or transaction callbacks that can escape ownership. Connection and statement `close()` are documented as idempotent. The common `inTransaction()` default throws `NotImplementedError`, but both actual bundled implementations inspected here provide it; the native implementation tests `sqlite3_get_autocommit(db) == 0`.

Exact sources: [SQLite interfaces](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite/2.7.1/sqlite-2.7.1-multiplatform-sources.jar), [bundled implementations](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled/2.7.1/sqlite-bundled-2.7.1-multiplatform-sources.jar), [native implementation](https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-framework/2.7.1/sqlite-framework-2.7.1-multiplatform-sources.jar). Use these pinned sources when behavior differs from moving online API pages.

## Initialization and transaction policy

Before any transaction, set and read back the fixed policy:

```sql
PRAGMA busy_timeout = 5000;
PRAGMA journal_mode = DELETE;
PRAGMA synchronous = EXTRA;
PRAGMA fullfsync = ON;
PRAGMA foreign_keys = ON;
PRAGMA trusted_schema = OFF;
PRAGMA secure_delete = ON;
PRAGMA temp_store = MEMORY;
```

Require returned journal mode `delete`, synchronous `3`, foreign keys `1`, trusted schema `0`, secure delete `1`, temporary store `2`, and timeout `5000`. `fullfsync` requests the additional platform sync behavior where supported. Foreign keys cannot be enabled inside a transaction. Some pragmas take effect while preparing, so execute only application-owned statements. Inspect journal-mode results rather than assuming a requested change succeeded. Busy timeout is SQLite lock waiting, not an overall coroutine deadline; cancellation does not automatically interrupt a blocking native call. [Pragma contract](https://www.sqlite.org/pragma.html), [busy timeout](https://www.sqlite.org/c3ref/busy_timeout.html).

EXTRA adds a directory sync after journal deletion and is SQLite's documented stronger rollback-journal durability setting. FULL was an earlier proposal and is not the implemented policy: with DELETE, it can lose the last committed transaction after power loss on some filesystems. EXTRA follows the documented ACID configuration, but local restart tests are not a physical power-loss test or proof that every device honors sync requests. [Synchronous setting semantics](https://www.sqlite.org/pragma.html#pragma_synchronous).

Use `BEGIN IMMEDIATE` to acquire the write transaction before owner/epoch checks, reads, and writes. Close every operation statement before `COMMIT`. Do not nest `BEGIN`; use a single transaction helper with a non-suspending body. Keep migration DDL and its migration history updates in the same transaction. A store's transaction must never invoke the network.

The owner authorization decision must be made from persisted state inside the transaction that performs the mutation. A cached owner/lease check followed by a later transaction permits a second connection to invalidate the lease in between. Locking serializes writers; the persisted generation predicate establishes who is authorized when that writer obtains the lock. This is application design guidance rather than a SQLite feature.

## Failures and uncertain commit outcomes

`SQLiteException` has no common structured `errorCode` property in 2.7.1. The inspected helper constructs a message beginning with `Error code: N`, optionally followed by engine text. The implementation does not parse exception messages: engine/provider failures become `STORAGE_FAILURE` or `OUTCOME_UNKNOWN` according to transaction state. If finer diagnostics are added later, recognize only the anchored numeric prefix from a caught SQLite exception and discard other text. Do not expose SQL, paths, payload values, or raw exception messages. Unknown formats must remain unknown; never guess from substrings such as “locked”.

Both current bundled paths enable extended result codes. Classify a numeric result by its low byte (`code and 0xff`) while retaining the full number only in safe internal diagnostics. Relevant primary codes are BUSY 5, LOCKED 6, NOMEM 7, READONLY 8, INTERRUPT 9, IOERR 10, CORRUPT 11, FULL 13, CANTOPEN 14, CONSTRAINT 19, MISUSE 21, and NOTADB 26. FULL can concern temporary storage too. BUSY ordinarily means another connection; LOCKED is a different conflict category. [SQLite result codes](https://www.sqlite.org/rescode.html).

SQLite may automatically roll back the statement or the entire transaction after FULL, IOERR, INTERRUPT, or NOMEM. After a failure, inspect transaction state while retaining exclusive application ownership; if active, attempt `ROLLBACK`. Preserve the original failure if cleanup also fails. An inability to establish a clean state requires closing/poisoning the connection. `inTransaction() == false` establishes autocommit state, not whether a prior COMMIT succeeded. [Transaction errors](https://www.sqlite.org/lang_transaction.html), [autocommit state](https://www.sqlite.org/c3ref/get_autocommit.html).

Recommended outcome handling for the store:

| Failure stage | Result and next action |
| --- | --- |
| Rejected before `BEGIN` or failed `BEGIN` | No transaction body ran; return the appropriate failure, with no implicit write retry |
| Body failed before any commit attempt | Roll back if active; return failure only after cleanup, or poison the connection if cleanup cannot establish its state |
| `COMMIT` threw and the transaction remained active, then rollback was confirmed | Return `STORAGE_FAILURE`; no write replay |
| `COMMIT` threw and transaction state was already inactive, or cleanup/state cannot be established after a write | Return `OUTCOME_UNKNOWN`; preserve operation identity and do not replay the write automatically |
| `COMMIT` returned but delivery to caller was cancelled/failed | Do not report “definitely rolled back”; resolve using persisted operation identity or a later read |
| Commit succeeded but later cleanup failed | Keep the committed state; surface the cleanup/storage problem separately rather than repeating the command |

The transaction-state classification is an application policy. A specifically recognized COMMIT/BUSY has a documented active-transaction behavior, but that does not justify a generic COMMIT retry loop or retrying the entire body. JVM tests inject both “commit persisted, response failed” and “commit did not execute” cases; neither replays the body. Coroutine cancellation is rethrown, so cancellation while synchronous work executes can hide a committed result from the caller; the caller must reconcile CAS state.

## Schema checks and owner erasure

The implemented version-1 initializer atomically creates three fixed tables, sets `application_id=0x464d5331` and `user_version=1`, and verifies exact `sqlite_schema` table definitions plus the absence of additional triggers, views, and explicit indexes. It rejects any other version/application ID or incompatible shape. There is no migration-history checksum ledger yet.

For future schema versions, treat migration history and observed database structure as separate checks. Define each migration as an immutable ordered list of application-owned statements. Hash an unambiguous byte encoding, such as UTF-8 with a domain/version prefix and byte-length prefixes per statement, using SHA-256. Persist version plus checksum only within the successful migration transaction. Validate the entire expected sequence; fail closed for missing, altered, duplicate, or unknown-future entries. `PRAGMA user_version` alone does not prove a matching schema.

Also validate `sqlite_schema` and structural pragmas for expected tables, columns, indexes, foreign keys, and absence of unexpected triggers/views. `CREATE TABLE IF NOT EXISTS` by itself accepts a table with the wrong shape. Do not compute the migration checksum from raw `sqlite_schema.sql`: SQLite normalizes portions of CREATE statements and changes them during ALTER TABLE. Exact-text schema comparison is possible for a pinned fresh-schema representation, but the expected normalized text must be tested against SQLite and migration paths. Avoid broad whitespace normalization inside SQL string literals. [SQLite schema table](https://www.sqlite.org/schematab.html).

Migration checksums detect accidental or unsupported drift; they are not proof against an attacker who can edit both database and checksum. Keep schema definitions and expected hashes in trusted application code. A database that fails validation should not be silently deleted or recreated.

Persist the generation used by owner leases and include it in every mutation predicate. Erasing an owner must delete its data and advance its durable generation/tombstone in the same transaction. Preserve that fence so an older store instance cannot recreate data using its cached lease; reopening must not reset generation to a reusable value. Bind owner identifiers and every data value as parameters. Require affected-row counts for conditional updates instead of treating a no-op as successful authorization.

The implementation marks the generation locally retired before attempting erasure, then persists owner retirement, record deletion, and a pending key-cleanup row in one transaction. Pending key deletion is retried by open/resume/activate; erasing with an already retired handle can retry cleanup without erasing a newer generation. If the erasure transaction rolls back, the local fence protects this database instance while the same handle retries. It does not establish durable logout: after process restart the older persisted active owner can still be resumed until erasure succeeds. Clear the application session first, and keep failed-erasure recovery in the separate session-coordination release gate.

Cleanup validates owner metadata before destroying any key, rejects active references, and permits idempotent deletion of an already missing key. Key identifiers are type-checked and bounded by byte length before `getText`; TEXT character length alone is insufficient because SQLite's text length stops at embedded NUL characters. [SQLite length semantics](https://www.sqlite.org/lang_corefunc.html#length). Tests include malformed active/generation fields, BLOB representations of referenced key IDs, and large/NUL-suffixed owner and cleanup key identifiers. Record metadata must have the exact SQLite types, and existing ciphertext is authenticated before a CAS overwrite or deletion.

The Kotlin reader checks ciphertext length before calling `getBlob`. This bounds the materialized Kotlin byte array; selecting a raw BLOB alongside its length is not, by itself, a verified bound on SQLite's internal allocation for a malicious database file. The foundation accepts only its private database with fixed SQL. A future arbitrary database import feature needs separate engine and memory-safety review.

Logical owner erasure is not forensic media sanitization. Old data may remain in rollback-journal storage, previously used WAL files, filesystem snapshots, or backups. Do not unlink a live database or its sidecar files as an erasure shortcut: another connection can retain the old database handle while a new file is created at the same path. Any later physical removal needs proven exclusive lifetime ownership and explicit product semantics. The persisted owner fence is needed even when SQLite's write locking works correctly. Do not open the actual database file directly merely to implement the lifetime lock; use the separate sibling lock file, since closing unrelated POSIX file descriptors can interfere with SQLite's own advisory locks. [SQLite corruption hazards](https://www.sqlite.org/howtocorrupt.html).

## Security status and required evidence

The vendor CVE table identifies CVE-2025-6965 as fixed in 3.50.2 and requiring arbitrary SQL injection, and CVE-2025-7709 as fixed in 3.50.3 and requiring attacker-controlled corrupt FTS5 content. The inspected 3.50.1 version predates those fixes. Application-owned parameterized SQL, private database paths, no database imports, and no FTS use constrain those documented routes; they do not patch the defects or make the engine current. DELETE addresses the separately documented WAL-reset precondition. The table also includes disputed/non-SQLite reports, so do not infer vulnerability merely from a matching product name. [SQLite CVE assessments](https://www.sqlite.org/cves.html).

Meaningful release evidence should include actual bundled-driver restart persistence; transaction rollback for a mid-write error; duplicate command rejection; generation fencing before and after erasure; schema drift rejection; SQLite FULL behavior; lock contention; and commit-unknown failure injection. Exercise the lifetime lock with separate store instances and a second process, including failed initialization, close, and reopen. Record the runtime SQLite version and DELETE/EXTRA pragma values. Android device and iOS simulator/device execution remain distinct checks; source/API compatibility and JVM success do not substitute for them. No test in this audit demonstrates a physical power-loss guarantee.
