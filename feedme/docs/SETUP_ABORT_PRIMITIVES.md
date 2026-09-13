# Exact setup-abort primitives

13 September 2026. M1.05d.5b.2b.3.6b is **DONE as a bounded component**. This extends [read-only interrupted-setup inspection](INTERRUPTED_SETUP_INSPECTION.md) with two low-level native cleanup operations. It does not implement user confirmation, a composite abort coordinator, startup recovery factories or recovery UI. [Full integration gates](COMPOSITE_SESSION_SETUP.md) remain open; FeedMe's cooking/social scope is unchanged.

## Trust and integration boundary

Only the trusted session owner may invoke these primitives, after independently acknowledging explicit abort intent for the exact original composite setup. Neither a plan, an inspection report nor a matching ABORTED record supplies that confirmation. The future coordinator must preflight every original component, serialize operations, reject a live/newer lease, revalidate control between effects, and complete control only after all required acknowledgements and owned closes.

The storage layer cannot decode or approve provider identity, configuration, session operation IDs or user consent. For a written activation binding, the session owner must derive the full canonical schema2 payload from the original operation, scope, configuration, credential incarnation, work origin and native data target. The data primitive compares these supplied bytes against the authenticated sole stored binding; it is not a public API accepting a user-supplied deletion predicate.

## Work-origin abort

`SessionWorkRegistry.abortOrigin(plan)` requires an inactive process boundary and native authentication of the exact plan against the original work-store identity. It accepts only:

- the exact authenticated prepared predecessor;
- the canonical setup-selected marker for that plan;
- its untouched, empty sealed origin retaining the exact setup provenance;
- its exact canonical setup-aborted marker on retry.

It writes `SetupAborted(originalPlan)` through a genuinely changed ledger CAS and requires the exact write acknowledgement/readback on **every** call, including an already-visible aborted retry. Unknown/failed writes never become success through matching readback alone. It allocates no ID, acquires no lease, calls no admission policy and installs/cancels no OS work.

Ordinary active/retiring origins, consumed setup provenance, nonempty work, foreign/tampered plans, missing capability, stale revisions and competing selection are rejected. Ordinary `retire`/`retireEmpty` cannot consume selected, aborted or untouched sealed setup provenance. Ordinary resume after separately acknowledged publication still consumes seal provenance; subsequent real logout remains on its existing path.

The authenticated aborted marker can be the exact raw predecessor for a later new plan. Planning does not write or reserve anything: an older exact abort may still re-acknowledge the current marker until the later plan selects. That changes the predecessor revision and invalidates the unselected successor. Conversely, selection of the successor consumes the marker and fences the old plan. The single independent pending-control owner must not authorize two competing workflows.

## Private-data abort

`EncryptedStateDatabase.abortPlannedActivation(scope, plan, expectedBinding = null)` is the already-open entry point. Null retains the old strict empty-only behavior. An expected binding of 1–4096 bytes permits only the one reserved, non-tombstoned, schema2 binding with byte-exact authenticated plaintext. The restricted existing-only recovery handle exposes `abortBound(expectedBinding)` over the same implementation; its existing `abort()` remains empty-only.

Inside the same write transaction, the operation authenticates plan/scope, validates the exact owner/predecessor and competing references, checks every row including tombstones, optionally decrypts only the reserved binding, deletes only that exact revision/schema row, consumes the two reserved generations and increments the exact consumed-plan receipt. An absent, unreadable, wrong-schema or mismatching binding never grants nonempty deletion. Other private rows and tombstones always block it.

Only a successfully returned COMMIT permits the second write-locked phase. That phase revalidates the original consumed owner, zero rows and the exact acknowledged abort-receipt revision before deleting only the planned key. Missing keys on an empty owner need not be recreated or tested for usability; missing/unusable keys with a binding prevent proving its plaintext, so the row and resources are preserved. No unrelated key garbage collection occurs.

Every retry changes and commits the receipt again, even if the planned key is already absent and the status remains ABORTED. Binding removal and receipt consumption roll back together on a precommit failure. A lost post-COMMIT application acknowledgement may leave the binding removed and the receipt visible, but the call must stop before key deletion. A subsequent retry obtains a new acknowledgement rather than granting credit to visibility. Key-deletion failure preserves consumed metadata for exact retry.

## Integration safeguards

The read-only inspector can report authenticated ABORTED work only with an existing abort-request flag; it cannot create the flag or execute cleanup. Live selection/retry rejects ABORTED or SEALED work at its selection preflights, before the next native effect. A stored aborted plan never reconstructs verified credentials or permits resuming an old live attempt.

