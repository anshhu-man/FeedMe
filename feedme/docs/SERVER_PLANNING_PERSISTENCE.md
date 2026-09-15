# Server planning persistence

This is a transactional PostgreSQL application-service component for manual meal planning, not an enabled HTTP service or a deployed recommendation system. The current release status and full-batch evidence belong in [BUILD_STATUS.md](BUILD_STATUS.md). This document describes the implemented server boundary and its integration requirements.

## Implemented operations

[PlansStore](../server/src/main/kotlin/com/feedme/server/planning/PlansStore.kt) accepts an explicitly verified account/device or guest principal. Its methods use the canonical request and response schemas; they do not invent alternate API fields.

| Method | Persisted behavior |
| --- | --- |
| `createPlan` | Validates the canonical request, obtains current authoritative input evidence, runs the shared deterministic planner, and atomically stores one immutable Plan, its original lineage/evidence, command receipt, and outbox event. A successful new creation returns canonical HTTP-shaped status 201. |
| `getPlan` | Returns the exact stored Plan content after current principal, ownership, expiry, recipe-access, and recall checks. It does not rerank or silently refresh the snapshot. |
| `getPlanExplanation` | Paginates the Plan's stored reasons after the same read authorization. Explanations are persisted planner facts, not a later language-model interpretation. |
| `nextPlan` | Consumes the exact current parent/cursor once and atomically writes a new immutable child, advances the lineage head, records its command result, and appends its event. It uses canonical `AdaptRequest` with reason `alternative`; there is no invented `If-Match` header. |

Only the lineage head can produce a new alternative. An exact idempotency replay can return its original child, subject to fresh authorization, without advancing that head again. Exclusions must name recipes already presented in that same lineage, not recipes from a different request. Historical parents remain available for Back navigation while their current read authorization permits it.

The original bounded candidate order is persisted. A new service instance reconstructs the pure planner from the original request, policy and evidence; it does not depend on an in-memory continuation. Traversal checks current evidence, skips newly recalled/retired/unavailable future candidates, and never reranks or recycles the original sequence. A changed taxonomy or changed same-ID recipe material invalidates traversal instead of silently changing the meaning of the request.

## Evidence, recipe content and manual modes

[PlanningEvidenceSnapshot](../server/src/main/kotlin/com/feedme/server/planning/PlanningEvidenceSnapshot.kt) is a strict, server-private, version-1 persistence format. It stores preference revision and explicit exclusions/dislikes; pantry revision and confirmed/uncertain/unavailable states; optional confirmed base-meal evidence; taxonomy revision and ingredient composition; and complete canonical recipe bodies with independent editorial evidence. Parsing this format proves structure, not provider authentication, source ownership, licensing, or editorial approval.

Evidence is bounded to 2 MiB, 128 candidates, 1,024 taxonomy nodes and 256 pantry entries; each source recipe is bounded to 64 KiB. Duplicate keys, malformed Unicode, unknown fields/enums, duplicate normalized IDs and oversized inputs fail closed. Unknown composition is distinct from an explicitly known ingredient with no components. Snapshot and proof hashes detect persisted-content disagreement; they are not an authorization mechanism.

The service preserves actual recipe material, quantities, preparation steps, equipment, safety flags and rights-related fields rather than manufacturing a recipe from a title. Reviewed quantity scaling uses the [shared planner](../shared/planning/src/commonMain/kotlin/com/feedme/planning/DeterministicPlanner.kt) and [canonical adapter](../shared/planning/src/commonMain/kotlin/com/feedme/planning/CanonicalPlanningAdapter.kt); the materialized Plan does not overwrite its original catalog source. Stored JSON is normalized through the wire projection, so preservation of arbitrary incoming whitespace is not claimed.

Manual no-source requests work through the same engine. `cook`, `assemble`, `auto`, and enabled `improve` are supported according to reviewed candidate eligibility and explicit policy. Improve requires the authority adapter to supply evidence for the exact confirmed base meal, its preparation state, catalog type and complete composition. A description or ingredient list alone is not confirmation. Missing evidence returns the engine's non-cookable result rather than a fabricated match.

