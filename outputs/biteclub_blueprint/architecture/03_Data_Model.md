# Data model and transaction invariants

Proposed PostgreSQL schema, 13 September 2026. Names below are canonical persistence names; API uses camelCase. `id` means UUID, generated server-side except documented client-command IDs. Times are `timestamptz` in UTC; time-zone names use IANA identifiers. All mutable aggregate roots have `version bigint NOT NULL DEFAULT 1`, `created_at`, `updated_at`. Every change increments version and returns ETag. Immutable recipe/plan snapshots are never edited in place. API DTOs intentionally omit internal evidence, provider credentials and object-storage keys.

## Ownership and common types

`principals(id,kind,user_id?,guest_session_id?,status)` establishes one owner key for registered and guest private data; a CHECK enforces exactly one matching subtype. `users` is permanent app identity independent from a provider's mutable email. `principal_id` is present on all private plan, pantry, feedback and save rows. User-owned social objects use `author_user_id` and require kind=user. UUID possession is not authorization.

Quantities use `numeric(12,4)` plus canonical unit ID; do not mix strings such as "half packet" into arithmetic. Optional human notes are separately bounded. Ingredient IDs reference canonical taxonomy. Enums use CHECK constraints for additive rollout without disruptive enum migration. Text lengths are validated in both API and database where stable. JSONB is reserved for versioned immutable documents and constrained payloads, not opaque ownership or status.

## Tables and required indexes

