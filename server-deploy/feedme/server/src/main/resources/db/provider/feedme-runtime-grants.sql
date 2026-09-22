-- Fixed account-core runtime privileges. Apply only inside an installer-owned transaction
-- AFTER verified V001--V089 and the exact managed Auth projector installation. No roles,
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
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=33 AND checksum='2b4d048c6aa1b8d7459e859462794788695d2f567851159b0d7e13e789689054') THEN
        RAISE EXCEPTION 'Substitution immutable-key migration is not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=39 AND checksum='5b72536aedc01b7497063d7602f0525a259fbdfe5a2450f9fc45d7c9567d9197') THEN
        RAISE EXCEPTION 'Typed OAuth reconnection migration is not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=42 AND checksum='a0deb4ba3a8c0cc6a95db6d86163f90102bdc41d1267aa1d33884c6bbb2aba25') THEN
        RAISE EXCEPTION 'Explicit outbox ownership migration is not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=44 AND checksum='458427599ac9aadc335d4d32bbb25a58e54bc35ba561e466f1ccc153f11b84fc') THEN
        RAISE EXCEPTION 'Narrow account core erasure guards are not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=45 AND checksum='db08c47a051791781ffb5f26257484aea4dc0cadaf2c56db2dad764817aa77e5') THEN
        RAISE EXCEPTION 'Separate provider erasure boundary is not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=46 AND checksum='4cdc07044f2c7c6c84787362e4b46abc1a34f07d30943554716872a0a20d140a') THEN
        RAISE EXCEPTION 'Narrow account manifest erasure guards are not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=47 AND checksum='bd8162bb5ad751e7d67d4796264d09d71f1d90fd1d4b519f1a626a4112be0e47') THEN
        RAISE EXCEPTION 'Controlled provider erasure retry boundary is not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=48 AND checksum='dbaf7b8b52dfd64f728c2c0d2695e45d30c12e32fae41151b6762134785a7a57') THEN
        RAISE EXCEPTION 'Narrow account draft erasure guards are not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=49 AND checksum='a131bc97d5889afeeecb16e710ceb035777f549f120d1c7635208ebb09458842') THEN
        RAISE EXCEPTION 'Exact media source erasure boundary is not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=50 AND checksum='c2c6d4a71e36a8e3f20b541d04ea1452af832380c2735e2f7ff6e6f967effcf9') THEN
        RAISE EXCEPTION 'Accepted account media capture boundary is not installed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations
        WHERE version=89 AND checksum='da4f0903499b3257c97bc8a9f2f86159e0f78a0a9ef8b6a205ebd11cca87dc5f') THEN
        RAISE EXCEPTION 'Never-dispatched export erasure boundary is not installed';
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
    ONLY catalog.substitution_heads, ONLY catalog.substitution_publications,
    ONLY identity.principals, ONLY identity.device_reconnections, ONLY identity.account_terms_acceptances,
    ONLY identity.account_erasure_scope, ONLY identity.account_erasure_work, ONLY planning.account_plan_windows,
    ONLY planning.manifest_headers, ONLY planning.manifest_ranks, ONLY planning.manifest_seals,
    ONLY platform.post_drafts, ONLY platform.post_draft_heads, ONLY platform.media_draft_lifecycles,
    ONLY erasure.provider_deletions, ONLY erasure.provider_deletion_retries,
    ONLY erasure.media_source_deletions, ONLY erasure.media_source_observations,
    ONLY erasure.media_source_captures,
    ONLY social.post_reactions, ONLY platform.account_reaction_notifications,
    ONLY platform.account_export_jobs, ONLY platform.account_export_artifacts,
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
        ('catalog','substitution_publications','publication_id','substitution_publications_immutable','catalog','reject_substitution_mutation',27,false,'Substitution history is immutable'),
        ('planning','plans','id','immutable_plan_snapshot','planning','reject_plan_update',19,false,'Plan snapshots are immutable'),
        ('cooking','step_events','command_id','immutable_cooking_event','cooking','reject_step_event_update',19,false,'Cooking events are immutable'),
        ('planning','account_plan_windows','principal_id','account_plan_window_retained','planning','reject_manifest_mutation',34,false,'Planning manifests are immutable'),
        ('planning','manifest_headers','manifest_id','manifest_header_no_truncate','planning','reject_manifest_mutation',34,false,'Planning manifests are immutable'),
        ('planning','manifest_ranks','manifest_id','manifest_rank_no_truncate','planning','reject_manifest_mutation',34,false,'Planning manifests are immutable'),
        ('planning','manifest_seals','manifest_id','manifest_seal_no_truncate','planning','reject_manifest_mutation',34,false,'Planning manifests are immutable'),
        ('catalog','ingredient_heads','environment','ingredient_heads_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated'),
        ('catalog','recipe_heads','environment','recipe_heads_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated'),
        ('catalog','substitution_heads','environment','substitution_heads_lock_key_immutable','platform','reject_lock_only_key_update',19,true,'Lock-only identity columns cannot be updated'),
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