`needsConfirmation` and `noMatch` are persisted successful non-cookable Plans. Unresolved automatic mode may omit `mode`, as the canonical contract permits; the service never invents `cook` or an `auto` Plan enum to fill the gap. Ready Plans require a real resolved mode and actual recipe snapshot.

## Current authorization and replay

[PlanningAuthority](../server/src/main/kotlin/com/feedme/server/planning/PlanningAuthority.kt) is mandatory and has no accepting default. The production adapter must verify and lock current account eligibility/device binding/revocation or bounded guest eligibility/expiry/merge state. Owner keys include environment, principal kind and principal ID, so equal opaque account and guest IDs do not share plans. An inaccessible or missing private Plan returns the same unavailable result.

Every command replay rechecks the current principal before disclosing its stored result. A cached ready selection also requires the original policy and private input evidence to remain current, the exact taxonomy revision and normalized ingredient-to-component evidence to match, and its recipe to remain authorized, published and eligible. UUID case and ordering do not change taxonomy identity; an altered component set or known-to-unknown transition does, even if a producer incorrectly reuses its old taxonomy revision.

The adapter must enforce current rights and independent review before including any source recipe. A direct recipe UUID is not an access grant. Free-catalog eligibility is explicit; raw social IDs, a copied recipe body or a client review flag cannot establish it. A recalled recipe blocks new reads, explanation reads and ready-result replay. Historical reads retain original preferences and recipe facts rather than silently applying new preferences. Safe retirement permits historical reads but stops ready selection/replay. The lifecycle guard requires a monotonic recipe resource version, rejects any changed metadata at the same version, and permits higher-version lifecycle/review metadata only when actual recipe material, rights fields and independent editorial evidence remain unchanged.

New nonempty natural-language requests and social post/saved-recipe sources are explicitly `NOT_CONFIGURED`. The service has no language-provider implementation or social-grant adapter. Deferred tonight/household request paths are also gated. Direct reviewed catalog source and confirmed-base improve paths are not replaced with permissive fixture defaults.

## Transaction and storage boundary

The required lock order is current principal/session → idempotency → lineage → authoritative input/catalog/policy roots. Adapter calls execute on the supplied PostgreSQL connection in the same transaction: no remote calls, independent commits or preflight-only authorization. Revocation, preference, pantry, catalog and rights writers must participate in compatible locks; a real cross-module lock-order review is required before exposing routes.

[DurableCommands](../server/src/main/kotlin/com/feedme/server/db/DurableCommands.kt) supplies operation/owner/key/request binding and seven-day command retention. The mutation, owned lineage/head change, immutable Plan, exact result and [outbox](../server/src/main/kotlin/com/feedme/server/db/DurableEvents.kt) append commit together. Competing next commands cannot consume one parent twice. Outbox failure rolls back the domain and receipt; a lost application commit response remains an unknown outcome until the same original request/key is retried and reauthorized. A cached JSONB response is checked semantically against the exact stored Plan before returning the stored Plan projection.

The canonical `planning.plan.created.v1` event contains bounded identifiers, Plan status and ranking version. It does not contain private constraints, recipe material, the pagination cursor or natural-language text. Event consumer/relay deployment is separate from transactional outbox persistence.

[V003__private_planning.sql](../server/src/main/resources/db/migration/V003__private_planning.sql) adds `planning.plan_requests` and `planning.plans`, bounded text/hash fields, same-owner/same-lineage parent and head references, expiry indexes, and an immutable-Plan update trigger. Plans begin at resource version 1 and are not updated in place; lineage versions advance with the head. V001 and V002 bytes/checksum history are preserved. [PlatformMigrations](../server/src/main/kotlin/com/feedme/server/db/PlatformMigrations.kt) registers V003; migration tests verify the existing history and one-time upgrade.

## Cursor and lifetime configuration

Alternative cursors are fresh random 32-byte values encoded as 43-character base64url strings. Their hash supports lookup, but the raw cursor is also deliberately retained inside the private Plan and command reply. This is owner-bound pagination, not an authentication bearer or hash-only secret-storage design.

