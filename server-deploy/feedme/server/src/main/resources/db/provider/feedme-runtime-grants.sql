-- Fixed account-core runtime privileges. Apply only inside an installer-owned transaction
-- AFTER verified V001--V032 and the exact managed Auth projector installation. No roles,
-- passwords, provider grants, policies, default privileges or database connections are
-- created here. The installer separately controls CONNECT on its exact selected database.
-- This is not an ACL reset: reject unexpected inherited/PUBLIC/existing privileges before
-- installation and verify the complete effective ACL afterwards, including callable
-- SECURITY DEFINER functions. Keep these schemas outside the client Data API.
--
-- Required role: pre-created feedme_api, BYPASSRLS for the reviewed server-owned account
-- transaction path, but no administrative flags, memberships, ownership or trigger bypass.
-- LOGIN/password provisioning is separate; isolated acceptance can use NOLOGIN + SET ROLE.
-- Application SQL still enforces account/environment ownership. These database grants do
-- not establish per-user authority, eligibility, catalog publication or provider readiness.

SELECT pg_catalog.pg_advisory_xact_lock(x'464545444d450001'::bit(64)::bigint);

DO $feedme$
DECLARE api pg_catalog.pg_roles%ROWTYPE;
BEGIN
    SELECT * INTO STRICT api FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    IF api.rolsuper OR api.rolcreatedb OR api.rolcreaterole OR api.rolreplication
        OR NOT api.rolbypassrls OR current_user='feedme_api'
        OR pg_catalog.current_setting('session_replication_role')<>'origin'
        OR pg_catalog.has_parameter_privilege(api.oid,'session_replication_role','SET')
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_class WHERE relowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc WHERE proowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace
            WHERE nspname !~ '^pg_temp_' AND nspname !~ '^pg_toast_temp_'
                AND pg_catalog.has_schema_privilege(api.oid,oid,'CREATE'))
        OR pg_catalog.has_schema_privilege(api.oid,'auth','USAGE') THEN
        RAISE EXCEPTION 'Unsafe account-core runtime role';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=31 AND checksum='3d6746742df8bccd7a9f95cbe32bac1892f8149bdcbf462e2558d4a46d13581c') THEN
        RAISE EXCEPTION 'Account-core immutable-key migration is not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=32 AND checksum='e59d05ea87aea30722b43330f21653bfc078a340332110de4bdf7f08e89a2f98') THEN
        RAISE EXCEPTION 'Account Terms evidence migration is not installed';
    END IF;
END;
$feedme$;

-- Protect the relevant table/trigger attachments through this provisioning transaction.
-- ROW EXCLUSIVE coexists with normal DML; no provider table or migration history is locked.
LOCK TABLE ONLY catalog.ingredient_heads, ONLY catalog.ingredient_releases,
    ONLY catalog.ingredient_release_items, ONLY catalog.ingredient_release_aliases,
    ONLY catalog.recipe_heads, ONLY catalog.recipe_releases,
    ONLY catalog.recipe_release_entries, ONLY catalog.recipe_release_compositions,
    ONLY catalog.recipe_copy_grants, ONLY catalog.recipe_copy_revocations,
    ONLY identity.principals, ONLY identity.device_reconnections, ONLY identity.account_terms_acceptances,
    ONLY platform.outbox, ONLY profile.onboarding_decisions, ONLY planning.plans,
    ONLY cooking.step_events, ONLY memory.collection_items, ONLY memory.save_commands
    IN ROW EXCLUSIVE MODE;