| Module/table | Significant columns beyond common root fields | Keys, indexes and invariants |
|---|---|---|
| identity.users | provider_issuer, provider_subject, status, terms_version, terms_accepted_at, eligibility_policy_version, eligibility_state, deleted_at | UNIQUE(provider_issuer,provider_subject); no email as identity key; index(status,created_at) |
| identity.principals | kind, user_id, guest_session_id, status | Unique subtype FK; private-resource owner lookup never nullable |
| identity.guest_sessions | token_hash, inactivity_expires_at, absolute_expires_at, last_seen_at, merged_to_user_id, merge_result_json, revoked_at | UNIQUE(token_hash), index(absolute_expires_at) WHERE revoked_at IS NULL; opaque token only returned once |
| identity.device_sessions | user_id, installation_id_hash, platform, token_family_id_hash, last_seen_at, revoked_at | UNIQUE(user_id,installation_id_hash) WHERE revoked_at IS NULL; lookup(id,user_id); app session checked every request |
| identity.guest_merge_items | guest_session_id, source_type, source_id, destination_id | PK(guest_session_id,source_type,source_id); makes interrupted imports resumable without duplication |
| profile.profiles | user_id, display_name, normalized_handle, bio, avatar_media_id, onboarding_step | UNIQUE(user_id), UNIQUE(normalized_handle) WHERE live; handle tombstone cooldown policy proposed 30 days |
| profile.preferences | principal_id, hard_excluded_ids uuid[], dietary_patterns text[], disliked_ids uuid[], equipment_ids text[], taste_tags text[], default_energy, default_servings | UNIQUE(principal_id); validated canonical IDs; GIN only if actual staff aggregate use needs it, not initially |
| profile.notification_settings | user_id, event_settings jsonb, quiet_start time, quiet_end time, time_zone | UNIQUE(user_id); schema-validated map of supported event booleans |
| profile.privacy_settings | user_id, default_audience_json, allow_circle_member_messages, allow_recipe_requests, analytics_consent, public_discovery_enabled=false | UNIQUE(user_id); defaults affect future objects only |
| pantry.pantry_items | principal_id, ingredient_id, presence, confirmation_status, quantity?, unit_id?, confirmed_at?, staple | UNIQUE(principal_id,ingredient_id), index(principal_id,confirmed_at DESC); quantity≥0; usuallyHave/uncertain may have no confirmation time; stale availability never treated as confirmed |
| pantry.prepared_portions | principal_id, plan_id, label, prepared_at, amount?, unit_id?, user_confirmed | index(principal_id,prepared_at DESC); no automatic freshness/safety inference |
| catalog.ingredients | canonical_name, category, aliases jsonb, allergen_tags text[], supported_units text[], status | Trigram/name search index only after extension approval; normalized alias lookup table UNIQUE(locale,alias,ingredient_id) |
| catalog.recipes | current_published_version_id?, author_staff_id, status | index(status,id); pointer changes only to approved version |
| catalog.recipe_versions | recipe_id, revision_no, title, payload jsonb, content_hash, review_status, content_license, submitted_by, reviewed_by?, reviewed_at?, published_at?, recalled_at?, recall_code? | UNIQUE(recipe_id,revision_no), UNIQUE(recipe_id,content_hash), index(review_status,published_at DESC); payload schema version stored; submitted_by != approving reviewer |
| catalog.recipe_ingredients | recipe_version_id, ingredient_id, quantity, unit_id, optional, preparation | PK(recipe_version_id,ingredient_id,position), reverse index(ingredient_id,recipe_version_id); normalized rows must hash-match payload |
| catalog.recipe_steps | recipe_version_id, step_id, position, instruction, required_equipment_ids, duration_seconds, mandatory_step | PK(recipe_version_id,step_id), UNIQUE(recipe_version_id,position); positions contiguous on review submission |
| catalog.substitutions | from_ingredient_id, to_ingredient_id, ratio, preserved_tags, step_patch jsonb, review_status, submitted_by, reviewed_by, content_hash | index(from_ingredient_id,to_ingredient_id,review_status); no global substitution assumed safe in every recipe |
| catalog.substitution_applicability | substitution_id, recipe_version_id, min_servings,max_servings | PK(substitution_id,recipe_version_id); immutable reviewed edge applicability |
| catalog.reviews | target_type, target_id, reviewer_id, decision, checklist_version, checks jsonb, notes, reviewed_at | index(target_type,target_id,reviewed_at DESC); append-only decision history |
| catalog.recalls | target_type, target_id, reason_code, scope, initiated_by, effective_at, resolution_version_id? | index(target_type,target_id,effective_at); immutable recall event, never merely unpublish |
| planning.plan_requests | principal_id, source_type, source_id?, mode, constraints_json, preference_version, catalog_revision, interpretation_json?, interpreter_version?, expires_at | index(principal_id,created_at DESC); natural-language raw input not retained by default |
| planning.plans | principal_id, request_id, parent_plan_id?, recipe_version_id, source_post_id?, snapshot_json, snapshot_hash, proof_json, explanation_json, ranking_version, status | index(principal_id,created_at DESC); FK parent same principal enforced in service transaction; immutable snapshot |
| cooking.cook_sessions | principal_id, plan_id, status, current_step_id, device_sequence, started_at, completed_at?, timer_state_json | index(principal_id,status,updated_at DESC); completion terminal; step IDs must belong to pinned plan |
| cooking.step_events | session_id, command_id, device_session_id, device_sequence, kind, payload, accepted_at | UNIQUE(session_id,command_id); index(session_id,accepted_at); monotonic per-device sequence |
| memory.feedback | principal_id, cook_session_id?, target_kind, target_resource_id?, target_tag?, taste?, effort?, make_again?, note?, deleted_at? | UNIQUE(principal_id,cook_session_id) WHERE live AND cook_session_id IS NOT NULL; canonical recipe/ingredient/taste/preparation targets validated; no inferred feedback or another user's memory |
| memory.memories | principal_id, kind, semantic_key, value_json, confidence?, enabled, derivation_version, user_override | UNIQUE(principal_id,kind,semantic_key); index(principal_id,enabled); explicit override wins |
| memory.memory_sources | memory_id, feedback_id, signal_key | PK(memory_id,feedback_id,signal_key); cascade owned feedback deletion to rebuild jobs |
| memory.memory_suppressions | principal_id, semantic_key, source_fingerprint, suppressed_at | UNIQUE(principal_id,semantic_key,source_fingerprint); forget cannot immediately recreate from unchanged old signals |
| memory.saved_recipes | principal_id, recipe_snapshot_json, recipe_hash, source_type, source_recipe_version_id?, source_post_id?, save_grant_id?, creator_label?, recalled_at?, content_license | index(principal_id,created_at DESC), UNIQUE(principal_id,source_type,source_id,recipe_hash); media/comment fields forbidden from snapshot |
| memory.collections | principal_id, name, description, smart_rule?, entitlement_key? | index(principal_id,updated_at DESC); paid expiry makes tools read-only while basic recipe access remains |
| memory.collection_items | collection_id, saved_recipe_id, position | PK(collection_id,saved_recipe_id); index(collection_id,position,saved_recipe_id); same-owner assertion |
| social.post_drafts | author_user_id, client_draft_id, draft_json, status, published_post_id?, last_touched_at, expires_at | UNIQUE(author_user_id,client_draft_id); index(expires_at) WHERE status=draft; draft attachments structured and versioned |
| social.posts | author_user_id, caption, status, published_at, expires_at, keep_on_plate, acl_version, source_post_id?, deletion_reason? | index(published_at DESC,id DESC) WHERE status=published; index(author_user_id,published_at DESC,id DESC) WHERE status=published AND keep_on_plate; expires_at=published_at+24h |
| social.post_audiences | post_id, audience_kind, circle_id?, author_membership_generation? | Unique(post_id,circle_id); self has no circle; index(circle_id,post_id); evaluate current viewer membership and active author generation so leave/rejoin does not resurrect historical grants |
| social.post_media | post_id, media_id, position | PK(post_id,media_id), UNIQUE(post_id,position); only READY sanitized owned asset may attach |
| social.post_attachments | post_id, recipe_version_id?, own_plan_id?, personal_recipe_json?, confirmed_changes_json, confirmed_at, rights_basis | UNIQUE(post_id); exactly one source kind; source grant with privateCopyOnly cannot authorize a new full-card publication |
| social.recipe_save_policies | post_id, recipe_version_hash, allow_future_saves, policy_version, disclosure_version, accepted_at | PK(post_id,recipe_version_hash); policy version locked during save transaction |
| social.recipe_save_grants | post_id, recipient_principal_id, policy_version, recipe_hash, copied_at, recalled_at?, rights=privateCopyOnly | UNIQUE(post_id,recipient_principal_id,recipe_hash); proves permitted copy independent from later post expiry |
| social.remix_edges | child_post_id, parent_post_id?, source_hash?, attribution_visibility | PK(child_post_id); index(parent_post_id,child_post_id); own child only; max traversal depth 20; cannot create cycles |
| social.reactions | post_id, actor_user_id, kind | PK(post_id,actor_user_id); index(post_id,kind); mutation checks current access and block state |
| platform.media_assets | owner_user_id, client_draft_id, kind, state, quarantine_key, quarantine_version_id, expected_sha256, expected_bytes, sanitized_key?, content_type, width?,height?,duration?, rejection_code?, deleted_at? | index(owner_user_id,client_draft_id), index(state,updated_at); immutable object version and hash needed before processing |
| circles.circles | owner_user_id, name, description, status | index(owner_user_id,status); one active owner membership |
| circles.memberships | circle_id, user_id, role, status, generation, joined_at, removed_at | UNIQUE(circle_id,user_id); index(user_id,status,circle_id); membership role changes lock circle row; generation increments for a fresh rejoin |
| circles.invitations | target_type,target_id, token_hash, issuer_user_id, expires_at, max_uses, used_count, revoked_at | UNIQUE(token_hash), index(target_type,target_id,expires_at); used_count≤max_uses |
| circles.invitation_acceptances | invitation_id,user_id,accepted_at,membership_id | PK(invitation_id,user_id); repeat accept does not consume another seat/use |
| conversations.threads | kind, context_type?, context_id?, direct_pair_key?, last_message_at | UNIQUE(direct_pair_key) WHERE kind=direct; no raw member names in pair key |
| conversations.thread_members | thread_id,user_id,role,last_read_message_id?,last_read_at,left_at? | PK(thread_id,user_id), index(user_id,left_at,thread_id); monotonic read watermark |
| conversations.messages | thread_id,sender_user_id,client_message_id,kind,text,recipe_version_id?,post_id?,removed_at | UNIQUE(sender_user_id,client_message_id), index(thread_id,created_at DESC,id DESC) |
| conversations.recipe_requests | requester_user_id,post_id,thread_id,status | Unique active(requester_user_id,post_id); duplicate requests coalesce |
| platform.notifications | user_id,event_id,kind,object_type,object_id,read_at?,expires_at | UNIQUE(user_id,event_id,kind), index(user_id,created_at DESC,id DESC); reference rather than sensitive payload |
| platform.push_devices | user_id,device_session_id,platform,encrypted_token,token_hash,permission,last_seen_at | UNIQUE(token_hash); session-bound reassignment on logout/login; invalid token disables delivery |
| coordination.sos | author_user_id,shared_ingredients_json,shared_constraints_json,caption,audience_circle_ids,status,expires_at | index(audience_circle_ids) via normalized sos_audiences in implementation; index(status,expires_at) |
| coordination.sos_replies | sos_id,author_user_id,recipe_version_id?,post_id?,text,client_command_id | UNIQUE(author_user_id,client_command_id); access of referenced recipe independent from SOS access |
| coordination.pacts | host_user_id,source_recipe_version_id,title,scheduled_at,time_zone,status,thread_id | index(host_user_id,scheduled_at); status monotonic except explicit reopen unsupported |
| coordination.pact_members | pact_id,user_id,status,private_plan_id? | PK(pact_id,user_id); private_plan_id returned only to owning member |
| coordination.potlucks | host_user_id,title,scheduled_at,status,selected_recipe_version_id?,thread_id | index(host_user_id,scheduled_at); confirm only with supported selection and reconciled quantities |
| coordination.potluck_members | potluck_id,user_id,status | PK(potluck_id,user_id) |
| coordination.contributions | potluck_id,ingredient_id,quantity,unit_id,volunteered_by,claimed_by?,status | index(potluck_id,status); claimed_by permitted participant; quantity>0 |
| coordination.shortcuts | author_user_id,recipe_version_id,text,status,audience_json | index(recipe_version_id,created_at DESC); no promotion by vote count |
| coordination.shortcut_saves | user_id,shortcut_id | PK(user_id,shortcut_id); reference save, inaccessible source shows unavailable; no independent re-publication rights |
| coordination.shortcut_helpful | user_id,shortcut_id | PK(user_id,shortcut_id); one current reaction per account |
| coordination.polls | author_user_id,question,audience_json,closes_at,status | index(status,closes_at); close checked in transaction |
| coordination.poll_options | poll_id,option_id,recipe_version_id,label | PK(poll_id,option_id); exactly two at publication, frozen after first vote |
| coordination.poll_votes | poll_id,user_id,option_id | PK(poll_id,user_id), FK(poll_id,option_id), index(poll_id,option_id); aggregate derived not trusted client count |
| commerce.households | owner_user_id,name,seat_limit,status | index(owner_user_id,status); entitlement checked separately |
| commerce.household_members | household_id,user_id,status | PK(household_id,user_id); only accepted active seats count |
| commerce.household_preferences | household_id,user_id,shared_with_household,shared_fields_json | PK(household_id,user_id); explicit per-person matching consent; API returns actor's fields and neutral group compatibility, never another member's exclusions |
| commerce.household_kitchen | household_id,equipment_ids,default_servings | UNIQUE(household_id); owner edits common equipment/servings independently of per-member food preferences |
| commerce.customers | user_id,provider,provider_customer_id | UNIQUE(provider,provider_customer_id), UNIQUE(user_id,provider); opaque UUID mapped, never email |
| commerce.purchase_events | provider,event_id,environment,received_at,raw_encrypted_json,processed_at?,failure_code? | UNIQUE(provider,event_id,environment); no deletion until retention policy approves reconciliation safety |
| commerce.entitlements | user_id,entitlement_key,status,product_id,source,verified_at,expires_at?,provider_revision?,reconcile_generation | UNIQUE(user_id,entitlement_key); generation prevents stale worker response overwrite |
| commerce.packs | title,description,status,required_entitlement,product_map_json | index(status,created_at DESC); store-price display comes from native storefront, not hardcoded database price |
| commerce.pack_items | pack_id,recipe_version_id,position | PK(pack_id,recipe_version_id); all versions approved before publish |
| safety.blocks | blocker_user_id,blocked_user_id | PK(blocker_user_id,blocked_user_id), reverse index(blocked_user_id,blocker_user_id); self-block forbidden |
| safety.mutes | user_id,target_type,target_id | UNIQUE(user_id,target_type,target_id) |
| safety.reports | reporter_user_id,target_type,target_id,reason,text,evidence_pointer,case_id,status | index(status,created_at); restricted evidence in separate encrypted store, no shared media grants |
| safety.moderation_cases | priority,status,assignee_staff_id?,target_type,target_id,latest_action,reason_code | index(status,priority,created_at); actions append-only history |
| safety.case_actions | case_id,staff_id,action,reason_code,notes,previous_state,created_at | index(case_id,created_at); suspension/removal require explicit role |
| platform.idempotency | principal_scope,operation_id,key,request_hash,state,response_code,response_json,expires_at | PK(principal_scope,operation_id,key); lock before execution; no raw credential request bodies |
| platform.outbox | event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,payload,occurred_at,published_at?,attempts | PK(event_id), partial index(occurred_at) WHERE published_at IS NULL |
| platform.consumer_inbox | consumer_name,event_id,processed_at | PK(consumer_name,event_id); inbox insertion and local effect share transaction |
| platform.account_jobs | principal_id,type,status,stage_cursor,result_key?,expires_at?,error_code? | index(principal_id,created_at DESC); only owner sees export URL; deletion can be tracked by opaque receipt after account removal |
| platform.flags | key,enabled,rollout_percent,config_json,revision,changed_by,reason | PK(key), check 0≤rollout≤100; mutations audited |
| platform.flag_changes | proposed_by,reviewed_by?,target_flag,proposed_json,base_revision,status | independent approval for increased exposure; immediate restrictive kill permitted for on-call |
| platform.audit_log | actor_type,actor_id,action,target_type,target_id,reason,trace_id,redacted_delta_json,created_at | append-only database role; index(target_type,target_id,created_at), date partition after measured growth |
| platform.incidents | severity,status,owner_staff_id,title,runbook_key,notes,timestamps | index(status,severity,created_at); no arbitrary shell-command storage/execution |

