# Durable session setup and late access publication

13 September 2026. **DONE — bounded native runtime component**, verified at **2026-09-13T17:13:06.803Z**. Task M1.05d.5b.2b.3.5 in [composite session setup](COMPOSITE_SESSION_SETUP.md). This replaces the native runtime's old setup composition; it does not implement an identity provider, bootstrap endpoint or product sign-in screen.

Subsequent [already-open inspection](INTERRUPTED_SETUP_INSPECTION.md), [exact abort primitives](SETUP_ABORT_PRIMITIVES.md) and [confirmed composite abort](COMPOSITE_SETUP_ABORT.md) are DONE bounded. Startup factories/retained close ownership and integrated interruption acceptance remain open. [Build status](BUILD_STATUS.md#current-verification) indexes current sources; counts and hashes in this publication document describe its historical checkpoint.

## Production contract

The application supplies one trusted session owner, its identity dispatcher, distinct native control/work stores, an encrypted private database, a planned credential store and an externally approved `NativeSessionVerifier`. The verifier must validate provider challenges and FeedMe owner/bootstrap mapping under immutable configuration. No default accepts UI-supplied credentials as proof. Stores must not be independently mutated outside that owner.

The live path is:

1. Verify identity, then prepare and acknowledge one `PendingSetup` containing the exact credential, data and work plans.
2. Select those resources in order without a lease. The earlier [selection-only component](LIVE_SESSION_SETUP.md) retains its separate `begin`/`retry` contract.
3. Authenticate the selected data plan and inspect only an empty owner or its sole exact reserved binding. Create a version-2 activation payload binding setup operation UUID, credential incarnation, work origin, data retirement identity, owner and immutable configuration.
4. Write the reserved binding at a genuinely new record revision, reseal it with that revision in its encryption AAD, and require a successful real SQLite COMMIT followed by exact readback. A matching visible record after a failed or unknown commit is not success.
5. Seal the exact setup-selected work origin with a changed acknowledged CAS. Preserve authenticated setup provenance while it remains unused, so an interrupted seal can receive another fresh acknowledgement. Ordinary resume or any other lifecycle write consumes that provenance; empty work later is not permission to reseal it.
6. Revalidate all component identities and the independent control record. Write `Complete(originalSetupOperationId)` with a changed acknowledgement and exact readback; revalidate again. The coordinator returns an internal detached receipt, not a lease, private store or credential view.
7. The runtime checks the receipt against current credentials, exact private target/binding, sealed work and the same control revision. Only then does it activate its process lease, consume work provenance through ordinary resume, capture retirement state and publish protected access. Installation/execution remains behind the runtime's admission and domain policy.

The fixed record key remains `session-activation/binding-v1`; the key is a reserved namespace, not the payload's version. New records use schema 2 and require `setupOperationId`. Legacy schema-1 records retain their exact wire representation and a separately verified restore path. A schema-2 restore requires matching `Complete.operationId`; matching owner names alone never join stores.

## Retry, cancellation and restore

`retryCreate()` accepts no identity or plan arguments and never calls the provider. It is available only while the original verified live attempt is retained after a non-cancellation failure. Incomplete selection reuses all original plans. Once binding begins, completion retries inspect the sole exact binding instead of pretending the owner is empty, then perform fresh changed binding, work and control acknowledgements. Foreign payloads, extra rows, tombstones, replaced control, refreshed credentials, consumed work provenance and newer native owners fail closed.

An observed `Complete` after a lost final acknowledgement is not enough. The same live attempt must still find its exact unused native resources and obtain new acknowledgements. After ordinary work resume starts, setup authority is dropped; an interrupted publication follows explicit recovery and verified restore, not resealing already-used work.

Cancellation, explicit cancel, close and dispatcher-return cancellation invalidate retained live credentials without erasing selected resources or inferring an abort request. A late callback cannot continue to another setup effect. Runtime cleanup clears only the lease owned by that composition, never a newer external lease. A caller cancelled while queued behind another composition does not own that other attempt.

On restart, persisted plans cannot reconstruct live verified credentials. Pending setup remains repair-only. When exact completed state exists, restore reads the binding without GC, verifies identity, obtains a fresh changed control acknowledgement, checks the exact data target/binding without writes or GC, and only then grants a lease. Later ordinary domain records are allowed during restore; setup binding creation/replay requires a sole reserved row.

## Evidence and boundaries

For current sources use `node scripts/verify-startup-recovery-owners.mjs` in the established JDK17/Android SDK/NDK/local PostgreSQL/emulator environment. This historical publication checkpoint used `node scripts/verify-session-setup-publication.mjs`; its [passing receipt](verification/session-setup-publication/verification.json) retains the immutable attempt `verification/session-setup-publication/attempts/2026-09-13T17-11-01.355Z/`. Do not run a historical fixed-count verifier against newer source inventory.

| Check | Verified result |
| --- | --- |
| Shared Kotlin | 1,298 passed: core48, contracts119, transport72, storage/integration396, sync103, kitchen142, session418 |
| Server / isolated PostgreSQL | 46 + 45 passed; total Kotlin/server/database1,389 |
| Node | 122 passed |
| Native Android | 182 passed on API35 arm64: storage97 and session85 |
| Android artifacts | Five fresh AARs, zero-issue lint, both rebuilt test APKs; historical demo unchanged |
| Retained evidence | 249 source inputs, 13 artifacts, 132 evidence files; zero failures/errors/skips |

New coverage comprises 25 work-seal, 34 completion-protocol, eight activation-v2, 18 real-SQLite binding and eight additional runtime JVM tests; three native binding-VFS methods, nine native runtime-publication methods and one additional runtime interruption method; and ten evidence-parser Node tests. Existing legacy credential recovery and selection-only proof remains covered separately.

Source-manifest SHA-256: `8d455b8de18adcc527904cef4e8bb4fd4281c55401751cc476550b145b1ee650`. Receipt SHA-256: `c707ee0151521cd4a2eba4a33163a4d62a2bf8bba55e87255eec784475d80c12`.

The storage test APK is 8,738,420 bytes (`0f65ebf42deb164f28535a59726f4c77ec9b84d1df767df4007b55b2dac09f08`); the session test APK is 8,721,783 bytes (`bdd898d6a75fe7f08def4837d591c538299ea8752201a8fb73d5950620b22c36`). All four test-helper ABIs remain confined to the storage test APK, absent from the five libraries, session test APK and unchanged historical demo. Existing-hardlink branches remain platform-denied and unexercised; exact delivered-notification cancellation passed again.

Common protocol fixtures test ordering and malformed/lost acknowledgements; real JVM SQLite tests exercise exact binding and replay. Android tests use real native owners. New binding VFS tests inject SQLite journal, database and directory sync failures for both initial writes and replay; they are distinct from session wrappers that lose an application response after a successful native call. Neither is proof of physical power loss or public-factory hot-journal recovery.

All 27 VFS labels (21 retained plus six new binding initial/replay labels) and five controlled process stages passed. The new binding labels record extended SQLite errors1034/1290, no successful receipt and exact delegated-unlink counts0/1; an exact fresh changed revision is required on successful retry. The component suites do not make this a process-kill or whole-app recovery test.

Independent source/JUnit/TAP/hash audit matched 1,389 unique executed identities in 77 XML files and 122 paired TAP identities against current source; all 431 recursively referenced file hashes and the current/last-attempt/immutable receipt copies matched. Native fixture directories were checked clean. The task-owned emulator exited successfully; notification permission remained granted. No port8789 listener or PID files remained across 106 retained synthetic PostgreSQL clusters. Clusters and historical evidence were retained, not deleted.

The separate native audit matched all 182 exact source-method start/success pairs across 15 invocations, all 27 label-to-method mappings and five process stages. It rehashed 31 native source/copy pairs and 89 native evidence/artifact paths and checked actual APK metadata and four ELF ABI headers/bytes against stripped and retained copies. All seven excluded archives contained no test helper. No material discrepancy was found.

Confirmed exact composite abort/startup and the integrated process-separation failure matrix remain tasks 6 and 7. Native credential poisoning and partial-inventory ordinary-open refusal are not bypassed. Approved provider/bootstrap/configuration, first-factory/close/journal recovery, physical devices, API26 credentials, full Xcode/iOS, product screens and domain behavior, content/safety/moderation, billing and release acceptance remain open. The 54-feature/98-screen FeedMe scope, historical demo and public GitHub snapshot are unchanged; no deployment or publication is authorized by this component.