DO $feedme$
DECLARE wanted record; found_guard record;
BEGIN
    FOR wanted IN SELECT * FROM (VALUES
        ('catalog','ingredient_releases','release_id','ingredient_releases_immutable','catalog','reject_ingredient_release_mutation',27,false,'Ingredient releases are immutable'),
        ('catalog','ingredient_release_items','ingredient_id','ingredient_items_immutable','catalog','reject_ingredient_release_mutation',27,false,'Ingredient releases are immutable'),
        ('catalog','ingredient_release_aliases','ingredient_id','ingredient_aliases_immutable','catalog','reject_ingredient_release_mutation',27,false,'Ingredient releases are immutable'),
        ('catalog','recipe_releases','release_id','recipe_releases_immutable','catalog','reject_recipe_release_mutation',27,false,'Recipe releases are immutable'),
        ('catalog','recipe_release_entries','recipe_version_id','recipe_entries_immutable','catalog','reject_recipe_release_mutation',27,false,'Recipe releases are immutable'),
        ('catalog','recipe_release_compositions','ingredient_id','recipe_compositions_immutable','catalog','reject_recipe_release_mutation',27,false,'Recipe releases are immutable'),
        ('catalog','recipe_copy_grants','grant_id','recipe_copy_grants_immutable','catalog','reject_recipe_copy_rights_mutation',27,false,'Recipe copy rights originals are immutable'),
        ('catalog','recipe_copy_revocations','grant_id','recipe_copy_revocations_immutable','catalog','reject_recipe_copy_rights_mutation',27,false,'Recipe copy rights originals are immutable'),
        ('planning','plans','id','immutable_plan_snapshot','planning','reject_plan_update',19,false,'Plan snapshots are immutable'),
        ('cooking','step_events','command_id','immutable_cooking_event','cooking','reject_step_event_update',19,false,'Cooking events are immutable'),
        ('profile','onboarding_decisions','command_key','onboarding_decision_immutable','profile','keep_onboarding_decision_immutable',27,false,'Onboarding decision is immutable'),
        ('identity','device_reconnections','command_key','device_reconnection_immutable','identity','keep_device_reconnection_immutable',27,false,'Device reconnection evidence is immutable'),
        ('catalog','ingredient_heads','environment','ingredient_heads_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated'),
        ('catalog','recipe_heads','environment','recipe_heads_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated'),
        ('identity','principals','id','principals_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated'),
        ('platform','outbox','event_id','outbox_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated'),
        ('memory','collection_items','saved_recipe_id','collection_items_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated'),
        ('memory','save_commands','command_id','save_commands_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated')
    ) AS expected(schema_name,table_name,column_name,trigger_name,function_schema,function_name,trigger_type,column_guard,error_text)
    LOOP
        SELECT t.tgtype,t.tgenabled,t.tgisinternal,t.tgqual,t.tgattr,t.tgnargs,t.tgconstraint,
            c.relkind,a.attnum,p.prosrc,p.prosecdef,p.pronargs,p.prorettype,l.lanname,
            EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) AS inherited
        INTO found_guard
        FROM pg_catalog.pg_namespace n
        JOIN pg_catalog.pg_class c ON c.relnamespace=n.oid
        JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid
        JOIN pg_catalog.pg_trigger t ON t.tgrelid=c.oid
        JOIN pg_catalog.pg_proc p ON p.oid=t.tgfoid
        JOIN pg_catalog.pg_namespace pn ON pn.oid=p.pronamespace
        JOIN pg_catalog.pg_language l ON l.oid=p.prolang
        WHERE n.nspname=wanted.schema_name AND c.relname=wanted.table_name
            AND a.attname=wanted.column_name AND a.attnum>0 AND NOT a.attisdropped
            AND t.tgname=wanted.trigger_name
            AND pn.nspname=wanted.function_schema AND p.proname=wanted.function_name;
        IF NOT FOUND THEN RAISE EXCEPTION 'Missing account-core immutable-key guard'; END IF;
        IF found_guard.relkind<>'r' OR found_guard.inherited
            OR found_guard.tgtype<>wanted.trigger_type OR found_guard.tgenabled NOT IN ('O','A')
            OR found_guard.tgisinternal OR found_guard.tgqual IS NOT NULL
            OR found_guard.tgnargs<>0 OR found_guard.tgconstraint<>0
            OR found_guard.tgattr::text <> (CASE WHEN wanted.column_guard THEN found_guard.attnum::text ELSE '' END)
            OR found_guard.prosecdef OR found_guard.pronargs<>0
            OR found_guard.prorettype<>'pg_catalog.trigger'::pg_catalog.regtype OR found_guard.lanname<>'plpgsql'
            OR pg_catalog.regexp_replace(found_guard.prosrc,'[[:space:]]','','g') <>
                pg_catalog.regexp_replace('BEGIN RAISE EXCEPTION '||pg_catalog.quote_literal(wanted.error_text)||
                    ' USING ERRCODE = ''23514''; END;','[[:space:]]','','g') THEN
            RAISE EXCEPTION 'Account-core immutable-key guard differs from reviewed source';
        END IF;
    END LOOP;
