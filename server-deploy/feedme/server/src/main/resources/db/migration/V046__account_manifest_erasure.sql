-- LOCAL additive account-manifest erasure only. No guest erasure, provider retry,
-- final completion, runtime privilege, API activation or deployment is installed.
-- V044/V045 original resources remain unchanged. Replace only the exact core
-- capability whitelist and purge body; all other holds, fences and checkpoints remain.
-- Account header insertion is now fenced by the actual user/private-principal roots.

CREATE OR REPLACE FUNCTION identity.account_erasure_delete_allowed(p_relation oid,p_environment text,
 p_account_id uuid,p_principal_id uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_core_allowed$
BEGIN
 IF current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' THEN RETURN false; END IF;
 RETURN EXISTS(
  SELECT 1 FROM identity.account_erasure_scope s
  JOIN identity.account_deletion_jobs j ON j.environment=s.environment AND j.id=s.job_id
  JOIN identity.account_erasure_work w ON w.environment=s.environment AND w.job_id=s.job_id
  WHERE s.backend_pid=pg_backend_pid() AND s.transaction_id=txid_current()
    AND s.environment=p_environment AND s.account_id=j.user_id AND s.principal_id=j.principal_id
    AND j.stage='pending' AND w.stage='inventory' AND w.lease_token=s.lease_token
    AND w.generation=s.generation AND w.lease_expires_at>clock_timestamp()
    AND ((p_relation IN ('profile.onboarding_decisions'::regclass,
           'identity.device_reconnections'::regclass,'identity.account_terms_acceptances'::regclass)
          AND p_account_id=s.account_id AND p_principal_id IS NULL)
      OR (p_relation IN ('planning.account_plan_windows'::regclass,'planning.manifest_headers'::regclass,
           'planning.manifest_ranks'::regclass,'planning.manifest_seals'::regclass)
          AND p_account_id IS NULL AND p_principal_id=s.principal_id))
 );
END;
$feedme_core_allowed$;
REVOKE ALL ON FUNCTION identity.account_erasure_delete_allowed(oid,text,uuid,uuid) FROM PUBLIC;

CREATE FUNCTION planning.guard_account_manifest_erasure() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_manifest_erasure$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.actor_kind='account' AND TG_RELID IN ('planning.manifest_headers'::regclass,
      'planning.manifest_ranks'::regclass,'planning.manifest_seals'::regclass)
    AND identity.account_erasure_delete_allowed(TG_RELID,OLD.environment,NULL,OLD.principal_id) THEN
   RETURN OLD;
  END IF;
 END IF;
 -- All UPDATE and guest DELETE retain the original unconditional refusal.
 RAISE EXCEPTION 'Planning manifests are immutable' USING ERRCODE='23514';
END;
$feedme_manifest_erasure$;
REVOKE ALL ON FUNCTION planning.guard_account_manifest_erasure() FROM PUBLIC;

DROP TRIGGER manifest_header_immutable ON planning.manifest_headers;
CREATE TRIGGER manifest_header_immutable BEFORE UPDATE OR DELETE ON planning.manifest_headers
 FOR EACH ROW EXECUTE FUNCTION planning.guard_account_manifest_erasure();
DROP TRIGGER manifest_rank_immutable ON planning.manifest_ranks;
CREATE TRIGGER manifest_rank_immutable BEFORE UPDATE OR DELETE ON planning.manifest_ranks
 FOR EACH ROW EXECUTE FUNCTION planning.guard_account_manifest_erasure();
DROP TRIGGER manifest_seal_immutable ON planning.manifest_seals;
CREATE TRIGGER manifest_seal_immutable BEFORE UPDATE OR DELETE ON planning.manifest_seals
 FOR EACH ROW EXECUTE FUNCTION planning.guard_account_manifest_erasure();
-- Existing manifest_*_no_truncate triggers still invoke the unchanged shared
-- planning.reject_manifest_mutation(). No erasure capability permits TRUNCATE.

CREATE FUNCTION planning.guard_account_manifest_owner_insert() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_manifest_owner$
DECLARE account_id uuid; account_status text; principal_status text;
BEGIN
 IF NEW.actor_kind<>'account' THEN RETURN NEW; END IF;
 IF current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' THEN
  RAISE EXCEPTION 'Account manifest owner transaction is unavailable' USING ERRCODE='23514';
 END IF;
 -- Read the mapping without locking, then take user -> principal in the established
 -- account order. Re-read the exact mapping under locks: the first read is not authority.
 SELECT p.user_id INTO account_id FROM identity.principals p
  WHERE p.environment=NEW.environment AND p.id=NEW.principal_id AND p.kind='user' AND p.guest_session_id IS NULL;
 IF NOT FOUND OR account_id IS NULL THEN
  RAISE EXCEPTION 'Account manifest owner is unavailable' USING ERRCODE='23514';
 END IF;
 SELECT u.status INTO account_status FROM identity.users u
  WHERE u.environment=NEW.environment AND u.id=account_id FOR SHARE NOWAIT;
 IF NOT FOUND OR account_status NOT IN ('active','suspended') THEN
  RAISE EXCEPTION 'Account manifest owner is unavailable' USING ERRCODE='23514';
 END IF;
 SELECT p.status INTO principal_status FROM identity.principals p
  WHERE p.environment=NEW.environment AND p.id=NEW.principal_id AND p.kind='user'
    AND p.user_id=account_id AND p.guest_session_id IS NULL FOR SHARE NOWAIT;
 IF NOT FOUND OR principal_status NOT IN ('active','suspended') THEN
  RAISE EXCEPTION 'Account manifest owner is unavailable' USING ERRCODE='23514';
 END IF;
 -- Accepted originals also fence a contradictory restored row. No current Terms,
 -- onboarding or meal-eligibility condition is introduced here.
 IF EXISTS(SELECT 1 FROM identity.account_deletion_jobs j WHERE j.environment=NEW.environment
    AND (j.user_id=account_id OR j.principal_id=NEW.principal_id)) THEN
  RAISE EXCEPTION 'Accepted deletion fences account manifests' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_manifest_owner$;
REVOKE ALL ON FUNCTION planning.guard_account_manifest_owner_insert() FROM PUBLIC;
-- PostgreSQL fires same-kind triggers by name; this fence precedes the existing
-- manifest_header_anchor trigger, whose catalog/header validation is unchanged.
CREATE TRIGGER account_manifest_owner_fence BEFORE INSERT ON planning.manifest_headers
 FOR EACH ROW EXECUTE FUNCTION planning.guard_account_manifest_owner_insert();

CREATE OR REPLACE FUNCTION identity.purge_account_core(p_environment text,p_job uuid,p_token uuid,p_generation bigint)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_core_purge$
DECLARE
 accepted identity.account_deletion_jobs%ROWTYPE;
 work identity.account_erasure_work%ROWTYPE;
 root identity.users%ROWTYPE;
 private_root identity.principals%ROWTYPE;
 account_scope text; owned_principal_scope text; relation_name text; related boolean;
 at_time timestamptz; changed bigint; owned_events uuid[];
 account_ops constant text[]:=ARRAY['bootstrapAccount','logoutSession','acceptAccountTerms','updateMe','requestAccountDeletion'];
 private_ops constant text[]:=ARRAY['updatePreferences','upsertPantryItem','removePantryItem',
   'createPlan','nextPlan','adaptPlan','simplifyPlan','createCookSession','updateCookSession','completeCookSession',
   'saveRecipe','deleteSavedRecipe','createFeedback','updateFeedback','deleteFeedback','updateMemory','deleteMemory'];
 unsupported_ops constant text[]:=ARRAY['createCircle','updateCircle','updateCircleMember','leaveCircle','removeCircleMember',
   'transferCircleOwnership','deleteCircle','createInvitation','acceptInvitation','revokeInvitation',
   'prepareMediaUpload','completeMediaUpload','deleteDraftMedia','createPostDraft','updatePostDraft','deletePostDraft',
   'publishPost','blockUser','unblockUser','createReport'];
 account_events constant text[]:=ARRAY['identity.account.bootstrapped.v1','identity.session.revoked.v1',
   'profile.profile.changed.v1','identity.account.deletion_requested.v1'];
 private_events constant text[]:=ARRAY['profile.preferences.changed.v1','pantry.item.changed.v1','planning.plan.created.v1',
   'cooking.session.started.v1','cooking.session.progressed.v1','cooking.session.completed.v1',
   'memory.recipe.saved.v1','memory.recipe.deleted.v1','memory.collection.changed.v1',
   'memory.feedback.changed.v1','memory.preference.changed.v1'];
 public_events constant text[]:=ARRAY['catalog.recipe.published.v1','catalog.recipe.recalled.v1','catalog.substitution.reviewed.v1'];
 -- Fixed reviewed ordinary-table set. Names never come from a caller or JSON value.
 fenced_tables constant text[]:=ARRAY[
   'identity.users','identity.principals','identity.device_sessions','identity.bootstrap_consents',
   'identity.device_reconnections','identity.account_terms_acceptances','identity.guest_sessions',
   'identity.guest_bootstrap_receipts','identity.guest_issuance_windows','identity.guest_planning_policies','identity.guest_plan_windows',
   'profile.profiles','profile.preferences','profile.onboarding_decisions','pantry.pantry_items',
   'planning.plan_requests','planning.plans','planning.account_plan_windows','planning.guest_preparations',
   'planning.manifest_headers','planning.manifest_ranks','planning.manifest_seals',
   'cooking.cook_sessions','cooking.device_cursors','cooking.step_events',
   'memory.library_heads','memory.saved_recipes','memory.collections','memory.collection_items','memory.save_commands',
   'memory.feedback','memory.feedback_commands','memory.make_again_actions','memory.memory_heads','memory.memory_feedback_state',
   'memory.memory_dirty_groups','memory.memories','memory.memory_sources','memory.memory_suppressions','memory.memory_commands','memory.memory_projection_events',
   'platform.idempotency','platform.outbox','platform.consumer_inbox','platform.media_draft_lifecycles','platform.media_assets',
   'platform.media_cleanup_jobs','platform.media_processing_jobs','platform.media_processing_inbox','platform.media_derivative_intents',
   'platform.media_processing_cleanup','platform.post_draft_heads','platform.post_drafts','platform.post_draft_discard_media',
   'platform.media_upload_issuances','platform.media_private_materializations','platform.media_safety_records','platform.media_safety_revocations',
   'social.circles','social.circle_members','social.circle_invitations','social.block_pairs','social.blocks','social.posts',
   'social.post_publications','social.post_media','social.post_audiences','social.post_attachments','social.recipe_save_policies',
   'social.post_publication_discard_media','safety.moderation_cases','safety.reports','safety.report_evidence',
   'staff.publication_policies','staff.actors','staff.publication_approvals','staff.approval_revocations'];
 private_delete_order constant text[]:=ARRAY[
   'planning.manifest_seals','planning.manifest_ranks','planning.manifest_headers',
   'memory.memory_projection_events','memory.memory_commands','memory.memory_sources','memory.memory_suppressions',
   'memory.memory_feedback_state','memory.memory_dirty_groups','memory.feedback_commands','memory.feedback',
   'memory.memories','memory.memory_heads','memory.collection_items','memory.save_commands','memory.saved_recipes',
   'memory.collections','memory.library_heads','cooking.step_events','cooking.device_cursors','cooking.cook_sessions',
   'planning.plans','planning.plan_requests','planning.account_plan_windows','pantry.pantry_items','profile.preferences'];
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$' OR p_job IS NULL OR p_token IS NULL
   OR p_generation IS NULL OR p_generation<1 OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid account core erasure invocation' USING ERRCODE='23514';
 END IF;
 -- Same immutable-job -> work order as V043. NOWAIT avoids waiting for another worker.
 SELECT j.* INTO accepted FROM identity.account_deletion_jobs j
  WHERE j.environment=p_environment AND j.id=p_job FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN 'lease_lost'; END IF;
 SELECT w.* INTO work FROM identity.account_erasure_work w
  WHERE w.environment=p_environment AND w.job_id=p_job FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN 'lease_lost'; END IF;
 IF work.stage='core_erased' THEN
  IF work.core_erased_token=p_token AND work.core_erased_generation=p_generation THEN RETURN 'already_core_erased'; END IF;
  RETURN 'lease_lost';
 END IF;
 IF work.stage<>'inventory' OR work.lease_token IS DISTINCT FROM p_token OR work.generation<>p_generation
   OR work.lease_expires_at IS NULL OR work.lease_expires_at<=clock_timestamp() THEN RETURN 'lease_lost'; END IF;

 -- This local first implementation refuses unknown relations/inheritance instead of
 -- deleting roots while a newly added private table silently retains their data.
 IF EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname IN ('identity','profile','pantry','planning','cooking','memory','platform','social','safety','staff')
     AND c.relkind IN ('r','p','v','m','f')
     AND NOT (n.nspname||'.'||c.relname=ANY(fenced_tables||ARRAY['platform.schema_migrations',
       'identity.account_deletion_jobs','identity.account_deletion_devices','identity.account_erasure_work','identity.account_erasure_scope']))) THEN
  RETURN 'related_data';
 END IF;
 FOREACH relation_name IN ARRAY fenced_tables LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_class c WHERE c.oid=relation_name::regclass AND c.relkind='r'
    AND NOT EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)) THEN
   RETURN 'identity_conflict';
  END IF;
  EXECUTE format('LOCK TABLE ONLY %s IN SHARE ROW EXCLUSIVE MODE NOWAIT',relation_name::regclass);
 END LOOP;
 -- All fixed writer tables are now quiescent. Existing root readers must drain too.
 SELECT u.* INTO root FROM identity.users u WHERE u.environment=p_environment AND u.id=accepted.user_id FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN 'identity_conflict'; END IF;
 IF root.status<>'deleting' OR root.provider_issuer IS DISTINCT FROM accepted.provider_issuer
   OR root.provider_subject IS DISTINCT FROM accepted.provider_subject THEN RETURN 'identity_conflict'; END IF;
 SELECT q.* INTO private_root FROM identity.principals q
  WHERE q.environment=p_environment AND q.id=accepted.principal_id FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN 'identity_conflict'; END IF;
 IF private_root.kind<>'user' OR private_root.user_id IS DISTINCT FROM accepted.user_id
   OR private_root.status<>'deleting' OR private_root.guest_session_id IS NOT NULL THEN RETURN 'identity_conflict'; END IF;
 PERFORM 1 FROM identity.device_sessions d WHERE d.environment=p_environment AND d.user_id=accepted.user_id ORDER BY d.id FOR UPDATE NOWAIT;
 IF EXISTS(SELECT 1 FROM identity.device_sessions d WHERE d.environment=p_environment AND d.user_id=accepted.user_id AND d.revoked_at IS NULL)
   OR NOT EXISTS(SELECT 1 FROM profile.profiles p WHERE p.environment=p_environment AND p.user_id=accepted.user_id AND NOT p.live) THEN
  RETURN 'identity_conflict';
 END IF;
 PERFORM 1 FROM profile.profiles p WHERE p.environment=p_environment AND p.user_id=accepted.user_id FOR UPDATE NOWAIT;
 account_scope:=p_environment||':account:'||accepted.user_id::text;
 owned_principal_scope:=p_environment||':account:'||accepted.principal_id::text;

 -- Non-core history is a hold, never a permission to purge another person's rows.
 IF EXISTS(SELECT 1 FROM identity.guest_sessions g WHERE g.environment=p_environment AND g.merged_to_user_id=accepted.user_id)
   OR EXISTS(SELECT 1 FROM social.circles c WHERE c.environment=p_environment AND c.owner_id=accepted.user_id)
   OR EXISTS(SELECT 1 FROM social.circle_members m WHERE m.environment=p_environment AND (m.user_id=accepted.user_id OR m.removed_by=accepted.user_id))
   OR EXISTS(SELECT 1 FROM social.circle_invitations i WHERE i.environment=p_environment AND (i.created_by=accepted.user_id OR i.consumed_by=accepted.user_id))
   OR EXISTS(SELECT 1 FROM social.block_pairs b WHERE b.environment=p_environment AND (b.user_low=accepted.user_id OR b.user_high=accepted.user_id))
   OR EXISTS(SELECT 1 FROM social.blocks b WHERE b.environment=p_environment AND (b.owner_user_id=accepted.user_id OR b.target_user_id=accepted.user_id))
   OR EXISTS(SELECT 1 FROM safety.reports r WHERE r.environment=p_environment
       AND (r.reporter_user_id=accepted.user_id OR (r.target_type='user' AND r.target_id=accepted.user_id)))
   OR EXISTS(SELECT 1 FROM safety.report_evidence e WHERE e.environment=p_environment
       AND (e.reporter_user_id=accepted.user_id OR e.target_owner_id=accepted.user_id OR (e.target_type='user' AND e.target_id=accepted.user_id)))
   OR EXISTS(SELECT 1 FROM safety.moderation_cases c WHERE c.environment=p_environment AND c.target_type='user' AND c.target_id=accepted.user_id)
   OR EXISTS(SELECT 1 FROM staff.actors a WHERE a.environment=p_environment AND a.issuer=accepted.provider_issuer AND a.subject=accepted.provider_subject::text)
   OR EXISTS(SELECT 1 FROM staff.publication_approvals a WHERE a.environment=p_environment AND a.issuer=accepted.provider_issuer
       AND (a.publisher_subject=accepted.provider_subject::text OR a.reviewer_subject=accepted.provider_subject::text)) THEN
  RETURN 'related_data';
 END IF;
 -- Every indirect media/publication child has a mandatory FK to one of these roots.
 -- Roots remain until external issuance/derivative settlement is separately implemented.
 FOREACH relation_name IN ARRAY ARRAY['platform.media_draft_lifecycles','platform.media_assets','platform.media_cleanup_jobs',
   'platform.media_processing_jobs','platform.media_processing_cleanup','platform.post_draft_heads','platform.post_drafts',
   'platform.post_draft_discard_media','platform.media_upload_issuances','platform.media_safety_records',
   'social.posts','social.post_publications','social.post_media','social.post_audiences','social.post_attachments',
   'social.recipe_save_policies','social.post_publication_discard_media'] LOOP
  EXECUTE format('SELECT EXISTS(SELECT 1 FROM %s WHERE environment=$1 AND owner_user_id=$2)',relation_name::regclass)
   INTO related USING p_environment,accepted.user_id;
  IF related THEN RETURN 'related_data'; END IF;
 END LOOP;

 -- Literal operation-aware namespace attribution; keep this list aligned with the
 -- source-reviewed AccountErasureReceiptOwnership, not the entire aspirational API.
 IF EXISTS(SELECT 1 FROM platform.idempotency i WHERE i.principal_scope=account_scope AND i.operation_id=ANY(unsupported_ops)) THEN
  RETURN 'related_data';
 END IF;
 IF EXISTS(SELECT 1 FROM platform.idempotency i WHERE i.principal_scope IN (account_scope,owned_principal_scope)
   AND NOT (i.operation_id=ANY(account_ops||private_ops||unsupported_ops))) THEN RETURN 'unknown_receipts'; END IF;
 -- Other ACCOUNT owners can retain a semantic reference to this account. These
 -- exact non-core response paths are reviewed; no generic response.id/UUID scan.
 IF EXISTS(SELECT 1 FROM platform.idempotency i
   WHERE starts_with(i.principal_scope,p_environment||':account:') AND i.principal_scope<>account_scope
     AND ((i.operation_id='blockUser' AND i.response_json->>'targetUserId'=accepted.user_id::text)
       OR (i.operation_id IN ('updateCircleMember','acceptInvitation') AND i.response_json#>>'{user,userId}'=accepted.user_id::text)
       OR (i.operation_id='publishPost' AND i.response_json#>>'{author,userId}'=accepted.user_id::text)
       OR (i.operation_id='createReport' AND i.response_json->>'targetType'='user' AND i.response_json->>'targetId'=accepted.user_id::text))) THEN
  RETURN 'related_data';
 END IF;

 -- Unattributed history cannot be assigned an environment by a coincident UUID.
 -- Public catalog facts are the only reviewed unowned event exceptions here.
 IF EXISTS(SELECT 1 FROM platform.outbox e WHERE e.owner_id IS NULL AND NOT(e.event_type=ANY(public_events))) THEN
  RETURN 'unattributed_events';
 END IF;
 IF EXISTS(SELECT 1 FROM platform.outbox e WHERE e.owner_environment=p_environment
   AND ((e.owner_kind='account' AND e.owner_id=accepted.user_id AND NOT(e.event_type=ANY(account_events)))
     OR (e.owner_kind='private_principal' AND e.owner_id=accepted.principal_id AND NOT(e.event_type=ANY(private_events))))) THEN
  RETURN 'unattributed_events';
 END IF;
 IF EXISTS(SELECT 1 FROM platform.outbox e WHERE e.owner_environment=p_environment AND e.owner_kind='account'
   AND e.owner_id<>accepted.user_id
   AND ((e.event_type='safety.block.changed.v1' AND (e.payload->>'blockerUserId'=accepted.user_id::text OR e.payload->>'blockedUserId'=accepted.user_id::text))
     OR (e.event_type='circles.membership.changed.v1' AND e.payload->>'userId'=accepted.user_id::text)
     OR (e.event_type='circles.ownership.transferred.v1' AND (e.payload->>'fromUserId'=accepted.user_id::text OR e.payload->>'toUserId'=accepted.user_id::text))
     OR (e.event_type='social.post.published.v1' AND e.payload->>'authorUserId'=accepted.user_id::text)
     OR (e.event_type='social.post_draft.changed.v1' AND e.payload->>'ownerId'=accepted.user_id::text))) THEN
  RETURN 'related_data';
 END IF;
 SELECT coalesce(array_agg(e.event_id),ARRAY[]::uuid[]) INTO owned_events FROM platform.outbox e
  WHERE e.owner_environment=p_environment AND
   ((e.owner_kind='account' AND e.owner_id=accepted.user_id AND e.event_type=ANY(account_events))
    OR (e.owner_kind='private_principal' AND e.owner_id=accepted.principal_id AND e.event_type=ANY(private_events)));
 IF EXISTS(SELECT 1 FROM memory.memory_projection_events e WHERE e.event_id=ANY(owned_events)
    AND ROW(e.environment,e.actor_kind,e.principal_id) IS DISTINCT FROM ROW(p_environment,'account'::varchar,accepted.principal_id)) THEN
  RETURN 'related_data';
 END IF;
 -- All holds above are read-only. The first write creates a non-forgeable,
 -- transaction-local capability, inaccessible to the restricted worker/API roles.
 IF work.lease_expires_at<=clock_timestamp() THEN RETURN 'lease_lost'; END IF;
 INSERT INTO identity.account_erasure_scope(backend_pid,transaction_id,environment,job_id,account_id,principal_id,lease_token,generation)
  VALUES(pg_backend_pid(),txid_current(),p_environment,p_job,accepted.user_id,accepted.principal_id,p_token,p_generation);
 SET CONSTRAINTS planning.planning_current_owned_plan,planning.planning_derived_owned_parent DEFERRED;
 FOREACH relation_name IN ARRAY private_delete_order LOOP
  EXECUTE format('DELETE FROM %s WHERE environment=$1 AND actor_kind=''account'' AND principal_id=$2',relation_name::regclass)
   USING p_environment,accepted.principal_id;
 END LOOP;
 SET CONSTRAINTS planning.planning_current_owned_plan,planning.planning_derived_owned_parent IMMEDIATE;
 FOREACH relation_name IN ARRAY ARRAY['profile.onboarding_decisions','identity.device_reconnections',
    'identity.account_terms_acceptances','identity.bootstrap_consents','identity.device_sessions'] LOOP
  EXECUTE format('DELETE FROM %s WHERE environment=$1 AND user_id=$2',relation_name::regclass)
   USING p_environment,accepted.user_id;
 END LOOP;
 DELETE FROM platform.consumer_inbox i WHERE i.event_id=ANY(owned_events);
 DELETE FROM platform.outbox e WHERE e.event_id=ANY(owned_events);
 DELETE FROM platform.idempotency i WHERE
   (i.principal_scope=account_scope AND i.operation_id=ANY(account_ops))
   OR (i.principal_scope=owned_principal_scope AND i.operation_id=ANY(private_ops));
 DELETE FROM profile.profiles p WHERE p.environment=p_environment AND p.user_id=accepted.user_id;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Account core profile changed' USING ERRCODE='23514'; END IF;
 DELETE FROM identity.principals p WHERE p.environment=p_environment AND p.id=accepted.principal_id AND p.user_id=accepted.user_id AND p.kind='user' AND p.status='deleting';
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Account core principal changed' USING ERRCODE='23514'; END IF;
 DELETE FROM identity.users u WHERE u.environment=p_environment AND u.id=accepted.user_id AND u.status='deleting'
   AND u.provider_issuer=accepted.provider_issuer AND u.provider_subject=accepted.provider_subject;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Account core identity changed' USING ERRCODE='23514'; END IF;
 -- After any deletion, failures MUST abort, never return a partial-success status.
 at_time:=clock_timestamp();
 IF work.lease_expires_at<=at_time THEN RAISE EXCEPTION 'Account core lease expired before checkpoint' USING ERRCODE='23514'; END IF;
 UPDATE identity.account_erasure_work w SET stage='core_erased',core_erased_at=at_time,
   core_erased_token=p_token,core_erased_generation=p_generation,lease_token=NULL,lease_expires_at=NULL,updated_at=at_time
  WHERE w.environment=p_environment AND w.job_id=p_job AND w.stage='inventory' AND w.lease_token=p_token AND w.generation=p_generation;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Account core lease changed before checkpoint' USING ERRCODE='23514'; END IF;
 DELETE FROM identity.account_erasure_scope s WHERE s.backend_pid=pg_backend_pid() AND s.transaction_id=txid_current()
   AND s.environment=p_environment AND s.job_id=p_job AND s.lease_token=p_token AND s.generation=p_generation;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Account core scope changed' USING ERRCODE='23514'; END IF;
 RETURN 'core_erased';
END;
$feedme_core_purge$;
REVOKE ALL ON FUNCTION identity.purge_account_core(text,uuid,uuid,bigint) FROM PUBLIC;

