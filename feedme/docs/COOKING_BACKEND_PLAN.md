# Cooking backend — bounded persistence handoff

14 September 2026. **COMPONENT IMPLEMENTED; BOUNDED SIXTH-BATCH VERIFICATION PASSED.** Cooking persistence, same-transaction Plan authorization, optional HTTP ingress and actual shared-client protocol tests passed the [source-bound combined verification](verification/parallel-cooking-ui-timers/verification.json), finished `2026-09-14T03:28:36.056Z`. This is component evidence, not provider, native scheduler or release acceptance. [BUILD_STATUS.md](BUILD_STATUS.md) remains the current acceptance reference; historical fifth-batch receipts are preserved separately.

F12 governs guided cooking; F13 governs the reviewed immutable content and recall authority on which it depends. This component records explicit user progress. It does not certify food safety, complete unsubmitted steps, deliver timers, save a recipe, consume pantry stock or publish a post.

## Canonical operations

| Operation | Request and precondition | Success |
| --- | --- | --- |
| `createCookSession` | POST `/v1/cook-sessions`; CookStart requires planId, optionally deviceSequence; Idempotency-Key, no If-Match. | 201 CookSession + quoted version ETag |
| `getCookSession` | GET `/v1/cook-sessions/{sessionId}`; current ownership, no body/key/conditional304. | 200 CookSession + ETag |
| `updateCookSession` | PATCH same path; CookPatch requires deviceSequence; original Idempotency-Key and If-Match. | 200 CookSession + ETag |
| `completeCookSession` | POST `/v1/cook-sessions/{sessionId}/complete`; required makeAgain/deviceSequence and optional finishedAtClient; Idempotency-Key, **no If-Match and no final progress body**. | 200 CookSession + ETag |

CookStart references an owned immutable Plan, not client-supplied instructions. PATCH supports only the existing status active/paused/abandoned, currentStepId, completedStepIds, timers and personalNotes fields. Completion remains a separate explicit transition; visiting the last step does not complete a session.

The root-owned [CookingHttpConfiguration](../server/src/main/kotlin/com/feedme/server/http/CookingHttpConfiguration.kt) and [CookingHttpRoutes](../server/src/main/kotlin/com/feedme/server/http/CookingHttpRoutes.kt) provide optional canonical ingress with mandatory verifier/store/dispatcher, 64KiB/depth32 request validation, account/device versus guest binding, sanitized Problems, no-store and operation/status/version checks. Default Main is still closed/degraded, and this document does not claim provider or deployment activation.

## Implemented storage and authority

[CookingStore](../server/src/main/kotlin/com/feedme/server/cooking/CookingStore.kt) uses real PostgreSQL transactions and the existing DurableCommands/OutboxStore. Public methods have the four canonical operation names. Mutations return CommandResult; GET returns StoredReply. The service exposes its configured environment and policy so HTTP cannot impose a contradictory post-commit response cap.

[CookingAuthority](../server/src/main/kotlin/com/feedme/server/cooking/CookingAuthority.kt) is mandatory, with no accepting implementation or provider defaults:

- lockPrincipal must revalidate the actual current account/device or bounded guest session, expiry/revocation/merge and eligibility, taking the same exclusive principal lock as planning, kitchen and lifecycle writers.
- requireNewCookingEnabled gates fresh starts, not continued ownership of an existing pin.
- validatePersonalNotes must authorize referenced shortcut/community-tip sources or reject unsupported integration. Private text cannot become editorial review or replace pinned instructions.

VerifiedCookingPrincipal requires account deviceSessionId or a distinct internally verified guestSessionId, never both. Guests have no device header. Neither a local originBinding nor a supplied opaque identifier is server authority.

All callbacks are DB-only in the supplied connection: no independent commit or remote provider call. Actual lock order is principal → original receipt → planning lineage → current input/catalog-rights locks → cooking row → per-device cursor/events. The two adapters must use compatible actual locks, not similarly named but independent locks. Real identity/editorial/reference lifecycle integration is still a release gate.

## NEW_SELECTION versus EXISTING_PIN

[PlansStore.lockCookingPlan](../server/src/main/kotlin/com/feedme/server/planning/PlansStore.kt) operates on the caller's existing transaction. [CookingPlanUse](../server/src/main/kotlin/com/feedme/server/planning/CookingPlanAccess.kt) makes the intent explicit, but its enum alone conveys no authority.

NEW_SELECTION requires a live READY owned Plan, current exact preference/pantry/base and taxonomy evidence, published eligible recipe, unchanged immutable recipe material and independent current review/rights. The service then requires complete nonempty unique steps, contiguous positions, nonblank instructions and ingredient references within the materialized recipe. It never reranks, substitutes a newer body or invents confirmation.

