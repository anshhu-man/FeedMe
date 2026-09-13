# FeedMe threat model

M7.01 design deliverable · 13 September 2026 · **proposed controls, not a production security assessment**.

FeedMe must preserve low-effort healthy cooking, Make Mine, Today, My Plate and kitchen circles without turning private cooking choices into social data. This model applies to the retained 54-feature / 98-screen blueprint, including later coordination and paid features. A first-release manifest can select implementation order only after owner approval; it cannot silently remove these trust requirements.

## Evidence and authority

The companion [security acceptance matrix](SECURITY_ACCEPTANCE_MATRIX.md) translates these threats into test fixtures, canonical screen/action/API references and required evidence. These two documents complete a specification task only. They do not establish M7 implementation, store readiness, legal compliance or actual moderation coverage.

Reviewed sources, in precedence context:

- [Security and privacy design](../../outputs/biteclub_blueprint/architecture/10_Security_Privacy.md): proposed authentication, authorization, media, rights, staff and erasure controls.
- [Data model](../../outputs/biteclub_blueprint/architecture/03_Data_Model.md): ownership, immutable versions, transaction order, membership generations, retention proposals and lifecycle states.
- [OpenAPI contract](../../outputs/biteclub_blueprint/architecture/04_API_Contract.json): 201 canonical operations; version `1.0.0-draft`, explicitly undeployed example host.
- [Screen/action registry](../../outputs/biteclub_blueprint/registry/screen_registry.json) and [canonical button bindings](../../outputs/biteclub_blueprint/registry/button_actions.csv): 98 screens and 900 action bindings. A local navigation action may lead to protected hydration or a later server command; it does not itself authorize data.
- [Events and offline behavior](../../outputs/biteclub_blueprint/architecture/05_Events.md), [runbooks](../../outputs/biteclub_blueprint/architecture/09_Production_Runbooks.md), [F30 copy rights](../../outputs/biteclub_blueprint/features/F30.md), [F49 account lifecycle](../../outputs/biteclub_blueprint/features/F49.md), and [F50 billing](../../outputs/biteclub_blueprint/features/F50.md).
- Current local [models](../shared/core/src/commonMain/kotlin/com/feedme/core/Models.kt), [reducer](../shared/core/src/commonMain/kotlin/com/feedme/core/Reducer.kt), [visibility rules](../shared/core/src/commonMain/kotlin/com/feedme/core/PlateVisibility.kt), and [domain tests](../shared/core/src/commonTest/kotlin/com/feedme/core/ReducerTest.kt). The reducer describes itself as a pure in-memory preview, not a security boundary. Also inspected the newly added [client ports](../shared/core/src/commonMain/kotlin/com/feedme/core/ports/ClientPorts.kt) and [explicit demo runtime](../shared/core/src/commonMain/kotlin/com/feedme/core/KitchenRuntime.kt).

Where specifications conflict, the conflict below is a release blocker to resolve, not permission to choose the easier rule. Provider setup, policy wording, content rights and operational owners remain subject to [user action IDs U04–U08](USER_ACTIONS.md).

## What exists versus what must be proved

The inspected core has explicit `DEMO`/`PRODUCTION` modes, simulated identities, local plate filtering, reviewed-status checks, local command deduplication and reset/logout clearing. It uses fixture circle membership and the injected client clock. The new client ports define account/environment storage scopes, redacted wrapper types and a session lease that rejects stale client results after logout or account switch. They specify secure/durable storage and network boundaries but do not supply a real secure store, durable store or network adapter. They do not authenticate users, query current server membership, issue signed media, enforce a distributed transaction, process a provider webhook, perform account erasure or know that a disconnected saved recipe has been recalled.

In particular:

- `canViewPlate` returns false outside the explicit simulated demo. Its tests are useful local behavior evidence, never proof of server authorization.
- `SourceAttribution` retains source name/IDs in local saves; production responses need current redaction and rights decisions.
- Demo `BlockUser` removes saved recipes attributed to that author. Do not carry this behavior into production as an erasure/copy-rights decision; immutable authorized private copies and ordinary source visibility are different resources.
- Demo `ReviewStatus` has no production recall lifecycle. Rejecting `UNKNOWN`/`REJECTED` or unreviewed content is not a recall propagation implementation.
- Pure reducer idempotence does not establish concurrent database correctness, durable recovery, process-death behavior or multi-account storage isolation.

All server, provider, native credential-storage and physical-device gates in the matrix remain **NOT RUN** unless separate evidence proves them. A passing document checker or demo test does not change that status.

## Assets and damage to prevent

