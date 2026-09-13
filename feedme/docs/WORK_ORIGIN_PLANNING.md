# Lease-free work-origin planning

13 September 2026. **DONE — bounded component**, verified at **2026-09-13T15:54:49.066Z**. Task M1.05d.5b.2b.3.2 within [composite session setup](COMPOSITE_SESSION_SETUP.md). This component does not complete session setup, enable login or change FeedMe's cooking/social scope.

Current verification is indexed in [Build status](BUILD_STATUS.md#current-verification). The subsequent [composite journal](COMPOSITE_SETUP_JOURNAL.md) and [ordered live selection coordinator](LIVE_SESSION_SETUP.md) are DONE bounded. The journal validates structure, not native MACs or trusted configuration approval; the coordinator uses externally verified credentials and keeps the exact journal pending after selection, without a lease or runtime replacement. Three later composite tasks remain open; the counts, hashes and artifacts below retain this work-origin component's historical snapshot.

## Production contract

The trusted session coordinator prepares a work origin before any active session lease exists. `SessionWorkRegistry.planOrigin(scope, expectedIdleRevision)` reads the existing independent work ledger, requires its exact Idle revision, captures one canonical UUID, obtains an install-bound proof and rechecks the exact predecessor. Planning does not reserve the ID, write a record, create or delete a key, activate/clear a session, consult admission/execution policies or call native scheduling/cancellation ports. The scope must already come from the coordinator's verified identity; this method is not an identity verifier.

`SessionWorkOriginPlan` is opaque and redacted. `copyForStorage()` returns detached bytes; `fromStorage()` performs strict canonical structural decoding, not authentication. Persist those exact bytes only in the independent encrypted setup journal before selection. The [composite journal format/control gates](COMPOSITE_SETUP_JOURNAL.md) and [selection-only live coordinator](LIVE_SESSION_SETUP.md) implement that ordering in b.2b.3.3–4. They are not wired into today's runtime; task b.2b.3.5 must supply durable binding, work sealing and late publication before replacing its existing create path.

`inspectOrigin(plan)` authenticates the exact plan and returns only PREPARED or SELECTED. It cannot publish a lease, access handle, binding or ticket. Its final read bracket rejects metadata changed during a suspending proof check. An observation is not a durability acknowledgement.

`selectOrigin(plan)` accepts only the exact authenticated Idle predecessor or the exact setup-selected marker containing this same plan. It returns Unit, not a binding. Every attempt—including already-selected replay—performs a changed compare-and-set, requires that invocation's successful exact revision/payload receipt, and reads back the exact committed result. Failure, unknown outcome or cancellation never becomes success by finding matching bytes afterward. A retry uses the retained original plan and freshly acknowledges the currently selected revision; it never allocates another ID.

## Distinct setup state

The ledger adds one strict state: `setup-selected`, containing only the canonical plan encoded as lowercase hex. It is deliberately different from an empty ordinary active origin. Otherwise an old setup plan could be replayed after work had been installed and then cancelled back to an empty origin.

Ordinary snapshot returns CONFLICT for setup-selected instead of disguising it as Idle. Ordinary resume, install and callback execution cannot use it. Both ordinary retirement paths reject it without writing Idle or cancelling work; a rejected ordinary retirement removes only a fence newly introduced by that rejected call. Reopening the registry validates raw ledger structure so exact setup recovery remains available even though an ordinary snapshot is unavailable.

Later setup sealing must explicitly convert this marker to the normal active origin only after the composite protocol's checks. Explicit confirmed setup abort must likewise consume only its exact plan. Those capabilities are intentionally not implemented here. Runtime diagnostics currently report this state as unavailable through their ordinary snapshot path; a redacted dedicated setup diagnostic belongs to b.2b.3.6.

## Native proof and boundaries

The optional core `WorkOriginPlanAuthentication` capability has three fixed operations: sign an exact predecessor, verify a plan against the current original work owner, and additionally verify the exact current predecessor. A plain `SessionControlStore` without this capability returns NOT_CONFIGURED; there is no weaker fallback.

`EncryptedSessionWorkStore` implements it using the already-open encrypted database and the wrapper's captured original owner handle. Every operation verifies that handle belongs to this exact database, that its owner generation/key is still current, and that the work record still exists with valid authenticated contents. It runs one read transaction without ordinary resume, activation, initialization or key garbage collection.

The 64-byte proof comprises two domain-separated HMACs: one binds the original raw record bytes and revision; the other binds that predecessor MAC and the unsigned canonical proposal. Both include the existing install index key, work owner tag/generation/key ID and work record tag. Domains are fixed internally, not caller-controlled. Selected replay verifies the proposal proof without requiring the old raw payload to remain selected. Only the registry's exact state checks grant replay eligibility.

This prevents copying a plan to another native work store at the same Idle revision, rebinding it to a replacement work owner, altering its scope/origin/proof, or treating a newer ordinary/retiring/nonempty origin as setup. It is not protection against a fully privileged attacker rolling back an entire installation and its keys.

## Format and exhaustion

Canonical JSON version 1 is bounded to 4,096 bytes with exact root/scope keys, a non-DEMO bounded Unicode scope, canonical UUID, positive integer revision and exactly 128 lowercase hexadecimal proof characters. Duplicate/unknown keys, malformed Unicode, numeric aliases, overflow and noncanonical stored bytes are rejected. The setup-selected wrapper accepts no normal origin fields or entries.

Plans require revision 1 through Long.MAX_VALUE−2. Each selection/replay also requires the current revision no higher than Long.MAX_VALUE−2, retaining a revision for a later seal/abort. Revisions never wrap or reset. Exhaustion after an uncertain final acknowledgement fails closed; no new owner/ID or repair authority is invented.

## Verification and integration limits

For the current tree, use `node scripts/verify-live-session-setup.mjs` with the existing JDK17, Android SDK, local PostgreSQL binaries and a booted local emulator; [Build status](BUILD_STATUS.md#current-verification) indexes the current receipt. The historical work-origin runner was `node scripts/verify-work-origin-planning.mjs`; its [source-bound receipt](verification/work-origin-planning/verification.json) passed, with retained attempt `verification/work-origin-planning/attempts/2026-09-13T15-53-12.766Z/`. The following totals belong to that snapshot. Previous [independent-ledger evidence](SESSION_LEDGER_ACKNOWLEDGEMENTS.md) remains historical and is not overwritten.

Test-fixture fidelity correction first verified in the [composite-journal run](COMPOSITE_SETUP_JOURNAL.md): `AndroidWorkOriginPlanFixture.fileSnapshot()` now uses stat to assert that `state.lock` is empty, without opening another descriptor. Its previous `readBytes()` on that lock could release process-wide POSIX locks when the extra descriptor closed. Other regular-file digest checks remain unchanged. All prior work-origin coverage—14 regular native tests, three VFS tests and three process stages—was rerun with the corrected helper and remains included in the current full runner. This is a test-helper correction, not a production lock implementation fix or a new hard-kill/power-loss claim; the old receipt remains historical.

Common tests exercise strict codecs and the registry protocol using clearly synthetic proof fakes. JVM tests exercise actual encrypted bundled SQLite with a test vault and read-only SQL/key/byte assertions. Android tests exercise Android Keystore, real encrypted work stores, exact setup recovery and injected journal/database/directory sync barriers. Process-separated tests use controlled closure and distinct instrumentation processes, not a hard kill or physical power loss.

| Check | Verified result |
| --- | --- |
| Shared Kotlin | 1,113 passed: 48 core, 119 contracts, 72 transport, 339 storage/integration, 103 sync, 142 kitchen, 290 session; zero failures/errors/skips |
| Server / isolated PostgreSQL | 46 + 45 passed; total Kotlin/server/database 1,204 |
| Node | 112 passed, including ten new strict work-origin evidence-parser regressions |
| Native Android | 151 passed on API35 arm64: storage94 plus session57; no skips |
| New component tests | 22 codec + 20 registry + 16 real-SQLite proof tests; 14 regular native + 3 VFS tests covering six barriers + 3 process stages |
| Builds / lint | Five fresh Android AARs, five zero-issue lint reports and both test APKs rebuilt |
| Evidence | 228 source inputs, 13 artifacts, 119 retained evidence files |
| Test-helper packaging | Four ABI-specific ELF helpers only in storage test APK; absent from five AARs, session test APK and unchanged historical demo |

Storage94 comprises regular71, private-data VFS6, independent-ledger VFS9, work-origin VFS3, historical persistence stages2 and work-origin process stages3. Session57 comprises regular47 and integration10. All six new VFS labels are tied to successful exact native test identities and actual extended codes1034/1290, with zero downstream effects. The independent control fixture retains the opaque plan across the three new process stages; this is a test fixture, not the production composite journal. Existing delivered-notification cancellation passed. The platform denied hardlink construction with EACCES13, so those branches remain unexercised.

Source-manifest SHA-256: `64d475fb360d3124f6117d02b3ffeaee1c1cd1c612831b134e74e59ed3c108f4`.

Independent read-only audits of that snapshot rehashed every listed source/artifact/evidence file, checked the immutable receipt copy and native APK metadata/copies, and matched 1,204 unique JUnit identities across 68 XML files to then-current declared methods, 112 paired TAP successes and 151 exact source-bound native pairs. No discrepancies were found. JUnit/TAP identity recounting is a separate audit beyond the runner's aggregate checks; native identity matching is also enforced by the runner itself.

Receipt SHA-256: `f859c9ceecfbc96ae28505b283c0de8324a7d5161507214ebf7e3e0e2d904a4e`.

Storage test APK: 8,672,884 bytes, SHA-256 `92d28f81703f863e52a7435e9abf6ec9c8e940d4de0471196809436a8ef048d9`. Session test APK: 8,574,327 bytes, SHA-256 `a292a00e5e45ae9dcbc3f865224de0ec12e43954ee218d456ab29ea774df96a0`. The receipt contains all other artifact hashes.

The test-owned emulator was stopped after verification; native fixtures were cleaned and notification permission remained granted/unchanged. No listener remained on test port8789 and none of the 94 retained synthetic PostgreSQL clusters had a postmaster PID file. No user app data was reset, and no new repository publication occurred.

No first-factory repair, public hot-journal recovery, native app/domain scheduling, provider login, UI recovery, production cross-store publication, API26 credential recovery, physical-device testing, iOS compilation or release acceptance is implied. The historical demo and public GitHub snapshot are unchanged. After the bounded journal and selection tasks, three composite tasks remain: durable binding/work seal/late publication and runtime replacement, startup/confirmed abort, and integrated failure acceptance. The data component's selected replay is not a fresh durability acknowledgement; selection-only success cannot publish access.
