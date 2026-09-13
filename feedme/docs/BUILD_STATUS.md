# FeedMe — build status

Verified on 13 September 2026. This is a development foundation, not a production release or a Shipaton-ready store build.

## Rename and scope

The app is now **FeedMe**. Native project, Kotlin namespaces, Android display name, iOS project/scheme/framework, active blueprint and active UI prototype use the new name. The cooking-and-social concept is unchanged: low-effort cooking, Make Mine, 24-hour Today posts, deliberately retained My Plate posts and invited kitchen circles. TasteEcho has not been merged into this product.

The full blueprint still describes 54 features, 98 designed screens, 900 canonical action bindings and 201 API operations. These are design/contract coverage, **not native implementation coverage**. Existing `outputs/biteclub_ui` and `outputs/biteclub_blueprint` directory names remain for stable links. Original image-generation prompts and old BiteClub ZIP exports remain historical artifacts, not renamed deliverables.

## Implemented first native journey

Welcome → explicit demo entry → Kitchen/Today → sample plate → Make Mine → supported recipe version → private local save → guided cooking → completion → local post preview → Today or retained My Plate.

Also implemented: saved cookbook, source attribution, local settings, reset confirmation, native Android back navigation, retained Make Mine choices and tab selection semantics. The native screens share Kotlin/Compose across Android and the iOS host. Auth controls explicitly explain that sign-up/login is unavailable.

Make Mine selects declared fixture variants that support the requested time/effort constraints. It is not an AI service and does not promise allergy-safe substitutions. Unsupported requirements return an explicit failure. Recipe/sample-image content requires production content review.

The native roots now explicitly inject `DemoKitchenRuntime` and its clock. Shared UI no longer constructs fixtures or hard-codes the featured plate ID. Client integration interfaces separate account/guest credentials, owner/environment storage scopes, transport metadata and best-effort native timers. Session leases reject stale results after account switching; this is client result isolation, not server authorization or durable persistence. See [integration boundaries](CLIENT_INTEGRATION_BOUNDARIES.md).

## Verification

Current continuation: [planned private-data recovery](PLANNED_STATE_RECOVERY.md) is implemented but still under verification. The new v2 receipt/empty-only abort and existing-only native opener pass 315 targeted storage JVM tests and 57 regular Android storage tests. The separate sync-error harness failed to attach at an unsupported syscall hook. A replacement C VFS is authored but uncompiled; its Kotlin registration/URI flow and verifier labels are not yet connected. This work is paused for the user-requested GitHub snapshot, and the six sync cases remain incomplete. These partial results do not make the current sources fully verified. The complete receipt below is the preceding source snapshot.

The latest planned-private-data-activation run passed at **2026-09-13T13:35:54.272Z**: **1,069 Kotlin/server/database tests, 92 Node checks and 102 isolated Android tests**, plus five fresh Android library builds and zero-issue lint. [Implementation, exact evidence and remaining gates](PLANNED_STATE_ACTIVATION.md). M1.05d.5b.2b.1 is complete as a low-level storage-selection component: authenticated no-write planning, exact predecessor/key selection and bounded replay without unrelated GC. Its commit returns only a `Unit` selection acknowledgement, not a private-state handle, retirement target, session lease or composite-journal completion. The runtime is not wired to this planned-data path. Existing-only inspection/abort (b.2b.2), empty work-origin planning and the composite setup journal (b.2b.3) remain unfinished. No-GC applies to planning and planned commit, not ordinary database open or resume.

The earlier bounded credential-CREATE component (b.2a), read-only diagnostics and confirmed readable-empty setup discard remain verified. Distinct pending credential CREATE blocks ordinary activation and cleanup; it does not authorize arbitrary orphan deletion. API35 verifies 45 storage checks (43 ordinary plus 2 separate-process stages) and 57 credential/cancellation/plan/integration checks, including ten native integration tests and delivered-notification cancellation. Session tests target SDK36; storage tests target26. Native tests use synthetic verification and programmatic confirmation, not provider login or app UI. The current AFTER-COMMIT fault tests lose the application receipt after a successful SQLite COMMIT under DELETE/EXTRA; they do not simulate a failed filesystem synchronization. Genuine `SQLITE_IOERR_FSYNC`/`SQLITE_IOERR_DIR_FSYNC` provenance, transaction outcome and recovery need separate acceptance tests before irreversible cleanup or composite-journal clearing. No fresh sync is supplied by a no-op selected replay, and physical power-loss behavior is not proven; the present tests do not establish a current sync defect or justify a descriptor redesign.

