-- Packaged EXECUTE-only delta for explicit fixed-project rollout after V092.
-- It uses the already-selected feedme_api role after account-deletion acceptance
-- and staff-moderation admission are installed; it grants no direct table access.

SELECT pg_catalog.pg_advisory_xact_lock(x'464545444d450092'::bit(64)::bigint);

LOCK TABLE ONLY safety.account_deletion_access_audit,
    ONLY staff.publication_policies, ONLY staff.actors, ONLY staff.moderator_enrollments,
    ONLY auth.sessions, ONLY auth.mfa_factors,
    ONLY identity.account_deletion_jobs, ONLY identity.account_erasure_work,
    ONLY erasure.provider_deletions IN ACCESS SHARE MODE;

DO $feedme_deletion_operations_grant_review$
DECLARE
    api pg_catalog.pg_roles%ROWTYPE;
    observed record;
    ledger record;
    guard record;
    observe_oid oid := pg_catalog.to_regprocedure(
        'safety.observe_account_deletion_receipt(text,uuid,uuid,uuid,uuid,text,uuid)');
BEGIN
    SELECT * INTO STRICT api FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    IF api.rolsuper OR api.rolcreatedb OR api.rolcreaterole OR api.rolreplication
        OR api.rolinherit OR NOT api.rolbypassrls OR current_user='feedme_api'
        OR pg_catalog.current_setting('session_replication_role')<>'origin'
        OR pg_catalog.current_setting('transaction_isolation')<>'read committed'
        OR pg_catalog.current_setting('transaction_read_only')<>'off'
        OR pg_catalog.has_parameter_privilege(api.oid,'session_replication_role','SET')
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_class WHERE relowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc WHERE proowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace
            WHERE nspname !~ '^pg_temp_' AND nspname !~ '^pg_toast_temp_'
                AND pg_catalog.has_schema_privilege(api.oid,oid,'CREATE')) THEN
        RAISE EXCEPTION 'Unsafe account-deletion operations serving role';
    END IF;

    -- V092 adds no replacement for current account acceptance or moderator
    -- admission. Refuse the delta unless both reviewed foundations already exist.
    IF NOT pg_catalog.has_schema_privilege(api.oid,'staff','USAGE')
        OR NOT pg_catalog.has_schema_privilege(api.oid,'safety','USAGE')
        OR pg_catalog.has_schema_privilege(api.oid,'auth','USAGE,CREATE')
        OR NOT pg_catalog.has_table_privilege(api.oid,'staff.publication_policies','SELECT')
        OR NOT pg_catalog.has_table_privilege(api.oid,'staff.actors','SELECT')
        OR NOT pg_catalog.has_table_privilege(api.oid,'staff.moderator_enrollments','SELECT')
        OR pg_catalog.has_table_privilege(api.oid,'auth.sessions','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api.oid,'auth.mfa_factors','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_any_column_privilege(api.oid,'auth.sessions','SELECT,INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_any_column_privilege(api.oid,'auth.mfa_factors','SELECT,INSERT,UPDATE,REFERENCES')
        OR NOT pg_catalog.has_function_privilege(api.oid,
            'feedme_auth_access.staff_totp_facts(uuid,uuid)','EXECUTE')
        OR NOT pg_catalog.has_function_privilege(api.oid,
            'staff.lock_moderation_actor(text,text,text)','EXECUTE')
        OR NOT pg_catalog.has_function_privilege(api.oid,
            'feedme_auth_access.session_facts(uuid)','EXECUTE')
        OR NOT pg_catalog.has_function_privilege(api.oid,
            'feedme_auth_access.amr_facts(uuid)','EXECUTE')
        OR NOT pg_catalog.has_function_privilege(api.oid,
            'identity.accept_account_deletion(text,uuid,uuid,text,uuid,uuid,uuid,uuid,text,uuid,text,text,uuid,bigint,timestamptz,timestamptz,timestamptz,timestamptz)',
            'EXECUTE') THEN
        RAISE EXCEPTION 'Account-deletion operations prerequisites are unavailable';
    END IF;

    SELECT p.*,l.lanname INTO STRICT observed
    FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang
    WHERE p.oid=observe_oid;
    IF NOT observed.prosecdef OR observed.prokind<>'f' OR NOT observed.proretset
        OR observed.pronargs<>7 OR observed.prorettype<>'pg_catalog.record'::pg_catalog.regtype
        OR observed.lanname<>'plpgsql' OR observed.provolatile<>'v'
        OR observed.proparallel<>'u' OR observed.proisstrict OR observed.proleakproof
        OR observed.proconfig IS DISTINCT FROM
            ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
        OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles owner
            WHERE owner.oid=observed.proowner AND (owner.rolsuper OR owner.rolbypassrls))
        OR pg_catalog.encode(pg_catalog.sha256(
            pg_catalog.convert_to(observed.prosrc,'UTF8')),'hex')<>
            'b8cca9b52f36362118dde45b916af0c6ad409aea3379a87f901c3aa07908b745'
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc p
            WHERE p.pronamespace=observed.pronamespace AND p.proname=observed.proname
                AND p.oid<>observe_oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(observed.proacl,
            pg_catalog.acldefault('f',observed.proowner))) a
            WHERE a.grantee<>observed.proowner
              AND (a.grantee<>api.oid OR a.privilege_type<>'EXECUTE' OR a.is_grantable)) THEN
        RAISE EXCEPTION 'Account-deletion observe-and-audit authority differs';
    END IF;

    SELECT c.* INTO STRICT ledger FROM pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='safety' AND c.relname='account_deletion_access_audit';
    IF ledger.relkind<>'r' OR NOT ledger.relrowsecurity OR NOT ledger.relforcerowsecurity
        OR ledger.relowner<>observed.proowner
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits
            WHERE inhrelid=ledger.oid OR inhparent=ledger.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy WHERE polrelid=ledger.oid)
        OR (SELECT pg_catalog.array_agg(a.attname::text ORDER BY a.attnum)
            FROM pg_catalog.pg_attribute a WHERE a.attrelid=ledger.oid
                AND a.attnum>0 AND NOT a.attisdropped) IS DISTINCT FROM ARRAY[
            'environment','id','actor_id','authority_revision','receipt_id',
            'purpose','trace_id','created_at']::text[]
        OR (SELECT count(*) FROM pg_catalog.pg_constraint c
            WHERE c.conrelid=ledger.oid AND c.contype='p' AND c.conkey=ARRAY[1,2]::smallint[])<>1
        OR (SELECT count(*) FROM pg_catalog.pg_constraint c
            WHERE c.conrelid=ledger.oid AND c.contype='u' AND c.conkey=ARRAY[1,7]::smallint[])<>1
        OR (SELECT count(*) FROM pg_catalog.pg_constraint c
            WHERE c.conrelid=ledger.oid AND c.contype='f' AND c.conkey=ARRAY[1,3]::smallint[]
              AND c.confrelid='staff.moderator_enrollments'::pg_catalog.regclass
              AND c.confkey=ARRAY[1,2]::smallint[] AND NOT c.condeferrable AND c.convalidated)<>1
        OR (SELECT count(*) FROM pg_catalog.pg_constraint c
            WHERE c.conrelid=ledger.oid AND c.contype='c' AND c.convalidated)<>4
        OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(ledger.relacl,
            pg_catalog.acldefault('r',ledger.relowner))) a WHERE a.grantee<>ledger.relowner) THEN
        RAISE EXCEPTION 'Account-deletion access ledger differs';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
        WHERE col.attrelid=ledger.oid AND col.attnum>0 AND NOT col.attisdropped
          AND col.attacl IS NOT NULL) THEN
        RAISE EXCEPTION 'Account-deletion access ledger column privileges differ';
    END IF;

    FOR guard IN SELECT t.*,p.prosrc,p.prosecdef,p.proconfig,p.pronargs,
        p.prorettype,p.proowner,p.proacl,l.lanname
      FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_proc p ON p.oid=t.tgfoid
      JOIN pg_catalog.pg_language l ON l.oid=p.prolang
      WHERE t.tgrelid=ledger.oid AND NOT t.tgisinternal
      ORDER BY t.tgname LOOP
        IF guard.tgname NOT IN ('account_deletion_access_audit_immutable',
                'account_deletion_access_audit_retained')
            OR guard.tgtype<>(CASE guard.tgname
                WHEN 'account_deletion_access_audit_immutable' THEN 27 ELSE 34 END)
            OR guard.tgenabled NOT IN ('O','A') OR guard.tgqual IS NOT NULL
            OR guard.tgattr::text<>'' OR guard.tgnargs<>0 OR guard.tgconstraint<>0
            OR guard.proowner<>ledger.relowner OR guard.prosecdef OR guard.pronargs<>0
            OR guard.prorettype<>'pg_catalog.trigger'::pg_catalog.regtype
            OR guard.lanname<>'plpgsql'
            OR guard.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(
                pg_catalog.convert_to(guard.prosrc,'UTF8')),'hex')<>
                'ffc0ecb766f56cac4cbf64c5b01f8a08b6a82ea9fe68811b1dfd334dd5df0aab'
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(guard.proacl,
                pg_catalog.acldefault('f',guard.proowner))) a
                WHERE a.grantee<>guard.proowner) THEN
            RAISE EXCEPTION 'Account-deletion access ledger guard differs';
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM pg_catalog.pg_trigger t
        WHERE t.tgrelid=ledger.oid AND NOT t.tgisinternal)<>2 THEN
        RAISE EXCEPTION 'Account-deletion access ledger trigger set differs';
    END IF;

    -- V092 must add no direct application-role access to the worker or audit
    -- ledgers. Existing acceptance-job reads remain governed by their older grant.
    IF pg_catalog.has_table_privilege(api.oid,ledger.oid,
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_any_column_privilege(api.oid,ledger.oid,
            'SELECT,INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_table_privilege(api.oid,'identity.account_erasure_work',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_any_column_privilege(api.oid,'identity.account_erasure_work',
            'SELECT,INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_table_privilege(api.oid,'erasure.provider_deletions',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_any_column_privilege(api.oid,'erasure.provider_deletions',
            'SELECT,INSERT,UPDATE,REFERENCES') THEN
        RAISE EXCEPTION 'Account-deletion operations role has direct ledger authority';
    END IF;
END;
$feedme_deletion_operations_grant_review$;

-- FEEDME_DELETION_OPERATIONS_EXPLICIT_GRANT_BEGIN
DO $feedme_deletion_operations_installer$
BEGIN
    IF current_user='feedme_api' THEN
        RAISE EXCEPTION 'Account-deletion operations grant requires the separate installer';
    END IF;
END;
$feedme_deletion_operations_installer$;
GRANT EXECUTE ON FUNCTION
    safety.observe_account_deletion_receipt(text,uuid,uuid,uuid,uuid,text,uuid)
    TO feedme_api;

DO $feedme_deletion_operations_grant_verify$
DECLARE api oid;
BEGIN
    SELECT oid INTO STRICT api FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    IF NOT pg_catalog.has_function_privilege(api,
            'safety.observe_account_deletion_receipt(text,uuid,uuid,uuid,uuid,text,uuid)','EXECUTE')
        OR pg_catalog.has_function_privilege(api,
            'safety.observe_account_deletion_receipt(text,uuid,uuid,uuid,uuid,text,uuid)',
            'EXECUTE WITH GRANT OPTION')
        OR pg_catalog.has_table_privilege(api,'safety.account_deletion_access_audit',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_any_column_privilege(api,'safety.account_deletion_access_audit',
            'SELECT,INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_table_privilege(api,'identity.account_erasure_work',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'erasure.provider_deletions',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') THEN
        RAISE EXCEPTION 'Account-deletion operations privilege was not installed exactly';
    END IF;
END;
$feedme_deletion_operations_grant_verify$;
