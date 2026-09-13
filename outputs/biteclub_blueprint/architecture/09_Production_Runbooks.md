# Production execution, observability and recovery

All numbers are proposed acceptance targets and planning assumptions, not measured FeedMe results. Prepared 13 September 2026. This plan is executable engineering work for Android/iOS shared Kotlin and the proposed AWS deployment; no infrastructure or accounts have been created.

## Capacity model and initial envelope

Pilot planning envelope: 10,000 monthly active accounts, 2,000 daily active accounts, 200 concurrent foreground users, 40,000 API calls/day, 6,000 plan attempts/day and 50 requests/s dinner-time burst. The burst is approximately 100× daily average; validate with realistic arrival distributions rather than simply extrapolating averages. Proposed uploads: 400 photos/day at 3 MB average original and 0.3 MB sanitized derivative set gives 1.2 GB/day temporary original ingress and 0.12 GB/day derivative growth before retention. Later clip budget starts at 100 clips/day ×4 MB derivative average =0.4 GB/day; cap and meter separately.

Initial candidate deployment: two API tasks across two AZs, autoscale to six; two general worker tasks; isolated media queue and at most two concurrent clip-transcode tasks initially. Benchmark task CPU/memory and RDS class before selecting paid capacity. Budget database connection pools against actual configured max connections: reserve ≥20% for administration/failover; API at most 20 connections/task initially; workers at most 10/task; autoscale must not exceed the connection envelope. Queue depth scales workers; queue age and database load cap maximum concurrency.

Track cost per successful plan, completed cook, published photo and active household. Cost model is explicit: API/worker compute hours + database/backups + S3 retained bytes/requests + CDN bytes + model input/output usage + Cognito users + notification/provider fees + commerce provider plan. Prices, free tiers and launch region remain procurement inputs; no fictitious monthly cost is asserted. Stop model-assisted interpretation per actor at a daily budget; deterministic controls continue. Clips have their own kill switch and budget.

## Proposed service objectives

| Surface | Target | Measurement and exclusions |
|---|---|---|
| Core owned-resource reads/writes | 99.9% successful eligible requests over 28 days | Count API 5xx/timeout/incorrect 2xx; exclude valid validation/permission denials and planned sandbox tests, not provider failures |
| Core API latency | p95 ≤500ms, p99 ≤1.5s | Server ingress to response at 50 RPS target load, excluding plan interpretation/media transfer |
| Deterministic plan generation | p95 ≤1.5s | Valid structured requests with supported catalog; no-match is a valid result, separately measured |
| Interpreted meal request | p95 ≤6s, hard timeout 8s | Model adapter times out to manual confirmation; timeout rate <1% pilot target |
| Today authorization/expiry | Zero successful fresh unauthorized API reads | Test server clock boundaries, removed memberships, blocks, ACL generations; media bearer residual window is separately disclosed ≤60s |
| Image readiness | 95% ≤30s, 99% ≤120s | Successful supported image uploads from confirmed completion to ready; moderation review can show a distinct pending state |
| Later clips readiness | 95% ≤90s, 99% ≤5m | Supported ≤30s clip under stated queue envelope |
| Outbox propagation | p95 ≤5s, p99 ≤30s | DB commit to durable queue receipt; old-age alert includes backlog |
| In-app messages | p95 ≤2s available to foreground pull | Accepted message commit to authorized read; push delivery is best effort and not a durability guarantee |
| Entitlement reconciliation | 95% ≤60s after received event or user reconcile | Do not measure from purchase time when provider event has not yet arrived; show pending honestly |
| Crash-free foreground sessions | ≥99.8% pilot target | Separate Android/iOS and minimum supported versions; monitor media OOM separately |
| App first useful cached screen | p95 ≤2s warm; cold ≤3s candidate | Physical midrange Android and supported older iPhone with release builds; not simulator-only |
| Account deletion visibility | API deny/hide at accepted request commit | Background physical purge target ≤24h except documented permitted retention; status reports partial completion accurately |
| Disaster recovery | RPO ≤15m, RTO ≤4h proposed | Achievable only after timed restore drill validates backup latest-restorable point and erasure-ledger replay |

Targeting 99.9% permits roughly 40 minutes of unavailability in a 28-day window; error-budget burn, not a calendar promise, governs release pause. A 1-hour fast burn >14.4× plus a 5-minute confirmation alerts on-call; 6-hour burn >6× plus 30-minute confirmation escalates investigation. For privacy/authorization violations use zero-tolerance incident classification rather than waiting for aggregate SLO burn.

## Dashboards and logs

API dashboard: request count, 5xx, latency by operationId, auth rejection reason, 409/412/422 categories, throttling and database pool wait. Planning: supported-match rate, no-match reason, interpreter timeout/schema failure, invalid-substitution attempt, recall blocks, budget per accepted plan. Social: publish attempt→ready→commit latency, upload rejection code, Today expiry predicate failures, ACL generation misses and signed URL issuance. Jobs: oldest outbox row, oldest visible message, redrive count, DLQ depth and consumer errors. Commerce: raw receipt-to-durable ingress, reconciliation lag, customer-generation conflict, environment mismatch and known revocation count. Mobile: release/build, launch time, crash/ANR, network class, queue depth and sync conflict count.