Refresh repair, first-factory recovery, approved identity/bootstrap, production scheduling and cooking/recovery UI remain unfinished; credentials still reject API26 and iOS is uncompiled. The demo APK is unchanged. M1.03, M1.05, M1.06 and all release gates remain open. Earlier continuation paragraphs and receipts are historical evidence, not the latest totals.

| Check | Result | Evidence |
| --- | --- | --- |
| Shared Kotlin component tests | 978 passed: 48 core, 119 contract/wire/binding, 72 transport, 275 encrypted storage/integration, 103 command recovery, 142 kitchen, 219 session/credential/work; 0 failures, errors or skips | Latest `docs/verification/planned-state-activation/verification.json`; source-bound component/real-SQLite evidence, not app or iOS runtime proof |
| Contract generation / drift | 75 generator tests passed; all 201 operations and 1,031 registry bindings retained | `docs/verification/contract-artifacts/`; metadata only, not body validation |
| Android debug app assembly | Earlier pass; recorded APK retained, not rebuilt in the transport continuation | `apps/android/build/outputs/apk/debug/android-debug.apk`; prior emulator receipt below |
| Android transport library | Fresh debug AAR build and lint passed, 0 issues | `shared/transport/build/outputs/aar/transport-debug.aar`; latest planned-state-activation receipt; not device network execution |
| Android encrypted-storage/control/work library | Fresh AAR/test APK/lint pass, 0 issues. Actual API35 arm64 test APK passed 43 native tests + 2 separate-process storage stages | Latest `planned-state-activation/` receipt includes copied native logs; low-level planned selection, not composed setup, cooking/queue app recovery or iOS |
| Android command-recovery library | Fresh debug AAR and lint pass, 0 issues | `shared/sync/build/outputs/aar/sync-debug.aar`; latest planned-state-activation receipt; no native device/UI integration claim |
| Android private-kitchen library | Fresh debug AAR and lint pass, 0 issues | `shared/kitchen/build/outputs/aar/kitchen-debug.aar`; latest planned-state-activation receipt; owned save/cook components, not native UI or real server integration |
| Android session/credential/work library | Fresh debug AAR/test APK and lint pass, 0 issues; 19 credential + 10 cancellation + 18 CREATE plan + 10 native integration tests on API35 | `shared/session/build/outputs/aar/session-debug.aar`; latest planned-state-activation receipt; real stores/runtime/diagnostics/exact-recovery/OS integration with synthetic verifier, not actual sign-in or app scheduling |
| Earlier Android app lint | 0 errors; 0 warnings, rechecked after backend addition | Historical `docs/verification/backend-foundation/android-lint.xml`; current five library lint results are listed above |
| Android backup configuration | 3 parsed-XML regression tests passed | `scripts/android-backup-policy.test.mjs` |
| Local server checks | 46 tests passed again; includes all 188 schema baselines, 8,423 constraint mutations, all operation body maps, format regressions and 2 actual CIO/client integration tests; all 200 unfinished operations remain unavailable | Latest tests `docs/verification/planned-state-activation/`; prior exact packaged-distribution 4-probe receipt remains in `canonical-validation/` |
| PostgreSQL durability foundation | 45 integration tests passed again against fresh local PostgreSQL 15.19 clusters; no live feature/provider connection | Latest `docs/verification/planned-state-activation/`; scope in DURABLE_STORAGE_FOUNDATION.md |
| Android emulator journey | Final APK passed 10 screen checkpoints and 4 behavior checks; report hash matches artifact below | `docs/verification/android-smoke/report.json` |
| iOS project/plist syntax | Passed `plutil -lint` | `apps/ios/FeedMe.xcodeproj/project.pbxproj`, `apps/ios/FeedMe/Info.plist` |
| iOS shared scheme syntax | Passed `xmllint --noout` | `apps/ios/FeedMe.xcodeproj/xcshareddata/xcschemes/FeedMe.xcscheme` |
| iOS compilation / runtime | Not run: full Xcode is not installed | Active developer directory is CommandLineTools |
| Renamed blueprint checks | 137 structural checks and 22 browser scenarios passed | `../outputs/biteclub_blueprint/verification/` from the project root |
| Renamed UI prototype checks | 20 checks passed; 98 screen screenshots refreshed | `../outputs/biteclub_ui/verification/report.json` from the project root |

The Android artifact rebuilt after adding the isolated backend module is 18,981,844 bytes. SHA-256:

```text
bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805
```

