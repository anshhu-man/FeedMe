# Durable client commands — M1.06 / M0.07d

13 September 2026. `:shared:sync` persists and recovers immutable commands using the actual encrypted-store port and validated transport contract. The subsequent [private kitchen repositories](PRIVATE_KITCHEN_REPOSITORIES.md) now integrate owned cooking pins, ordered domain actions and exact receipt application. It is **not wired into the demo app, real login or production HTTP handlers**. Android library compilation/lint passes; no command-recovery Android device or iOS runtime proof is claimed.

The original 54 features, 98 screens and 201 operations remain intact. This foundation supports F12 guided cooking and F19 private saves, with explicit extension points for the other retained features. It does not accept any full feature package or grant offline permission merely because an API operation is idempotent. [Canonical source audit and feature exceptions](CLIENT_COMMAND_POLICY_NOTES.md).

## Small delivery packages

| Package | State | Verified work / remaining acceptance |
| --- | --- | --- |
| M1.06a | DONE | Pure full-contract validation; exact body/key/path/query/ETag/origin; discoverable owner-bound index; domain draft and command in one CAS batch before HTTP; real encrypted-SQLite reopen and rollback tests |
| M1.06b | IN_PROGRESS | Recovery, dependencies, cooking sequence watermark, bounded attempts/backoff, confirmation, transient resumption and receipt CAS implemented. Actual domain conflict/reconciliation adapters, retained-ledger quotas/compaction and native timing/crash proof remain |
| M1.06c | TODO | Wire the verified identity/origin coordinator, domain repositories, actual transport/authorized server effects, application worker and UI. Two-device terminal-state and real offline/background/restart acceptance required |

M1.06b's remaining tasks are deliberately small:

- Implement F12 conflict resolution against the current owned pinned cook; preserve completed state, device identity and both local/server versions. Do not automatically replace the original ETag or renumber a queued request.
- Implement F19 save-result reconciliation and source/rights/recall checks. A generic 2xx schema is not proof that a cached recipe may be retained.
- Add authoritative resolution for permanent rejection/unknown or expired outcomes, including safe lane release and user-visible choices. There is no generic receipt-query endpoint; do not invent one or turn an uncertain request into a new key.
- Define and implement terminal-ledger/sequence-watermark retention, quotas, compaction, dependency-safe cleanup and owner-erasure behavior. The current 128 limit bounds **pending** commands, not lifetime stored records.
- Close the final transport-suspension timing window, then verify native scheduler/background, cancellation, clock, hard-kill and two-device behavior. Couple this with M1.05d's durable logout/origin lifecycle and M1.05e's patched SQLite review.

No narrowing of the full release scope, provider choice, permanent identifier or publication is inferred.

## Integration contract

Construct `DurableCommandQueue` only after the application session coordinator resolves the real account/guest and resumes its current-generation `PrivateStateStore`. Supply that scope, the same `SessionBoundary` and serialized dispatcher used by transport, an epoch clock, a required `CommandExecutionGate`, and `AccountTransport`. DEMO is rejected. The module depends on core ports, contracts and transport validation, not on a native database implementation; storage's dependency on sync is **JVM-test only**.

The execution gate has no permissive default. Its real adapter must verify the immutable `originBinding`, current identity, owned pin/source permissions, recalls and operation-specific prerequisites. `originBinding` is a non-secret UUID reference owned by the identity coordinator, not a bearer/refresh/guest token, installation ID or fabricated `X-Device-Session`. Retain it for ordinary credential refresh; rotate it for a new native device/session bootstrap. Transport reads the real current credentials and registered device header. This identity coordinator remains M1.05d work.

All queue methods check current scope/lease before and after store suspensions; dispatch also fences after gate/transport. Store generation checks independently reject retired handles. Neither mechanism undoes a committed server action. Application logout must clear the boundary, durably retire/erase the owner and finish credential/timer cleanup; adding this journal alone does not close that gap. The subsequent [local retirement slice](LOCAL_SESSION_RETIREMENT.md) implements key-first exact erasure and an independent durable control ledger; actual credential/work adapters, verified identity composition and first-barrier crash recovery remain required.

