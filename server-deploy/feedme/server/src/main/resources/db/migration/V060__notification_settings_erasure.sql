-- Exact-private notification settings only. No shared-history or unknown-schema exemption.
-- Original capability, transaction, lease and checkpoint remain unchanged.
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
           'identity.device_reconnections'::regclass,'identity.account_terms_acceptances'::regclass,
           'social.account_privacy'::regclass,'profile.notification_settings'::regclass)
          AND p_account_id=s.account_id AND p_principal_id IS NULL)
      OR (p_relation IN ('planning.account_plan_windows'::regclass,'planning.manifest_headers'::regclass,
           'planning.manifest_ranks'::regclass,'planning.manifest_seals'::regclass,
           'planning.reuse_requests'::regclass,'planning.reuse_pages'::regclass,'planning.reuse_windows'::regclass)
          AND p_account_id IS NULL AND p_principal_id=s.principal_id))
 );
END;
$feedme_core_allowed$;
REVOKE ALL ON FUNCTION identity.account_erasure_delete_allowed(oid,text,uuid,uuid) FROM PUBLIC;

CREATE OR REPLACE FUNCTION profile.guard_notification_settings() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_notification_settings$
BEGIN
 IF TG_OP='DELETE' AND TG_RELID='profile.notification_settings'::regclass
   AND identity.account_erasure_delete_allowed(TG_RELID,OLD.environment,OLD.user_id,NULL) THEN RETURN OLD; END IF;
 IF TG_OP IN('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Notification preferences require account erasure' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.user_id,NEW.id,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.environment,OLD.user_id,OLD.id,OLD.created_at)
   OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at) THEN
  RAISE EXCEPTION 'Notification preference identity is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' AND (NEW.version<>1 OR NEW.replies OR NEW.reactions OR NEW.invitations OR NEW.remixes
   OR NEW.cooking_reminders OR NEW.quiet_start_local IS NOT NULL OR NEW.quiet_end_local IS NOT NULL OR NEW.time_zone<>'UTC') THEN
  RAISE EXCEPTION 'Notification preferences begin opted out' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM identity.users WHERE environment=NEW.environment AND id=NEW.user_id AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Notification owner unavailable' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM identity.principals WHERE environment=NEW.environment AND user_id=NEW.user_id AND kind='user' AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND OR EXISTS(SELECT 1 FROM identity.account_deletion_jobs WHERE environment=NEW.environment AND user_id=NEW.user_id) THEN
  RAISE EXCEPTION 'Notification owner unavailable' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_catalog.pg_timezone_names WHERE name=NEW.time_zone) THEN
  RAISE EXCEPTION 'Notification timezone unavailable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_notification_settings$;