The successful emulator run exercised welcome/auth-unavailable, Kitchen, Today, both declared bowl versions through Make Mine, a local cookbook save, all three cooking steps, completion, local publishing, My Plate and the saved cookbook. Additional assertions verify truthful auth unavailability, My Plate and cookbook back-navigation origins, and explicit reset clearing local state. The screenshot record is a set of first-viewport checkpoints, not an audit of every screen at every scroll position.

The exact final artifact was reinstalled and passed all 14 smoke checks after adding the backend module. Android assembly and lint passed again; its runtime dependency report contains neither Ktor nor the server module. The headless emulator started for this verification was closed afterward; no other device or app data was cleared. The prior passing artifact's receipt is retained at `docs/verification/earlier-attempt/passed-pre-backend-baseline-report.json`; its hash is historical, not the current APK.

The welcome, Kitchen, adapted recipe and My Plate captures were visually reviewed after the system-inset fix. They no longer overlap the Android status bar. Earlier failing screenshots are retained under `docs/verification/earlier-attempt`; the old `docs/verification/welcome.png` is also an earlier pre-fix capture. Use `docs/verification/android-smoke/01-welcome.png` for the verified current welcome screen.

The earlier backup lint warning is resolved. Backup is disabled using `allowBackup=false`, and explicit pre/post Android12 rules exclude all nine credential/device-protected domains from legacy backup, cloud backup and device transfer. Configuration tests do not prove every OEM backup/restore implementation. Kotlin/Compose dependency-accessor deprecation warnings and existing reducer-test unnecessary-assertion warnings remain; Android lint itself has no issues.

Initial verification found and fixed a test syntax error, an API-level theme attribute problem, status-bar overlap, lost Make Mine form choices, incorrect saved-state wording and back-navigation origins. The first complete emulator attempt also found a smoke-runner bug: a partially visible card title prevented scrolling. The runner now scrolls past partially clipped labels, and diagnostic captures do not count as passing checks.

The interface continuation also fixed a missing Android direct module dependency found by compilation. Independent review caught guest/pre-bootstrap credential conflation, lost response trace/retry metadata and ambiguous mutation receipts; these are now explicit and tested. No full DTO generator or real provider adapter has been adopted. The subsequent [generator compatibility spike](CONTRACT_GENERATION_SPIKE.md) found actual full-model compile failures and dropped constraints; this failed output stays out of the app. M0.07 remains incomplete.

The backend continuation adds a separate local-only service with 17 passing tests and a built local distribution. Only canonical public health works, with an intentionally degraded status; the 200 other canonical routes return explicit HTTP 503. Configuration rejects remote binding and production mode. The real CIO listener test starts on loopback and closes after use. This completes the scaffold task M1.01, not M1 or any account/social/security feature. [Backend details](LOCAL_BACKEND_FOUNDATION.md).

The subsequent storage continuation adds checksum-verified SQL migrations, durable command receipts, outbox leasing/quarantine, consumer deduplication and retention compaction. 45 real-PostgreSQL tests and 24 server tests now pass; shared Kotlin's 46 tests pass again. This completes M1.02's primitives, not product persistence: HTTP and native clients remain disconnected. The database test clusters were newly created and stopped; no existing database was used. No Android code or APK changed in this storage continuation, and iOS remains uncompiled. [Storage evidence and limits](DURABLE_STORAGE_FOUNDATION.md).

The contract metadata continuation completes M0.07a with an owned, deterministic generator and a standalone shared Kotlin catalog. All original schema bytes, 201 operations and 1,031 action/hydration bindings remain intact; 75 generator tests, 14 metadata tests and a new server parity test pass. Existing Node regressions bring that run to 92 tests; shared Kotlin totals 60 and server tests total 25. Only server tests depend on the new module: the mobile app and production service do not yet consume it. The Android APK and production server JAR hashes remain unchanged, and no new Android/PG/iOS execution is claimed. [Metadata evidence and remaining packages](CONTRACT_METADATA_FOUNDATION.md).

An additional real validator spike passed 154 of 158 cases but failed exact-number handling, including three canonical fields. That candidate is explicitly unadopted; the build does not include it. Schema/operation metadata and preservation are not faithful body validation. M0.07b–d remain unfinished. [Validator evidence](SCHEMA_VALIDATOR_SPIKE.md).

The subsequent JVM candidate passed all 158 cases plus 18 guards and is now adopted into the server, with exact BigInteger/BigDecimal parsing, denied external schema loads and bounded/redacted failures. Shared wire models preserve original numeric spelling and missing/null distinctions; typed cooking projections retain immutable versions, stable steps, source grants and recall state. All 149 real-schema fixtures round-trip through shared wire, all 9 documented request examples validate, and cross-layer cooking/save mappings pass. This is data-boundary integration, not actual authentication or persistence. The latest totals are 101 shared JVM tests, 37 server tests, 45 rerun PostgreSQL tests and 92 Node checks. [Implementation and limitations](WIRE_VALIDATION_FOUNDATION.md).

