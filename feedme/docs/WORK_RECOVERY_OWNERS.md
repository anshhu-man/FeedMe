# Existing-only work recovery

14 September 2026. Slice M1.05d.5b.2b.3.6d.2 is **DONE as a bounded component** supporting the included account/private-data foundations. It does not implement any of the ten [deferred V1 features](V1_RELEASE_SCOPE.md), complete startup recovery, or make FeedMe release-ready.

## Ownership and integration

`AndroidSessionWorkRecovery.createOwner(context, scope, plan, boundary)` synchronously returns a `SessionWorkOriginRecoveryOwner`. Retain that owner **before** awaiting `open()`, including after failed or cancelled opening. Its only operations are `open`, `inspect`, `abort` and `close`. It does not expose a normal work registry, raw ledger, signing, new IDs, scheduler policies or a session lease.

Opening requires an inactive session boundary, the original non-demo scope and canonical plan, and supported existing native storage. It never creates the work directory/database, initializes or migrates its schema, resumes an ordinary session, or performs key garbage collection. An admitted failed/cancelled open becomes close-only; it cannot reopen and adopt a newer target. A rejected duplicate open does not invalidate a previously ready owner.

The lower `ExistingSessionWorkRecoveryStore` is an explicitly opted-in, trusted cross-module composition SPI. Unlike the application facade, it exposes fixed-ledger plaintext for the shared session codec. It verifies native proofs but cannot sign them or return an arbitrary private-store handle. Do not expose this SPI to feature code as a general storage service.

Android acquisition shares the retained lifecycle used by private-data recovery. It validates the existing private directory and exact `state.sqlite`/`state.lock` inventory, retains ownership before I/O, opens bundled SQLite without CREATE, and transfers the connection synchronously to its common owner before initialization can suspend. Work proof verification necessarily follows guarded existing-only SQLite reads of the fixed owner; it is **not pre-SQLite authentication**.

## What inspection means

The native store rechecks the captured owner incarnation and sole fixed ledger inside each read/proof/write transaction. Extra owners, records, garbage-collection rows, abort rows, malformed schema, missing keys or changed ownership are refused and preserved. The session layer authenticates the original plan and accepts only these exact states:

| Observation | Required evidence |
| --- | --- |
| PREPARED | Exact original revision and authenticated raw predecessor; an aborted predecessor also requires its own original authenticated plan. |
| SELECTED | Exact canonical selected-plan bytes and a later revision. |
| SEALED | Exact retained setup plan, scope and origin; canonical bytes, no retirement and no ordinary work entries. |
| ABORTED | Exact canonical original-plan marker and a later revision. |

Inspection returns status and full revision, with exact raw readback around proof checks. Ordinary used origins, noncanonical markers and consumed/newer provenance are not recovery authority. Inspection is advisory: it is neither user confirmation nor a fresh durability acknowledgement.

## Abort and close acknowledgements

Every abort, including an already-aborted replay, requires a fresh exact compare-and-set and advances the revision. The existing acknowledgement protocol brackets the operation with exact raw observations and current-boundary checks. A failed or unknown COMMIT is not promoted to success merely because readback resembles the desired marker. Cancellation cannot turn a synchronous native return into a successful caller receipt.

Closing is serialized and non-cancellable. SQLite close must acknowledge before the sibling ownership lock is released. Retain the same owner through a failed close; retry only unfinished acknowledged stages. The shared native guard refuses a later driver no-op after an ambiguous post-close failure. Terminal platform/driver ambiguity retains the reservation for explicit process repair; tests do not bypass it to manufacture successful cleanup.

The caller must not clear independent setup control merely because this owner closed. Since this historical checkpoint, [startup slices6d.3/6d.4](OWNED_STARTUP_RECOVERY.md) have added an owner before subordinate opens, retained partial-open cleanup, exact confirmation and close acknowledgements before fresh Complete. Their bounded proof does not complete production app wiring or task7 process-separated integration. The existing [borrowed coordinator](COMPOSITE_SETUP_ABORT.md) still closes none by default.

