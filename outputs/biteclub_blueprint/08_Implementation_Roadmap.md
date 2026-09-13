# FeedMe — Implementation roadmap and release plan

**Shipaton planning update:** See [the 2026 competition release plan](13_Shipaton_2026_Release_Plan.md) for the proposed reduced September scope and deadline gates. The effort estimates below still describe the full product; they are not compressed into the competition calendar. The competition proposal brings one narrow paid offering forward, subject to owner approval and production billing gates.

Status: execution design, 13 September 2026. Android and iOS share Kotlin; native camera, identity, timers, notifications and billing require platform implementations and device verification. This plan creates no repository, deployment or live purchase offer. All effort ranges below are planning estimates, not dates or commitments, and assume a greenfield implementation because Smart Kitchen code has not been inspected.

## Scope, ownership and completion rule

Use the 54-feature inventory in [spec/model.mjs](spec/model.mjs), each features/Fxx.md specification, the [architecture](architecture/02_Architecture.md), and [OpenAPI contract](architecture/04_API_Contract.json). The registry owns screen IDs; OpenAPI owns production method/path pairs. Resolve discrepancies before implementation rather than building parallel endpoint conventions.

Every implementation ticket must identify its feature ID, screen/action, API operation, owned data, emitted/consumed event, permission rule, offline behavior and acceptance test. A vertical slice is done only when Android, iOS, API, worker effects and recovery paths agree. A navigable prototype is design evidence and does not count as a production implementation or passing native test.

## Team and estimate assumptions

The reference team is two engineers focused on shared/mobile work with Android/iOS integration coverage, two backend/platform engineers, and one test-automation engineer. Add a product/design lead, a qualified nutrition/content reviewer, content authors and an accountable moderation operator; these are real dependencies, not duties presumed to be handled by the language model. Security/privacy and accessibility specialists review their gates. If one person covers several roles, work and review capacity do not become parallel automatically.

Engineering estimates include application/server/test implementation and integration. They exclude waiting for store review, legal/market decisions, external account approval, editorial production and ongoing moderation. Content/review effort needs a separate estimate after the launch recipe set and review protocol are chosen. Shared UI reduces duplicate work but does not remove platform testing, signing or accessibility remediation.

| Phase | Engineering person-weeks, estimate | Staffing/dependency interpretation |
|---|---:|---|
| P0 foundations and risk spike | 12–20 | Establish contracts, platform feasibility and critical transaction prototypes |
| P1 complete core + invited social | 48–78 | Five integrated slices below; includes production controls needed for first social release |
| P2 SOS and Tonight | 8–14 | Reuses validated planning, sharing and saved-recipe services |
| P3 coordination, video and optional paid value | 38–66 | Three independent streams; enable only after their own gates |
| Total designed scope | 106–178 | Sum of engineering effort, not elapsed calendar duration |

Re-estimate after P0 using measured native integration, editorial throughput and permission-test results. Do not divide the total mechanically by headcount to promise a ship date. Review queues, cross-platform specialists and catalog decisions constrain the critical path.

## P0 — Foundations and risk spike

P0 builds enabling slices of F13 and F47–F54. It does not change the manifest's feature phase assignments.

Deliver a shared Kotlin workspace structure and pinned compatible toolchain; generated transport contracts mapped to domain models; typed routes; local schema/migrations and command queue; Ktor module boundaries; PostgreSQL migrations; principal/object authorization; idempotency and outbox infrastructure. Prepare separate development/test environments as an implementation task, with infrastructure code reviewed before anyone deploys it.

Prove four thin end-to-end paths early: guest → reviewed recipe → offline resume; authenticated circle member → photo quarantine → sanitized authorized delivery; post save versus grant revocation race; sandbox purchase → authoritative entitlement reconciliation. The photo path must demonstrate EXIF removal and inaccessible quarantine originals. The identity path must prove login/refresh/logout and invalid callback handling on real Android and iOS devices. Catalog drafting/review begins in parallel so later matching is not blocked by placeholder content.

Exit gate: both mobile targets build; module dependency tests pass; server object authorization and idempotency fixtures pass; the four spike paths have recorded evidence; no critical unresolved toolchain limitation. Any critical architectural change is applied to contracts and screen registry before P1 implementation expands.

## P1 — Integrated first-release slices