Actual local HTTP responses now undergo outbound schema checks. An existing error-media bug was corrected to canonical `application/problem+json`; invalid generated health data returns a redacted 500. The packaged server JAR changed to SHA-256 `f4623d1280884de29f445a4449139fcd7775129e39f9e49eecc140a74bc38ab8`, passed four actual loopback probes and was explicitly stopped. Test database clusters were also stopped. The Android APK did not change, and no new Android or iOS execution is claimed. Incoming product routes still return 503 without authenticating or applying their bodies.

The latest transport continuation adds a standalone shared mobile HTTP adapter with contract-driven parameter preparation, public/account/guest credential isolation, stale-session checks, bounded streaming acceptance and preserved Problem/ETag/trace/content-type metadata. Failed mutation receipts remain uncertain and retain the original command key; the adapter never retries automatically. A real-engine test exposed ambient Java SOCKS lookup despite an explicit OkHttp direct route; a direct socket factory fixed it. All 50 transport tests, 48 core tests, 55 contract tests and 39 server tests now pass freshly, as do 92 Node regressions and Android transport-library lint. The two new server integration tests exercise actual CIO health and canonical unavailable-guest responses through the owned client. [Implementation, integration order and native gates](TRANSPORT_FOUNDATION.md).

The latest freshly rebuilt server distribution JAR is SHA-256 `89f0646bc0e0fcbe689b06e5dce19660504736d076624bd1c619d6a6f27965d5`; four exact-artifact loopback probes passed and the listener was explicitly stopped. Its production source remains unchanged from the wire-validation continuation; earlier artifact hashes remain historical. The recorded Android APK is unchanged and remains an earlier demo build, not a fresh build of the latest shared-port sources. No Android device, iOS or PostgreSQL rerun is claimed in this transport step. Full response schema binding, authorized ingress, native Darwin buffering/retry verification and actual auth/storage/UI orchestration remain unfinished. M0.07 remains IN_PROGRESS; no whole milestone or production feature is newly accepted.

The latest canonical-validation continuation connects complete current-contract request and response validation to the public mobile transport. Invalid commands stop before secure-store/network access; invalid mutation receipts remain uncertain. Response binding checks exact Problem status and HTTP/body trace consistency. It also fixes server date-time/URI format defects discovered through differential tests and a common newline-anchor defect. An independent 167-case format corpus is pinned separately; Networknt remains the independent structural/numeric oracle for 8,423 mutations, while client/server intentionally share the reviewed format scanner. [Current validation architecture and limitations](CANONICAL_VALIDATION_FOUNDATION.md).

Fresh verification totals are 227 shared/transport JVM tests, 46 server tests, 45 PostgreSQL integration tests and 92 Node regressions; Android transport AAR/lint also pass with zero issues. The exact packaged server JAR is now SHA-256 `daf955dc141c45b39e3ac613ec3632abaf5e6afa7bdaa78c5a318eac59d36a2f`; four actual loopback probes pass. The local server and all fresh test database processes were stopped. Earlier artifact hashes above are historical, and the recorded Android APK is still unchanged and not rebuilt from these latest sources. No new Android device or iOS execution is claimed. Domain authorization, durable client command/state coordination, real auth/social/billing/UI integrations and release gates remain open.

The client-storage continuation adds `:shared:storage`: actual encrypted SQLite transactions, owner-generation fencing, non-recycled CAS revisions, restartable key-erasure cleanup and a native Android Keystore/private-file adapter. The demo UI does not consume this module. Thirty-six real-SQLite JVM tests pass, including corruption, malformed cleanup metadata, concurrency, real SQLITE_FULL via a page quota, rollback/unknown commits and cancellation. Independent review fixed numeric coercion, embedded-NUL length checks, unsafe key cleanup and a second-descriptor POSIX lock hazard. [Architecture and task breakdown](CLIENT_STORAGE_FOUNDATION.md).

The API35 arm64 emulator passed 13 actual native tests plus two distinct-process persistence stages. This is an isolated `com.feedme.storage.test` APK; the FeedMe demo APK/data were not changed. Test fixtures and their exact key namespaces were cleaned afterward. The writer closes its store before exiting: this does not claim hard-kill-during-transaction, physical-device or full cooking-screen recovery. All 263 shared JVM, 46 server, 45 fresh PostgreSQL and 92 Node regressions pass; storage AAR/test APK and zero-issue lint pass. Current hashes, source manifest and attempt logs are in `docs/verification/client-storage/verification.json`. The final artifact/hash audit passed, and the emulator and isolated database test processes were stopped.

