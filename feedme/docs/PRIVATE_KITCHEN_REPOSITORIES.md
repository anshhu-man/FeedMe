# Private kitchen repositories — M1.05c

13 September 2026. `:shared:kitchen` adds actual owner-scoped cooking and saved-recipe adapters over the encrypted store and canonical transport. This is a component integration slice, **not a shipped app journey**. The demo still uses memory-only fixtures; authentication, authorized server routes, native session composition and UI wiring remain separate gates. All 54 features and 98 screens remain in scope.

## Small delivery packages

| Package | State | Acceptance |
| --- | --- | --- |
| M1.05c.1 | DONE | Bounded component: canonical owned saved cache, exact payloads, atomic index, personal/retired access, recall and attribution fences; 31 saved tests plus real-SQLite reopen/corruption/recall evidence |
| M1.05c.2 | DONE | Bounded component: owned materialized cooking pin, versioned progress, durable actions, head-only journal materialization and receipt CAS; 26 cooking tests plus actual SQLite ordered recovery and recall observer evidence |
| M1.05c.3 | TODO | Server manifests and explicit lifecycle/redaction/recall contract, permanent-conflict and uncertain-outcome reconciliation, authoritative new-save/delete/collection workflows |
| M1.05c.4 | TODO | Real session/credential/native store composition, application worker, cooking/save UI and native timer/process recovery; no demo promotion before these pass |

M1.05c remains IN_PROGRESS. These packages do not accept F12, F13, F14, F19 or F30 as production features. M1.05d/e, M1.06b/c and the original release/security gates still apply.

## How a production composition will work

The identity coordinator must first resolve an actual account or bounded guest, then resume its encrypted-store generation. One serialized owner dispatcher and the same `SessionBoundary`/lease are shared by transport, store adapters, repositories and command queue. Neither repository creates credentials, activates an identity, opens an arbitrary path, or turns a DEMO scope into an account.

Use `PrivateKitchenSession` to wire both the cooking execution gate and the validated-reply observer against the same owner store. It provides the cooking repository, saved repository and journal; non-cooking commands remain NOT_CONFIGURED. The generic journal's no-op observer is not sufficient for cooking. A custom composition must route to both `CookingRepository.executionDecision` and `observeReply`; other modules must provide their own gate and outcome interpretation. A non-secret random `originBinding` comes from the future durable session/device coordinator; it is not a token or an HTTP header. An origin change makes an existing pin read-only until explicit reauthorization/rebinding, including a pin with no pending actions. That lifecycle remains M1.05d work; do not silently transfer old intents. Never use a default `Ready` gate in the app.

The repositories use only canonical owned `getCookSession`, `getPlan` and `getSavedRecipe` fetches. Full operation/status/media/schema binding runs before accepting any response. A returned object's identity must match the requested resource and pinned relationships. A lease is checked before and after every transport/store suspension. This rejects stale client results; only the actual server can authorize the request.

## Cooking: tap, restart, sync

1. Download the owned session and its owned **materialized** plan. Keep the complete plan recipe snapshot, including reviewed substitutions already selected; do not replace it with a base catalog recipe. Store exact plan/server bytes, local progress, integrity metadata and the bounded session index in one CAS batch.
2. A tap carries the displayed local revision and a new command UUID. Validate stable step/timer references and terminal-state rules. Save updated local progress, the immutable domain action, its exact prospective body and an identity tombstone atomically. Only then may UI acknowledge the tap. This operation sends no HTTP request.
3. Offline edits form an ordered list, bounded to 64 per session. Only the head is materialized into the generic journal. PATCH binds the **actual last acknowledged ETag**; no guessed future version, stale precondition on every offline tap, changed idempotency key or body rebase is permitted. The journal action and the repository's materialized marker commit together.
4. The worker uses targeted `dispatchAutomatic(lease, commandId)`. This is not user confirmation, cannot select an unrelated command, and retains the journal's lane/dependency/backoff/retention/preflight rules. Actual server authorization still runs on dispatch.
5. A successful receipt is not yet an applied domain result. Validate the exact session/plan, advancing server version, originating sequence, status and patch fields. Persist remote state and acknowledge the journal atomically. If later local edits exist, preserve their newer progress; materialize the next head using the new real ETag.
6. A divergent refresh or unexpected successful receipt preserves a separate canonical remote candidate and holds the session in CONFLICT. It never silently overwrites local progress or acknowledges an unproven command. An exact staged candidate matching the separately validated successful head receipt can be cleared atomically by explicit receipt application; this prevents refresh-after-send from stranding a proven receipt. A genuinely newer/different candidate remains held for explicit conflict resolution. A later lower candidate cannot erase a newer conflict.