Every request has traceId, operationId, featureId where known, redacted actor hash and result code. Never log request bodies by default. Use allowlisted fields, sampled successful traces, complete error traces without secrets, and metric labels of bounded cardinality. Do not label metrics with user IDs, recipe IDs or raw URLs. Audit staff changes separately with immutable actor, target, reason and prior/new state digest.

## Release sequence

1. Contract spike: compile both targets with pinned dependency matrix; prove system-browser/native auth, local database migration, keychain/keystore adapters, universal/app links, media picker and sandbox store purchase. Define lowest supported OS versions after physical-device testing and current SDK requirements.
2. Cooking vertical slice: auth/guest → explicit preferences → constrained plan → step/timer → completion → optional feedback → private save. Catalog must include enough professionally reviewed varied examples for honest no-match behavior; content count is a review capacity decision, not an invented recipe guarantee.
3. Social slice: circle invite → own photo quarantine → confirmed attachment/rights → audience/save disclosure → post → friend Make Mine → own Your Take. Pass ACL-race, copy-grant, expiry and deletion tests before any pilot social access.
4. Operations slice: staff roles, report/block, independent content review, recall, exports/deletion, flags, dashboards, provider reconciliation and restore rehearsal. These ship with social, not after it.
5. Later slices: SOS/Tonight, then pacts/potlucks/shortcuts/polls/clips, and separately paid household/library/packs. Each feature flag remains false until its own feature release gates and abuse/retention tests pass.
6. Promote through local synthetic fixtures → integration environment → internal physical-device release → invited cohort of 20–50 → 5% cohort → 25% → full eligible cohort. Keep each stage at least one dinner-time peak with sufficient sample counts; do not treat tiny cohorts as statistical proof.

Release candidate must pass API-schema compatibility, route coverage from screen registry, schema migrations, security negative tests, crash/accessibility smoke tests on both platforms and product-specific fixtures. Deployment uses immutable container digest and versioned config. Database changes expand first, then backfill, then enable reads. Rollback runs previous compatible image/flag; never reverse a destructive schema migration as an emergency shortcut.

## Incident runbooks

### RB01 — API/database outage

Trigger: sustained core 5xx/latency burn or database unavailable. On-call declares severity, freezes deploys and records affected operations. Check recent release/config, connection pool saturation, RDS events, CPU/storage/locks and AZ health. Disable nonessential workloads (feed precomputation, analytics, clip jobs), not authorization. Roll back last compatible application image if correlated. Allow managed failover; reconnect clients with jitter and bounded attempts, never retry non-idempotent unkeyed writes. Validate auth→plan→cook→save and duplicate-command replay before closing. If recovery requires restore, use RB09.

### RB02 — Unauthorized content exposure or failed expiry

Trigger: any confirmed cross-account read, new signed URL issued after revocation, or Today-only content served by API after expiresAt. Immediately disable affected social/media issuance operation server-side; preserve cooking. Invalidate implicated object paths and remove sanitized origins for urgent takedown. Revoke affected session families if credential misuse suspected. Preserve minimal restricted evidence and trace IDs, determine scope from access logs without redistributing media, notify privacy/security owner, apply legal notification process as directed by confirmed obligations. Patch policy and add regression/race fixture. Do not claim issued bearer URLs or user screenshots were recalled. Restore only after a distinct reviewer proves denial and current ACL generation checks.

### RB03 — Media backlog, processing failure or hostile upload

Trigger: oldest image job >120s for 10m, DLQ growth or decoder crash. Pause clip intake first; cap concurrency to protect DB. Inspect safe rejection/error codes and one restricted sample only if necessary; do not dump user media to logs. Quarantine suspected decoder exploit, roll to patched scanner image, retry owned immutable object versions through allowlisted replay. If publish waits, composer shows Processing/Retry with no false live post. Originals remain private. Sweep orphan reservations by expiry. Recovery gate: valid JPEG/HEIC and clip fixtures, over-limit/decompression-bomb rejection, same-key re-upload race, and no quarantine CDN access.

### RB04 — Model provider failure or invalid recommendations

Trigger: interpreter schema-error/timeout budget exceeded, exclusion breach attempt, or unexpected unsupported step. Disable interpretation only and route to manual ingredient/time/effort controls. If deterministic matching violated constraints, disable affected catalog/plan revision and recall dependent snapshots as appropriate. Keep a restricted redacted trace with recipe IDs and constraint kind, not personal wording. Replay deterministic fixture bank containing every exclusion/required-step edge. Provider recovery alone does not close a correctness incident; prove the engine cannot relax constraints.

### RB05 — Duplicate, delayed or lost purchase events