## Critical SQL patterns

The database isolates concurrent transactions but does not infer application invariants. Use unique keys, explicit row locks or serializable transactions where required, and retry serialization failures with a bounded new transaction. [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html)

```sql
-- Optimistic session sync; principal comes from verified request context.
UPDATE cooking.cook_sessions
SET current_step_id = :step, device_sequence = :seq,
    version = version + 1, updated_at = now()
WHERE id = :session_id AND principal_id = :principal_id
  AND version = :if_match AND status IN ('active','paused')
RETURNING *;
-- Zero rows: first authorized existence/status lookup, then 412 or terminal 409;
-- never reveal another principal's session.

-- Story visibility must hold even if the expiry worker is down.
SELECT p.* FROM social.posts p
WHERE p.status = 'published' AND p.expires_at > now()
  AND (p.author_user_id = :viewer OR EXISTS (
    SELECT 1 FROM social.post_audiences a
    JOIN circles.memberships m ON m.circle_id = a.circle_id
    JOIN circles.memberships author_membership
      ON author_membership.circle_id = a.circle_id
      AND author_membership.user_id = p.author_user_id
    WHERE a.post_id = p.id AND m.user_id = :viewer AND m.status = 'active'
      AND author_membership.status = 'active'
      AND author_membership.generation = a.author_membership_generation))
  AND NOT EXISTS (SELECT 1 FROM safety.blocks b
      WHERE (b.blocker_user_id=:viewer AND b.blocked_user_id=p.author_user_id)
         OR (b.blocker_user_id=p.author_user_id AND b.blocked_user_id=:viewer))
  AND (:cursor_time IS NULL OR (p.published_at,p.id) < (:cursor_time,:cursor_id))
ORDER BY p.published_at DESC,p.id DESC LIMIT 21;
-- EXISTS prevents duplicates across selected circles; fetch 21 for page size 20.
```