The bundled AndroidX wrapper contains SQLite 3.50.1. This foundation refuses WAL and uses DELETE+EXTRA to avoid the documented WAL-reset precondition, but other engine defects remain unpatched and require release review/replacement. The failed-retirement/restart window still needs the credential/session coordinator. Actual command recovery, recipe/cooking repositories, iOS vault/factory and app UI remain unfinished. M1.05 is IN_PROGRESS; no whole milestone or production feature is accepted. A first verification-runner attempt rejected Node's human-readable reporter despite 92 passing tests; the runner now selects TAP explicitly and the failed receipt is retained under `earlier-reporter-attempt/`.

The private-kitchen continuation adds actual canonical saved/cooking repositories and a construction-only `PrivateKitchenSession` seam. Local progress and ordered domain intents commit before tap acknowledgement; only the head binds to the actual server ETag. Exact successful receipts apply atomically without overwriting later local progress. Review fixed mutation recall loss, refresh-after-send receipt stranding, same-version lifecycle regression and incomplete timer edits. Full validation passes 626 Kotlin/server/PG and 92 Node tests, with four zero-issue Android library builds/lint. This completes two bounded M1.05c packages, not their feature/UI or platform acceptance. Server content manifests, explicit permanent-conflict/uncertain-outcome reconciliation, origin/logout coordination, actual native UI/timers and iOS remain open. [Evidence and detailed task breakdown](PRIVATE_KITCHEN_REPOSITORIES.md).

## Reproduce

The latest component/native-store run is reproduced with `node scripts/verify-planned-state-activation.mjs`, using JDK17, the installed Android SDK, `FEEDME_POSTGRES_BIN` and a booted emulator selected by `FEEDME_TEST_DEVICE`. This reruns the full source-bound suite; the original app-demo commands below remain separate.

With JDK 17 and the installed Android SDK configured:

```sh
./gradlew --no-daemon --console=plain :shared:core:jvmTest :apps:android:assembleDebug :apps:android:lintDebug
```

With an already running local Android emulator and Node.js:

```sh
FEEDME_TEST_DEVICE=emulator-5554 node scripts/android-smoke.mjs
```

The smoke runner requires `ANDROID_HOME`, targets only an emulator and installs/relaunches only `com.feedme.development`. It exercises an explicit reset of FeedMe's demo state. The tested emulator was API 35 ARM64 at 1080 × 1920. Screenshots/accessibility trees and the APK hash are recorded alongside the report. This is not multi-device, accessibility, performance or production-security certification.

## Intentionally not connected

- Real authentication, accounts, server authorization and product backend APIs. The new standalone local scaffold implements only public degraded health and is not connected to the native clients.
- Durable product saves, cooking recovery, offline sync and process-death restoration in the app. Separate encrypted storage, command journal and actual saved/cooking repository components are verified, including real SQLite reopen and Android library builds; current demo state is still memory-only. Permanent domain conflicts, identity/lifecycle/retention, native timers and final native timing remain open.
- Real social delivery, circle membership service, photo capture/upload, media sanitization, moderation, blocking enforcement on a server and push notifications.
- Production recipe catalog, reviewed substitutions and nutrition calculations.
- RevenueCat, actual purchases, entitlements, ads and purchase restoration.
- Production analytics, monitoring, privacy/deletion workflows, signing and store release.

Domain tests cover several future-facing rules (including expiry, visibility and fail-closed production behavior), but local tests are not server implementation. No post leaves the device, no account is created and no payment occurs.

The [threat model](THREAT_MODEL.md) and [security acceptance matrix](SECURITY_ACCEPTANCE_MATRIX.md) define 43 test groups and record four unresolved contract/policy points. This completes a design task, not any production security test. Rights after account erasure, deletion receipts, restricted flag changes and export reauthentication must be resolved in the tracked implementation work.

## Release safeguards and next work

Android release variants are disabled. The iOS build phase rejects Release builds. Development identifiers `com.feedme.development` and `com.feedme.development.ios` are placeholders, not approved production identifiers. No account enrollment, signing-team selection, Xcode installation, cloud deployment or store submission was performed.

Next native slice: confirm production identity/providers, implement authentication plus durable private state against the existing API contract, then connect one end-to-end cooking/social journey with server authorization and moderation. Keep the original social identity intact. Separately, install full Xcode and verify the shared framework on an iOS simulator before claiming platform parity. Store-account readiness and the current competition rules remain independent release gates.