Trigger: reconciliation lag >5m, webhook signature errors or mismatched entitlement status. Ingress continues durably storing authenticated unique event IDs; return 200 only after commit. Check secret rotation, environment mapping, provider health and worker generation ordering. Reconcile authoritative customer state for bounded affected customers. Never grant new access from client screenshots or raw event type alone. Known refunds/revocations remain denied; previous verified access uses only documented bounded grace. Test duplicate, reversed delivery order, transfer/restore collision, sandbox mixing and timed-out provider response. Reconcile recently active customers nightly so missed webhooks do not become permanent drift. Manual correction requires audited staff role and expiry.

### RB06 — Recall or unsafe catalog instruction

Trigger: reviewer-confirmed recipe/substitution issue or urgent credible report. Qualified reviewer/safety staff identify exact immutable versions and applicability, set recall transactionally and emit catalog recall. Block new plans, adaptation, cooking starts, attachments and copies using those versions. Active online cooks receive clear stop/check guidance appropriate to the reviewed issue; show replacement version only after approval. Flag existing saved copies and private plans; offline clients cannot be guaranteed instant notice. Authorize human-reviewed notice text, record counts and delivery state. Never merely edit an old published JSON step and hide the history. Recovery requires independently reviewed new version and tests proving dependent graphs no longer reference recalled edges.

### RB07 — Message/social abuse escalation

Trigger: urgent threat/privacy report, spam burst or moderator backlog exceeding staffed window. Rate-limit abusive actions and use scoped block/suspend; retain reporter confidentiality. Moderator claims case, reviews only required evidence, records reason/action, and routes appeal to another reviewer. Removed content stops new access immediately. A deletion/takedown cannot be undone by replayed post-published events. Watch queue age by severity: proposed urgent triage <1h during declared staffed coverage; public launch cannot advertise 24/7 handling without actual staffing. If capacity fails, pause new public-facing social expansion and preserve private cooking.

### RB08 — Export/deletion job stuck

Trigger: job >24h or repeated stage error. Account remains hidden after deletion request even if downstream provider/S3 purge fails. Inspect stage cursor, object deletion version, provider response and retention hold reason; retry that stage idempotently. Exports reauthorize owner before signing and expire automatically. Verify no new push/renewed session reintroduces data. Give user a truthful job status and support reference without exposed records. Completion evidence contains counts and policy version, not deleted content. Rehearse deletion while media/message/reconciliation jobs are queued.

### RB09 — Database restore/disaster recovery

Run quarterly and before public launch. RDS point-in-time recovery creates a restored DB instance; this is not an in-place rewind. Record actual latest-restorable time and select target, restore into isolated networking, apply pending compatible migrations, then replay the erasure/recall ledger and validate provider reconciliation checkpoints. [RDS point-in-time recovery](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PIT.html)

Before routing traffic: validate row counts and constraints, credential isolation, deleted-account denial, post expiry using current time, stale memberships, outbox/inbox dedupe continuity, payment reconciliation and catalog recall state. Replayed events cannot restore deleted media or regenerate expired notifications. Switch traffic only after dual review of data and privacy checks. Measure RPO/RTO from incident start; document gaps. Retain original DB until recovery verification/retention policy allows disposal. Do not run destructive wipe commands as part of a hurried failback.

### RB10 — Faulty mobile release or local migration

Trigger: crash-free sessions below target, migration crash or offline cook loss. Stop store rollout and set minimum affected feature flag off; do not force update all users into a broken release. Use compatibility server path for prior app version, preserve raw local DB backup before migration where platform storage policy permits, and repair forward with tested migration. Critical evidence: existing active cook resumes, logout isolates accounts, encrypted storage lock failure has recovery UI, old deep link opens safe unavailable rather than crash. A server rollback cannot undo an installed mobile binary; keep N/N−1 support until adoption and migration evidence allow retirement.

## Test and signoff matrix

| Layer | Required examples | Owner/signoff |
|---|---|---|
| Shared unit/property | constraint filtering never relaxes hard exclusion; serving-range bounds; quantity units; state reducers; timer deadline; cursor parsing | Kotlin owner |
| Database integration | concurrent save/revoke, publish/member-remove, claims, vote-close, owner-transfer, merge, idempotency mismatch, duplicate outbox | Backend owner |
| Provider sandbox | Cognito verify/recovery/refresh, revoked app session, both stores purchase/restore/refund, webhook signature+raw body | Identity/commerce owner |
| API consumer contract | generated clients compile, required fields/examples validate, old client additive compatibility, all screen actions map | API + mobile owners |
| End-to-end physical device | signup→cook→post→friend Make Mine; no-account cooking; offline resume; rejected media; unavailable deep link; accessibility at large text | QA + design |
| Security/privacy | every matrix negative case from security doc; retained copy vs media grant; deletion/restore, private household constraints | Security/privacy owner |
| Content | reviewed recipe fixture cooks as specified, practical effort accurate, substitutions scoped, required steps preserved | Qualified reviewer |
| Performance | 50 RPS mixed workload, 2× spike, 24h soak, 400 photos/day distribution, queued clip isolation; measure costs | Platform owner |

The blueprint's local validators can prove documentation and map consistency only. Production claims require these runtime gates, actual staffing, configured cloud/store accounts and measured evidence.