| API | Caller obligation / effect |
| --- | --- |
| `enqueue(lease, intent, draftChanges)` | Provide a new logical UUID whose exact value is the call's idempotency key; validate and persist local changes plus discoverable intent atomically. No HTTP |
| `pending(lease)` | Return bounded redacted views, never private request/credential text; local phase is not remote success |
| `command(lease, id)` | Read one exact identity, including a terminal tombstone, to reject accidental reuse; no HTTP |
| `recoverInterrupted(lease)` | Recover persisted IN_FLIGHT attempts not owned by a live process claim; never send; keep original request/key and age |
| `dispatchNext(lease)` | At most one eligible automatic command; new feature preflight, durable claim, one transport invocation |
| `dispatchAutomatic(lease, id)` | Target only this automatic intention; no fallback to another ID or implication of user confirmation; all existing lane/age/gate rules apply |
| `dispatchConfirmed(lease, id)` | Fresh explicit user confirmation for that exact intention; still respects dependencies, backoff, clock/age and conflicts |
| `resumeAfterResolution(lease, id, revision)` | Re-enable a repaired auth/config/domain-prerequisite wait with exact CAS. No HTTP, altered request, reset age or bypass of the next execution gate. Manual actions still need a new confirmation |
| `receipt(lease, id)` | Read/revalidate the persisted canonical 2xx response, with its local revision, for the domain adapter |
| `applyReceipt(lease, id, revision, domainChanges)` | Domain adapter has interpreted this exact receipt. Atomically apply domain changes and acknowledge/remove the pending command; stale/conflicting CAS leaves receipt recoverable |
| `discardUnsent(lease, id, revision, domainChanges)` | Only zero-attempt intentions; atomic local compensation/archive. Never claims a dispatched action was cancelled remotely |

A 202 receipt can mean an accepted background job, not completed domain work. The domain adapter must preserve the response's actual semantics. Current HTTP product routes remain explicitly unavailable; this module does not manufacture server success.

`CommandReplyObserver` is called only for a fully bound response, outside the queue mutex and before outcome persistence, with lease checks around the suspension. Its generic no-op default is **not a cooking integration**: use `PrivateKitchenSession` or explicitly route cooking to `CookingRepository.observeReply` so mutation recalls fence all pinned copies before returning. Failure/cancellation leaves IN_FLIGHT recoverable; it cannot turn a rejection into success. The exact validated Problem code RECIPE_RECALLED becomes a safe durable issue; an ordinary 410 or a preflight decision cannot manufacture that protocol evidence.

## Persisted protocol and privacy

Reserved collections begin `feedme.command.`. Domain mutation lists cannot write these keys. The encrypted owner-scoped store contains:

- `index/v1`: versioned ordered pending IDs and last-observed wall clock.
- `metadata/<commandId>`: schema version 1; scope, origin, operation/parameters, dependency IDs, original creation/first-attempt time, retry deadline, phase, attempts and bounded local issue. Optional successful reply metadata is typed and bounded.
- `request/<commandId>`: exact original UTF-8 request bytes, separately stored so a valid 1 MiB body is not inflated by a JSON envelope. No parser reserialization or number normalization changes the replayed request.
- `receipt/<commandId>`: exact validated successful response bytes, separately persisted before domain acknowledgement.
- `cook-sequence/<originBinding>:<sessionId>`: original-device/session high watermark. Cooking bodies must contain a representable nonnegative Long integer; integral decimal/exponent spelling stays unchanged in the original body. This is an explicit client storage bound, not a schema rewrite.

Every new cooking sequence must exceed that origin/session's watermark. Enqueue updates watermark, draft, metadata, body and index in one transaction; failed enqueue leaves no reserved sequence. Acknowledgement/discard does not rewind it. This enforces local ordering, not server pin authorization, a global cross-device sequence algorithm, or permission to infer the next ETag.