Create/save/publish uses this transaction order: validate principal/account status → lock idempotency row → lock relevant mutable policy/audience/version roots in documented lexical type+ID order → recheck authorization → mutate domain rows → append outbox → store result → commit. If an access-removal transaction races after successful authorization, serializable retry or shared/exclusive locks on the same membership/policy root establishes a clear order. A mere preflight check outside the transaction is insufficient.

Recipe-save transaction locks post, applicable audience membership and save policy, verifies recipe hash and policy version, inserts unique grant and saved snapshot, then records result. Grant revocation locks the same policy. Consequently a concurrent save either commits before revocation with an authorized persistent copy or fails after revocation; there is no unlabeled half-copy. Existing grants are not media-access grants.

Invitation accept locks invitation and target aggregate; checks expiry, block state, active status and capacity; inserts acceptance and membership once; increments used_count only for a new acceptance. Ownership transfer locks circle+both members, promotes recipient and demotes owner in the same transaction. Last owner cannot leave. Potluck claim locks contribution and gathering membership; two actors cannot both win. Poll vote locks poll, checks server time, then UPSERTs one actor's vote.

Guest merge locks both principals in sorted ID order and guest session. Copy only authorized scopes; mapping table ensures source IDs survive retries. Conflict handling is deterministic and returned to the client. Completion creates a merge record and revokes old guest ownership. For bounded guest size use one transaction; if a later import exceeds the bound, introduce a staging job and atomic ownership switch, never a partly visible merge.