| Slice | Features and estimate | Concrete cross-layer deliverables | Exit evidence |
|---|---|---|---|
| S1: A meal that fits | F02–F11, F13, F17; 10–16 person-weeks | REQUEST/PANTRY/EFFORT/TASTE/results; preference/pantry APIs; deterministic filter/rank service; published recipe bundles; interpreter adapter with manual fallback; catalog-publish invalidation worker | Known exclusions never relaxed; mode/effort constraints survive alternatives and swaps; unsupported text/photos fail into confirmation; explanation matches evaluation |
| S2: Cook and remember | F12, F14–F20; 8–14 | COOK/TIMER/resume; pinned session APIs; offline local command queue; feedback/memory projection; cookbook/saved-copy reads; reuse option; explicit forget and rebuild | Native app-kill/restart/timer tests; idempotent completion; source-signal retraction; saved copies retain access under defined lifecycle |
| S3: Identity, circles and media | F21–F24, F31, F39–F43, F47–F49, F51–F54; 12–18 | Account/guest merge; profile and invites; composer/audience; quarantine upload/processing; Today/My Plate projection; signed delivery authorization; reports/blocks/delete and admin operations | Upload integrity/sanitization; publish atomicity; server-time expiry; audience/block/delete races; personal preferences absent from shared payloads |
| S4: A friend's plate becomes yours | F01, F25–F30 with integration across S1–S3; 10–16 | Make Mine from permitted source; atomic post recipe-save grants; recipe requests/private replies; reactions; Your Take/Remix Trail; notification fan-out with fresh authorization | Story → adapt → cook → optional attributed share on both platforms; private ancestor/media never leaked; save/revoke/delete races and duplicate replies handled |
| S5: Pilot hardening and operations | Remaining F47–F54 completion, F50 sandbox foundation; 8–14 | Recovery/exports/deletion jobs; support/moderation queues; telemetry/runbooks; native accessibility; migration/backward-compatibility test; sandbox restore/reconcile; pilot distribution package | Staff can resolve reports/recall and verify removal; account deletion/export drills; device matrix passed; rollout/rollback rehearsed |

The slice feature references overlap deliberately because integrations cannot be implemented in isolation. Engineering ranges sum once by slice, not once per repeated feature ID. F20 may remain behind an optional P1 flag until its reviewed relationships exist; it must never delay the immediate meal flow. Paid sales remain off even though F50 sandbox foundations are exercised.

Critical path: P0 contracts/platform proof → reviewed catalog + exclusion evaluator → pinned cooking/save semantics → social media/audience authorization → Make Mine/Your Take integration → privacy/moderation/native hardening → invited pilot. Content review, identity/provider setup and native device testing run in parallel where possible, but their acceptance evidence remains required before release.

## P2 — Useful new entry points

F32 Fridge SOS and F33 Tonight require 8–14 estimated engineering person-weeks together. SOS reuses explicit sharing, circles, threads and supported plan inputs; it must provide immediate personal meal help without waiting for a reply. Tonight revalidates a saved recipe against current ingredients, effort and rights. Deliver SOS_CREATE/SOS_DETAIL and TONIGHT clients, canonical API operations, explicit shared-field filtering, notification authorization and a plan-revalidation service; use existing outbox/worker infrastructure.

Exit gate: a missing friend response never blocks cooking; saved intent never creates an unsolicited reminder; stale permissions/exclusions are rechecked; both routes complete the existing cooking flow under offline and expired-source conditions. Release separately so the result can establish which entry point actually helps.

## P3 — Independent expansion streams

| Stream | Features and estimate | Implementation work | Required gate before enabling |
|---|---|---|---|
| Shared dinner coordination | F34, F35, F37; 12–20 person-weeks | Pact invitation/acceptance and per-person plans; volunteered ingredient contributions/claims; supported meal polls with vote replacement/close; participant-scoped threads/events | Atomic seat/claim/vote races; no private pantry or dietary-profile leakage; decline/leave has no penalty; actor roles and closing time enforced |
| Practical tips and short video | F36, F38; 10–18 | UGC shortcut provenance and helpful/save actions; separate editorial promotion; native capture/edit; duration/size validation; transcode/moderation workers and ready/rejected states | Unreviewed tips never change cooking steps; malicious/oversized media tests; background upload recovery; operating moderation/cost budget approved before video flag |
| Optional paid value | F44–F46 plus production activation of F50; 16–28 | Household roles/seats/consent/defaults and dissolve; custom collection tools with preserved basic access; reviewed versioned pack manifests; localized store offers; purchase/restore/webhook/reconciliation | Server-only entitlement authority; refund/restore/account-conflict scenarios; household access revocation; existing private saves readable after access change; completed pack review |

Shortcuts, video, each coordination feature and each paid offering have separate flags and independent enablement decisions. Subscription pricing, household seat limits and offered pack rights must be documented from the actual chosen configuration before sale; this roadmap does not set a live price or claim a platform sharing entitlement.