-- V045 is a separate worker boundary. API privileges remain exactly unchanged.
-- Explicitly provisioned workers may hold schema USAGE, ledger SELECT and only
-- the four reviewed worker function EXECUTEs, all without grant options. This
-- does not approve a worker identity, credential, scheduler or live deployment.
DO $feedme$
DECLARE expected record; actual record; ledger record; namespace record; api_oid oid;
BEGIN
    SELECT oid INTO STRICT api_oid FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    SELECT * INTO STRICT namespace FROM pg_catalog.pg_namespace WHERE nspname='erasure';
    SELECT * INTO STRICT ledger FROM pg_catalog.pg_class
        WHERE oid='erasure.provider_deletions'::pg_catalog.regclass;
    IF namespace.nspowner=api_oid OR ledger.relnamespace<>namespace.oid
        OR ledger.relowner<>namespace.nspowner OR ledger.relkind<>'r'
        OR NOT ledger.relrowsecurity OR NOT ledger.relforcerowsecurity
        OR pg_catalog.has_schema_privilege(api_oid,namespace.oid,'USAGE,CREATE')
        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(namespace.nspacl,pg_catalog.acldefault('n',namespace.nspowner))) a
            WHERE a.grantee<>namespace.nspowner AND
                (a.grantee IN (0,api_oid) OR a.privilege_type<>'USAGE' OR a.is_grantable))
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_class c WHERE c.relnamespace=namespace.oid
            AND c.relkind IN ('r','p','v','m','f','S')
            AND c.oid NOT IN (ledger.oid,'erasure.provider_deletion_retries'::pg_catalog.regclass,
                'erasure.media_source_deletions'::pg_catalog.regclass,'erasure.media_source_observations'::pg_catalog.regclass,
                'erasure.media_source_captures'::pg_catalog.regclass))
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=ledger.oid OR inhparent=ledger.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy WHERE polrelid=ledger.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(ledger.relacl,pg_catalog.acldefault('r',ledger.relowner))) a
            WHERE a.grantee<>ledger.relowner AND
                (a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable))
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
            CROSS JOIN LATERAL pg_catalog.aclexplode(col.attacl) a
            WHERE col.attrelid=ledger.oid AND a.grantee<>ledger.relowner AND
                (a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable))
        -- Five V045 originals, four V047 retries, eight V049 sources and two V050 captures.
        OR (SELECT count(*) FROM pg_catalog.pg_proc WHERE pronamespace=namespace.oid)<>19 THEN
        RAISE EXCEPTION 'Provider erasure schema and ledger are not private';
    END IF;
    FOR expected IN SELECT * FROM (VALUES
        ('erasure.guard_account_provider_deletion()',0,false,'trigger','8a730aa577131fbafe59f69bd73a34d2f892b27cd524e708f1275a6e2d03b80c',false),
        ('erasure.claim_account_provider_erasure(text,uuid,uuid,integer)',4,true,
            'TABLE(job_id uuid, attempt_id uuid, user_id uuid, provider_issuer text, provider_subject uuid, generation bigint, lease_expires_at timestamp with time zone, action text)',
            'cebb9d9781600baa49502fceaede34b150c4b62236c0f59dfa3e28d244d4b5dd',true),
        ('erasure.dispatch_account_provider_erasure(text,uuid,uuid,uuid,bigint)',5,false,'boolean','c7f3ea180f22aff4eea72828ada0c27c3fe751e6425a58d19b7333e729de9133',true),
        ('erasure.record_account_provider_erasure(text,uuid,uuid,uuid,bigint,text)',6,false,'boolean','9391cd6be35b01e5a6788de079a4e9c298bb29a6c0f52ee367f0b4227a554691',true),
        ('erasure.reconcile_account_provider_erasure(text,uuid,uuid,uuid,bigint,integer)',6,false,'text','384ab2429800b57100eee37da87c255d3dbcf3e1000c9a50ea2755fcda99a483',true)
    ) AS wanted(signature,argument_count,returns_set,result_text,body_sha256,worker_callable) LOOP
        SELECT p.*,l.lanname,(o.rolsuper OR o.rolbypassrls) AS bypass,
            pg_catalog.pg_get_function_result(p.oid) AS result_text INTO STRICT actual
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            JOIN pg_catalog.pg_roles o ON o.oid=p.proowner
            WHERE p.oid=expected.signature::pg_catalog.regprocedure;
        IF actual.proowner<>ledger.relowner OR NOT actual.bypass OR NOT actual.prosecdef
            OR actual.pronargs<>expected.argument_count OR actual.prokind<>'f'
            OR actual.proretset<>expected.returns_set OR actual.result_text<>expected.result_text OR actual.lanname<>'plpgsql'
            OR actual.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(actual.prosrc,'UTF8')),'hex')<>expected.body_sha256
            OR pg_catalog.has_function_privilege(api_oid,actual.oid,'EXECUTE')
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.proacl,pg_catalog.acldefault('f',actual.proowner))) a
                WHERE a.grantee<>actual.proowner AND (a.grantee IN (0,api_oid) OR a.is_grantable
                    OR a.privilege_type<>'EXECUTE' OR NOT expected.worker_callable)) THEN
            RAISE EXCEPTION 'Provider erasure capability differs from reviewed source or boundary';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('account_provider_deletion_guard',31),('account_provider_deletion_retained',34)
    ) AS wanted(trigger_name,trigger_type) LOOP
        SELECT * INTO STRICT actual FROM pg_catalog.pg_trigger
            WHERE tgrelid=ledger.oid AND tgname=expected.trigger_name;
        IF actual.tgfoid<>'erasure.guard_account_provider_deletion()'::pg_catalog.regprocedure
            OR actual.tgtype<>expected.trigger_type OR actual.tgenabled NOT IN ('O','A') OR actual.tgisinternal
            OR actual.tgqual IS NOT NULL OR actual.tgattr::text<>'' OR actual.tgnargs<>0 OR actual.tgconstraint<>0 THEN
            RAISE EXCEPTION 'Provider erasure guard attachment differs from reviewed source';
        END IF;
    END LOOP;
END;
$feedme$;