## Lifecycle state machines

| Aggregate | Valid transitions | Terminal protection |
|---|---|---|
| Media | awaitingUpload → processing → ready/rejected → deleted | Re-upload creates new reserved version; rejected bytes never publish |
| Draft | draft → published or discarded; stale draft → expired | Publish ID links to one durable post; no automatic background publish |
| Post | published → hidden/restored-published or deleted | Deleted cannot be restored by ordinary client; Today expiry is a placement predicate |
| Recipe | draft → inReview → draft/approved → published → retired/recalled | Retirement stops new recommendation/promotion without implying a safety recall; existing pinned safe content remains readable. Published changes create new version; recall always stays flagged |
| Plan | needsConfirmation → new ready child; ready → recalled | Snapshot immutable; no in-place inferred correction |
| Cook session | active ↔ paused; active/paused → completed/abandoned | Old offline step commands cannot reopen completion |
| Pact/potluck | invited/planning → active/confirmed → completed or cancelled | Cancellation denies new participation and scheduled reminders |
| Poll | open → closed/deleted | Deadline tested on every vote, not only scheduler |
| Entitlement | unverified → active/grace → expired/revoked; reconciled restoration → active | Known revocation overrides outage grace; provider generation controls writes |
| Account deletion | requested → hidden → purging → completed; stage retries | No normal login once hidden; job checkpoint cannot recreate removed data |