## Implementation ticket template and dependency control

Create tickets as deliverable verbs, for example: “F30 implement locked save-policy transaction and revoke-race fixture,” “F12 persist timer commands and verify process-death recovery on both platforms,” and “F18 recompute preferences after forget and prove replay cannot resurrect them.” Each ticket must include the relevant section of its feature spec as acceptance criteria.

The API/contract owner reviews request/response and event changes; module owners implement their tables behind service interfaces; shared-mobile owners implement state/rendering and platform ports; test owner adds cross-layer fixtures; editorial/moderation owners sign off operational flows. Outbox consumers require a recorded idempotency key, retry policy and dead-letter recovery owner. A ticket cannot claim completion because only the happy-path screen is clickable.

## CI and verification pipeline

1. **Contract and documentation gate:** validate OpenAPI/examples, unique operation IDs, screen/action references, feature coverage and backward-compatible diffs. Block an action that names no real API or approved native/local operation.
2. **Fast code gate:** formatting/static analysis, shared-domain unit tests, architecture dependency rules, secret scanning, dependency/license inventory and migration validation. Pin the reviewed toolchain and dependency lockfiles.
3. **Service integration gate:** ephemeral PostgreSQL tests for object ownership, exclusions/substitution versioning, idempotency, outbox replay, concurrent grants/memberships/votes, deletion and recall. Use provider fakes through the same contracts; fakes do not replace sandbox checks.
4. **Native build gate:** Android compile/package/test and iOS simulator compile/test on supported macOS runners. Verify generated shared artifacts and signed entitlement configuration separately; signing credentials never enter repository logs.
5. **Cross-platform journey gate:** guest → meal → interrupted cooking; account merge; photo → circle → Make Mine → Your Take; source privacy change; account recovery/deletion. Include poor network, expired sessions, denied OS permissions and N/N−1 client compatibility.
6. **Security/privacy/media gate:** authorization matrix across guest/member/owner/staff, cross-account IDs, callback misuse, upload limits/content sniffing/EXIF removal, private cache isolation, shortened media capabilities, replayed webhooks and log redaction. Confirm source photos/replies never enter recipe snapshots.
7. **Accessibility/native behavior gate:** VoiceOver and TalkBack, focus order, text scaling, contrast, touch targets, non-color status cues, reduced motion and keyboard use where applicable. Test native camera/pickers, timers and alert denial on physical devices.
8. **Billing and operational release gate:** platform sandbox purchases/restores/cancellations/pending/refund paths; authoritative reconciliation and event disorder; provider outage behavior; backups/restore drill, moderation queue, content recall and rollback rehearsal. Require the feature-specific device/operation evidence before enabling its flag.

Run fast gates per change and heavier native/security/media/billing suites on affected changes plus release candidates. Broaden tests in response to a change or failure, not by repeating unrelated suites without purpose. Store evidence with build/version identifiers and fixture seeds so a reviewer can reproduce a result.

## Rollout, observation and rollback

Progress through developer fixtures, internal device testing, invited pilot circles and a controlled broader release after gates pass. Configure feature flags by server capability, client minimum version and cohort. New client buttons are not authorization. Proposed operational SLOs and alert thresholds must be measured and tuned against the implementation's runbooks; do not publish unmeasured latency claims.

Observe accepted meal outcomes separately from taps, pending-command failures, authorization denials, content-recall lag, media processing failures, notification suppression and entitlement reconciliation. Analytics must avoid raw dietary input, private notes, credentials and signed URLs. Support and moderation owners need an actionable queue and response process before adding more users.

For rollback, disable new mutations at the server flag first, preserve safe reads/active cooking, drain or pause affected workers, and retain idempotency records. Use backward-compatible additive migrations; do not attempt destructive schema reversal while older clients or queued commands remain. Recipe retirement handles ordinary catalog rollback; explicit recall handles content that must stop being used. Disable new sales independently from established entitlements. After an incident, repair contracts/data under an audited runbook, replay only safe deduplicated events, and re-open the flag after the failed acceptance scenario passes.

## Final readiness check

All 54 feature specifications, screens/actions and API intents must resolve to an implementation owner and verified contract. P1 readiness additionally requires reviewed launch content, staffed moderation, working privacy/deletion/recall and physical-device cooking tests. P2/P3 remain designed backlog until their dependencies and exit gates are complete. Launch market, eligibility, region, store configuration and editorial/legal decisions remain explicit work items; no calendar promise or production authorization is inferred from completion of this documentation.
