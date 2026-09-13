# Native credentials — M1.05d.3 / M1.03 foundation

13 September 2026. This is provider-independent secure-storage work for FeedMe's real identity integration. It does not sign in a user, verify a provider response, stage guest merge, authenticate a backend route, or connect the demo UI. The original 54-feature/98-screen product remains in scope. U04/U05's sign-in-method/provider question is awaiting response.

## Small delivery packages

| Package | Status | Boundary | Acceptance |
| --- | --- | --- | --- |
| M1.05d.3a | DONE | Bounded component: strict shared credential protocol and lease/incarnation-bound transport projection | Account/guest separation, exact revisions and UTF-8 bounds; no refresh token or mutating operations through transport view; 47 new shared tests |
| M1.05d.3b | DONE | Bounded separate Android Keystore-backed credential component, API27+ with API35 emulator proof | 19 actual native tests pass: encrypted create/read/CAS/bootstrap/retire/reopen, wrong-owner, corruption, missing-key, file-ownership and interrupted writes; API26 and remaining lifecycle/device support are not accepted by this package |
| M1.05d.3c | TODO | iOS credential adapter | Actual Keychain-backed implementation, framework compilation and device lifecycle tests; full Xcode remains unavailable |
| M1.05d.3d | IN_PROGRESS | Native work/timer retirement | NATIVE_SESSION_WORK.md: shared registry and Android independent-store/exact-cancellation components verified; full identity/coordinator scheduling composition and iOS remain |
| M1.05d.3e | TODO | Compatible API26 credential descriptor path | Preserve exact private files, lifetime lock, no-follow and atomic close-on-exec semantics using public APIs; run actual API26 tests before removing the temporary factory gate |

These packages do not complete M1.03, M1.05d or F47. The live login/reset/verification/provider flow and full native logout remain separate acceptance gates.

## Why the old port is not the writer

The original `SecureCredentialStore` accepts unconditional account-scope `replace` and `erase`. That is insufficient when A logs out, A signs in again, and an old refresh/logout callback arrives. The new `IncarnationCredentialStore` separates the native identity writer from `CredentialTransportView`:

- `state()` returns a durable slot revision and optional owner/incarnation metadata, never credentials. Metadata is not proof that the selected key, credential bytes or identity are usable.
- `create(expectedSlotRevision, credentials)` requires the exact observed empty slot. An occupied slot is not overwritten, including a guest whose merge proof has not yet been transferred explicitly. Native code generates a fresh credential incarnation.
- `replace(expectedSnapshot, credentials)` is exact revision/incarnation CAS for refresh. Scope, credential kind and registered device-session ID cannot change through refresh.
- `attachDeviceSession(expectedSnapshot, deviceSessionId)` is a separate exact CAS for a verified bootstrap result. It is account-only and requires the previously unbootstrapped state. External UUID spelling is preserved, including accepted uppercase hex.
- `retire(scope, incarnation)` targets only the captured native credential incarnation. It must not call an unconditional current-account erase.
- `CredentialTransportView` binds to one `SessionBoundary` lease and incarnation, checks them before/after native reads, and strips the account refresh token. Its legacy `replace`/`erase` methods return NOT_CONFIGURED without invoking the writer. A new same-account login needs a new view.

The immutable endpoint/provider configuration must be paired with this view in the trusted composition root. Environment text alone is not a verified API origin, issuer, client ID or account mapping. No endpoint, provider or permanent application identity has been selected by this component.

## Native persistence contract