[PlanningCursors](../server/src/main/kotlin/com/feedme/server/planning/PlanningCursors.kt) requires an explicitly configured HMAC key ring for explanation pagination: 1–8 named 32-byte keys, with no built-in production secret. Explanation cursors bind environment, principal kind/ID, Plan ID, snapshot hash and offset. Rotation must retain applicable verification keys for the supported lifetime.

Operational policy explicitly supplies ranking version, heat/improve gates, Plan retention and alternative-cursor lifetime. Implemented bounds are 60 seconds–30 days for a Plan and 1–600 seconds for a lineage's alternative cursor, with cursor expiry no later than Plan expiry. These are configuration bounds, not an approved product retention promise. Database time and expiry are rechecked after policy waits and before mutation commit. Traversal does not extend the original lineage cursor lifetime.

## Client integration and remaining release gates

[MealRequestController](../shared/mealflow/src/commonMain/kotlin/com/feedme/mealflow/MealRequestController.kt) is the client integration point, not evidence that HTTP wiring exists. A real adapter must preserve its original idempotency key/body on retries, bind canonical replies and ETags, send ancestry-only exclusions for `nextPlan`, and distinguish ready/non-cookable/exhausted results. Client history is bounded and owner-scoped; it does not confer fresh cooking, saving or catalog authority.

Still required before shipping this service:

- Real verified account/device and guest adapters; current profile/preferences/pantry/catalog/taxonomy sources; rights/licensing and independent review enforcement; compatible lifecycle locks and their race tests.
- HTTP routing, error mapping, request limits, observability/redaction, abuse/quota policy and integration with the authenticated transport. No route is enabled by this package.
- Deployment configuration, managed database access/encryption/backups, HMAC key provisioning/rotation and operational policy approval. Test secrets and synthetic identities are not deployment defaults.
- Retention/erasure and recall propagation workflows, outbox relay/consumer execution, and their operational verification. An expiry check or queued event is not proof that a worker has run.
- Real client/server end-to-end coverage with the actual adapters. The full component batch now passes as recorded below; that does not establish this production integration gate.

## Focused verification provenance

The root's focused run `43298` passed **71 server unit tests and101 real PostgreSQL integration tests**, including the monotonic recipe-lifecycle guard. These same totals passed the [final combined source-bound run](verification/parallel-meal-startup/verification.json) at2026-09-13T22:19:20.584Z. This package contributes **15 unit tests and28 PostgreSQL tests**; the totals also include existing suites and the root-owned Plan-mode parity test. [Independent audit and component-only acceptance](PARALLEL_MEAL_STARTUP.md).

Historically, focused run `13977` passed 71 server unit tests and 100 PostgreSQL tests before the additional lifecycle guard/regression. That earlier result is not used as proof of the later change.

- [PlanningPersistenceCodecTest](../server/src/test/kotlin/com/feedme/server/planning/PlanningPersistenceCodecTest.kt): strict evidence format, redaction, principal shape, bounded explicit policy and explanation-cursor binding/key handling.
- [PlansStoreIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/planning/PlansStoreIntegrationTest.kt): real PostgreSQL transactions/migrations, private account/guest boundaries, current-session replay checks, immutable materialization and confirmed improve, durable alternatives/concurrency, expiry, taxonomy/composition changes, recall, exact replay and rollback.
- [PlatformMigrationsIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/db/PlatformMigrationsIntegrationTest.kt) and [CirclesStoreIntegrationTest](../server/src/integrationTest/kotlin/com/feedme/server/social/CirclesStoreIntegrationTest.kt): migration upgrade/history preservation alongside the existing suites.

The planning integration fixtures use actual PostgreSQL with synthetic test-only principal/input tables and a mandatory test authority. Editorial evidence, catalog contents and identities in those fixtures are not a real provider, licensed production catalog or tested production authorization adapter. Injected post-commit exceptions simulate lost application receipts after a real commit; they are not physical storage or network fault proof.
