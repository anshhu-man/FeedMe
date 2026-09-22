-- OPTIONAL account-deletion ACCEPTANCE authority, never part of the core grant bundle.
-- Apply explicitly in an installer-owned transaction after the canonical runtime grants.
-- No roles, passwords, worker capabilities, Auth-admin access or raw deletion rights are
-- created here. This accepts/fences an account and retains its original acknowledgement;
-- it does not erase provider data or claim completed deletion. Worker activation is separate.
-- The read-only prefix is also used by AccountDeletionServingCompatibility. Keep the
-- unique boundary marker below: runtime admission must never execute the grant suffix.

LOCK TABLE ONLY identity.account_deletion_jobs, ONLY identity.account_deletion_devices,
    ONLY identity.users, ONLY identity.principals, ONLY identity.device_sessions,
    ONLY profile.profiles IN ACCESS SHARE MODE;

DO $feedme_deletion_serving$
DECLARE api pg_catalog.pg_roles%ROWTYPE; accepted record; ledger record; wanted record; guard record;
    acceptance oid := pg_catalog.to_regprocedure('identity.accept_account_deletion(text,uuid,uuid,text,uuid,uuid,uuid,uuid,text,uuid,text,text,uuid,bigint,timestamptz,timestamptz,timestamptz,timestamptz)');