Credentials never enter the recipe SQLite database, command journal, preferences, backups or telemetry. Android uses a dedicated no-backup directory and independent Keystore namespace. Keys are non-exportable through AndroidKeyStore APIs; hardware/StrongBox residency and forensic erasure are not claimed. Android documents that extractability protection and authorized key use are separate controls. [Android Keystore guidance](https://developer.android.com/privacy-and-security/keystore).

The storage design uses an encrypted manifest, a separate install HMAC key and independently erasable per-incarnation AES-256-GCM keys. Opaque HMAC-derived target names bind environment, actor kind, actor ID and the locally generated incarnation; caller scope text never becomes a path. Immutable revision-qualified encrypted credential blobs are selected by the manifest's exact revision. This avoids overwriting the old credential snapshot before the new selection has committed.

One monotonic revision covers the slot and selected snapshot: empty revision 1 → create revision 2 → refresh/bootstrap revision 3 → retired empty revision 4. Retirement does not recycle the empty revision, preventing a stale empty-slot observation from installing credentials after an intervening login/logout. This is local CAS, not protection against restoring an entire authentic old filesystem/Keystore state.

Writes must force the new encrypted file, atomically rename the exact target and synchronize the directory. Native failures must be returned, not logged and treated as successful persistence. Android's `AtomicFile` explicitly leaves mutual exclusion to the caller, and its current implementation logs certain sync/close failures rather than returning them; it is not enough by itself for FeedMe's explicit uncertain-outcome contract. [Android AtomicFile source](https://android.googlesource.com/platform/frameworks/base.git/+/master/core/java/android/util/AtomicFile.java).

The factory must maintain one lifetime OS lock and reject a duplicate process-local opener before opening another lock descriptor. It validates fixed private paths, UID, file type, hard/symbolic links and private modes. Initial revision 1 is permitted only when this opener successfully created the previously absent fixed directory and the key namespace is fresh. An existing empty/lock-only directory is a repair gate even if all install keys are also missing: it cannot reset the slot revision or revive stale empty-slot observations. A missing initialized manifest or install key cannot be converted into a new empty slot. Unknown encrypted orphan files/keys remain an explicit repair condition; no broad key deletion or recursive production reset is used to hide interrupted writes.

The current Android credential adapter requires API27+ for public atomic close-on-exec descriptor flags. On API26 its factory must return NOT_CONFIGURED before file/key access; the application's declared minSdk remains 26. This explicit temporary gate is not approval to drop Android 8.0 support. A compatible API26 implementation and device proof remain required before release. Directory validation uses public NOFOLLOW/NONBLOCK flags and checks the opened descriptor's directory type before fsync; it does not depend on hidden `O_DIRECTORY`/`unlink` APIs.

The first API35 native attempt ran 18 successful cases but failed a hard-link fixture at the platform's `Os.link` permission check. Verification must respect this protection: assert that denied construction leaves the original bytes, key aliases and owner unchanged, or, where permitted, assert that the store rejects the linked file. This device's denied construction does not prove execution of the adapter's existing-hard-link rejection branch. No security policy is disabled and no case is silently skipped.

## Revisions, bootstrap and retirement

A new credential snapshot is written before the manifest chooses it. A failed or lost manifest acknowledgement is not permission to repeat a refresh or generate another login. Read the exact current state and reconcile the original expected operation; never overwrite a newer revision to make a retry succeed. Provider refresh rotation/grace behavior still requires the chosen provider's verified contract and a serialized identity coordinator.

Exact retirement attempts key deletion and confirms absence before deleting the selected encrypted blob and committing an empty manifest. Missing selected key/blob is a recoverable retirement condition, never permission to regenerate credentials. A late retirement for an older incarnation cannot read or erase the new active identity. Already-copied access tokens and dispatched network requests cannot be retracted by local file/key deletion; remote session revocation is separate.

The [local retirement coordinator](LOCAL_SESSION_RETIREMENT.md) owns the independent durable barrier. Before restoring credentials or activating private UI, finish or explicitly surface pending recovery. Its memory latch cannot survive process death if all initial barrier writes fail. This native credential store does not, by itself, close that full application-lifecycle gap.

## Data validation and privacy

The exact version-1 JSON protocols reject duplicate/unknown/missing fields, malformed UTF-8/surrogates, future versions, coerced/fractional/exponent metadata integers, mismatched guest/account shapes and invalid local incarnation IDs. Manifest owner/incarnation must both be present or both null. Numeric metadata is bounded to nonnegative/positive Long as appropriate; oversized values do not round through Double.

Snapshot wire size is bounded to 65,536 bytes, with each secret bounded to 16,384 UTF-8 bytes. Missing optional credentials and explicit null are not interchangeable in persisted schema. These are local-store bounds, not provider validity claims; the transport independently enforces its bearer/header restrictions. Expired credentials remain expired metadata: the store does not refresh them, sign in, or erase permitted offline cooking automatically.

Transport receives access/guest bearer, expiry and the applicable registered device-session ID, not account refresh credentials. Debug strings and errors redact private values. Temporary byte buffers are cleared where practical; immutable Kotlin strings, already-returned snapshots, process memory and rooted/debugger access are not securely erased by that step. No real credentials are used in verification.

## Remaining integration and release gates

- Verified provider callback/challenge flow, PKCE/state/nonce, issuer/client/subject verification and explicit FeedMe owner mapping; do not infer it from email or `Profile.id`.
- A serialized identity coordinator handling pending login cancellation, refresh single-flight, trusted configuration binding, bootstrap idempotency and exact lost-outcome reconciliation. Native CAS does not authenticate a caller or stop an unrelated caller constructing a fresh activation.
- Explicit two-identity secure guest-merge staging and server receipt; a single active slot is not a reason to drop guest data/proof.
- Connection of the separate [native work registry and Android cancellation component](NATIVE_SESSION_WORK.md) to the full logout recovery barrier, actual scheduler/receiver/workers, app UI, push and authorized backend; iOS cancellation remains unimplemented. Remote logout remains canonical POST `{}` → 200 `{}`, not a local-storage method.
- Provider-supported refresh rotation/grace and the approved native unlock/background-access policy. Development key settings do not settle launch security policy.
- iOS Keychain implementation/full Xcode build, a public-API26 credential implementation and native tests, physical devices, hard-kill/power-loss, key invalidation/device lock, cross-process contention, migration/restore, orphan repair and final security/privacy tests.
- User-approved providers, callback identities, signing, staging/deployment and publication. No accounts, purchases, external messages or public release have been performed.

## Verification evidence

The figures below are the historical credential-source snapshot. The subsequent [session-work receipt](NATIVE_SESSION_WORK.md) freshly rechecks these credentials alongside the new registry: 813 Kotlin/server/database, 92 Node and 59 native tests pass, with five clean library lint reports. It does not add provider or full app-lifecycle proof.

The fresh [native credential receipt](verification/native-credentials/verification.json), completed **2026-09-13T10:29:07.272Z**, passes **733 Kotlin/server/isolated-PostgreSQL tests, 92 Node checks and 41 actual Android checks**, with no failures, errors or skips. Shared counts: core 48, contracts 119, transport 72, storage 99, sync 103, kitchen 142, session 59 (26 credential-codec, 21 transport-view, 12 retirement-codec); server 46 and PostgreSQL 45. All five Android libraries build with zero lint issues.

Source-manifest SHA-256: `7c09a6a1b032d608f419cb4d822c22dbf4a8c2b5d0b83994adf606eeea633838` (160 source inputs). The immutable attempt `verification/native-credentials/attempts/2026-09-13T10-28-32.356Z/` retains 83 evidence files and records 13 artifact hashes, including the unchanged historical demo APK. Earlier session-retirement receipts remain historical and do not cover these newly added credential files.

The credential test APK is 6,733,010 bytes, SHA-256 `231ce0f498c28b7aa1c9d82c675294a3a7a57b3c2c9c3293109d16c54812c3ea`. Its 19 tests ran on API35 arm64 using actual Android Keystore/private files in isolated package `com.feedme.session.test`. The other isolated storage APK ran 20 regular tests plus two separate instrumentation-process stages. Credential tests cover controlled close/reopen and injected file outcomes, **not credential process restart**. Both hard-link fixtures record `platform-denied:13; existing-hardlink-branch-unexercised` in the retained final result; the adapter's existing-hard-link branch remains unexecuted on this device.

Review fixed an Android borrowed-descriptor leak, surrogate-colliding destructive target framing, guest-session rebinding, and existing-empty-directory revision reset. Native compilation caught unavailable public APIs; fresh lint caught the API26 flag requirement and annotation warnings. The first native fixture failure and first full lint-rejected attempt remain retained; they are not overwritten or counted as successful release evidence. Temporary test namespaces are isolated from real app credentials, and no real credentials/provider accounts were used.

An independent final audit rehashed all 160 current source inputs, 13 artifacts and 83 retained evidence files, including 18 native source/copy pairs and two APK metadata pairs, with no mismatch. It parsed all 733 distinct JUnit testcase identities, 92 individual TAP successes and the exact 41 source-declared native method identities against their matching start/success events. All five retained lint files are clean; the receipt pointer, last attempt and immutable receipt bytes agree. The owned emulator was shut down and its execution handle completed. All 50 retained synthetic PostgreSQL test directories have no running PID file, and no test-server listener remains on port 8789. Only isolated test directories and exact test key namespaces were cleaned; FeedMe demo data and unrelated apps were not reset.

Reproduce with installed JDK17, Android SDK, local PostgreSQL binaries and an already booted owned emulator:

```sh
FEEDME_TEST_DEVICE=emulator-5554 node scripts/verify-native-credentials.mjs
```

Set `JAVA_HOME`, `ANDROID_HOME` and `FEEDME_POSTGRES_BIN` to the installed paths. The runner forces fresh builds/tests, verifies exact APK metadata/hash and native start/success identities, rejects skipped tests or changed source inputs, and retains the logs with the receipt. This completes d.3a/d.3b's bounded components, not M1.03, full native logout or any shipping gate.