| Asset | Harm to prevent | Required owning boundary |
| --- | --- | --- |
| Credentials, callback state, guest bearer, app session and push token | Account takeover, credential replay, cross-account notifications | Native protected storage + identity service |
| Preferences, exclusions, pantry, feedback, memories, plans and private cookbook | Disclosure or alteration of another person's private or potentially sensitive choices | Principal ownership on every read/write; account-scoped local storage |
| Reviewed recipes, substitutions and immutable plans | Unreviewed or recalled instructions shown as approved; hard exclusions silently relaxed | Independent editorial workflow + deterministic planning engine |
| Posts, clips, original media, audience membership and source attribution | Delivery after expiry/revocation; private-source metadata exposed to wider audience | Social authorization + quarantined media pipeline + bounded delivery capabilities |
| Recipe grants and recipient copies | Unlicensed redistribution, accidental destruction or prohibited retention | Versioned rights/grant policy + explicit erasure/recall policy |
| Invitations, threads, polls, pacts, potlucks and households | Unauthorized entry, impersonated replies, double claims, restriction inference | Current membership/role checks + transactional aggregate rules |
| Purchases, account association and entitlements | Forged unlock, double grant, refund rollback, account resurrection | Native storefront + authenticated provider intake + authoritative server ledger |
| Reports, staff evidence, exports, audit records and backups | Insider overreach, reporter exposure, export theft, erasure undone by restore | Separate workforce identity, restricted jobs/storage and audited recovery |

## Actors and trust boundaries

Use synthetic actors only during development. The threat model includes an unauthenticated caller, two unrelated guests, a legitimate member, an unrelated member, a removed/blocked member, object owner, circle/household owner, role-limited staff, compromised client, abusive authorized user, and delayed/duplicated provider or worker messages. "Owner" is resource-specific: a household owner does not own another member's food preferences; a post author does not own a recipient's entire cookbook; a paid subscriber is not a staff member.

| Boundary | Untrusted input crossing it | Checks required before effects |
| --- | --- | --- |
| B1 native app ↔ provider/browser | Redirect URI, code, state, nonce, refresh response, native billing callback | Exact outstanding auth transaction; system/native flow; protected token storage; billing result remains provisional |
| B2 app ↔ API | JWT/guest bearer, session ID, every path/query/body ID, ETag, command key, claimed capability | Credential kind/issuer/client/scope/time, live session/account binding, current object authority, schema/size limits and server time |
| B3 API ↔ database | Ownership queries, policy roots, concurrent commands, outbox writes | Derive actor from verified context; lock/recheck mutable authorization roots; atomically commit domain mutation, idempotency result and outbox |
| B4 upload ↔ quarantine worker ↔ delivery | Filenames, bytes, MIME, dimensions, checksums, object versions, post/surface/media IDs | Owned reserved immutable version; bounded decode/scan; safe re-encode; separate quarantine/delivery permissions; current issuance authorization |
| B5 API ↔ queue/worker/provider | Events, retries, clock skew, provider customer IDs and product environment | Durable dedupe, schema/version checks, fresh lifecycle/ACL check at effect, authoritative reconciliation and no resurrection |
| B6 consumer identity ↔ staff operations | Staff-looking consumer claims, role, case scope, reason, flag change/replay input | Separate workforce client/MFA, least privilege, conflict separation, bounded explicit commands and append-only audit |
| B7 active storage ↔ export/backup/restore | Archive selection, output capability, retention hold, restored rows/events | Owned-data selection, third-party redaction, scoped short URL, policy-versioned purge and erasure/recall ledger replay before traffic |
| B8 external/freeform content ↔ planner | Captions, recipe text, source URLs, model output, community shortcuts | Treat as data; validate structured constraints; no arbitrary URL fetch/tools; only reviewed deterministic transforms; no inferred allergy safety |

Clients are adversary-controlled for server authorization purposes. A successful UI check, UUID, hidden button, signed-in screen, feature flag, cached plan or provider purchase callback is not proof of permission. Staff role and object ownership are separate checks. No endpoint may allow a flag to disable authorization, hard-exclusion checks or recall enforcement.

## Principal and object policy

The API's `x-principal` values (`public`, `both`, `user`, `admin`, `webhook`) identify entry credential classes, not full permissions. Every operation also needs object, lifecycle and capability checks.

