# FeedMe — parallel component foundations

14 September 2026. These packages accelerate independent parts of the approved44-feature V1. They are not whole-feature acceptance, enabled production routes or a new release-date commitment. The10 deferred features remain excluded. See [team ownership](AI_TEAM_PLAN.md), [release scope](V1_RELEASE_SCOPE.md) and [needs-you register](USER_ACTIONS.md).

This is the **historical first-batch checkpoint**. Its source/artifact counts and remaining-work descriptions refer to the21:19:09Z run, not the later source tree. The [second batch handoff](PARALLEL_MEAL_STARTUP.md) records the now-verified manual controller, PostgreSQL planning persistence and owned startup components, current evidence and next integration work. Earlier immutable receipts are preserved.

## Package boundaries

| Package | Current implementation boundary | Not provided by this package |
| --- | --- | --- |
| Retained control recovery | An existing-only Android/SQLite owner for the exact encrypted CONTROL ledger; shared lifetime core with retained WORK recovery | Session-plan authentication, user confirmation, coordinated subordinate closes, startup completion or an authenticated app session |
| Shared deterministic planning | Reviewed-input constraint evaluation, bounded matching/ranking, supported modes, reviewed exact scaling, explanations and alternatives | Editorial approval, licensed catalog ingestion, source-post/save authorization, server-issued plan identities, durable cursors, language-provider integration or native journey wiring |
| PostgreSQL circles | Explicit-principal circle/membership/invitation persistence, transactional invariants, keyed capabilities, durable commands and minimal outbox facts | Identity-provider adapter, block/profile implementation, post/media grants, notification delivery, native invitation UI or enabled HTTP routes |

Every caller must meet the relevant boundary. Passing a trusted interface in a test does not constitute production identity, content review or authorization.

## Combined acceptance

The [fresh source-bound run](verification/parallel-feature-foundations/verification.json) passed at2026-09-13T21:19:09.119Z:1,753 Kotlin/server/database tests (1,625 shared,55 server,73 PostgreSQL),161 Node checks and311 Android native tests. All six Android libraries built with zero lint issues. The run includes all final review fixes, including the database-level direct-writer capacity race. It retains326 sources,15 artifacts and161 evidence files.

An independent audit matched all1,753 unique source-declared Kotlin methods across89 XMLs, all161 exact TAP identities across nine source files and fresh JAR/AAR/lint task outputs. This is additional evidence beyond the runner's inherited JVM/TAP count gates. Recursive hashing matched627 records across553 paths; current, last-attempt and immutable receipts are identical. Receipt SHA-256: `eafe9c313e12daf37e281da4256889c603619588fa28885ef23201235fae8ddd`. Source manifest SHA-256: `62f67908b62a394625fcd1ab3e2aa0b3e168776c9130cdcbab1fe1d234ddad0a`.

A second independent audit matched311 paired source-declared native tests across28 invocations,39 VFS fault labels and five controlled process stages. All44 original/retained native evidence-copy pairs and APK metadata agree. Four actual ELF ABI headers/bytes match the current and retained test helper; that helper is absent from six production AARs, the session APK and the historical demo. This does not claim all Kotlin test/fault seams are absent from bytecode. Runtime execution is API35 ARM64 only; the other three ABIs have packaging evidence. Hardlink creation was denied by the platform with errno13, so that branch remains unexercised. Exact delivered-notification cancellation passed.

The owned emulator process5540/session81598 exited0 and the device list is empty. Storage test-private state is empty; session state retains only the three WorkManager runtime files, with test notification permission still granted as before. All157 retained local PostgreSQL clusters have no postmaster PID file and port8789 has no listener. No clusters were deleted. The public export remains clean at commit `4b9a5ac0b2bef352d3b617082d0bb62b31ec966c`; these new changes are local.

After that handoff's doc updates, all33 tracking regressions and both validators passed at21:25:09Z. At that time only the standalone scope report's verification timestamp differed from the retained full-run report, and all326 sources still matched. Subsequent source work and scope runs supersede those mutable pointers, not this immutable historical evidence. Use the retained scope report for this run's exact hash. Delivery tracking retained72 milestone tasks and162 feature work packages; no whole feature was checked off.

## Control recovery: ownership before I/O

The trusted composition factory returns an owner before opening existing native resources. Failed or cancelled opening retains acquired resources so the same owner can close them; a failed close cannot become an acknowledgement merely because a later driver close does nothing. It admits the exact preexisting schema and sole fixed ledger, not normal initialization, migration, garbage collection or ordinary session resume. Reads and changed-CAS writes recheck the captured owner/ledger before returning an acknowledgement.

The storage body is opaque. A fixture spelling `Pending` or `Complete` is not a verified session transition. Production startup still needs to authenticate the original credential/data/work plans, retain the exact recovery checkpoint, close subordinate owners truthfully, and then recheck lifecycle/control state before publishing completion. No access lease, signing operation or automatic erasure follows from this component.

Focused acceptance passed26 new control JVM tests and30 existing work regressions. Android passed12 control plus12 work focused tests and the complete164-test storage suite at2026-09-13T20:54:59.411Z, followed by the combined acceptance above.

## Planning: reviewed decisions, not generated recipe truth

The shared module consumes canonical `PlanRequest` and `RecipeVersion` values with explicit reviewed metadata, preferences, ingredient composition and reported availability. The editorial adapter must supply evidence that the catalog wire schema alone does not encode, including preparation type and whether steps, effort and units remain valid over a scaling range. No permissive production review or free-access default is inferred.