BEGIN
    SELECT * INTO STRICT api FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    IF api.rolsuper OR api.rolcreatedb OR api.rolcreaterole OR api.rolreplication OR api.rolinherit OR NOT api.rolbypassrls
        OR pg_catalog.current_setting('session_replication_role')<>'origin'
        OR pg_catalog.current_setting('transaction_isolation')<>'read committed'
        OR pg_catalog.has_parameter_privilege(api.oid,'session_replication_role','SET')
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_class WHERE relowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc WHERE proowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname !~ '^pg_temp_' AND nspname !~ '^pg_toast_temp_'
            AND pg_catalog.has_schema_privilege(api.oid,oid,'CREATE'))
        OR pg_catalog.has_schema_privilege(api.oid,'auth','USAGE')
        OR pg_catalog.has_schema_privilege(api.oid,'erasure','USAGE') THEN
        RAISE EXCEPTION 'Unsafe account-deletion serving role';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations WHERE version=41
        AND checksum='6cf0836bd8b4508b81c95f5ac2f08148394a97f1f68c27e947b4f07a903972db') THEN
        RAISE EXCEPTION 'Account-deletion acceptance source is not installed';
    END IF;
    SELECT p.*,l.lanname INTO STRICT accepted FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_language l ON l.oid=p.prolang WHERE p.oid=acceptance;
    IF NOT accepted.prosecdef OR accepted.prokind<>'f' OR accepted.proretset OR accepted.pronargs<>18
        OR accepted.prorettype<>'pg_catalog.uuid'::pg_catalog.regtype OR accepted.lanname<>'plpgsql'
        OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles owner WHERE owner.oid=accepted.proowner
            AND (owner.rolsuper OR owner.rolbypassrls))
        OR accepted.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
        OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(accepted.prosrc,'UTF8')),'hex')<>
            'b26b653019b681278e941b37f876ccb2815a223bedcc8b14a809e7ca181bbfa5'
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc p WHERE p.pronamespace=accepted.pronamespace
            AND p.proname=accepted.proname AND p.oid<>acceptance)
        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(accepted.proacl,pg_catalog.acldefault('f',accepted.proowner))) a
            WHERE a.grantee<>accepted.proowner AND (a.grantee<>api.oid OR a.privilege_type<>'EXECUTE' OR a.is_grantable)) THEN
        RAISE EXCEPTION 'Account-deletion acceptance function differs from reviewed authority';
    END IF;
    FOR wanted IN SELECT * FROM (VALUES
        ('account_deletion_jobs',ARRAY['environment','id','user_id','principal_id','provider_issuer','provider_subject',
            'device_session_id','provider_session_id','command_key','request_sha256','acknowledged_version','policy_revision',
            'proof_session_id','maximum_authentication_age_seconds','provider_session_created_at','oauth_authenticated_at',
            'authorized_at','valid_until','accepted_at','stage']::text[]),
        ('account_deletion_devices',ARRAY['environment','job_id','device_session_id','provider_session_id','prior_version','revoked_at']::text[])
    ) x(name,columns) LOOP
        SELECT c.* INTO STRICT ledger FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='identity' AND c.relname=wanted.name;
        IF ledger.relkind<>'r' OR NOT ledger.relrowsecurity OR NOT ledger.relforcerowsecurity
            OR ledger.relowner<>accepted.proowner
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits WHERE inhrelid=ledger.oid OR inhparent=ledger.oid)
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy WHERE polrelid=ledger.oid)
            OR (SELECT array_agg(a.attname::text ORDER BY a.attnum) FROM pg_catalog.pg_attribute a
                WHERE a.attrelid=ledger.oid AND a.attnum>0 AND NOT a.attisdropped) IS DISTINCT FROM wanted.columns
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(ledger.relacl,pg_catalog.acldefault('r',ledger.relowner))) a
                WHERE a.grantee=0 OR (a.grantee<>ledger.relowner AND (a.privilege_type<>'SELECT' OR a.is_grantable)))
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col, LATERAL pg_catalog.aclexplode(col.attacl) a
                WHERE col.attrelid=ledger.oid AND a.grantee<>ledger.relowner) THEN
            RAISE EXCEPTION 'Account-deletion acceptance ledger boundary differs';
        END IF;
    END LOOP;
    FOR wanted IN SELECT * FROM (VALUES
        ('identity','account_deletion_jobs','account_deletion_job_immutable','keep_account_deletion_acceptance_immutable',27,false,'90bef382880e242790abc1f7db7e9ad644b7aef3a8236c575325fd22097ea891'),
        ('identity','account_deletion_jobs','account_deletion_job_retained','keep_account_deletion_acceptance_immutable',34,false,'90bef382880e242790abc1f7db7e9ad644b7aef3a8236c575325fd22097ea891'),
        ('identity','account_deletion_devices','account_deletion_device_immutable','keep_account_deletion_acceptance_immutable',27,false,'90bef382880e242790abc1f7db7e9ad644b7aef3a8236c575325fd22097ea891'),
        ('identity','account_deletion_devices','account_deletion_device_retained','keep_account_deletion_acceptance_immutable',34,false,'90bef382880e242790abc1f7db7e9ad644b7aef3a8236c575325fd22097ea891'),
        ('identity','users','account_deletion_root_fence','guard_deleting_account_root',23,true,'4c3e4421e805f49704bed02977471276a690e33c1509fde37bb693730146c270'),
        ('identity','principals','account_deletion_principal_fence','guard_deleting_principal',23,true,'3af67376c3292876f430a68bcbfa5ed89c40894f5e980aa07eee796fe7b54387'),
        ('identity','device_sessions','account_deletion_device_fence','guard_deleting_device',23,true,'366a329e3ffdaf599c0a467c19b5581a3c75d814b62ef41628a75866c6c80fe1'),
        ('profile','profiles','account_deletion_profile_fence','guard_deleting_profile',23,true,'f63025d5837594652f11251b1f15a23b591563640520a47ebfc617ef6914f1eb')
    ) x(schema_name,table_name,trigger_name,function_name,trigger_type,is_definer,body_sha256) LOOP
        SELECT t.*,p.prosrc,p.prosecdef,p.proconfig,p.pronargs,p.prorettype,p.proowner,p.proacl,l.lanname,c.relowner
            INTO STRICT guard FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid
            JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace JOIN pg_catalog.pg_proc p ON p.oid=t.tgfoid
            JOIN pg_catalog.pg_namespace pn ON pn.oid=p.pronamespace JOIN pg_catalog.pg_language l ON l.oid=p.prolang
            WHERE n.nspname=wanted.schema_name AND c.relname=wanted.table_name AND t.tgname=wanted.trigger_name
                AND pn.nspname='identity' AND p.proname=wanted.function_name;
        IF guard.tgtype<>wanted.trigger_type OR guard.tgenabled NOT IN ('O','A') OR guard.tgisinternal
            OR guard.tgqual IS NOT NULL OR guard.tgattr::text<>'' OR guard.tgnargs<>0 OR guard.tgconstraint<>0
            OR guard.proowner<>guard.relowner OR guard.proowner<>accepted.proowner OR guard.prosecdef<>wanted.is_definer
            OR guard.pronargs<>0 OR guard.prorettype<>'pg_catalog.trigger'::pg_catalog.regtype OR guard.lanname<>'plpgsql'
            OR guard.proconfig IS DISTINCT FROM (CASE WHEN wanted.is_definer
                THEN ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[] ELSE ARRAY['search_path=pg_catalog, pg_temp']::text[] END)
            OR pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(guard.prosrc,'UTF8')),'hex')<>wanted.body_sha256
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(guard.proacl,pg_catalog.acldefault('f',guard.proowner))) a
                WHERE a.grantee<>guard.proowner) THEN
            RAISE EXCEPTION 'Account-deletion acceptance guard differs from reviewed source';
        END IF;
    END LOOP;
    -- Serving cannot read or mutate work ledgers, even through column/PUBLIC grants.
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
        WHERE (n.nspname='erasure' OR (n.nspname='identity' AND c.relname IN ('account_erasure_work','account_erasure_scope')))
        AND c.relkind IN ('r','p','v','m','f') AND (
            pg_catalog.has_table_privilege(api.oid,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
            OR pg_catalog.has_any_column_privilege(api.oid,c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
        RAISE EXCEPTION 'Account-deletion serving role has worker ledger authority';
    END IF;
    -- Exactly the seven reviewed Auth projectors plus acceptance may be callable definer
    -- routines. The private erasure schema is denied even for an invoker-function drift.
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND (p.prosecdef OR n.nspname='erasure'
            OR (n.nspname='identity' AND (p.proname LIKE '%erasure%' OR p.proname='purge_account_core')))
            AND pg_catalog.has_function_privilege(api.oid,p.oid,'EXECUTE') AND p.oid<>acceptance
            AND p.oid<>ALL(ARRAY[
                'feedme_auth_access.schema_lock()'::pg_catalog.regprocedure,
                'feedme_auth_access.migration_versions()'::pg_catalog.regprocedure,
                'feedme_auth_access.user_facts(uuid)'::pg_catalog.regprocedure,
                'feedme_auth_access.factor_facts(uuid)'::pg_catalog.regprocedure,
                'feedme_auth_access.session_facts(uuid)'::pg_catalog.regprocedure,
                'feedme_auth_access.amr_facts(uuid)'::pg_catalog.regprocedure,
                'feedme_auth_access.password_facts(uuid,uuid)'::pg_catalog.regprocedure]::oid[])) THEN
        RAISE EXCEPTION 'Account-deletion serving role has unexpected definer authority';
    END IF;
END;
$feedme_deletion_serving$;

-- FEEDME_DELETION_SERVING_EXPLICIT_GRANTS_BEGIN
DO $feedme_installer$
BEGIN
    IF current_user='feedme_api' THEN RAISE EXCEPTION 'Deletion serving grants require the separate installer'; END IF;
END;
$feedme_installer$;
GRANT SELECT ON identity.account_deletion_jobs,identity.account_deletion_devices TO feedme_api;
GRANT EXECUTE ON FUNCTION identity.accept_account_deletion(text,uuid,uuid,text,uuid,uuid,uuid,uuid,text,uuid,text,text,uuid,bigint,timestamptz,timestamptz,timestamptz,timestamptz) TO feedme_api;
