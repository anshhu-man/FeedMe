# Local session retirement — M1.05d

13 September 2026. `:shared:session` implements a bounded, provider-independent local retirement coordinator. The coordinator itself is not login, remote logout, account deletion, guest merge or an app-integrated session lifecycle. Subsequent [private-session composition](PRIVATE_SESSION_COMPOSITION.md) now connects the native credential/data/control/work components in four actual Android integration tests, including a runtime path with a synthetic verifier. Original coordinator tests retain explicit fake ports; neither suite proves real provider login. The demo remains memory-only and unchanged. The original 54-feature/98-screen scope is retained.

## Delivery packages

| Package | Status | Implemented boundary | Remaining acceptance |
| --- | --- | --- | --- |
| M1.05d.1 | DONE | Bounded component: authenticated exact-incarnation targets; key-first erasure and retry after failed SQL retirement; 19 targeted real-SQLite tests | Physical-device/power-loss and patched-engine gates remain M1.05e/M8 |
| M1.05d.2 | DONE | Bounded component: independent encrypted Android control store, strict protocol and retirement coordinator; now 12 codec, 8 control and 22 coordinator tests plus 8 native control tests | New pre-mutex lease/latch and directory-provenance regressions pass; not app recovery |
| M1.05d.3 | IN_PROGRESS | NATIVE_CREDENTIAL_STORAGE.md, NATIVE_SESSION_WORK.md and PRIVATE_SESSION_COMPOSITION.md: native components plus bounded actual Android integration | iOS credentials/work, API26-compatible credentials and production scheduler; synthetic verifier is not an app logout journey |
| M1.05d.4 | IN_PROGRESS | PRIVATE_SESSION_COMPOSITION.md: exact durable activation binding, shared lifecycle owner and scoped handles implemented/tested | Actual approved provider/owner binding, bootstrap/refresh/account switch, explicit guest merge, safe origin reconciliation, platform root/recovery UI |
| M1.05d.5 | IN_PROGRESS | SESSION_RECOVERY_DIAGNOSTICS.md d.5a and EMPTY_SETUP_DISCARD.md d.5b.1 complete as bounded components; explicit empty-only discard uses independent optional-target intent | Pre-write ownership/orphan repair, first-write crash proof, native factory failure UX, actual confirmation UI and physical-device restore drills |

M1.05d remains IN_PROGRESS until the native adapters and full lifecycle are integrated. No F47/F48 or whole feature is accepted by these components.

## Ownership and integration

`AndroidSessionControlStore.open(context)` owns a separate `noBackupFilesDir/feedme-session-control` database and `com.feedme.session.control.v1` Keystore namespace. Ordinary private state uses `feedme-state` and a different namespace. Each has exact private modes and a lifetime file lock. Control metadata cannot be erased by retiring a data owner. The public control API exposes only read, exact revision CAS and close: no reset, erase, activate or underlying owner handle.

Only the authoritative native factory's own successful creation of the previously absent directory and new file may initialize the single idle record. Existing empty, lock-only or lost-all-state directories, missing/corrupt records, missing owner/index keys, or a missing file with surviving control keys fail closed; they are never repaired into idle. Initial-setup interruption can therefore require an explicit recovery workflow, not silent database deletion. The control wrapper stores opaque bounded bytes; the coordinator validates their strict schema.

Application composition must use one serialized session-owner dispatcher and one application-lifetime `SessionBoundary`. The boundary is not a thread-safe authentication authority. Storage dispatches its own disk/crypto I/O separately. Required order:

1. Open the independent control, native work and data stores. Before reading credentials, resuming private state or activating a lease, inspect and recover pending retirement. Missing/corrupt control is a recovery state, not permission to sign back in.
2. Independently verify the provider identity and its explicit FeedMe owner mapping. Do not infer an account ID from email or `Profile.id`. Account and guest identities stay distinct.
3. Compose the actual data-owner incarnation, credential incarnation and origin binding, then `capture(lease, originBinding, credentialIncarnation)`. Capture is read-only; an absent private owner returns NOT_CONFIGURED and creates no key. It cannot be called from a delayed response to obtain fresh authority.
4. On explicit logout/account switch, use that binding and a fresh operation UUID with `retire`. It clears the lease and registers the process latch before any disk read. Registration is cancellation-safe; subsequent I/O remains cancellable.
5. Keep private UI fenced while pending or failed. Startup/explicit `recover` clears authority, uses only stored targets and never activates/resumes an account. `restorationAllowed` is a recovery snapshot, not an activation grant; the full identity coordinator must serialize activation with retirement and check the result again at its final activation boundary.

