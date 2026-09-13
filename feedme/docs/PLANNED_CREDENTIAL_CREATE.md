# Planned credential creation — M1.05d.5b.2a

13 September 2026. This package records exact credential CREATE ownership before native key/file writes and supplies explicit, restricted recovery. It does not implement refresh repair, planned private-data/work creation, provider login, recovery UI, iOS or release readiness. FeedMe's 54 features and 98 screens remain in scope.

## Delivery packages

| Package | Status | Acceptance |
| --- | --- | --- |
| M1.05d.5b.2a | DONE | Bounded credential-only component: authenticated CREATE plan, independent pre-write journal, exact native commit, restricted existing-only abort; 61 added JVM and 19 added native tests pass |
| M1.05d.5b.2b | IN_PROGRESS | Low-level planned private-data selection is complete as b.2b.1; existing-only activation recovery code is implemented with full verification pending; composite data/work journal remains absent |
| M1.05d.5b.2c | TODO | Separate refresh-write ownership, consumption and exact old-blob cleanup without deleting a shared incarnation key |
| M1.05d.5b.3 | TODO | Physical-device hard-kill/power-loss, full platform lifecycle, backup/restore and first-native-factory initialization failure drills |
| M1.05d.5c | TODO | Actual recovery screen, explicit confirmation/retry UX and approved provider/platform integration |

### Private-data/work follow-up tasks

The low-level selection primitive below is implemented separately from credential-only acceptance. Existing-only data recovery code is now implemented but awaits full/native verification; the runtime-journal task remains future work.

| Task | Status | Implement and prove |
| --- | --- | --- |
| M1.05d.5b.2b.1 | DONE | [Planned private-data activation](PLANNED_STATE_ACTIVATION.md): MAC-authenticated exact absent/retired predecessor, generation and preselected key; no-write planning and exact selection commit, with 16 codec, 26 real-SQLite and 14 native tests. `Unit` acknowledges selection only, not a lease, record handle, retirement target or durable journal resolution. |
| M1.05d.5b.2b.2 | IN_PROGRESS | [Planned state recovery](PLANNED_STATE_RECOVERY.md): existing-only plan-bound inspection/empty-only abort; every attempt commits a changed V2 receipt and consumed generation before exact key erasure. Targeted storage JVM tests pass; full/native verification pending. |
| M1.05d.5b.2b.3 | TODO | Plan an empty work origin before its CAS, without a synthetic lease. Retain credential/data/work plans in one pending setup journal through exact activation-binding persistence and final control completion; activate a real lease only afterward. |

The low-level tests cover key creation and owner COMMIT interruption; work CAS, binding CAS and composite control completion remain future integration tests. Retry must not invent another identity or generation. A selected data replay's no-op transaction adds no fresh synchronization and does not implement independent journal resolution. Historical AFTER-COMMIT injection follows successful SQLite COMMIT under `journal_mode=DELETE` / `synchronous=EXTRA`: a lost application receipt, not a demonstrated original sync failure. The separate recovery continuation now commits an actually changed abort receipt before deleting the key on every attempt, including ABORTED retries; its native sync-failure tests await verification. No universal extra-fsync requirement or descriptor redesign is inferred; see [the evidence boundary](PLANNED_STATE_RECOVERY.md). The runtime has not adopted planned data selection/recovery. Existing empty-only discard cannot erase a written activation binding; any verifier-backed completion or binding-aware rollback requires its own exact protocol. Missing stores/index keys, unexpected rows/jobs and unknown files remain preservation gates. Native process-kill/power-loss and iOS proof remain separate from injected close/reopen tests.

Data recovery is an API27+ existing-only capability, not a general database opener: it rejects V1, preexisting journals and unknown children, never creates files/locks/keys/schema, and runs no GC. Normal data/control/work opening may atomically upgrade the exact V1 schema to V2. Historical same/foreign-owner abort receipts forbid candidate-key reuse. Only inspect/abort/close escape; returned-handle close can retry, while failed construction followed by failed cleanup close remains a process-restart gate. These data-specific rules do not change the credential manifest protocol or grant authority to clear its independent `PendingCreate` intent.

## Live creation

The serialized `PrivateSessionRuntime` requires `PlannedCredentialCreateStore`; there is no fallback to unjournaled credential creation. The native root must supply approved genuine provider verification and explicit FeedMe owner/bootstrap mapping. Tests supply synthetic/fake verifiers, and the demo app still does not consume this private runtime.

After verified credentials and existing empty-slot/private-owner/work checks, the runtime asks the native store for a CREATE plan. Planning uses the existing install HMAC key and a fresh native UUID; it creates no credential key, blob, manifest revision or activation lease. The runtime writes that opaque plan to the separate encrypted session-control store, then verifies the exact control revision and bytes. Only acknowledged persistence or exact reconciled ambiguous acknowledgement permits `commitPlannedCreate`.