Completing the last step does not complete the session. `Complete` uses canonical `Completion`, preserves the caller's `makeAgain` Boolean and optional client timestamp, and never invents a saved-recipe ID or issues an extra save. Completed/abandoned progress cannot reopen through a normal edit. A response claiming a different terminal result is retained for reconciliation. Missing ETag permits read-only caching but cannot produce a PATCH.

Timer records retain their real stable IDs, step references, duration, wall deadline or paused remainder. Running timers without a deadline and paused timers without a remainder are incomplete; new edits cannot persist that state. The native scheduler, monotonic anchors, clock-change recovery and notification permission handling are **not** implemented here. Timers never advance a step or certify food safety.

## Saved recipes and recall behavior

`SavedRecipeRepository.download/read/search` operates on existing owned copies. A prior authorized private copy survives ordinary source-story expiry, future grant revocation and a paid downgrade; basic local reading does not query the expired grant or a purchase service. This does not authorize a new community save, redistribution, source media or deleted comments. Personal and retired copies remain readable; professional review is not fabricated.

Every saved body and metadata/index update is one CAS transaction. Search is local title search only, bounded to 512 indexed copies and a 100-code-point query. Missing/damaged indexed records are explicit integrity-failure entries, not silently dropped. Exact raw bytes are retained separately from local metadata. Immutable content cannot change under an existing copy ID, even with a higher aggregate version. Source labels/IDs may be removed at a newer version; removed attribution cannot reappear from a later stale response. Arbitrary replacement/restoration needs an explicit server contract.

Same-resource recalls are learned before ordinary ETag/version/immutability rejection. Validated `RECIPE_RECALLED` Problems can fence an already known pin; a new unknown saved ID fences only that saved identity, never invents a recipe-version identity. The queue's required cooking reply observer also fences a mutation recall before normal outcome recording, outside its mutex; observer failure leaves the original IN_FLIGHT intent recoverable and never changes a rejection into success. Only fully bound responses reach it, and a safe RECIPE_RECALLED issue is retained without raw Problem text. Both repositories consult shared one-way recipe-version markers. A saved recall hides the recipe projection; cooking exposes a domain snapshot marked RECALLED that application UI must refuse to render as cookable.

Recall markers are separate from cache CAS, installed in memory before durable persistence. A failed body refresh cannot undo a learned recall. The marker is stored when possible and rechecked on local access. **This is not complete recall delivery:** an offline device cannot instantly learn new server state; failed marker persistence followed by process death still requires the session/recovery coordinator. Process memory fences currently retain scoped identities until process exit and need lifecycle/erasure integration. No automatic retry, broad wipe or automatic recall clearance is supplied.

## Storage and integrity contract

Collections are separate `feedme.kitchen.saved.*`, `feedme.kitchen.cook.*` and `feedme.kitchen.recall`. Payloads use store schema version 1 and strict bounded metadata. Cooking index is capped at 64 pins, pending actions at 64 per pin; existing encrypted-store limits remain 1 MiB/record, 4 MiB/transaction and 64 mutations. Durable one-byte used-action tombstones prevent accidental local command-ID reuse. The queue's terminal lookup also prevents reusing a known journal identity. No credentials or raw server error text are written here.

SHA-256 checks exact locally retained bodies. JVM/Android use the platform digest provider; iOS source uses CommonCrypto/CoreCrypto and remains uncompiled without full Xcode. This is a local integrity check protected by the encrypted-store envelope, **not** a server-issued content manifest or offline authorization lease. Digests do not prove recipe safety.