-- V047 extends only the separate worker boundary. No API read, mutation or
-- direct function call is added. Worker SELECT/EXECUTE provisioning remains a
-- distinct reviewed operation; the retry guard itself is owner-only.
DO $feedme$
DECLARE expected record; actual record; ledger record; api_oid oid; parent_owner oid;
BEGIN
    SELECT oid INTO STRICT api_oid FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    SELECT relowner INTO STRICT parent_owner FROM pg_catalog.pg_class
        WHERE oid='erasure.provider_deletions'::pg_catalog.regclass;
    SELECT * INTO STRICT ledger FROM pg_catalog.pg_class
        WHERE oid='erasure.provider_deletion_retries'::pg_catalog.regclass;
    IF ledger.relowner<>parent_owner OR ledger.relkind<>'r'
        OR NOT ledger.relrowsecurity OR NOT ledger.relforcerowsecurity
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=ledger.oid OR inhparent=ledger.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy WHERE polrelid=ledger.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(ledger.relacl,pg_catalog.acldefault('r',ledger.relowner))) a
            WHERE a.grantee<>ledger.relowner AND
                (a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable))
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
            CROSS JOIN LATERAL pg_catalog.aclexplode(col.attacl) a
            WHERE col.attrelid=ledger.oid AND a.grantee<>ledger.relowner AND
                (a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable)) THEN
        RAISE EXCEPTION 'Provider retry ledger is not private';
    END IF;
    FOR expected IN SELECT * FROM (VALUES
        ('erasure.guard_account_provider_retry()',0,false,'trigger','9cb58affe5d188c6718de8968d1b0306ad368114970e6feca8b939bf61c0ad4b',false),
        ('erasure.prepare_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid,text,integer)',8,true,
            'TABLE(outcome text, retry_id uuid, retry_ordinal integer)','719f649f9ca3cf85da9bceaf34e87f56a10c3c332113512192a3a42ddffe2843',true),
        ('erasure.dispatch_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid)',6,false,'boolean','76207277687e36f05df53d844b09542221c7703f37eecad92fc7ba110a844dc1',true),
        ('erasure.record_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid,text)',7,false,'boolean','abc3a013e2a3401c94450d382f020acbdbe34c7578310f0331027b3d06706dfd',true)
    ) AS wanted(signature,argument_count,returns_set,result_text,body_sha256,worker_callable) LOOP
        SELECT p.*,l.lanname,(o.rolsuper OR o.rolbypassrls) AS bypass,
            pg_catalog.pg_get_function_result(p.oid) AS result_text INTO STRICT actual
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            JOIN pg_catalog.pg_roles o ON o.oid=p.proowner
            WHERE p.oid=expected.signature::pg_catalog.regprocedure;
        IF actual.proowner<>ledger.relowner OR NOT actual.bypass OR NOT actual.prosecdef
            OR actual.pronargs<>expected.argument_count OR actual.prokind<>'f'
            OR actual.proretset<>expected.returns_set OR actual.result_text<>expected.result_text OR actual.lanname<>'plpgsql'
            OR actual.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(actual.prosrc,'UTF8')),'hex')<>expected.body_sha256
            OR pg_catalog.has_function_privilege(api_oid,actual.oid,'EXECUTE')
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.proacl,pg_catalog.acldefault('f',actual.proowner))) a
                WHERE a.grantee<>actual.proowner AND (a.grantee IN (0,api_oid) OR a.is_grantable
                    OR a.privilege_type<>'EXECUTE' OR NOT expected.worker_callable)) THEN
            RAISE EXCEPTION 'Provider retry capability differs from reviewed source or boundary';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('account_provider_retry_guard',31),('account_provider_retry_retained',34)
    ) AS wanted(trigger_name,trigger_type) LOOP
        SELECT * INTO STRICT actual FROM pg_catalog.pg_trigger
            WHERE tgrelid=ledger.oid AND tgname=expected.trigger_name;
        IF actual.tgfoid<>'erasure.guard_account_provider_retry()'::pg_catalog.regprocedure
            OR actual.tgtype<>expected.trigger_type OR actual.tgenabled NOT IN ('O','A') OR actual.tgisinternal
            OR actual.tgqual IS NOT NULL OR actual.tgattr::text<>'' OR actual.tgnargs<>0 OR actual.tgconstraint<>0 THEN
            RAISE EXCEPTION 'Provider retry guard attachment differs from reviewed source';
        END IF;
    END LOOP;
END;
$feedme$;