| Object class | Guest | Ordinary member | Object owner / scoped privileged role | Staff |
| --- | --- | --- | --- | --- |
| Private cooking data | Own bounded guest scopes only | Own principal only | No extra rights over another person's data | No blanket consumer-data access; only separately justified incident process |
| Free catalog / paid tools | Published permitted free material | Free plus verified entitlement where required | Ownership alone does not unlock paid tools | Author/reviewer/publisher roles separated |
| Social media and post | No social access | Current permitted audience, active source/viewer membership and no block | Author updates own object; keep flag does not broaden audience | Scoped moderation removal/evidence access only |
| Circle / invitation | Minimal token-bound public preview only; no acceptance | Current member permissions | Owner/admin role; transfer/dissolve owner-only; last-owner invariant | Not automatically a member or invitation recipient |
| Thread / coordination | Denied | Explicit active participant, own contribution/vote/reply | Host config subject to participant and version rules | Case-limited safety action, not unrestricted message browsing |
| Household | Denied | Accepted seat; own selected sharing fields | Owner edits common kitchen, never another member's exclusions | No entitlement or owner bypass |
| Export / deletion / sessions | No member-job access | Own identity/resource; required recent auth | No transfer to a circle or household owner | Restricted incident role must be separately specified and audited |

Private-resource read denial should be indistinguishable from nonexistent IDs (`404`). `403` is for a forbidden action on an object the actor legitimately knows; `410` can explain authorized-known expiry. Responses, counts, pagination cursors, errors, thumbnails, source labels and logs must all obey the same non-disclosure rule. Do not equate a denial status with privacy if the response body or side effects disclose the object.

## Critical invariants and residual risks

1. **Access uses current state and a definite transaction order.** Save-versus-revoke, publish-versus-remove, invitation capacity, ownership transfer, two claims and vote close must share lock roots or retry serializable transactions. No preflight-only permission check.
2. **Today is a placement, not a deletion worker.** At `publishedAt + 24h`, Today reads stop even when the expiry worker is down. A retained My Plate post still uses its original/current audience; it never becomes public through retention. Leave/rejoin uses membership generations so historical author grants do not revive accidentally.
3. **Media authorization is not instantly retractable.** New signed derivative URLs require current post/surface/media/ACL permission, proposed lifetime at most 60 seconds and no later than Today-only expiry. Existing bearer delivery may persist within that window; already-started transfers and user-downloaded copies are not retractable promises. Cache validation and emergency invalidation reduce exposure, not erase screenshots.
4. **Save grant ≠ source/media access ≠ republishing license.** Only exact authorized recipe version/hash is copied. No original media, comments, audience or private preferences. A wider-audience Your Take must not leak a private ancestor's name/title/photo/count; own photo and permitted source link/changes do not prove rights to publish the complete source card.
5. **Review, recall and personal content are distinct.** Review applies to immutable content and substitutions. Mandatory cooking steps and exclusions survive every supported transformation. Personal/community notes never gain review through votes, author assertion or model output. Recall must gate new plans/cooking/attachments/copies and reach active/cached content; disconnected clients cannot learn an event instantly.
6. **Queues are not authorization caches.** Recheck membership, block, account state, recall, schedule version and entitlement when a worker sends or mutates. Idempotency suppresses duplicate effects but may not replay private response content to a principal that has since lost access.
7. **Revocation and erasure have honest states.** Immediate hide/revoke is not physical erasure. Each module/provider/object purge stage retries independently; approved retention holds are disclosed, minimal and auditable. Replayed purchase/media/notification events and restored backups cannot re-create a deleted account or content.
8. **Entitlement state is server-owned.** Raw native success, client `providerCustomerId`, webhook arrival order and an expired cached entitlement cannot unlock new premium actions. Known revocation cannot be overwritten by an older reconciliation generation. Restore/account transfer needs an approved association policy.
9. **No secrets or cooking details in telemetry.** Use the existing allowlisted event taxonomy and bounded labels. Exclude credentials, callback/invite queries, signed URLs, captions, messages, exclusions, raw meal requests, recipes and export contents. Necessary staff audit is separately scoped from optional analytics.
10. **Native account separation survives lifecycle changes.** Tokens and per-account keys stay behind platform adapters; secrets are not bundled or in common preferences. Logout, reauth failure, device-lock/key invalidation, process death, backup/restore and switching A → B must not expose or replay A's data. Approved offline private content is not a license for fresh server/social access.

## Retention and erasure inventory — awaiting policy approval

These are copied planning values from the data model, not approved legal retention or measured guarantees. U06/U08 must settle rights, audience/market, disclosure wording, holds and accountable operators before public release. Every table, object prefix, cache, provider record and event containing user data needs an owner, export behavior, erasure behavior, maximum age, hold authority and test fixture. New stores fail release inventory review until included.