## Verification status

The [full source-bound receipt](verification/work-recovery-owners/verification.json) passed at **2026-09-13T20:33:22.344Z** (14 September in India): **1,650 Kotlin/server/database tests, 161 Node checks and 299 isolated Android tests**, with five freshly built Android libraries and zero-issue lint. Shared Kotlin totals 1,559: core 48, contracts 119, transport 72, storage 518, sync 103, kitchen 142 and session 557; server 46 and isolated PostgreSQL 45 remain separate. Native coverage is storage 152/session 147 on API35 arm64.

New coverage comprises 58 common/JVM methods (30 storage and 28 session), 12 lower native-store methods and seven real public-facade methods. Review added a same-dispatcher synchronous-read cancellation regression; the focused storage rerun passed. The first full attempt at `verification/work-recovery-owners/attempts/2026-09-13T20-25-04.110Z/` passed Gradle and scope checks but failed eleven storage methods at their shared fixture parser: Android ICU rejects an unescaped closing brace. Only that test regex was corrected; strict matching and all expectations remain unchanged. The corrected 12-test native run and complete 147-test session suite passed before the fresh full rerun. Failed evidence remains retained.

The final receipt binds 293 source inputs, 13 artifacts and 154 evidence files. Source SHA-256: `c9c94ae6563d703d45eb03b322e57aa1aa3d73348c7694e4cd1acd9e93bb41eb`. Receipt SHA-256: `f37412cb0909c2bd12076f004fd4a8b5f66a4375c05d2ded123b47befd3b3ca1`. Root audit matched all 581 recursive size/hash records (510 unique descriptors), the source aggregate and identical current/immutable/last-attempt receipts. The actual Gradle V1 scope task and independently recomputed manifest report passed; neither is runtime feature-gating acceptance.

Independent audits matched all 1,650 unique source-declared JVM identities across 85 fresh current/retained XMLs, 161 exact Node declaration/result pairs, and 299 source-matched native start/success pairs across 27 invocations. All 39 VFS labels, five controlled process stages, 43 native copy pairs, APK metadata and four actual ELF ABI packages match. Only API35 arm64 was executed; the other three ABIs have packaging evidence only. Existing-hardlink branches remain platform-denied with errno13 and unexercised; exact delivered-notification cancellation passed. No discrepancy was found.

The owned emulator exited normally. Storage fixtures are empty, session retains only its three expected WorkManager files, and notification permission remained granted. No port8789 listener or PostgreSQL PID files remain across 142 retained synthetic clusters; none were deleted. The demo APK and clean public export remain unchanged. No new source was pushed.

The new `scripts/verify-work-recovery-owners.mjs` retains source inputs, fresh JUnit and Node results, exact native source-matched test transcripts, APK/build metadata, lint and packaging evidence. Earlier fixed-inventory verifiers and receipts remain historical. Run with existing JDK17, Android SDK36/NDK28.2.13676358, local PostgreSQL selected by `FEEDME_POSTGRES_BIN`, and a booted API35 emulator selected by `FEEDME_TEST_DEVICE`.

## Explicit limits

Native success uses real Android Keystore and bundled SQLite, but synthetic identities and programmatic test actions. Common session tests use a detached fake SPI and are not native cryptography evidence. Existing injected VFS failures and five controlled process stages remain distinct from integrated hard-kill recovery, physical power loss and public hot-journal acceptance. The C VFS helper must remain isolated to the storage test APK; internal Kotlin test seams are not claimed absent from production bytecode.

API26 recovery, iOS compilation, real provider/bootstrap configuration, confirmation UI, complete startup composition, domain/backend integration and release acceptance remain open. This local work does not publish source, deploy infrastructure, activate paid products or submit FeedMe.