The returned native credential snapshot must match the planned incarnation, revision, scope and exact supplied credential payload. Only then is the credential CREATE journal resolved. Data activation, work-origin creation and the immutable session-activation binding happen afterward through their existing flow. Although [planned data selection](PLANNED_STATE_ACTIVATION.md) is now available as a low-level primitive, the runtime still uses unplanned data activation and lacks the composite data/work journal. A credential plan must never be used to infer ownership of unrelated data or work keys. No-GC guarantees apply to data plan/commit themselves, not normal database opening or `resume()`.

If a journal write, native commit, control resolution, cancellation or current-attempt check fails, no alternate plan or identity is silently substituted. A durable pending CREATE blocks normal restore, leased data/credential access and native work admission. Ordinary logout recovery and empty-setup discard do not cast this new intent into their own cleanup targets or automatically abort it.

## Capability and protocol

`CredentialCreatePlan` is an opaque defensive byte capability with a redacted representation. Persist it only in the independent encrypted control record, not logs, analytics, general preferences or recipe rows. `fromStorage` validates bounded canonical structure, not authenticity. The issuing native store authenticates the plan before any effect.

The version-1 CREATE-only record contains an exact prior empty-slot revision R, native-random incarnation, opaque HMAC-derived target, keyed commitment to the canonical intended credential snapshot, and an install/namespace-bound authentication MAC. It contains no raw owner ID, password, access/refresh token or guest proof. R must reserve two future revisions: selected R+1 and aborted R+2. JSON fields, purpose, UUID, lowercase MACs, integer bounds, Unicode, size and canonical bytes are strict; native HMAC domains distinguish payload commitment from plan authentication.

A token for another installation/namespace, changed payload, wrong target/incarnation, refreshed selected revision or newer login fails without retargeting. Whole-install rollback protection is not claimed by a local revision or backup exclusions.

## Native commit and files

The normal Android credential factory remains strict about its authenticated selected inventory. The planned writer creates only its exact key and immutable revision-qualified blob, followed by its exact manifest selection. Selected replay is read-only with respect to stored material: it validates the existing snapshot and payload commitment and re-acknowledges directory durability. It never recreates missing selected key/blob material. A blob without a key cannot trigger key replacement. Partial temporary ciphertext is not overwritten or guessed into a completed write.

Planned manifest writes use `manifest.create.<incarnation>.pending` and abort writes use `manifest.abort.<incarnation>.pending`. The global legacy `manifest.bin.pending` is not attributable to this plan and remains blocked. All names retain private directory/file modes, regular-file/no-symlink/single-link checks, bounded reads, exact deletion, file/directory synchronization and the exclusive native lifetime lock. No arbitrary filename or alias comes from UI input.

## Startup and explicit abort

Open and authenticate independent control first. If it contains a pending CREATE, use `CredentialCreateCoordinator` on the same serialized session dispatcher and boundary. Stop normal session work and close its native handles before opening recovery. `inspectPending()` returns a redacted, coordinator-bound proposal and whether an abort was already requested; it is not a sign-in grant or confirmation. A newly observed plan does not automatically abort.

After actual user confirmation, `requestAbort(proposal)` rechecks the exact control record and inactive boundary, persists `abortRequested`, and only then opens the restricted native recovery handle. Startup may call `recoverAbort()` only for an already requested abort. The handle exposes inspection, exact abort and close—not credential reads, refresh, arbitrary create, activation or installation reset. The native factory requires existing credential directory, lock, manifest and install authentication keys; it creates none of those to repair missing state.

Only the authenticated plan's exact artifacts and matching authenticated manifest state are admitted. Unknown extras, another plan's temporary file, lost install/manifest authentication, unsafe paths or a newer/refreshed selection remain blocked and preserved. Owned partial/corrupt ciphertext may be removed by explicit exact abort without decrypting it; that does not authorize deleting an unknown file by resemblance.

The native states are PREPARED (original empty slot, no artifacts), PARTIAL (original empty slot with planned artifacts), SELECTED (exact R+1 selection), ABORTING (abort temp or consumed slot with remaining artifacts), and ABORTED (empty R+2 with no planned artifacts). SELECTED does not prove usable credentials. ABORTING does not alone prove a durable consumed revision. A read-only ABORTED observation is not a fresh durability acknowledgement.

Unselected abort durably advances the original empty slot to R+2 before deleting its key/blob/temp; replay can no longer create at R. Selected R+1 already consumes CREATE because selected commit replay never generates replacement material. That selection is re-synchronized before key-first deletion, then the manifest advances to empty R+2. Retries of an already consumed slot re-synchronize it before cleanup. Exact target and revision checks reject refreshed/newer selections before any deletion. Even an initially ABORTED handle must acknowledge abort durability before the coordinator clears independent control.

Each failure retains the exact plan/abort request for retry. Ambiguous native outcomes do not imply completed cleanup; poisoned handles must close and reopen through the restricted factory. A successful abort and ABORTED check, acknowledged handle close and unchanged independent control are all required before marking the intent complete. No lease or provider verification is created by that completion.

## Verification