REVOKE ALL ON FUNCTION profile.guard_notification_settings() FROM PUBLIC;

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
 account_ops constant text[]:=ARRAY['bootstrapAccount','logoutSession','revokeSession','acceptAccountTerms','updateMe','requestAccountDeletion',
   'createPostDraft','updatePostDraft','deletePostDraft','updatePrivacySettings','updateNotificationSettings'];
 private_ops constant text[]:=ARRAY['updatePreferences','upsertPantryItem','removePantryItem',
   'createPlan','nextPlan','adaptPlan','simplifyPlan','createCookSession','updateCookSession','completeCookSession',
   'saveRecipe','deleteSavedRecipe','createFeedback','updateFeedback','deleteFeedback','updateMemory','deleteMemory','createReuseOptions'];
 unsupported_ops constant text[]:=ARRAY['createCircle','updateCircle','updateCircleMember','leaveCircle','removeCircleMember',
   'transferCircleOwnership','deleteCircle','createInvitation','acceptInvitation','revokeInvitation',
   'prepareMediaUpload','completeMediaUpload','deleteDraftMedia',
   'publishPost','deletePost','blockUser','unblockUser','createReport','createThread','sendMessage','markThreadRead',
   'requestRecipe','respondToRecipeRequest','cancelRecipeRequest'];
 account_events constant text[]:=ARRAY['identity.account.bootstrapped.v1','identity.session.revoked.v1',
   'profile.profile.changed.v1','identity.account.deletion_requested.v1','social.post_draft.changed.v1'];
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
   'profile.profiles','profile.preferences','profile.onboarding_decisions','profile.notification_settings','pantry.pantry_items',
   'planning.plan_requests','planning.plans','planning.account_plan_windows','planning.guest_preparations',
   'planning.reuse_requests','planning.reuse_pages','planning.reuse_windows',
   'planning.manifest_headers','planning.manifest_ranks','planning.manifest_seals',
   'cooking.cook_sessions','cooking.device_cursors','cooking.step_events',
   'memory.library_heads','memory.saved_recipes','memory.collections','memory.collection_items','memory.save_commands',
   'memory.feedback','memory.feedback_commands','memory.make_again_actions','memory.memory_heads','memory.memory_feedback_state',
   'memory.memory_dirty_groups','memory.memories','memory.memory_sources','memory.memory_suppressions','memory.memory_commands','memory.memory_projection_events',
   'platform.idempotency','platform.outbox','platform.consumer_inbox','platform.media_draft_lifecycles','platform.media_assets',
   'platform.media_cleanup_jobs','platform.media_processing_jobs','platform.media_processing_inbox','platform.media_derivative_intents',
   'platform.media_processing_cleanup','platform.post_draft_heads','platform.post_drafts','platform.post_draft_discard_media',
   'platform.media_upload_issuances','platform.media_private_materializations','platform.media_safety_records','platform.media_safety_revocations',
   'platform.media_digest_readiness',
   'social.circles','social.circle_members','social.circle_invitations','social.block_pairs','social.blocks','social.posts',
   'social.account_privacy','social.direct_threads','social.thread_messages','social.thread_read_watermarks','social.recipe_requests',
   'social.post_publications','social.post_media','social.post_audiences','social.post_attachments','social.recipe_save_policies',
   'social.post_publication_discard_media','safety.moderation_cases','safety.reports','safety.report_evidence',
   'staff.publication_policies','staff.actors','staff.publication_approvals','staff.approval_revocations'];
 private_delete_order constant text[]:=ARRAY[
   'planning.reuse_pages','planning.reuse_requests','planning.reuse_windows',
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

 -- Shared history has no approved anonymization/retention implementation. Inspect both
 -- participants and independent message/watermark ownership in every status; never remove
 -- a peer's conversation, text, watermark, receipt or delivery evidence.
 IF EXISTS(SELECT 1 FROM social.direct_threads t WHERE t.environment=p_environment
      AND (t.user_low=accepted.user_id OR t.user_high=accepted.user_id))
   OR EXISTS(SELECT 1 FROM social.thread_messages m WHERE m.environment=p_environment AND m.sender_user_id=accepted.user_id)
   OR EXISTS(SELECT 1 FROM social.thread_read_watermarks r WHERE r.environment=p_environment AND r.user_id=accepted.user_id) THEN
  RETURN 'related_data';
 END IF;
 -- V054 request participants and their linked shared thread/message history are
 -- explicit inventory, not a global hold merely because the relation exists. Every
 -- status is retained; no request/message or other participant's history is erased.
 IF EXISTS(SELECT 1 FROM social.recipe_requests r WHERE r.environment=p_environment
   AND (r.requester_user_id=accepted.user_id OR r.author_user_id=accepted.user_id
     OR EXISTS(SELECT 1 FROM social.direct_threads t WHERE t.environment=r.environment AND t.id=r.thread_id
       AND (t.user_low=accepted.user_id OR t.user_high=accepted.user_id))
     OR EXISTS(SELECT 1 FROM social.thread_messages m WHERE m.environment=r.environment
       AND m.recipe_request_id=r.id AND m.thread_id=r.thread_id AND m.sender_user_id=accepted.user_id))) THEN
  RETURN 'related_data';
 END IF;
 -- V056 exact-owner digest evidence is retained media history. Its existence for
 -- unrelated accounts is not a global hold; no readiness/shared row is erased.
 IF EXISTS(SELECT 1 FROM platform.media_digest_readiness d WHERE d.environment=p_environment
     AND d.owner_user_id=accepted.user_id) THEN
  RETURN 'related_data';
 END IF;
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
 -- Only unpublished, media-free draft records are covered. Missing/malformed mediaIds
 -- is not an empty selection, and terminal media history is not proof of object absence.
 IF EXISTS(SELECT 1 FROM platform.post_drafts d WHERE d.environment=p_environment AND d.owner_user_id=accepted.user_id
   AND (d.status NOT IN ('draft','discarded','expired') OR d.published_post_id IS NOT NULL
     OR (d.status='discarded' AND d.content IS DISTINCT FROM '{}'::jsonb)
     OR (d.status<>'discarded' AND d.content->'mediaIds' IS DISTINCT FROM '[]'::jsonb))) THEN
  RETURN 'related_data';
 END IF;
 -- Every indirect media/publication child has a mandatory FK to one of these roots.
 -- Preserve every state until external issuance/derivative settlement is implemented.
 FOREACH relation_name IN ARRAY ARRAY['platform.media_assets','platform.media_cleanup_jobs',
   'platform.media_processing_jobs','platform.media_processing_cleanup',
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
       OR (i.operation_id='createReport' AND i.response_json->>'targetType'='user' AND i.response_json->>'targetId'=accepted.user_id::text)
       OR (i.operation_id IN ('createThread','markThreadRead') AND i.response_json->'participantIds' @> jsonb_build_array(accepted.user_id::text))
       OR (i.operation_id='sendMessage' AND i.response_json#>>'{sender,userId}'=accepted.user_id::text)
       OR (i.operation_id IN ('requestRecipe','respondToRecipeRequest')
         AND (i.response_json->>'requesterUserId'=accepted.user_id::text OR i.response_json->>'authorUserId'=accepted.user_id::text)))) THEN
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
 -- V042 allowed complete owner tuples for then-unknown families. Do not trust an
 -- older typed draft row whose source fact contradicts its explicit owner tuple.
 IF EXISTS(SELECT 1 FROM platform.outbox e WHERE e.event_type='social.post_draft.changed.v1'
   AND e.owner_environment=p_environment AND e.owner_kind='account' AND e.owner_id=accepted.user_id
   AND (jsonb_typeof(e.payload->'environment') IS DISTINCT FROM 'string'
     OR jsonb_typeof(e.payload->'ownerId') IS DISTINCT FROM 'string'
     OR e.payload->>'environment' IS DISTINCT FROM e.owner_environment
     OR e.payload->>'ownerId' IS DISTINCT FROM e.owner_id::text)) THEN
  RETURN 'unattributed_events';
 END IF;
 IF EXISTS(SELECT 1 FROM platform.outbox e WHERE e.owner_environment=p_environment AND e.owner_kind='account'
   AND e.owner_id<>accepted.user_id
   AND ((e.event_type='safety.block.changed.v1' AND (e.payload->>'blockerUserId'=accepted.user_id::text OR e.payload->>'blockedUserId'=accepted.user_id::text))
     OR (e.event_type='circles.membership.changed.v1' AND e.payload->>'userId'=accepted.user_id::text)
     OR (e.event_type='circles.ownership.transferred.v1' AND (e.payload->>'fromUserId'=accepted.user_id::text OR e.payload->>'toUserId'=accepted.user_id::text))
     OR (e.event_type='social.post.published.v1' AND e.payload->>'authorUserId'=accepted.user_id::text)
     OR (e.event_type='social.post_draft.changed.v1' AND e.payload->>'ownerId'=accepted.user_id::text)
     OR (e.event_type='conversations.message.created.v1' AND e.payload->>'senderUserId'=accepted.user_id::text))) THEN
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
 -- The checks above establish this slice's media-free local inventory; external
 -- settlement is not implemented. Owner-write fences prevent root resurrection.
 DELETE FROM platform.post_drafts d WHERE d.environment=p_environment AND d.owner_user_id=accepted.user_id;
 DELETE FROM platform.post_draft_heads h WHERE h.environment=p_environment AND h.owner_user_id=accepted.user_id;
 DELETE FROM platform.media_draft_lifecycles d WHERE d.environment=p_environment AND d.owner_user_id=accepted.user_id;
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
 -- Private settings belong only to this accepted account. The row guard requires
 -- the same live capability; neither FK CASCADE nor an operator-supplied UUID grants it.
 DELETE FROM profile.notification_settings p WHERE p.environment=p_environment AND p.user_id=accepted.user_id;
 DELETE FROM social.account_privacy p WHERE p.environment=p_environment AND p.user_id=accepted.user_id;
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