-- V049 adds only private source-deletion/observation ledgers and separately
-- provisioned worker functions. No API or PUBLIC access is added; private
-- validator and trigger functions are owner-only even for a provisioned worker.
DO $feedme$
DECLARE expected record; actual record; ledger record; api_oid oid; parent_owner oid;
BEGIN
    SELECT oid INTO STRICT api_oid FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    SELECT nspowner INTO STRICT parent_owner FROM pg_catalog.pg_namespace WHERE nspname='erasure';
    FOR expected IN SELECT * FROM (VALUES
        ('erasure.media_source_deletions'),('erasure.media_source_observations')
    ) AS wanted(table_name) LOOP
        SELECT * INTO STRICT ledger FROM pg_catalog.pg_class WHERE oid=expected.table_name::pg_catalog.regclass;
        IF ledger.relowner<>parent_owner OR ledger.relkind<>'r'
            OR NOT ledger.relrowsecurity OR NOT ledger.relforcerowsecurity
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=ledger.oid OR inhparent=ledger.oid)
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy WHERE polrelid=ledger.oid)
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(ledger.relacl,pg_catalog.acldefault('r',ledger.relowner))) a
                WHERE a.grantee<>ledger.relowner AND
                    (a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable))
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
                CROSS JOIN LATERAL pg_catalog.aclexplode(col.attacl) a
                WHERE col.attrelid=ledger.oid AND a.grantee<>ledger.relowner AND
                    (a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable)) THEN
            RAISE EXCEPTION 'Media source erasure ledger is not private';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('erasure.lock_account_media_source(text,uuid,uuid,bigint,uuid)',5,false,'boolean','0d958e1e16cc5d875de14fae547ca0a9b4221c81c7fc43f72119c8b1db855854',false),
        ('erasure.guard_account_media_source()',0,false,'trigger','6317aeecf9b0d346c77b116fdf6a1179c090a78b692e4388cbb899d21fd95a28',false),
        ('erasure.guard_account_media_observation()',0,false,'trigger','195603b24375c8d6ab639b5516f247619ad3da49e36cd13fab838f67167ee76f',false),
        ('erasure.prepare_account_media_source(text,uuid,uuid,bigint,uuid,uuid)',6,true,
            'TABLE(intent_id uuid, user_id uuid, bucket text, object_key text, already_dispatched boolean)',
            '152448948f9bd8a3317cae35cc38a93bb2db8d2e6c381eef886e1404f8e7bc20',true),
        ('erasure.dispatch_account_media_source(text,uuid,uuid,bigint,uuid,uuid)',6,false,'boolean','95c57c541b4e4180e9156e222948cc51ade9a34a4681226d1aee98ce1c318a08',true),
        ('erasure.record_account_media_source(text,uuid,uuid,bigint,uuid,uuid,text)',7,false,'boolean','694c786a5bfe03109e4a846dd8e8bb624d886c5dde12b425f269fdb59ef00bbd',true),
        ('erasure.prepare_account_media_observation(text,uuid,uuid,bigint,uuid,uuid,uuid)',7,false,'boolean','20752b5f94ba345dcb3a92ff34e7efe4179598d8dd8f28633709f17d069b7cb3',true),
        ('erasure.record_account_media_observation(text,uuid,uuid,bigint,uuid,uuid,uuid,text)',8,false,'boolean','83c7af9f27c735cb2fbcb25132908ff94c1ecc0d0b7ac7021595a0a6d65ceb92',true)
    ) AS wanted(signature,argument_count,returns_set,result_text,body_sha256,worker_callable) LOOP
        SELECT p.*,l.lanname,(o.rolsuper OR o.rolbypassrls) AS bypass,
            pg_catalog.pg_get_function_result(p.oid) AS result_text INTO STRICT actual
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            JOIN pg_catalog.pg_roles o ON o.oid=p.proowner
            WHERE p.oid=expected.signature::pg_catalog.regprocedure;
        IF actual.proowner<>parent_owner OR NOT actual.bypass OR NOT actual.prosecdef
            OR actual.pronargs<>expected.argument_count OR actual.prokind<>'f'
            OR actual.proretset<>expected.returns_set OR actual.result_text<>expected.result_text OR actual.lanname<>'plpgsql'
            OR actual.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(actual.prosrc,'UTF8')),'hex')<>expected.body_sha256
            OR pg_catalog.has_function_privilege(api_oid,actual.oid,'EXECUTE')
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.proacl,pg_catalog.acldefault('f',actual.proowner))) a
                WHERE a.grantee<>actual.proowner AND (a.grantee IN (0,api_oid) OR a.is_grantable
                    OR a.privilege_type<>'EXECUTE' OR NOT expected.worker_callable)) THEN
            RAISE EXCEPTION 'Media source erasure capability differs from reviewed source or boundary';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('erasure.media_source_deletions','account_media_source_guard','erasure.guard_account_media_source()',31),
        ('erasure.media_source_deletions','account_media_source_retained','erasure.guard_account_media_source()',34),
        ('erasure.media_source_observations','account_media_observation_guard','erasure.guard_account_media_observation()',31),
        ('erasure.media_source_observations','account_media_observation_retained','erasure.guard_account_media_observation()',34)
    ) AS wanted(table_name,trigger_name,function_signature,trigger_type) LOOP
        SELECT * INTO STRICT actual FROM pg_catalog.pg_trigger
            WHERE tgrelid=expected.table_name::pg_catalog.regclass AND tgname=expected.trigger_name;
        IF actual.tgfoid<>expected.function_signature::pg_catalog.regprocedure
            OR actual.tgtype<>expected.trigger_type OR actual.tgenabled NOT IN ('O','A') OR actual.tgisinternal
            OR actual.tgqual IS NOT NULL OR actual.tgattr::text<>'' OR actual.tgnargs<>0 OR actual.tgconstraint<>0 THEN
            RAISE EXCEPTION 'Media source erasure guard attachment differs from reviewed source';
        END IF;
    END LOOP;
END;
$feedme$;

-- V050's accepted-account capture stays behind the separate worker boundary.
-- The guard is owner-only; worker access, when separately provisioned, is only
-- ledger SELECT and the exact capture function. No new API grant is installed.
DO $feedme$
DECLARE expected record; actual record; ledger record; api_oid oid; parent_owner oid;
BEGIN
    SELECT oid INTO STRICT api_oid FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    SELECT nspowner INTO STRICT parent_owner FROM pg_catalog.pg_namespace WHERE nspname='erasure';
    SELECT * INTO STRICT ledger FROM pg_catalog.pg_class WHERE oid='erasure.media_source_captures'::pg_catalog.regclass;
    IF ledger.relowner<>parent_owner OR ledger.relkind<>'r'
        OR NOT ledger.relrowsecurity OR NOT ledger.relforcerowsecurity
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=ledger.oid OR inhparent=ledger.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy WHERE polrelid=ledger.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(ledger.relacl,pg_catalog.acldefault('r',ledger.relowner))) a
            WHERE a.grantee<>ledger.relowner AND
                (a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable))
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
            CROSS JOIN LATERAL pg_catalog.aclexplode(col.attacl) a
            WHERE col.attrelid=ledger.oid AND a.grantee<>ledger.relowner AND
                (a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable)) THEN
        RAISE EXCEPTION 'Media source capture ledger is not private';
    END IF;
    FOR expected IN SELECT * FROM (VALUES
        ('erasure.guard_account_media_capture()',0,false,'trigger','d20544d72cccf8d6ec448f5a3c841c94129b73f7610f886c930ce5dc9b20042b',false),
        ('erasure.capture_account_media_source(text,uuid,uuid,bigint,uuid,uuid)',6,true,
            'TABLE(capture_id uuid, intent_id uuid, user_id uuid, bucket text, object_key text, already_dispatched boolean)',
            'e5a62e27c0048a7df993f4f929ba3d7d906374cea101ca7e54e00aca865d154d',true)
    ) AS wanted(signature,argument_count,returns_set,result_text,body_sha256,worker_callable) LOOP
        SELECT p.*,l.lanname,(o.rolsuper OR o.rolbypassrls) AS bypass,
            pg_catalog.pg_get_function_result(p.oid) AS result_text INTO STRICT actual
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            JOIN pg_catalog.pg_roles o ON o.oid=p.proowner
            WHERE p.oid=expected.signature::pg_catalog.regprocedure;
        IF actual.proowner<>parent_owner OR NOT actual.bypass OR NOT actual.prosecdef
            OR actual.pronargs<>expected.argument_count OR actual.prokind<>'f'
            OR actual.proretset<>expected.returns_set OR actual.result_text<>expected.result_text OR actual.lanname<>'plpgsql'
            OR actual.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(actual.prosrc,'UTF8')),'hex')<>expected.body_sha256
            OR pg_catalog.has_function_privilege(api_oid,actual.oid,'EXECUTE')
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.proacl,pg_catalog.acldefault('f',actual.proowner))) a
                WHERE a.grantee<>actual.proowner AND (a.grantee IN (0,api_oid) OR a.is_grantable
                    OR a.privilege_type<>'EXECUTE' OR NOT expected.worker_callable)) THEN
            RAISE EXCEPTION 'Media source capture capability differs from reviewed source or boundary';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('account_media_capture_guard',31),('account_media_capture_retained',34)
    ) AS wanted(trigger_name,trigger_type) LOOP
        SELECT * INTO STRICT actual FROM pg_catalog.pg_trigger
            WHERE tgrelid=ledger.oid AND tgname=expected.trigger_name;
        IF actual.tgfoid<>'erasure.guard_account_media_capture()'::pg_catalog.regprocedure
            OR actual.tgtype<>expected.trigger_type OR actual.tgenabled NOT IN ('O','A') OR actual.tgisinternal
            OR actual.tgqual IS NOT NULL OR actual.tgattr::text<>'' OR actual.tgnargs<>0 OR actual.tgconstraint<>0 THEN
            RAISE EXCEPTION 'Media source capture guard attachment differs from reviewed source';
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
            OR guard.proowner<>evidence.relowner OR NOT guard.prosecdef OR guard.pronargs<>0
            OR guard.prorettype<>'pg_catalog.trigger'::pg_catalog.regtype OR guard.lanname<>'plpgsql'
            OR guard.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(guard.prosrc,'UTF8')),'hex') <>
                '3f6d6d808a3b4b2f6f8be41d4bcb85240bbd7ebbcfc34448765355b6fe16d34f'
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(guard.proacl,pg_catalog.acldefault('f',guard.proowner))) a
                WHERE a.grantee<>guard.proowner) THEN
            RAISE EXCEPTION 'Account Terms evidence guard differs from reviewed source';
        END IF;
    END LOOP;