Current continuation is [planned state recovery](PLANNED_STATE_RECOVERY.md), **IN_PROGRESS**: **315 targeted storage JVM tests pass**; full source-bound/native verification is pending. It implements a bounded data-abort component, not runtime data-plan wiring or composite setup-journal resolution. The previous complete [planned private-data activation](PLANNED_STATE_ACTIVATION.md) run finished **2026-09-13T13:35:54.272Z** with **1,069 Kotlin/server/isolated-PostgreSQL tests, 92 Node checks and 102 native checks**, five clean lint reports, shared 978 (storage 275/session 219) and native 45 storage/57 session. Those totals and the following credential receipt/counts/hashes remain historical, not proof of changed V2 sources.

The [historical source-bound verification receipt](verification/planned-credential-create/verification.json), finished **2026-09-13T13:08:42.379Z**, passes **1,027 Kotlin/server/isolated-PostgreSQL tests, 92 Node checks and 88 isolated Android checks**, with no failures, errors or skips. Five fresh Android libraries build with zero lint issues. Shared JVM totals are 936: core 48, contracts 119, transport 72, storage 233, sync 103, kitchen 142 and session 219; server 46 and PostgreSQL 45 are additional.

The 61 added JVM methods comprise 18 plan-codec, 32 create-coordinator, four retirement-codec and seven real-SQLite runtime integration tests. Android adds 18 actual Keystore/private-file plan tests and one actual runtime/control/recovery integration, bringing session-native coverage to 57 (19 credential, ten cancellation, 18 CREATE plan and ten integration tests). Native storage contributes 29 regular checks plus two separate instrumentation-process stages. Native identity verification is synthetic and confirmation is programmatic, not provider login or a shipped recovery screen.

Coverage includes read-only planning, exact payload and installation binding, pre-write control barriers, stale/foreign plans, consumed-plan replay, selected missing/corrupt material without key regeneration, partial/zero-length planned ciphertext, private paths/modes, existing-only factory guards, exclusive handle ownership, cancellation, durability re-acknowledgement and independent-control recovery. Tests inject interruptions before/after native key/file/manifest boundaries and close/reopen real stores. Only the storage suite includes separate instrumentation processes; CREATE tests are not physical-device process-hard-kill or power-loss proof.

The first full attempt (`2026-09-13T13-01-46.215Z`) is retained as failed evidence. It exposed malformed UTF-16 escaping the codec's typed error boundary and an Android test comparing a canonical descriptor path against a path alias. The codec now wraps the complete encoding operation and returns sanitized `INVALID_DATA` before mutation; exact typed Unicode assertions were strengthened. The descriptor test now resolves canonical paths while retaining its exact live/closed count and exclusivity assertions. Focused tests and then the entire frozen-source suite were rerun successfully.

Source-manifest SHA-256: `6f8c74e3f9e4e07d6e8f88eb9a3badca683f62e2c0834ab7ee00a5dcc7192cc7` (198 inputs). The immutable final attempt `verification/planned-credential-create/attempts/2026-09-13T13-07-27.325Z/` retains 94 evidence files and 13 artifact hashes. Receipt SHA-256: `786e528942aa5011a59fee0f8711158133b0fa23321314596cb3bb052b0d7e05`. Session test APK: 8,525,175 bytes, SHA-256 `617dfe6f6d810dc65f39620c446ff5500765aa780fa1b917210a7e50045fadf2`. Storage test APK remains 6,273,871 bytes, SHA-256 `2e81270458ff87b287d8abecdd2fa7755f4d089a21be2d92736223edfbc9753f`.

Independent read-only audit rehashed all 198 current inputs, 13 build artifacts, 94 evidence files and every recorded source/copy/metadata reference without mismatch. All 1,027 unique actual JUnit methods in 61 XML files resolve to current Kotlin source; all 92 TAP test names and 88 native start/success pairs match their declared tests. The latest and immutable receipts are identical, all four verification commands exited zero, and native APK/metadata identities and platform-branch limitations were checked separately from aggregate counts.

API35 arm64 was used; session tests target SDK36 and storage tests target26. Delivered-notification cancellation passed; its initial granted test-app permission was preserved unchanged. Android denied hardlink creation, so existing-hardlink guard branches remain unexercised. The owned emulator was stopped with acknowledged exit. All 78 retained synthetic PostgreSQL fixture directories have no postmaster PID file, and no test server remains listening on port 8789. Only isolated test fixtures/keys and exact test work were cleaned; demo or unrelated user data was not reset. The demo APK is unchanged and does not consume these components.

With JDK17, Android SDK, `FEEDME_POSTGRES_BIN` and a booted owned emulator configured, reproduce with `FEEDME_TEST_DEVICE=emulator-5554 node scripts/verify-planned-credential-create.mjs`. Earlier empty-discard and credential receipts are historical source snapshots, not coverage of the new sources.

Real provider/bootstrap configuration, native platform root/UI, API26 credential compatibility, full Xcode/iOS, domain receivers/workers, reviewed content, social safety, purchases and publication approvals remain separate gates. No account was provisioned, provider selected, service deployed, purchase made or store build uploaded by this work.