EXISTING_PIN requires an actual same-owner cooking row with exact stored Plan text/hash, proof hash, evidence hash and unexpired cooking lifetime. Missing/foreign pins do not bypass Plan expiry. Existing progress does not rerun matching after input changes. A correctly versioned retirement can preserve authorized owned use; recall and lost rights deny reads and mutations, including cached replies.

The canonical historical getPlan/getPlanExplanation paths can retain an exact owned cooking pin past the planning TTL. This is necessary for CookingRepository's session-then-Plan download, not a new start grant. New creates still require the original live Plan lifetime/current selection checks. Cooking retention is separately configured, fixed at creation and bounded to 60–2,592,000 seconds; after it expires, the pin cannot extend Plan read authority. No expiry/deletion worker is enabled.

The current engine materializes direct reviewed recipes and reviewed quantity scaling. Social/private sources, substitution dependency authorization and a signed/offline content manifest remain separate integrations. The stored snapshot hashes are integrity/provenance checks, **not** an offline cooking grant, provider approval or native lease.

## Exact sequence, progress and completion decisions

The team-owned sequence decisions are implemented explicitly rather than added as undocumented wire fields:

- Omitted CookStart deviceSequence initializes to zero; a supplied nonnegative mathematical integer is retained within Long range.
- Every fresh progress/completion requires the next aggregate sequence, plus monotonicity for that verified device's cursor. The response scalar is the last accepted aggregate sequence. Distinct devices can have gaps in their own cursor sequence; a stale completion cannot bypass another device's acknowledged patch.
- Original command replay occurs before fresh sequence admission but only after current principal/rights checks. It requires the exact current session version/body, original operation/request hash and original verified device identity. A later session mutation does not allow an older receipt to masquerade as current progress.
- A command UUID cannot be reused by a different operation within one session: the private step-event identity is unique per session/command, matching the data model.
- PATCH checks current If-Match and supports active/paused → active/paused/abandoned. Foreign or duplicate step/timer identities, missing running deadlines, missing paused remaining time and remaining time beyond duration fail without mutation.
- Completion permits active/paused → completed only. It preserves **all** locked current steps/timers/notes and uses the database accepted time for completedAt. finishedAtClient remains separate untrusted private event provenance. It does not mark all steps or timers done or attest to safety.
- Completed/abandoned sessions cannot reopen. Only the exact current terminal command receipt can replay; a new completion key conflicts. Completion requires earlier client head patches to be acknowledged because its canonical body contains no final progress snapshot.
- makeAgain=false is implemented. makeAgain=true fails NOT_CONFIGURED before any completion/save effect until an actual durable save/snapshot/grant integration exists.

Personal notes and timer state remain private user reports. Completion/abandon does not prove native alerts were canceled. A response bound is checked before domain/receipt/outbox commit and again against the persisted response. maxResponseBytes is explicitly configured within 1..262144; test/client composition uses 65536.

## V005 and atomic effects

[V005__private_cooking.sql](../server/src/main/resources/db/migration/V005__private_cooking.sql) adds cooking.cook_sessions, cooking.device_cursors and append-only cooking.step_events. It preserves V001–V004 bytes and migration history. Owned foreign keys pin the immutable Plan; the session trigger rejects pin/lifetime replacement, version/sequence skips and terminal reopening. Service validation supplies the canonical body and pinned-reference checks that SQL shape constraints alone do not prove.

Each admitted command commits its session snapshot/version, device cursor, unique private step event, exact original-key receipt and registered outbox event together:

| Event | Producer / aggregate | Data only |
| --- | --- | --- |
| cooking.session.started.v1 | cooking / cook_session | principalId, sessionId, planId |
| cooking.session.progressed.v1 | cooking / cook_session | principalId, sessionId, deviceSequence |
| cooking.session.completed.v1 | cooking / cook_session | principalId, sessionId, planId |

Outbox facts contain no instructions, notes, ingredient lists, raw timer state or credentials. Private step-event payloads retain the accepted command's bounded progress/provenance; they are not public telemetry. A pre-commit failure rolls back all effects. A failure after actual COMMIT remains CommitOutcomeUnknown and reconciles the original key/body; no automatic resend or replacement identity is introduced. Cancellation is propagated, including through the new planning seam.

## Test inventory and present evidence limit

The centralized focused server run passed all 87 cooking tests (33 unit and 54 real-Postgres/client integration), with zero failures, errors or skips:

- [CookingPolicyTest](../server/src/test/kotlin/com/feedme/server/cooking/CookingPolicyTest.kt): 12 input/policy/privacy/event-contract unit tests with rejecting test adapters.
- [CookingStoreIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/cooking/CookingStoreIntegrationTest.kt): 33 real-Postgres tests for ownership, exact pins, current selection versus historical use, retirement/recall/expiry, versions/sequences, terminal state, bounds, rollback/unknown commit, concurrency and same-lock lifecycle races.
- Root-owned [CookingHttpInputTest](../server/src/test/kotlin/com/feedme/server/http/CookingHttpInputTest.kt): 21, [CookingHttpIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/http/CookingHttpIntegrationTest.kt): 18, [CookingClientServerIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/http/CookingClientServerIntegrationTest.kt): 3. The last traverses actual FeedMeTransport, canonical response binding, KitchenRepository/queue, CIO and PostgreSQL for ordered progress/completion, preserved remote conflicts and learned recall. Its create request enters through transport, not a claimed complete CookingFlowController consent journey.

The sixth full run started at `2026-09-14T03:16:38.009Z`. Its [Gradle log](verification/parallel-cooking-ui-timers/attempts/2026-09-14T03-16-38.009Z/gradle.log) records BUILD SUCCESSFUL in 3m 24s and 518 actionable tasks, all executed; all 40 required build/verification tasks executed freshly. An independent read-only audit matched every retained XML method against the frozen source and freshly audited live output: **2,418 tests in 125 classes**, consisting of 1,997 shared, 174 server-unit and 247 PostgreSQL integration tests, all with zero failures/errors/skips. XML suite timestamps span `03:16:43.401Z`–`03:19:50.690Z`, after this attempt began; retained/current XML bytes, modification times and test-source hashes agree. The focused 87 cooking cases are included in these totals, not additional tests.

The final [immutable attempt receipt](verification/parallel-cooking-ui-timers/attempts/2026-09-14T03-16-38.009Z/verification.json) also records 192 exact Node tests, 354 passed native tests and seven separately witnessed interrupted starts that are not counted as passes. Native runs used isolated `emulator-5556`, API35 ARM64; the cooking-host subset is nine tests with seven source-method-linked native screenshots using actual encrypted stores and explicitly synthetic service/identity fixtures. This does not convert that host coverage into a deployed-provider journey or timer-delivery proof.

Independent final reconciliation verified all 452 source inputs, the complete 347-file retained evidence inventory, 20 artifacts (19 freshly rebuilt plus the unchanged historical demo), eight clean lint reports and 1,467 recursive hash references across 1,020 unique files. Initial/current source discovery agrees; current, immutable and last-attempt receipt bytes agree. Receipt SHA-256: `7d102184e46c21857978f46de899e9460b132d48ca0b8c5b595bd8b0d4f7cfa7`. Source-manifest SHA-256: `9e19d6258f5ff2408d4998fc7e4730ec834152581f377b04329db9bd935c886d`. The fifth runner/receipt and V001–V004 migration bytes remain unchanged.

[CookingTestFixture](../server/src/integrationTest/kotlin/com/feedme/server/cooking/CookingTestFixture.kt) uses the actual planning engine and database, but identity/session/catalog/review facts are explicitly synthetic. Client compatibility uses a labelled atomic-memory CAS fixture, not native encryption/durability. Application commit-fault hooks are not SQLite/VFS/physical power-loss tests. Fixture expiry control temporarily disables the immutable-lifetime trigger solely to move test DB time evidence; no production bypass is provided.

## Remaining work and release gates

Persistence tasks 1–3, the database portion of task 5, optional HTTP task 4 and protocol compatibility tests have passed the bounded source-bound sixth-batch verification. The remaining external-authority and product release gates below are not waived by that result.

The component does not supply a real provider, reviewed catalog/lifecycle adapter, qualified review, deployment, retention/erasure/outbox worker, signed bundle manifest, native timer delivery/cancellation, live-provider cooking UI journey, Save/Make Again, feedback/share, iOS or physical-device acceptance. Default service startup does not enable the optional ingress. [SERVER_PLANNING_PERSISTENCE.md](SERVER_PLANNING_PERSISTENCE.md) and [KITCHEN_HTTP_INGRESS.md](KITCHEN_HTTP_INGRESS.md) describe adjacent components, not substitutes for those gates.

Canonical inputs are outputs/biteclub_blueprint/features/F12.md, F13.md and architecture/03_Data_Model.md, 04_API_Contract.json and 05_Events.md. [BUILD_STATUS.md](BUILD_STATUS.md) owns current source-bound acceptance and preserves historical receipts.