END;
$feedme$;

-- V044/V046/V048 change only narrowly scoped worker cleanup and account-owned
-- manifest/draft write fences. The API retains
-- its original table privileges and cannot invoke or populate these capabilities.
-- Exact bodies, fixed execution settings and every retained trigger attachment
-- are checked; merely observing a function with the expected name is insufficient.
DO $feedme$
DECLARE expected record; actual record; scope_owner oid; api_oid oid;
BEGIN
    SELECT oid INTO STRICT api_oid FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    SELECT relowner INTO STRICT scope_owner FROM pg_catalog.pg_class
        WHERE oid='identity.account_erasure_scope'::pg_catalog.regclass;
    FOR expected IN SELECT * FROM (VALUES
        ('planning.manifest_headers'),('planning.manifest_ranks'),('planning.manifest_seals'),
        ('platform.post_drafts'),('platform.post_draft_heads'),('platform.media_draft_lifecycles')
    ) AS wanted(table_name) LOOP
        SELECT c.* INTO STRICT actual FROM pg_catalog.pg_class c
            WHERE c.oid=expected.table_name::pg_catalog.regclass;
        -- The role preflight already rejects memberships/ownership. Reject all
        -- direct or PUBLIC rights, including new privilege types such as MAINTAIN,
        -- without imposing the API's boundary on a separately reviewed guest writer.
        IF EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.relacl,pg_catalog.acldefault('r',actual.relowner))) a
                WHERE a.grantee IN (0,api_oid))
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
                CROSS JOIN LATERAL pg_catalog.aclexplode(col.attacl) a
                WHERE col.attrelid=actual.oid AND a.grantee IN (0,api_oid)) THEN
            RAISE EXCEPTION 'Account manifest or draft erasure cannot add API data privileges';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('identity.account_erasure_scope'),('identity.account_erasure_work')
    ) AS wanted(table_name) LOOP
        SELECT c.* INTO STRICT actual FROM pg_catalog.pg_class c
            WHERE c.oid=expected.table_name::pg_catalog.regclass;
        IF actual.relkind<>'r' OR actual.relowner<>scope_owner
            OR NOT actual.relrowsecurity OR NOT actual.relforcerowsecurity
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=actual.oid OR inhparent=actual.oid)
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy WHERE polrelid=actual.oid)
            -- A separately provisioned worker may inspect work through its retained
            -- read-only boundary. Neither the API nor PUBLIC receives that access;
            -- the transaction-local scope remains owner-only for every caller.
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.relacl,pg_catalog.acldefault('r',actual.relowner))) a
                WHERE a.grantee<>actual.relowner AND (expected.table_name<>'identity.account_erasure_work'
                    OR a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable))
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
                CROSS JOIN LATERAL pg_catalog.aclexplode(col.attacl) a
                WHERE col.attrelid=actual.oid AND a.grantee<>actual.relowner
                    AND (expected.table_name<>'identity.account_erasure_work'
                        OR a.grantee IN (0,api_oid) OR a.privilege_type<>'SELECT' OR a.is_grantable)) THEN
            RAISE EXCEPTION 'Account erasure capability table is not private';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('identity.account_erasure_delete_allowed(oid,text,uuid,uuid)',4,'pg_catalog.bool','338d951b6492cb7687d71ad32bc6fc220f0a028139587e7cb38b3c0ad2352d39'),
        ('social.protect_post_reaction_history()',0,'pg_catalog.trigger','43e91a4dfda213b272fd587c69ade48f2eacedccdad4819ab2bdd59ae12c6d7d'),
        ('platform.guard_reaction_notification()',0,'pg_catalog.trigger','9868586627d80c1d1dbe384d0b4193c48eda21a121d813aaa7646f56dac1e249'),
        ('profile.keep_onboarding_decision_immutable()',0,'pg_catalog.trigger','a54640ac9019bf9410723ea6d57951e4a258c4490cca098dde128ad6d716cfc0'),
        ('identity.keep_device_reconnection_immutable()',0,'pg_catalog.trigger','850f78c3bf5fa5b316d515f94b03d7c9938ba401d1368d1250c0bf8264fc3f58'),
        ('identity.keep_account_terms_acceptance_immutable()',0,'pg_catalog.trigger','3f6d6d808a3b4b2f6f8be41d4bcb85240bbd7ebbcfc34448765355b6fe16d34f'),
        ('planning.guard_account_plan_window_delete()',0,'pg_catalog.trigger','ac99256d012b9ed68611342259051c2400785b0b0416d53e2e1853a06ca8b647'),
        ('identity.guard_account_core_erasure_checkpoint()',0,'pg_catalog.trigger','56556eed1d9b41bf60cdb393c39a0e8b05f22044d61cfbf63b05450011d0d9af'),
        ('planning.guard_account_manifest_erasure()',0,'pg_catalog.trigger','08f5bb3adeeaf4112d9c2297557b165364aa6676900fd5cd9ac11abca3f60c1a'),
        ('planning.guard_account_manifest_owner_insert()',0,'pg_catalog.trigger','5420705ceebfd5e48997a575878f098838202b1ea352ca298b2daaa057b8d2c1'),
        ('platform.guard_account_draft_owner_write()',0,'pg_catalog.trigger','3aeb70fc0c2a911555d59c17d414f8807d19a9eaf1249f608e12177e35287128'),
        ('identity.purge_account_core(text,uuid,uuid,bigint)',4,'pg_catalog.text','85a7e0cdcba5ad9abdbc8a49cfc02e7efa85b2450b29dbc24c74baebab83c446')
    ) AS wanted(signature,argument_count,return_type,body_sha256) LOOP
        SELECT p.*,l.lanname,(o.rolsuper OR o.rolbypassrls) AS bypass INTO STRICT actual
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            JOIN pg_catalog.pg_roles o ON o.oid=p.proowner
            WHERE p.oid=expected.signature::pg_catalog.regprocedure;
        IF actual.proowner<>scope_owner OR NOT actual.bypass OR NOT actual.prosecdef
            OR actual.pronargs<>expected.argument_count OR actual.prokind<>'f' OR actual.proretset
            OR actual.prorettype<>expected.return_type::pg_catalog.regtype OR actual.lanname<>'plpgsql'
            OR actual.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(actual.prosrc,'UTF8')),'hex')<>expected.body_sha256
            OR pg_catalog.has_function_privilege(api_oid,actual.oid,'EXECUTE')
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.proacl,pg_catalog.acldefault('f',actual.proowner))) a
                WHERE a.grantee<>actual.proowner AND (a.grantee IN (0,api_oid) OR a.is_grantable
                    OR a.privilege_type<>'EXECUTE'
                    OR expected.signature<>'identity.purge_account_core(text,uuid,uuid,bigint)')) THEN
            RAISE EXCEPTION 'Account erasure capability differs from reviewed source or boundary';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('platform.guard_account_export_jobs()','platform.account_export_jobs','535e46b390fa48a1acdf6cf0796688486f0b7e7ea05f286d11437b6e138dc60b'),
        ('platform.guard_account_export_artifacts()','platform.account_export_artifacts','260ad9e7d29d6012df45b8064964ff16f44694ad1b84aeb4defddd4729c42881')
    ) AS wanted(signature,table_name,body_sha256) LOOP
        SELECT p.*,l.lanname,t.relowner AS table_owner INTO STRICT actual
            FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            JOIN pg_catalog.pg_class t ON t.oid=expected.table_name::pg_catalog.regclass
            WHERE p.oid=expected.signature::pg_catalog.regprocedure;
        IF actual.proowner<>actual.table_owner OR actual.prosecdef OR actual.pronargs<>0
            OR actual.prokind<>'f' OR actual.proretset
            OR actual.prorettype<>'pg_catalog.trigger'::pg_catalog.regtype OR actual.lanname<>'plpgsql'
            OR actual.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(actual.prosrc,'UTF8')),'hex')<>expected.body_sha256
            OR pg_catalog.has_function_privilege(api_oid,actual.oid,'EXECUTE')
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.proacl,pg_catalog.acldefault('f',actual.proowner))) a
                WHERE a.grantee<>actual.proowner) THEN
            RAISE EXCEPTION 'Account export erasure guard differs from reviewed source or boundary';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('profile.onboarding_decisions','onboarding_decision_immutable','profile.keep_onboarding_decision_immutable()',27),
        ('identity.device_reconnections','device_reconnection_immutable','identity.keep_device_reconnection_immutable()',27),
        ('identity.device_reconnections','device_reconnection_retained','identity.keep_device_reconnection_immutable()',34),
        ('planning.account_plan_windows','account_plan_window_erasure_delete','planning.guard_account_plan_window_delete()',11),
        ('planning.manifest_headers','manifest_header_immutable','planning.guard_account_manifest_erasure()',27),
        ('planning.manifest_ranks','manifest_rank_immutable','planning.guard_account_manifest_erasure()',27),
        ('planning.manifest_seals','manifest_seal_immutable','planning.guard_account_manifest_erasure()',27),
        ('planning.manifest_headers','account_manifest_owner_fence','planning.guard_account_manifest_owner_insert()',7),
        ('platform.post_drafts','account_draft_owner_fence','platform.guard_account_draft_owner_write()',23),
        ('platform.post_draft_heads','account_draft_owner_fence','platform.guard_account_draft_owner_write()',23),
        ('platform.media_draft_lifecycles','account_draft_owner_fence','platform.guard_account_draft_owner_write()',23),
        ('social.post_reactions','post_reaction_history','social.protect_post_reaction_history()',31),
        ('social.post_reactions','post_reaction_history_truncate','social.protect_post_reaction_history()',34),
        ('platform.account_reaction_notifications','account_reaction_notification_write','platform.guard_reaction_notification()',31),
        ('platform.account_reaction_notifications','account_reaction_notification_retained','platform.guard_reaction_notification()',34),
        ('platform.account_export_jobs','account_export_jobs_guard','platform.guard_account_export_jobs()',31),
        ('platform.account_export_jobs','account_export_jobs_truncate','platform.guard_account_export_jobs()',34),
        ('platform.account_export_artifacts','account_export_artifacts_guard','platform.guard_account_export_artifacts()',31),
        ('platform.account_export_artifacts','account_export_artifacts_truncate','platform.guard_account_export_artifacts()',34),
        ('identity.account_erasure_work','account_core_erasure_checkpoint','identity.guard_account_core_erasure_checkpoint()',19),
        ('identity.account_erasure_work','account_core_erasure_checkpoint_delete','identity.guard_account_core_erasure_checkpoint()',11),
        ('identity.account_erasure_work','account_core_erasure_checkpoint_retained','identity.guard_account_core_erasure_checkpoint()',34)
    ) AS wanted(table_name,trigger_name,signature,trigger_type) LOOP
        SELECT t.*,c.relowner,c.relkind INTO STRICT actual FROM pg_catalog.pg_trigger t
            JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid
            WHERE t.tgrelid=expected.table_name::pg_catalog.regclass AND t.tgname=expected.trigger_name;
        IF actual.relkind<>'r' OR actual.relowner<>scope_owner
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=actual.tgrelid OR inhparent=actual.tgrelid)
            OR actual.tgfoid<>expected.signature::pg_catalog.regprocedure
            OR actual.tgtype<>expected.trigger_type OR actual.tgenabled NOT IN ('O','A') OR actual.tgisinternal
            OR actual.tgqual IS NOT NULL OR actual.tgattr::text<>'' OR actual.tgnargs<>0 OR actual.tgconstraint<>0 THEN
            RAISE EXCEPTION 'Account erasure guard attachment differs from reviewed source';
        END IF;
    END LOOP;