## Data retention proposal

Retention is a product/operations proposal pending launch-market review, not a legal compliance assertion. Enforce a central retention configuration, audited holds, and deletion receipts.

| Data | Proposed retention and purge behavior |
|---|---|
| Unverified signup | Provider policy; app creates no public profile until verified and gated |
| Guest server state | Seven days inactivity, thirty days absolute; app clearly distinguishes local persistence |
| Unpublished draft | Thirty days since last edit; notify in draft UI; user can delete immediately |
| Quarantined uncommitted upload | Twenty-four hours; rejected dangerous material follows restricted incident policy |
| Today-only sanitized media | Deny viewer access at 24h; purge derivatives within 24h after placement expiry unless author explicitly retained; no feed visibility during purge delay |
| Retained post media | Until owner deletion or policy removal; purge derivatives within 24h proposed normal objective |
| Feedback/pantry/private saves | Until owner deletion, account erasure or explicit grant recall; privateCopyOnly saves retain no original media |
| Messages | Until user/account deletion policy; shared conversation removal redacts sender content and avoids promising deletion from recipient screenshots |
| Operational raw intent | Not stored by default; temporary processing only. Opt-in diagnostics separately redacted, seven-day cap |
| Idempotency results | Seven days; compact command tombstones can outlive full response for irreversible flows |
| Outbox and inbox | Thirty-day hot retention, ninety-day event-ID tombstones; replay window cannot exceed dedupe retention without explicit rebuild mode |
| Security logs | Thirty days raw redacted telemetry; ninety days restricted audit candidate; justify longer holds explicitly |
| Purchase/audit evidence | Minimum necessary provider/transaction proof; schedule finalized with tax/fraud/legal review; no speculative forever retention |
| Exports | Private download available for 24h; object purged within 48h after expiry |
| Database backups | Proposed 14-day PITR window, encrypted; erasure ledger is replayed after restore so deleted data is not reintroduced into service |

## Schema release and verification

Every migration has forward SQL, backfill checkpoint, old/new application compatibility and rollback impact. Test against a scrubbed synthetic database at intended volume, not production personal data copied into laptops. Validate foreign keys, uniqueness, check constraints and all lifecycle transitions. Required race tests: save vs revoke, post publish vs circle removal, duplicate webhook vs restore, account deletion vs notification job, two claims vs one contribution, two-device cook completion, owner transfer vs leave, guest merge double-submit. See runbooks for replay and restore rehearsals.