The native credential port must delete exactly `(scope, credentialIncarnation)`, treating an already absent target as success and never deleting the current credentials merely because the account matches. The Android credential component implements this exact port; iOS and full application composition remain unfinished. The native work port must cancel exactly `(scope, originBinding)`. `SessionWorkRegistry` implements it with independent durable evidence, and the Android adapter cancels exact alarm/notification/WorkManager identities. The new shared runtime binds these exact components; four separate Android integration tests exercise successful retirement, pending cleanup after lost acknowledgment, newer-login protection and runtime close/reopen/restore with actual stores and OS resources. Identity verification remains synthetic, and original coordinator unit/integration tests retain explicit credential/work fakes. No no-op production default or authenticated app logout journey is claimed.

## Persisted sequence and failure behavior

The encrypted control record is at most 32 KiB, schema version 1, exact keys, bounded UTF-8/depth and strict UUIDs. Pending stores owner scope, origin, credential incarnation, authenticated opaque data target and completed steps; it contains no access/refresh/guest token, reauthentication proof or raw exception. Completed stores only version/state/operation ID and drops owner and cleanup targets. Local storage encryption is not server authorization or whole-database rollback detection.

| Event | Durable evidence and permitted behavior |
| --- | --- |
| Explicit retirement begins | Clear current lease; retain exact intent in a shared process latch; CAS pending before native cleanup |
| Preflight | Authenticate the target's install HMAC, scope and exact original/already-retired incarnation before touching independent credentials/work |
| Native work, credentials, private data | Attempt each unfinished exact target; checkpoint each success with CAS; a typed failure does not prevent independent cleanup |
| A step fails or coroutine cancels | Keep pending, preserve exact targets and block restore; retry unfinished idempotent steps |
| Commit acknowledgement is lost | Read back only the same higher-revision exact payload; never replace the operation ID or guess rollback |
| All steps succeed | CAS compact completed record, then remove the matching process latch |
| Old callback/binding/retirement token arrives | Reject stale lease/target or mismatched intent; do not erase a newer owner incarnation |

`EncryptedStateDatabase.captureRetirement` returns a detached 137-byte versioned token: opaque owner HMAC, generation, random key handle and a domain-separated install HMAC. `validateRetirement(scope,target)` is read-only and checks scope, exact generation/key and safe key references; it accepts an absent original key because a previous attempt may have deleted it before SQL failed. `recoverRetirement(target)` never captures a new target, creates a key or reactivates an owner.

Retirement first attempts deletion of that exact native data key and confirms absence. It then atomically advances the owner generation, marks it retired and removes only its records. If native deletion fails, SQL retirement plus an exact key-GC row is still attempted and the call remains failed. If key deletion succeeds but SQL fails before commit, restart cannot decrypt the former active row; the authentic saved target can finish retirement without recreating the missing key. Corrupt metadata, foreign/shared active key references and newer generations cannot authorize deletion. No physical-media sanitization is claimed.

## Deliberate limits and remaining product work