| Data class | Blueprint proposal | Required proof / unresolved boundary |
| --- | --- | --- |
| Guest state / unpublished drafts / uncommitted quarantine | Guest 7-day inactivity and 30-day absolute; draft 30 days since edit; quarantine 24h | Server timestamps, retry-safe sweeper, immediate owner deletion; guest merge cannot extend ownership indefinitely |
| Today-only derivatives | Deny at 24h; proposed purge within a further 24h | Access stops before worker purge; no resurrection after failed worker/replay |
| Retained post media | Until deletion/removal; proposed derivative purge within 24h | Owner receipt + all variants/object versions/CDN paths; restricted evidence handled separately |
| Preferences, pantry, feedback, memories and private saves | Until owner deletion/erasure or applicable recall | Source feedback removal rebuilds memories; minimum suppression footprint; copy rights/erasure conflict below unresolved |
| Messages / shared UGC / restricted evidence | Approved shared-content and incident policy needed | Sender-contribution redaction, reporter confidentiality, justified holds; no promise to erase recipient screenshots |
| Idempotency, outbox and inbox | Results 7 days; hot events 30 days; event-ID tombstones 90 days | Replay horizon fits dedupe retention; retained response bodies obey later erasure and authorization |
| Raw intents / logs / purchase evidence | No raw intents normally; opt-in diagnostics cap 7 days; redacted logs 30 days and audit candidate 90 days; purchase retention unresolved | Per-field allowlist and purpose; no "forever" default or raw sensitive payload copied into logs |
| Exports | Available 24h; purge object within 48h after expiry | Owner-only fresh issuance; no public emailed URL; archive excludes third-party secrets; retention clock is not URL TTL |
| Backups | Proposed encrypted 14-day PITR window | Isolated restore replays erasure/recall ledger before serving; timed receipts, approved expiry and holds |

## Contract decisions and implementation gaps

| ID | Evidence | Required resolution, without silently approving policy |
| --- | --- | --- |
| C01 — **actual policy contradiction** | F30 "Permissions and lifecycle" says ordinary post/**account** deletion leaves private recipe content available. F49 and Security_Privacy distinguish ordinary post deletion from account erasure, defaulting to recall of personal user-authored copies where continued retention is not demonstrably permitted. | U06/U08 plus privacy/content owner must approve exact rights-class handling and amend F30, disclosures and tests together. Until then no release claim that personal third-party copies necessarily survive account erasure. Ordinary post deletion and future-save revocation remain distinct from erasure. Matrix SEC-19/SEC-32. |
| C02 — deletion completion contract incomplete | Data model allows an opaque receipt after removal; F49 explicitly says revoked sessions cannot poll protected jobs and refers to an approved receipt/support process. `getJob` only accepts live `UserBearer` + `X-Device-Session`; `Job` has no deletion receipt capability. | Design the minimal non-content deletion receipt/support transport and its authentication/abuse policy before M7.04 completion. Do not weaken `getJob`, preserve a live deleted-account session, or invent an endpoint here. Matrix SEC-31/SEC-33. This is a missing contract, not evidence that protected job polling should be public. |
| C03 — direct flag mutation is underspecified | `ADMIN_FLAGS.03` means disable; its `adminUpdateFlag`/`FlagWrite` accepts both boolean values and a 0–100 rollout. Security design requires reviewed proposals and a distinct approver for increased exposure; `adminApplyFlagChange` models that path. | Make the direct command's server semantics restrictive-only or otherwise enforce independent approval without a direct-call bypass; specify typed rejection and compatibility behavior. A permissive schema is not permission. Matrix SEC-28. |
| C04 — export recent-auth proof path underspecified | F49 says export authenticates recent identity. `requestAccountExport` exposes `ExportRequest`, whereas deletion/revoke-all explicitly carry `reauthenticationProof`; normal refresh is not fresh auth. | Specify the provider-verified freshness carrier/check for export (and download issuance if policy requires it). Do not silently accept an arbitrary recent JWT issue time or client boolean. Matrix SEC-30. |

Provider check: current [RevenueCat webhook documentation](https://www.revenuecat.com/docs/integrations/webhooks#webhook-signature-verification-hmac), read 13 September 2026, documents opt-in `X-RevenueCat-Webhook-Signature`, HMAC over timestamp and raw body, and a newly signed timestamp per retry. Thus the canonical HMAC requirement is not a discovered incompatibility. Actual integration enablement and secret rotation remain U07 configuration evidence, not an assumed production control.

## Signoff and change control

Implement the matrix alongside M1–M7; run it on the exact candidate/environment for M8.04. Record source/build/config/schema versions, synthetic fixtures, provider sandbox/live distinction, negative assertions, concurrency schedule and owner signoff. `NOT RUN`, unavailable evidence and unresolved policy are not passes. Any unauthorized fresh read, entitlement escalation, unreviewed/known-recalled cooking path, credential leakage or erasure resurrection blocks release of the affected surface.

The backend/security owner owns B2–B7 implementation; mobile owners own B1/native lifecycle; qualified content owner owns review/recall; privacy/operator owns retention and response; commerce owner owns provider association; QA executes independent negative tests. These are required roles, not claims that named people have accepted them. Revisit this model for every new credential class, data store, media surface, external ingestion path, paid capability or release-scope change.