END;
$feedme$;

-- New core writes carry an immutable, explicit owner tuple. Legacy NULL ownership
-- is not retroactively inferred. Verify current shape and attachments under the
-- outbox table lock above; a migration-history hash alone is not current evidence.
DO $feedme$
DECLARE expected record; actual record; evidence record;
BEGIN
    SELECT c.oid,c.relowner INTO STRICT evidence
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='platform' AND c.relname='outbox' AND c.relkind='r';
    FOR expected IN SELECT * FROM (VALUES
        ('owner_environment','pg_catalog.text'::pg_catalog.regtype),
        ('owner_kind','pg_catalog.text'::pg_catalog.regtype),
        ('owner_id','pg_catalog.uuid'::pg_catalog.regtype)
    ) AS wanted(column_name,column_type) LOOP
        SELECT * INTO STRICT actual FROM pg_catalog.pg_attribute
            WHERE attrelid=evidence.oid AND attname=expected.column_name
                AND attnum>0 AND NOT attisdropped;
        IF actual.atttypid<>expected.column_type OR actual.atttypmod<>-1
            OR actual.attnotnull OR actual.atthasdef OR actual.attidentity<>''
            OR actual.attgenerated<>'' OR actual.attndims<>0 THEN
            RAISE EXCEPTION 'Outbox ownership column differs from reviewed shape';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('outbox_owner_tuple','(num_nonnulls(owner_environment, owner_kind, owner_id) = ANY (ARRAY[0, 3]))'),
        ('outbox_owner_environment','(owner_environment ~ ''^[a-z][a-z0-9-]{0,39}$''::text)'),
        ('outbox_owner_kind','(owner_kind = ANY (ARRAY[''account''::text, ''private_principal''::text, ''guest_principal''::text]))')
    ) AS wanted(constraint_name,expression) LOOP
        SELECT c.*,pg_catalog.pg_get_expr(c.conbin,c.conrelid,false) AS expression
            INTO STRICT actual FROM pg_catalog.pg_constraint c
            WHERE c.conrelid=evidence.oid AND c.conname=expected.constraint_name;
        IF actual.contype<>'c' OR NOT actual.convalidated OR NOT actual.conislocal
            OR actual.coninhcount<>0 OR actual.connoinherit
            OR actual.condeferrable OR actual.condeferred
            OR pg_catalog.regexp_replace(actual.expression,'[[:space:]]','','g') <>
                pg_catalog.regexp_replace(expected.expression,'[[:space:]]','','g') THEN
            RAISE EXCEPTION 'Outbox ownership constraint differs from reviewed source';
        END IF;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('outbox_ownership_required',7,'protect_outbox_ownership','012bb964cdcb55a43fb1ee13f74319c310c8061babae806189b8854a364d2fe2'),
        ('outbox_ownership_immutable',19,'protect_outbox_ownership','012bb964cdcb55a43fb1ee13f74319c310c8061babae806189b8854a364d2fe2'),
        ('outbox_post_draft_owner_required',7,'protect_post_draft_event_owner','7144a1b835c55433203da5661af3173d4d366940fb8b823c5a829f8a803f4633')
    ) AS wanted(trigger_name,trigger_type,function_name,body_sha256) LOOP
        SELECT t.*,p.prosrc,p.proowner,p.prosecdef,p.pronargs,p.prorettype,p.proconfig,p.proacl,l.lanname
            INTO STRICT actual FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_proc p ON p.oid=t.tgfoid
            JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
            JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            WHERE t.tgrelid=evidence.oid AND t.tgname=expected.trigger_name
                AND n.nspname='platform' AND p.proname=expected.function_name;
        IF actual.tgtype<>expected.trigger_type OR actual.tgenabled NOT IN ('O','A') OR actual.tgisinternal
            OR actual.tgqual IS NOT NULL OR actual.tgattr::text<>'' OR actual.tgnargs<>0 OR actual.tgconstraint<>0
            OR actual.proowner<>evidence.relowner OR actual.prosecdef OR actual.pronargs<>0
            OR actual.prorettype<>'pg_catalog.trigger'::pg_catalog.regtype OR actual.lanname<>'plpgsql'
            OR actual.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(actual.prosrc,'UTF8')),'hex') <> expected.body_sha256
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(actual.proacl,pg_catalog.acldefault('f',actual.proowner))) a
                WHERE a.grantee<>actual.proowner) THEN
            RAISE EXCEPTION 'Outbox ownership guard differs from reviewed source';
        END IF;
    END LOOP;
    SELECT i.*,c.relowner,pg_catalog.pg_get_indexdef(i.indexrelid,0,false) AS definition
        INTO STRICT actual FROM pg_catalog.pg_index i
        JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid
        JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
        WHERE i.indrelid=evidence.oid AND n.nspname='platform' AND c.relname='outbox_owned_events' AND c.relkind='i';
    IF NOT actual.indisvalid OR NOT actual.indisready OR NOT actual.indislive
        OR actual.indisunique OR actual.indisexclusion OR actual.relowner<>evidence.relowner
        OR pg_catalog.regexp_replace(actual.definition,'[[:space:]]','','g') <>
            pg_catalog.regexp_replace('CREATE INDEX outbox_owned_events ON platform.outbox USING btree (owner_environment, owner_kind, owner_id, event_id) WHERE (owner_id IS NOT NULL)',
                '[[:space:]]','','g') THEN
        RAISE EXCEPTION 'Outbox ownership index differs from reviewed shape';
    END IF;