[Task6c confirmed coordination](COMPOSITE_SETUP_ABORT.md) is now DONE bounded: generation-bound proposal, fresh all-component preflight, acknowledged exact abort intent, ordered work → data → credential cleanup, pending retry and final control acknowledgement on already-open borrowed stores. Task6d still needs existing-only startup work recovery without ordinary `resume()`/GC, credential partial-inventory ownership and retained failed-close handles. Task7 still needs integrated process-separation and failure acceptance. API26 credential/recovery, full Xcode/iOS, physical-device/power-loss, public hot-journal and product provider/UI gates remain separate.

## Historical 6b verification

The full [source-bound receipt](verification/setup-abort-primitives/verification.json) passed at **2026-09-13T18:19:25.362Z**: **1,508 Kotlin/server/database tests, 142 Node checks and 231 isolated Android tests**, five freshly built Android libraries and zero-issue lint. Shared Kotlin totals 1,417 (core48, contracts119, transport72, storage/integration444, sync103, kitchen142, session489), plus server46 and isolated PostgreSQL45. Native coverage is storage125/session106. Production JVM compilation and both focused JVM suites also passed.

New coverage comprises 24 common work-abort cases, one inspector guard, two live-selection guards, 20 real-SQLite data-abort cases, 22 regular native work/data-abort cases, six native VFS methods and 20 Node evidence-parser tests. The six VFS methods exercise 12 separate initial/replay synchronization labels, retaining all earlier 27 labels and five controlled process stages. Injected bundled-SQLite VFS errors, application acknowledgement-loss wrappers and controlled reopen remain distinct from physical power loss, hard kill or public hot-journal recovery. Native identity remains synthetic, not provider authentication or UI.

The receipt binds **267 source inputs, 13 artifacts and 142 retained evidence files**. Source aggregate SHA-256: `425538d5ba0be70f50da403d4c261075bdd9ca8f60f431aa5821db427c15822d`. Receipt SHA-256: `c987182d4738ed06b2ac09d7558209921afa654bfae9bb037c4af4e394e8af87`. Root audit verified all 529 recursively referenced size/hash records, the source aggregate and exact current/immutable/last-attempt receipt copies. The owned emulator exited normally, its notification permission remained granted, and no port8789 listener or PostgreSQL PID files remained across 122 retained synthetic clusters. No retained clusters were deleted.

Independent audits matched all 1,508 unique JUnit methods across81 XML files and142 exact TAP pairs to current source declarations and fresh command timestamps, plus231 unique native start/success identities across21 invocations. All39 VFS labels, five process stages,37 original/retained native copy pairs and actual four-ABI ELF bytes matched. The helper is absent from five AARs, the session test APK and the unchanged demo. Storage fixtures were removed; only three expected WorkManager database files remain in the session test app. No discrepancy was found. Existing-hardlink branches remain unexercised because the platform denied creation; exact delivered-notification cancellation passed.

The first full attempt failed on a new test's incorrect expectation that an ABORTED snapshot returns CONFLICT. Two new tests shared that expectation. The specified API returns an empty projection while preserving its authenticated marker. Both tests now assert exact revision, empty projection and unchanged raw marker/files; no production code was changed to make this expectation pass. That [failed attempt](verification/setup-abort-primitives/attempts/2026-09-13T18-05-57.027Z/verification.json) remains retained. A subsequent functional run passed; inaccurate scope descriptions were then corrected and the final full run above binds those corrected sources. In particular, storage compares trusted expected binding bytes; it does not parse session identity or establish consent.

For current sources use `node scripts/verify-startup-recovery-owners.mjs`; [Build status](BUILD_STATUS.md#current-verification) indexes its evidence. The following command reproduced the historical6b snapshot with JDK17/SDK36/NDK28.2.13676358, local PostgreSQL and a dedicated API35 emulator; its fixed inventory must not be run against newer sources:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home \
ANDROID_HOME=/Users/LOCAL_USER/Library/Android/sdk \
FEEDME_POSTGRES_BIN=/opt/homebrew/opt/postgresql@15/bin \
FEEDME_TEST_DEVICE=emulator-5554 \
node scripts/verify-setup-abort-primitives.mjs
```

The preceding [inspection receipt](verification/interrupted-setup-inspection/verification.json) is historical for these changed sources. The demo and public GitHub snapshot remain unchanged. No whole feature, milestone, composite recovery workflow or release gate is accepted by these primitive tests.