The strict metadata codec rejects missing/unknown fields, duplicate decoded JSON keys, malformed UTF-8/surrogates, coerced/fractional/exponent metadata integers, future versions, inconsistent phases/attempts/receipts and cross-owner data. Nullable fields are explicit; raw request body syntax is separately revalidated against the full canonical schema before execution. Errors expose fixed local categories rather than raw private payload or server Problem text.

Bounds: at most 128 pending IDs, 64 unique earlier dependencies, 128 KiB metadata, 16 KiB total parameter values and 4,096 UTF-16 units per value. At most 60 caller mutations accompany enqueue/application, staying inside the store's 64-key/4 MiB transaction limit even with a cooking watermark. Oversized batches fail atomically; they are never truncated into success. The raw request/receipt limit is 1 MiB each.

Terminal acknowledgement strips request parameters/body, ETag, dependencies and reply; encrypted minimal command tombstones retain key identity so an old ID cannot become a new logical request. These tombstones and cooking watermarks currently remain until owner erasure. This is **not** a completed retention/space-quota solution or forensic erasure promise. Native file protections and cryptographic limits are in [client storage](CLIENT_STORAGE_FOUNDATION.md).

## Execution, ordering and recovery

The normal state sequence is READY (or AWAITING_CONFIRMATION) → durable IN_FLIGHT → durable RECEIPT_READY → atomic domain apply/APPLIED. Only the last step acknowledges local domain application. Transport failure may instead yield RETRY_WAIT/AWAITING_CONFIRMATION or NEEDS_RESOLUTION. The original body/key/ETag/origin cannot be edited through any queue API.

FIFO lanes serialize each cooking session, conversation thread or social post; other operations use conservative module lanes. A waiting/conflicting first command blocks later commands in its lane while independent lanes can progress. Dependencies must already exist, making new-ID cycles impossible; a child waits for APPLIED, not merely RECEIPT_READY. A discarded parent puts its child into a visible resolution hold.

Selection releases the queue mutex while running preflight, then rechecks both index and command revisions before claiming. An intervening queue/draft transaction invalidates that selection. A process-wide mutex-protected claim registry prevents a second queue instance from recovering a live local attempt. Claims are reserved before the suspending durable write and released in cancellation-safe `finally`; process restart clears only this transient registry. The Android factory independently excludes competing database writer processes. Neither is a distributed server lock.

Recovery updates at most 32 commands plus the index per batch; if a later batch fails, already committed recovery remains valid. CAS conflicts and possibly committed local writes are returned, not blindly repeated. A late response can only update its exact claimed revision. Cancellation after a possible send leaves IN_FLIGHT for same-key recovery; no new key is minted.

## Retry, confirmation and conflict policy

The explicit automatic-operation list is in `CommandPolicy`, with its source interpretation in [policy notes](CLIENT_COMMAND_POLICY_NOTES.md). Each listed operation still needs its own gate: reaction latest-intent coalescing, message eligibility/draft deletion, save grants, privacy suppression and notification watermarks are **not implemented by the generic queue**. Other eligible mutations require fresh confirmation. Identity, credential, signed-upload-capability, native-purchase and recent-reauth workflows are excluded from this generic journal, not removed from the product.

Client retry policy allows at most eight potentially dispatched attempts; exponential delay starts at one second, caps its base at 30 seconds and adds up to 50% jitter. Canonical 429/503 and transport uncertainty are eligible within that bound, not all 5xx. The contract does not enumerate every retryable 503 code; the component's bounded status policy is not a provider-specific readiness decision.

Use the maximum of jitter, validated numeric HTTP Retry-After and schema-bound Problem.retryAfterSeconds. Exact integral exponent/decimal forms are supported without floating-point rounding; unrepresentable values/deadlines saturate instead of causing an early retry. Retry-After never makes a permanent error retryable, and confirmation cannot bypass it.

Local transport OFFLINE and UNAUTHENTICATED are documented **pre-dispatch** outcomes, so the provisional attempt is refunded (and first-attempt age cleared only if no earlier attempt existed). Eight offline checks do not exhaust an unsent command. An actual HTTP 401 still counts as a remote attempt and requires repaired authentication before the same unchanged intent is eligible again. No automatic token refresh is performed here.