ETags remain original quoted decimal strings and must numerically equal the outer response `version`, including valid leading-zero spellings. Exact decimal/exponent versions are compared without `Double`. A missing ETag is never synthesized. Local device sequence is preserved exactly; new outbound edits require a non-overflowing `Long`, consistent with the existing command journal's explicit local bound.

## Unresolved canonical and release gates

- F13/F19 promise a server content hash, but current canonical DTOs have no content hash, signed manifest, offline lease, explicit owner field or `authorizedAt`. Owned operation + bound lease is the current provenance; the missing server guarantee must be designed and approved, not invented in client JSON.
- The exact immutable/lifecycle mask and attribution restoration discriminator are underspecified. The adapter is conservative: keep instructions/quantities/rights immutable, allow only declared lifecycle metadata and removal-only provenance. An approved server contract is needed for more transformations.
- F12's reviewed guided-cooking language and personal-copy use need a consistent policy. Personal copies are readable; this adapter labels them PERSONAL_UNREVIEWED and does not enable the reviewed guided editor. Retired prior pins remain usable, not eligible for new matching.
- `Completion.makeAgain` and the separate durable-save workflow need a single authoritative server effect/receipt contract. The current adapter preserves the request and accepts only the actual CookSession response; no save completion is fabricated.
- Resolve permanent 409/412 conflicts, expired/uncertain command outcomes, canonical recall delivery, remote list/save/delete/collections and cross-device origin/sequence semantics. Merely refreshing a resource is not proof that a particular command committed.
- Complete session/logout/credential/recall erasure, ledger/index quotas and compaction, native timers, app-kill/restore tests, iOS vault/factory/linkage, patched SQLite review and final transport timing. Real provider/backend/UI wiring remains unavailable.

## Verification

This section records the historical kitchen-source snapshot. The subsequent [session-retirement receipt](LOCAL_SESSION_RETIREMENT.md) reruns the kitchen/storage/sync regressions alongside new exact-retirement/control components and fresh isolated Android storage tests. It does not add actual auth, native cooking UI or origin-rebind integration.

The [source-bound receipt](verification/kitchen-repositories/verification.json), completed at 2026-09-13T09:05:41.571Z, passes **626 Kotlin/server/database tests and 92 Node checks**, with no failures, errors or skips. All storage/transport/sync/kitchen Android library builds and lint pass with zero lint issues. Source manifest SHA-256: `f5893cda21eb0c662cda5281f9b824ab8d08efd72efa469c704691531b3f79fe` (135 source inputs). The final audit independently rehashed all 135 inputs, nine artifacts and 52 retained evidence files with no mismatch.

- 142 kitchen tests: 31 saved, 26 cooking, 29 progress/metadata codecs, 31 owner/context, 20 exact-number/lifecycle validation, five native-provider digest vectors on JVM.
- 51 actual encrypted-SQLite JVM tests: previous 42 plus nine repository tests. These cover exact saved reopen, recall across a new boundary, corrupted authenticated body/progress, owner isolation, stale local CAS, two offline cooking actions with sequential ETags, huge exact counters that remain read-only, and mutation recall hiding another saved pin before any cooking read.
- 103 sync tests: previous 84 plus 19 targeted-dispatch/lookup/reply-observer cases. Invalid responses never reach the observer; failure/cancellation retains recoverable intent.
- 48 core, 119 contracts, 72 transport, 46 server and 45 fresh isolated PostgreSQL tests pass again. The 92 Node checks include contract generation, backup policy and tracker tests.

Reproduce with the installed JDK17, SDK36 and local PostgreSQL binaries configured as `JAVA_HOME`, `ANDROID_HOME` and `FEEDME_POSTGRES_BIN`:

```sh
node scripts/verify-kitchen-repositories.mjs
```

Tests distinguish detached atomic-CAS fakes from actual encrypted SQLite close/reopen. JVM component tests and Android library assembly/lint are not Android screen recovery, hard-kill, iOS runtime or store acceptance. No emulator was started in this continuation. A final read-only audit found no postmaster PID files across 38 retained synthetic test-cluster directories and no listener on the standalone local-test service port. The historical demo APK remains unchanged (`bf6dd07e31a4fd49b798672ba82edcee7f958b9d5c12ae0d7a89491d20ff3805`); no account, cloud service, payment, upload or release action was performed.