END;
$feedme$;

-- Terms evidence is insert/read only (no lock-only UPDATE is needed). The migration hash
-- alone cannot establish the current guards or ACLs after administrative DDL.
DO $feedme$
DECLARE expected record; guard record; evidence record; api_oid oid;
BEGIN
    SELECT oid INTO STRICT api_oid FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    SELECT c.oid,c.relowner,c.relkind,c.relrowsecurity,c.relforcerowsecurity,c.relacl INTO STRICT evidence
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='identity' AND c.relname='account_terms_acceptances';
    IF evidence.relkind<>'r' OR NOT evidence.relrowsecurity OR NOT evidence.relforcerowsecurity
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=evidence.oid OR inhparent=evidence.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(evidence.relacl,pg_catalog.acldefault('r',evidence.relowner))) a
            WHERE a.grantee<>evidence.relowner AND
                (a.grantee<>api_oid OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.is_grantable))
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
            CROSS JOIN LATERAL pg_catalog.aclexplode(col.attacl) a
            WHERE col.attrelid=evidence.oid AND a.grantee<>evidence.relowner AND
                (a.grantee<>api_oid OR a.privilege_type NOT IN ('SELECT','INSERT') OR a.is_grantable)) THEN
        RAISE EXCEPTION 'Unsafe Account Terms evidence table';
    END IF;
    FOR expected IN SELECT * FROM (VALUES
        ('account_terms_acceptance_immutable',27),('account_terms_acceptance_retained',34)
    ) AS wanted(trigger_name,trigger_type) LOOP
        SELECT t.*,p.prosrc,p.proowner,p.prosecdef,p.pronargs,p.prorettype,p.proconfig,p.proacl,l.lanname
            INTO guard FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_proc p ON p.oid=t.tgfoid
            JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
            JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            WHERE t.tgrelid=evidence.oid AND t.tgname=expected.trigger_name
                AND n.nspname='identity' AND p.proname='keep_account_terms_acceptance_immutable';
        IF NOT FOUND THEN RAISE EXCEPTION 'Missing Account Terms evidence guard'; END IF;
        IF guard.tgtype<>expected.trigger_type OR guard.tgenabled NOT IN ('O','A') OR guard.tgisinternal
            OR guard.tgqual IS NOT NULL OR guard.tgattr::text<>'' OR guard.tgnargs<>0 OR guard.tgconstraint<>0
            OR guard.proowner<>evidence.relowner OR guard.prosecdef OR guard.pronargs<>0
            OR guard.prorettype<>'pg_catalog.trigger'::pg_catalog.regtype OR guard.lanname<>'plpgsql'
            OR guard.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR pg_catalog.regexp_replace(guard.prosrc,'[[:space:]]','','g') <>
                pg_catalog.regexp_replace('BEGIN RAISE EXCEPTION ''Account Terms acceptance evidence is immutable'' USING ERRCODE=''23514''; END;','[[:space:]]','','g')
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(guard.proacl,pg_catalog.acldefault('f',guard.proowner))) a
                WHERE a.grantee<>guard.proowner) THEN
            RAISE EXCEPTION 'Account Terms evidence guard differs from reviewed source';
        END IF;
    END LOOP;
END;
$feedme$;

GRANT USAGE ON SCHEMA platform,identity,profile,pantry,planning,cooking,memory,catalog,feedme_auth_access TO feedme_api;
GRANT EXECUTE ON FUNCTION feedme_auth_access.schema_lock(),feedme_auth_access.migration_versions(),
    feedme_auth_access.user_facts(uuid),feedme_auth_access.factor_facts(uuid),feedme_auth_access.session_facts(uuid),
    feedme_auth_access.amr_facts(uuid),feedme_auth_access.password_facts(uuid,uuid) TO feedme_api;

