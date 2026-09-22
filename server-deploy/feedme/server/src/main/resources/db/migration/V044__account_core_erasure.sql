-- LOCAL core erasure only. No provider deletion, external-object cleanup, final F49
-- completion, runtime grant, scheduler or deployment is installed by this migration.
-- Fixed SHARE ROW EXCLUSIVE NOWAIT fences deliberately require write quiescence.
-- They are a release/performance gate, NOT a scalable per-account locking claim.
-- Unknown schema/data, related non-core history and unattributed events are held.

ALTER TABLE planning.plan_requests ALTER CONSTRAINT planning_derived_owned_parent
 DEFERRABLE INITIALLY IMMEDIATE;

CREATE TABLE identity.account_erasure_scope (
 backend_pid integer NOT NULL,
 transaction_id bigint NOT NULL,
 environment varchar(40) NOT NULL,
 job_id uuid NOT NULL,
 account_id uuid NOT NULL,
 principal_id uuid NOT NULL,
 lease_token uuid NOT NULL,
 generation bigint NOT NULL CHECK(generation>0),
 PRIMARY KEY(backend_pid,transaction_id),
 FOREIGN KEY(environment,job_id) REFERENCES identity.account_deletion_jobs(environment,id)
);
ALTER TABLE identity.account_erasure_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.account_erasure_scope FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE identity.account_erasure_scope FROM PUBLIC;

ALTER TABLE identity.account_erasure_work
 DROP CONSTRAINT account_erasure_work_stage_check,
 ADD CONSTRAINT account_erasure_work_stage_check CHECK(stage IN ('inventory','core_erased')),
 ADD COLUMN core_erased_at timestamptz NULL,
 ADD COLUMN core_erased_token uuid NULL,
 ADD COLUMN core_erased_generation bigint NULL,
 ADD CONSTRAINT account_erasure_core_checkpoint CHECK(
   (stage='inventory' AND num_nonnulls(core_erased_at,core_erased_token,core_erased_generation)=0)
   OR (stage='core_erased' AND num_nonnulls(core_erased_at,core_erased_token,core_erased_generation)=3
     AND isfinite(core_erased_at) AND core_erased_generation>0 AND core_erased_generation=generation
     AND lease_token IS NULL AND lease_expires_at IS NULL)
 );

CREATE FUNCTION identity.account_erasure_delete_allowed(p_relation oid,p_environment text,
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
      OR (p_relation='planning.account_plan_windows'::regclass
          AND p_account_id IS NULL AND p_principal_id=s.principal_id))
 );
END;
$feedme_core_allowed$;
REVOKE ALL ON FUNCTION identity.account_erasure_delete_allowed(oid,text,uuid,uuid) FROM PUBLIC;

CREATE OR REPLACE FUNCTION profile.keep_onboarding_decision_immutable() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_core_onboarding$
BEGIN
 IF TG_OP='DELETE' THEN
  IF identity.account_erasure_delete_allowed(TG_RELID,OLD.environment,OLD.user_id,NULL) THEN RETURN OLD; END IF;
 END IF;
 RAISE EXCEPTION 'Onboarding decision is immutable' USING ERRCODE='23514';
END;
$feedme_core_onboarding$;
REVOKE ALL ON FUNCTION profile.keep_onboarding_decision_immutable() FROM PUBLIC;

CREATE OR REPLACE FUNCTION identity.keep_device_reconnection_immutable() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_core_reconnection$
BEGIN
 IF TG_OP='DELETE' THEN
  IF identity.account_erasure_delete_allowed(TG_RELID,OLD.environment,OLD.user_id,NULL) THEN RETURN OLD; END IF;
 END IF;
 RAISE EXCEPTION 'Device reconnection evidence is immutable' USING ERRCODE='23514';
END;
$feedme_core_reconnection$;
REVOKE ALL ON FUNCTION identity.keep_device_reconnection_immutable() FROM PUBLIC;

CREATE OR REPLACE FUNCTION identity.keep_account_terms_acceptance_immutable() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_core_terms$
BEGIN
 IF TG_OP='DELETE' THEN
  IF identity.account_erasure_delete_allowed(TG_RELID,OLD.environment,OLD.user_id,NULL) THEN RETURN OLD; END IF;
 END IF;
 RAISE EXCEPTION 'Account Terms acceptance evidence is immutable' USING ERRCODE='23514';
END;
$feedme_core_terms$;
REVOKE ALL ON FUNCTION identity.keep_account_terms_acceptance_immutable() FROM PUBLIC;