END;
$feedme$;

GRANT USAGE ON SCHEMA platform,identity,profile,pantry,planning,cooking,memory,catalog,feedme_auth_access TO feedme_api;
GRANT EXECUTE ON FUNCTION feedme_auth_access.schema_lock(),feedme_auth_access.migration_versions(),
    feedme_auth_access.user_facts(uuid),feedme_auth_access.factor_facts(uuid),feedme_auth_access.session_facts(uuid),
    feedme_auth_access.amr_facts(uuid),feedme_auth_access.password_facts(uuid,uuid) TO feedme_api;

-- Whole-row reads are required by the existing SELECT */to_jsonb retained-observation
-- checks. History needs only its exact version/checksum columns. No Auth table reads.
GRANT SELECT(version,checksum) ON platform.schema_migrations TO feedme_api;
-- Optional adult meal interpretation reads only the original self-attestation evidence.
-- This adds no evidence mutation, account promotion or whole-row/audit-key disclosure.
GRANT SELECT(environment,user_id,submitted_terms_version,accepted_terms_version,
    eligibility_declaration,eligibility_state,eligibility_policy_version) ON identity.bootstrap_consents TO feedme_api;
GRANT SELECT ON platform.idempotency,platform.outbox,
    identity.users,identity.principals,identity.device_sessions,identity.device_reconnections,identity.account_terms_acceptances,
    profile.profiles,profile.preferences,profile.onboarding_decisions,pantry.pantry_items,
    planning.account_plan_windows,planning.plan_requests,planning.plans,
    cooking.cook_sessions,cooking.device_cursors,cooking.step_events,
    memory.saved_recipes,memory.collections,memory.collection_items,memory.save_commands,memory.library_heads,
    catalog.ingredient_heads,catalog.ingredient_releases,catalog.ingredient_release_items,catalog.ingredient_release_aliases,
    catalog.recipe_heads,catalog.recipe_releases,catalog.recipe_release_entries,catalog.recipe_release_compositions,
    catalog.recipe_version_history,catalog.recipe_copy_grants,catalog.recipe_copy_revocations,
    catalog.substitution_heads,catalog.substitution_publications TO feedme_api;