-- Whole-row reads are required by the existing SELECT */to_jsonb retained-observation
-- checks. History needs only its exact version/checksum columns. No Auth table reads.
GRANT SELECT(version,checksum) ON platform.schema_migrations TO feedme_api;
GRANT SELECT ON platform.idempotency,platform.outbox,
    identity.users,identity.principals,identity.device_sessions,identity.device_reconnections,identity.account_terms_acceptances,
    profile.profiles,profile.preferences,profile.onboarding_decisions,pantry.pantry_items,
    planning.account_plan_windows,planning.plan_requests,planning.plans,
    cooking.cook_sessions,cooking.device_cursors,cooking.step_events,
    memory.saved_recipes,memory.collections,memory.collection_items,memory.save_commands,memory.library_heads,
    catalog.ingredient_heads,catalog.ingredient_releases,catalog.ingredient_release_items,catalog.ingredient_release_aliases,
    catalog.recipe_heads,catalog.recipe_releases,catalog.recipe_release_entries,catalog.recipe_release_compositions,
    catalog.recipe_version_history,catalog.recipe_copy_grants,catalog.recipe_copy_revocations TO feedme_api;

-- Exact current INSERT columns. Defaults remain database-owned; in particular these grants
-- do not permit choosing new Plan storage formats, marking outbox delivery, or selecting
-- new optional columns merely because a future migration adds them.
GRANT INSERT(principal_scope,operation_id,key,request_hash,state,expires_at) ON platform.idempotency TO feedme_api;
GRANT INSERT(event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,
    producer,correlation_id,causation_id,payload) ON platform.outbox TO feedme_api;
GRANT INSERT(environment,id,provider_issuer,provider_subject,status,eligibility_state,eligibility_policy_version,version)
    ON identity.users TO feedme_api;
GRANT INSERT(environment,id,user_id,kind,status,version) ON identity.principals TO feedme_api;
GRANT INSERT(environment,id,user_id,installation_id_hash,provider_session_id,platform,device_label,app_version,version)
    ON identity.device_sessions TO feedme_api;
GRANT INSERT(environment,user_id,command_key,submitted_terms_version,accepted_terms_version,
    eligibility_declaration,eligibility_state,eligibility_policy_version) ON identity.bootstrap_consents TO feedme_api;
GRANT INSERT(environment,user_id,command_key,request_sha256,previous_device_id,new_device_id,
    previous_provider_session_id,new_provider_session_id,provider_issuer,provider_subject,installation_id_hash,platform,
    previous_device_version,policy_revision,consent_version,maximum_authentication_age_seconds,
    provider_session_created_at,password_authenticated_at,authorized_at,valid_until) ON identity.device_reconnections TO feedme_api;
GRANT INSERT(environment,user_id,command_key,request_sha256,device_session_id,provider_issuer,provider_subject,
    provider_session_id,terms_version,notice_sha256,terms_url,privacy_url,accepted_at) ON identity.account_terms_acceptances TO feedme_api;
GRANT INSERT(environment,user_id,onboarding_step,live,version) ON profile.profiles TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,id,version,created_at,updated_at,fields) ON profile.preferences TO feedme_api;
GRANT INSERT(environment,user_id,command_key,request_sha256,device_session_id,prompt,disposition,next_step,
    profile_version_before,profile_version_after,preference_id,preference_version,preference_sha256)
    ON profile.onboarding_decisions TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,ingredient_id,id,version,created_at,updated_at,fields) ON pantry.pantry_items TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,window_date,policy_revision,max_plans,used_count) ON planning.account_plan_windows TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,id,request_text,request_hash,evidence_text,evidence_hash,
    ordered_ids,policy_text,version,created_at,expires_at,cursor_expires_at) ON planning.plan_requests TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,id,request_id,parent_plan_id,version,position,recipe_version_id,
    status,snapshot_text,snapshot_hash,proof_text,proof_hash,next_cursor_hash,created_at) ON planning.plans TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,id,plan_id,version,status,device_sequence,snapshot,
    plan_snapshot_text,plan_snapshot_hash,plan_proof_hash,plan_evidence_hash,created_at,updated_at,expires_at)
    ON cooking.cook_sessions TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,session_id,device_identity,device_sequence) ON cooking.device_cursors TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,session_id,command_id,device_identity,device_sequence,
    session_version,kind,request_hash,payload,accepted_at) ON cooking.step_events TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,id,generation,version,recipe_version_id,recipe_hash,
    source_type,source_id,origin_plan_id,content_license,snapshot,copy_evidence,created_at,updated_at)
    ON memory.saved_recipes TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,id,version,is_default,name,created_at,updated_at) ON memory.collections TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,collection_id,saved_recipe_id,position) ON memory.collection_items TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,command_id,saved_recipe_id,collection_id,generation) ON memory.save_commands TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,revision) ON memory.library_heads TO feedme_api;