403/404/409/410/412/400/422/428 and other unsupported outcomes remain NEEDS_RESOLUTION. In particular a 412 does not replace If-Match, and unknown/expired outcomes cannot be cleared by `resumeAfterResolution`. Domain reconciliation and user choice remain unfinished acceptance gates rather than a silent discard path.

The client conservatively holds an attempted key at six days from its first durable claim, inside the contract's seven-day receipt guarantee; the contract does not specify the exact server retention start timestamp. Observed clock rollback also holds work. Time is rechecked after the claim write before calling transport. A long pause before a first-ever send can conservatively produce a hold despite zero actual sends; after refund that intent is safely discardable, not proof of an expired remote result.

**Remaining timing limitation:** transport's secure-store/network setup can itself suspend after the queue's last time check. The component does not prove an end-to-end server-time deadline or resist an undetectable whole-clock/database rollback. Final dispatch deadline enforcement/native suspend tests and authoritative expired-outcome reconciliation must land before enabling this in a production worker. The six-day margin is not a substitute for that proof.

## Historical command-journal verification and release boundary

Run with the existing local JDK17/SDK36/PostgreSQL setup:

```sh
node scripts/verify-command-recovery.mjs
```

The runner records exact source and artifact hashes and retains each attempt. The [historical command-recovery receipt](verification/command-recovery/verification.json) covers 113 source inputs at that point, all freshly executed tests, unchanged-source verification, and the unchanged historical demo APK hash. The kitchen continuation changes the source set; its newer evidence belongs to [private kitchen verification](PRIVATE_KITCHEN_REPOSITORIES.md). Both runners now include kitchen regression inputs; the historical receipt is not relabeled as current-source proof.

Final audit rechecked all 113 source inputs, seven artifacts and 43 retained evidence files with no size/hash mismatch or source trailing-whitespace issue. Current source-manifest SHA-256: `88268e1e4ae88b0cd887e8f873c104e06af8fe8859d655c27b3610448a77c5e9`. All 34 retained synthetic PostgreSQL test directories have no remaining postmaster PID file; no listener remains on the local test service port. No emulator was started in this continuation.

- 84 shared sync tests: 65 queue behavior/failure/concurrency cases, 14 codec cases and five exact retry-number cases.
- 72 transport tests, including 12 new pure request/precredential validation cases.
- 42 real encrypted-SQLite JVM tests, including six new queue integration cases. Test-only JCA keys persist across controlled reopen; no production JVM key store was added.
- 48 core, 119 contracts, 46 server and 45 isolated PostgreSQL tests. Total: **456 Kotlin/server/database tests**, no failures/errors/skips.
- 92 Node generator/tracker/backup-policy checks pass. Storage, transport and sync Android debug AAR builds and lint pass, with zero lint issues.

Integration evidence includes exact private bytes/key/path/ETag/origin across reopen, no plaintext markers in the SQLite files, lost-response same-key deduplication using a test remote, cancellation/recovery, receipt persistence and atomic domain application, rollback without orphan command/sequence, and retired-owner rejection. These are real SQLite transactions with a synthetic remote, not a live authenticated server or native app-kill drill.

Earlier native storage evidence remains [separately recorded](verification/client-storage/verification.json): 13 isolated API35 tests plus two staged fresh-process tests. It is historical, not a new command-queue device run. `verify-client-storage.mjs` has been updated to include the new sync dependency/tests when it is next run with an emulator. The old 354-test source receipt is not relabeled as today's 456-test run.

Next safe work is real cooking/save state codecs and repositories (M1.05c), durable session/logout/origin coordination (M1.05d), then explicit conflict adapters and UI wiring (M1.06b–c). iOS/full Xcode, native crash/security gates, real providers/content, store/account access and approved release scope remain required. No whole milestone, production feature, deployment, purchase integration or public release is accepted by this component receipt.