-- Exact current INSERT columns. Defaults remain database-owned; in particular these grants
-- include the explicitly integrated format1/format3 Plan discriminator and derived-command
-- fields. They do not permit marking outbox delivery or selecting new optional columns
-- merely because a future migration adds them.
GRANT INSERT(principal_scope,operation_id,key,request_hash,state,expires_at) ON platform.idempotency TO feedme_api;
GRANT INSERT(event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,
    producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id) ON platform.outbox TO feedme_api;
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
GRANT INSERT(authentication_method,oauth_authenticated_at) ON identity.device_reconnections TO feedme_api;
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
    ordered_ids,policy_text,version,created_at,expires_at,cursor_expires_at,current_plan_id,storage_format,
    derived_operation,derived_command_key,derived_request_sha256,derived_if_match,derived_parent_plan_id,derived_parent_version)
    ON planning.plan_requests TO feedme_api;
GRANT INSERT(environment,actor_kind,principal_id,id,request_id,parent_plan_id,version,position,recipe_version_id,
    status,snapshot_text,snapshot_hash,proof_text,proof_hash,next_cursor_hash,created_at,storage_format) ON planning.plans TO feedme_api;
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
-- by the exact checked unconditional row guard (13) or UPDATE OF key guard (7).
-- SELECT FOR SHARE/UPDATE itself does not execute UPDATE triggers. No UPDATE on migration history,
-- idempotency.key, catalog publication fields, principal status, or outbox delivery fields.
GRANT UPDATE(release_id) ON catalog.ingredient_releases TO feedme_api;
GRANT UPDATE(ingredient_id) ON catalog.ingredient_release_items,catalog.ingredient_release_aliases TO feedme_api;
GRANT UPDATE(release_id) ON catalog.recipe_releases TO feedme_api;
GRANT UPDATE(recipe_version_id) ON catalog.recipe_release_entries TO feedme_api;
GRANT UPDATE(ingredient_id) ON catalog.recipe_release_compositions TO feedme_api;
GRANT UPDATE(grant_id) ON catalog.recipe_copy_grants,catalog.recipe_copy_revocations TO feedme_api;
GRANT UPDATE(publication_id) ON catalog.substitution_publications TO feedme_api;
GRANT UPDATE(id) ON planning.plans TO feedme_api;
GRANT UPDATE(command_id) ON cooking.step_events TO feedme_api;
GRANT UPDATE(command_key) ON profile.onboarding_decisions,identity.device_reconnections TO feedme_api;
GRANT UPDATE(environment) ON catalog.ingredient_heads,catalog.recipe_heads,catalog.substitution_heads TO feedme_api;
GRANT UPDATE(id) ON identity.principals TO feedme_api;
GRANT UPDATE(event_id) ON platform.outbox TO feedme_api;
GRANT UPDATE(saved_recipe_id) ON memory.collection_items TO feedme_api;
GRANT UPDATE(command_id) ON memory.save_commands TO feedme_api;

-- No sequences are used (UUIDs and explicit counters). No grant to guest/social/media,
-- manifest writers, outbox workers, migration writers or recipe/substitution publishers.
-- V029's quota trigger reads identity.principals, already selected above. Account-derived
-- format3 trigger checks use the already-selected owned planning rows; format2 manifest
-- writer fields/permissions remain absent. Other exercised row guards use NEW/OLD or
-- already-granted tables. Invocation of installed triggers does not require exposing their functions as
-- callable runtime helpers. Only the seven explicit managed Auth functions receive EXECUTE.