-- Mutations actually used by the account-core stores, never full-table UPDATE grants.
GRANT UPDATE(state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at) ON platform.idempotency TO feedme_api;
GRANT UPDATE(terms_version,terms_accepted_at,version,updated_at) ON identity.users TO feedme_api;
GRANT UPDATE(last_seen_at,updated_at,version,app_version,revoked_at,logout_command_key) ON identity.device_sessions TO feedme_api;
GRANT UPDATE(display_name,normalized_handle,bio,avatar_media_id,onboarding_step,version,updated_at) ON profile.profiles TO feedme_api;
GRANT UPDATE(fields,version,updated_at) ON profile.preferences TO feedme_api;
GRANT UPDATE(id,version,created_at,updated_at,fields,deleted,deletion_key) ON pantry.pantry_items TO feedme_api;
GRANT UPDATE(used_count) ON planning.account_plan_windows TO feedme_api;
GRANT UPDATE(current_plan_id,version) ON planning.plan_requests TO feedme_api;
GRANT UPDATE(version,status,device_sequence,snapshot,updated_at) ON cooking.cook_sessions TO feedme_api;
GRANT UPDATE(device_sequence) ON cooking.device_cursors TO feedme_api;
GRANT UPDATE(deleted,snapshot,copy_evidence,deletion_key,version,updated_at) ON memory.saved_recipes TO feedme_api;
GRANT UPDATE(version,updated_at) ON memory.collections TO feedme_api;
GRANT UPDATE(revision) ON memory.library_heads TO feedme_api;
GRANT DELETE ON memory.collection_items TO feedme_api;

-- Lock-only UPDATE privileges: every attempted assignment, including key=key, is rejected
-- by the exact checked unconditional row guard (first12) or UPDATE OF key guard (last6).
-- SELECT FOR SHARE/UPDATE itself does not execute UPDATE triggers. No grant on history,
-- idempotency.key, catalog publication fields, principal status, or outbox delivery fields.
GRANT UPDATE(release_id) ON catalog.ingredient_releases TO feedme_api;
GRANT UPDATE(ingredient_id) ON catalog.ingredient_release_items,catalog.ingredient_release_aliases TO feedme_api;
GRANT UPDATE(release_id) ON catalog.recipe_releases TO feedme_api;
GRANT UPDATE(recipe_version_id) ON catalog.recipe_release_entries TO feedme_api;
GRANT UPDATE(ingredient_id) ON catalog.recipe_release_compositions TO feedme_api;
GRANT UPDATE(grant_id) ON catalog.recipe_copy_grants,catalog.recipe_copy_revocations TO feedme_api;
GRANT UPDATE(id) ON planning.plans TO feedme_api;
GRANT UPDATE(command_id) ON cooking.step_events TO feedme_api;
GRANT UPDATE(command_key) ON profile.onboarding_decisions,identity.device_reconnections TO feedme_api;
GRANT UPDATE(environment) ON catalog.ingredient_heads,catalog.recipe_heads TO feedme_api;
GRANT UPDATE(id) ON identity.principals TO feedme_api;
GRANT UPDATE(event_id) ON platform.outbox TO feedme_api;
GRANT UPDATE(saved_recipe_id) ON memory.collection_items TO feedme_api;
GRANT UPDATE(command_id) ON memory.save_commands TO feedme_api;

-- No sequences are used (UUIDs and explicit counters). No grant to guest/social/media,
-- manifest/derived writers, outbox workers, migration writers or catalog publishers.
-- V029's quota trigger reads identity.principals, already selected above. Current format1
-- Plans do not enter the format2/3 trigger workflows; their discriminator INSERT columns
-- are intentionally absent. Other exercised row guards use NEW/OLD or already-granted
-- tables. Invocation of installed triggers does not require exposing their functions as
-- callable runtime helpers. Only the seven explicit managed Auth functions receive EXECUTE.