CREATE FUNCTION planning.guard_account_plan_window_delete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_core_window$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.actor_kind='account' AND identity.account_erasure_delete_allowed(
      TG_RELID,OLD.environment,NULL,OLD.principal_id) THEN RETURN OLD; END IF;
 END IF;
 RAISE EXCEPTION 'Account planning allowance retention is not authorized' USING ERRCODE='23514';
END;
$feedme_core_window$;
REVOKE ALL ON FUNCTION planning.guard_account_plan_window_delete() FROM PUBLIC;
DROP TRIGGER account_plan_window_retained ON planning.account_plan_windows;
CREATE TRIGGER account_plan_window_erasure_delete BEFORE DELETE ON planning.account_plan_windows
 FOR EACH ROW EXECUTE FUNCTION planning.guard_account_plan_window_delete();
CREATE TRIGGER account_plan_window_retained BEFORE TRUNCATE ON planning.account_plan_windows
 FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_mutation();

CREATE FUNCTION identity.guard_account_core_erasure_checkpoint() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_core_checkpoint$
BEGIN
 IF TG_OP='TRUNCATE' THEN
  RAISE EXCEPTION 'Core erasure checkpoint is retained' USING ERRCODE='23514';
 END IF;
 IF OLD.stage='core_erased' THEN
  RAISE EXCEPTION 'Core erasure checkpoint is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF NEW.stage='inventory' AND ROW(NEW.core_erased_at,NEW.core_erased_token,NEW.core_erased_generation)
     IS NOT DISTINCT FROM ROW(OLD.core_erased_at,OLD.core_erased_token,OLD.core_erased_generation) THEN RETURN NEW; END IF;
 IF OLD.stage<>'inventory' OR NEW.stage<>'core_erased'
   OR NEW.generation IS DISTINCT FROM OLD.generation
   OR NEW.core_erased_token IS DISTINCT FROM OLD.lease_token
   OR NEW.core_erased_generation IS DISTINCT FROM OLD.generation
   OR NEW.core_erased_at IS NULL OR NOT isfinite(NEW.core_erased_at)
   OR NEW.core_erased_at>clock_timestamp() OR OLD.lease_expires_at IS NULL
   OR NEW.core_erased_at>=OLD.lease_expires_at OR OLD.lease_expires_at<=clock_timestamp()
   OR NEW.lease_token IS NOT NULL OR NEW.lease_expires_at IS NOT NULL
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed'
   OR NOT EXISTS(SELECT 1 FROM identity.account_erasure_scope s
       JOIN identity.account_deletion_jobs j ON j.environment=s.environment AND j.id=s.job_id
       WHERE s.backend_pid=pg_backend_pid() AND s.transaction_id=txid_current()
         AND s.environment=OLD.environment AND s.job_id=OLD.job_id
         AND s.account_id=j.user_id AND s.principal_id=j.principal_id
         AND s.lease_token=OLD.lease_token AND s.generation=OLD.generation AND j.stage='pending') THEN
  RAISE EXCEPTION 'Core erasure checkpoint requires the exact active purge scope' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_core_checkpoint$;
REVOKE ALL ON FUNCTION identity.guard_account_core_erasure_checkpoint() FROM PUBLIC;
CREATE TRIGGER account_core_erasure_checkpoint BEFORE UPDATE ON identity.account_erasure_work
 FOR EACH ROW EXECUTE FUNCTION identity.guard_account_core_erasure_checkpoint();
CREATE TRIGGER account_core_erasure_checkpoint_delete BEFORE DELETE ON identity.account_erasure_work
 FOR EACH ROW EXECUTE FUNCTION identity.guard_account_core_erasure_checkpoint();
CREATE TRIGGER account_core_erasure_checkpoint_retained BEFORE TRUNCATE ON identity.account_erasure_work
 FOR EACH STATEMENT EXECUTE FUNCTION identity.guard_account_core_erasure_checkpoint();

CREATE FUNCTION identity.purge_account_core(p_environment text,p_job uuid,p_token uuid,p_generation bigint)
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
 -- V017 allows account manifests even though the current writer is guest-only.
 -- Their immutable ranks/seals are not covered by the four guarded core deletes.
 IF EXISTS(SELECT 1 FROM planning.manifest_headers h WHERE h.environment=p_environment
       AND h.actor_kind='account' AND h.principal_id=accepted.principal_id)
   OR EXISTS(SELECT 1 FROM identity.guest_sessions g WHERE g.environment=p_environment AND g.merged_to_user_id=accepted.user_id)
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