- A durable pending record survives normal store close/reopen. If **all first barrier writes fail and the process is then killed**, the memory latch cannot survive; no universal logout-after-restart guarantee is claimed. User-visible blocked recovery, native credential authority and crash testing remain required. A newly constructed boundary must not be used to bypass a failed latch.
- Keystore and SQLite are not one transaction. If neither key deletion nor SQL/control writes can commit, do not report cleanup complete or allow identity restoration.
- The process latch prevents the same boundary from treating a stale idle read as permission to restore, including a delayed read from another coordinator. Its registration is non-cancellable, but there is no hard-kill test at the exact registration instruction.
- Genuine provider callbacks, PKCE/state/nonce/issuer/audience checks, refresh single-flight, verified bootstrap/session ID storage and account ownership are unimplemented. There is no guessed refresh endpoint, client secret or automatic owner mapping.
- Generic HTTP 401/403 is not global revocation evidence and must not erase recoverable private cooking. Canonical remote logout is POST with `{}` and a 200 `{}` response; this local coordinator makes no HTTP request or remote-revocation claim. Deletion acceptance is not completed erasure.
- Guest merge requires explicit choice, both identity proofs and an idempotent server receipt. Never relabel guest SQL/queue records as account-owned, infer destination IDs absent from the contract, or resolve dietary conflicts without the required review.
- Reauthentication/origin changes cannot rewrite a pending command's origin, body, key or sequence. Native cancellation must remain retryable independently of those retired stores. Server origin/manifest/lifecycle decisions remain tracked work.
- The Android control adapter is implemented; iOS Keychain/crypto/factory and full Xcode compilation remain absent. Android API26/physical-device, cross-process lock contention, migration/restore, native app lifecycle and hard-kill/power-loss tests remain open.
- SQLite 3.50.1 remains a release dependency gate. DELETE+EXTRA and WAL refusal do not waive other engine defects. No store upload, publication, provider selection or paid infrastructure is authorized by this work.

## Verification

The historical [session-retirement receipt](verification/session-retirement/verification.json), completed at **2026-09-13T09:44:26.875Z**, passes **686 Kotlin/server/isolated-PostgreSQL tests, 92 Node tests and 22 Android tests**, with no failures, errors or skips. Shared totals: core 48, contracts 119, transport 72, storage 99, sync 103, kitchen 142 and session 12; server 46 and PostgreSQL 45. All five Android libraries build with zero lint issues. The runner captured that shared/server/test source snapshot and the isolated Android storage/control test artifact and native logs. It rejects changed inputs, wrong APK identity/hash, stale/missing native runs and skipped expected tests. Later credential-source verification is tracked separately in [native credentials](NATIVE_CREDENTIAL_STORAGE.md); this earlier receipt does not cover those new files.

Source-manifest SHA-256: `7c4808d46ccd84d0afd7acc2a55234c8f89015f71bfc4cdd6c2f68f40782c6f5` (149 inputs). The receipt retains 69 evidence files and records 12 artifact hashes. Android API35 arm64 executed 20 regular tests and two distinct-process stages. Test APK SHA-256: `9e9df2a838772f65d2a78a59cb7dd97cce6c7da733092a28c539dba424e9dd61` (6,241,103 bytes). The historical demo APK remains unchanged.

An independent final audit rehashed all 149 current inputs, 12 artifacts, 69 retained evidence files and ten native source/copy pairs without mismatch. It counted actual testcase elements in 49 JUnit suites, all 92 TAP passes and all 22 matching native start/success pairs; all expected tests ran without skips. The owned headless emulator was stopped. All 42 retained synthetic PostgreSQL test directories have no running PID file; no local test-server listener remains on port 8789. Only isolated native test fixtures/aliases were cleaned, not FeedMe demo data or unrelated app data.

Coordinator tests use **two real encrypted SQLite databases** with separate test-only JCA vaults. Credential/work ports are explicit fakes: their calls, ordering, interruption and incarnation parameters are tested, not a real platform credential provider. Native tests use real Android Keystore and private-file factories in the separate `com.feedme.storage.test` APK. Its two process-restart stages close the store before the writer exits; they do not simulate a hard kill inside a transaction or FeedMe cooking-screen recovery.

With the installed JDK17/Android SDK/PostgreSQL paths configured and an already running owned emulator:

```sh
FEEDME_TEST_DEVICE=emulator-5554 node scripts/verify-session-retirement.mjs
```

No iOS, actual authenticated UI, remote domain effects, full native logout, cloud sync or release readiness is inferred from this receipt. Earlier storage/command/kitchen receipts remain historical and do not describe the current source snapshot.