Hard exclusions are applied before ranking and include compound ingredients. Unknown composition or required availability cannot silently become a cookable match. Missing or changed preference revision requires refresh/confirmation. Natural-language input requires structured confirmation; it cannot execute tools or write recipe bodies. Time, equipment, energy, modes and exclusions remain hard constraints. Taste/dislikes affect ranking only within explicitly permitted behavior.

Supported serving changes use exact bounded decimal arithmetic and explicitly reviewed linear scaling; unsupported units, ranges or nonterminating ratios are refused without rounding. This creates a **plan-local materialized snapshot**, not a new published recipe version. The original catalog object and source version identity remain unchanged. This follows [F01's materialized-plan convention](../../outputs/biteclub_blueprint/features/F01.md) and [architecture step5](../../outputs/biteclub_blueprint/architecture/02_Architecture.md). Never return the derived snapshot as the canonical catalog-version body or claim it independently passed editorial review.

Alternatives retain a deterministic original candidate order and original catalog provenance. The API requires the current request and current preference/availability/base-meal context and rejects changes. It separately records the latest eligibility-check catalog revision, including exhausted alternatives, while retaining the original ranking revision. An in-memory continuation is not an authenticated server cursor. Before route enablement, the planning transaction must authorize the principal and source grant, persist the effective constraints, source links, actual snapshot and decision facts, issue real plan/version/continuation identities and commit its outbox event atomically. Recall validation also belongs at cooking/save entry, not only at initial matching.

The focused planning run passed40 JVM tests, built its Android library and reported zero lint issues. The combined run also passed against the final files after ranking-documentation and redundant-test-assertion cleanup. No native planning-screen or iOS execution is claimed.

## Circles: current authority before command replay

The service requires an explicit environment, verified account/device principal, identity/profile/block policy, keyed capability configuration and launch limits. The identity policy participates in the same database transaction and lock order as lifecycle writers. Synthetic policy implementations are test fixtures, not a production bypass.

An expand-only V002 migration leaves V001 unchanged. Circle mutations serialize on the parent circle; the deferred database check also locks that parent with `FOR NO KEY UPDATE`, protecting capacity even when concurrent direct SQL writers bypass the repository. Tests require one commit and one23514 rejection rather than a deadlock. Physical `social.circle_*` tables implement the logical circles aggregates; other modules should consume the owned interface, not assume permission to mutate these tables. Rejoining advances membership generation so an author's old post grants cannot reappear from a user-ID match. An audience-membership predicate is only one part of post authorization: post status, placement, all valid grants, blocks and media policy still need to be evaluated together.

Invitation bearers are returned only to an authorized caller; the database stores their hash, not a reusable raw link. Acceptance, capacity consumption, membership and outbox facts belong to one acknowledged transaction. Retried commands must revalidate current identity, membership and applicable block/lifecycle state before exposing a cached success. Uncertain commit outcomes remain uncertain until safely reconciled.

`circles.circle.changed.v1` is a minimal internal invalidation fact for acknowledged name/description edits. Producer is `circles`; aggregate is the circle and its committed version; data is only `circleId` and `action=updated`. The [event registry](../../outputs/biteclub_blueprint/architecture/05_Events.md) records this addition. Names, descriptions, invitation tokens and private profile contents do not travel in that event. No notification consumer is claimed implemented.

## Integration and acceptance remaining

1. Preserve the accepted component invariants and repeat cross-review and regression acceptance for subsequent changes.
2. Keep the frozen test inventories, retained evidence and current source/artifact claims aligned; a later source edit needs new verification.
3. Connect mandatory identity, content and policy adapters; enable canonical routes only after cross-principal, recall, replay and lifecycle checks are exercised at the HTTP boundary.
4. Wire shared Kotlin screens to real state, preserving unavailable/offline/confirmation states and the V1 exclusion boundary; verify two-account journeys and native restoration.
5. Complete iOS/full-Xcode, physical-device, content rights/review, operational, monetization and signed-store gates separately.

The social package passed55 server and73 PostgreSQL tests in focused reruns and the final combined run. Reviewed corrections include operation-scoped removal markers, active inviter/owner/author eligibility, issuer generation/version fencing, current-profile replay, hidden-member pagination, invitation non-enumeration, strict UTF-8 and direct-writer capacity serialization.

## Next native vertical slice

The current native roots deliberately instantiate `DemoKitchenRuntime`; do not relabel those fixtures as production review evidence. The next dependency-ready package is a manual-request controller and canonical request builder with injected authenticated planning access, followed by REQUEST → RECOMMENDATIONS → plan-context RECIPE. Keep the demo runtime separate and avoid converting canonical snapshots through the demo's flattened recipe/variant types.

The controller must retain an encrypted draft, cancel superseded work and check its private-session lease and request generation around suspension points. Required states include editing, loading, needsConfirmation, noMatch, ready, offlineDraft, resolving, error and unavailable. Edits invalidate alternatives; back navigation restores the draft and selected plan. Offline initial matching is not automatically queued. After a dispatched timeout, retain the original idempotency key/body and show resolving instead of implicitly submitting a new request.

Recipe navigation must keep the owned Plan snapshot, including scaled ingredients and source lineage, instead of replacing it with the unscaled catalog-version body. Real cooking/save creation remains a separately authorized operation. Identity/backend integration (U04/U05) and licensed, qualified-reviewer catalog evidence (U06) remain required for production matching; the manual slice does not need a language-provider choice.

No whole feature, milestone, public repository refresh, deployment or store submission is marked complete by this document.
