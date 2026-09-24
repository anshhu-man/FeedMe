-- Packaged EXECUTE-only delta for explicit fixed-project rollout after the
-- provider observer and V093. It creates no role and grants no table access.

SELECT pg_catalog.pg_advisory_xact_lock(x'464545444d450093'::bit(64)::bigint);

LOCK TABLE ONLY safety.account_deletion_support_challenges,
    ONLY safety.account_deletion_support_claims,
    ONLY staff.publication_policies, ONLY staff.actors,
    ONLY staff.moderator_enrollments, ONLY auth.sessions,
    ONLY auth.mfa_factors, ONLY auth.identities IN ACCESS SHARE MODE;

DO $feedme_support_grant_review$
DECLARE
    api pg_catalog.pg_roles%ROWTYPE;
    observed record;
    relation record;
    function_oids oid[] := ARRAY[
        'feedme_auth_access.google_identity_facts(uuid)'::pg_catalog.regprocedure,
        'safety.issue_account_deletion_support_challenge(text,uuid,text,text,uuid,uuid,text)'::pg_catalog.regprocedure,
        'safety.claim_account_deletion_support_ownership(text,uuid,uuid,text,text,text,uuid,uuid)'::pg_catalog.regprocedure,
        'safety.redact_expired_account_deletion_support_ownership(text,integer)'::pg_catalog.regprocedure
    ]::oid[];
    function_hashes text[] := ARRAY[
        'b60ed1bb417553f16cdbdf67086a4ed1e4a11ceab656b114f88bd30e30002cd3',
        '559e36a733276ba514d0c245fb88aa3f6141a2d7974813b1d27075258424c13c',
        'f19913fcd781bc34eaa215360cdccc74bf5b9d8234906e67166812178b8e3cf4',
        '4d8cd0930865b9964681f38546f766430166f63b790404ab075ae47f7fd703ab'
    ];
    function_arguments integer[] := ARRAY[1,7,8,2];
    function_strict boolean[] := ARRAY[true,false,false,false];
    expected_owner oid;
    item integer;
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
        RAISE EXCEPTION 'Unsafe account-deletion support serving role';
    END IF;

    IF NOT pg_catalog.has_schema_privilege(api.oid,'safety','USAGE')
        OR NOT pg_catalog.has_schema_privilege(api.oid,'staff','USAGE')
        OR pg_catalog.has_schema_privilege(api.oid,'auth','USAGE,CREATE')
        OR NOT pg_catalog.has_schema_privilege(api.oid,'feedme_auth_access','USAGE')
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
            'feedme_auth_access.session_facts(uuid)','EXECUTE')
        OR NOT pg_catalog.has_function_privilege(api.oid,
            'feedme_auth_access.amr_facts(uuid)','EXECUTE') THEN
        RAISE EXCEPTION 'Account-deletion support prerequisites are unavailable';
    END IF;

    FOR item IN 1..pg_catalog.array_length(function_oids,1) LOOP
        SELECT p.*,l.lanname,n.nspname INTO STRICT observed
        FROM pg_catalog.pg_proc p
        JOIN pg_catalog.pg_language l ON l.oid=p.prolang
        JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
        WHERE p.oid=function_oids[item];
        IF NOT observed.prosecdef OR observed.prokind<>'f' OR NOT observed.proretset
            OR observed.pronargs<>function_arguments[item]
            OR observed.prorettype<>'pg_catalog.record'::pg_catalog.regtype
            OR observed.lanname<>'plpgsql' OR observed.provolatile<>'v'
            OR observed.proparallel<>'u' OR observed.proisstrict<>function_strict[item]
            OR observed.proleakproof OR observed.proconfig IS DISTINCT FROM
                ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[]
            OR pg_catalog.encode(pg_catalog.sha256(
                pg_catalog.convert_to(observed.prosrc,'UTF8')),'hex')<>function_hashes[item]
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc p
                WHERE p.pronamespace=observed.pronamespace AND p.proname=observed.proname
                  AND p.oid<>observed.oid)
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(observed.proacl,
                pg_catalog.acldefault('f',observed.proowner))) a
                WHERE a.grantee<>observed.proowner) THEN
            RAISE EXCEPTION 'Account-deletion support function differs';
        END IF;
        IF expected_owner IS NULL THEN expected_owner:=observed.proowner;
        ELSIF observed.proowner<>expected_owner THEN
            RAISE EXCEPTION 'Account-deletion support function owner differs';
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles r WHERE r.oid=expected_owner
        AND (r.rolsuper OR r.rolbypassrls)) THEN
        RAISE EXCEPTION 'Account-deletion support owner is unavailable';
    END IF;

    FOR relation IN SELECT c.*,n.nspname FROM pg_catalog.pg_class c
      JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
      WHERE c.oid IN ('safety.account_deletion_support_challenges'::pg_catalog.regclass,
        'safety.account_deletion_support_claims'::pg_catalog.regclass)
    LOOP
        IF relation.relkind<>'r' OR NOT relation.relrowsecurity
            OR NOT relation.relforcerowsecurity OR relation.relowner<>expected_owner
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits
                WHERE inhrelid=relation.oid OR inhparent=relation.oid)
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_policy WHERE polrelid=relation.oid)
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(relation.relacl,
                pg_catalog.acldefault('r',relation.relowner))) a
                WHERE a.grantee<>relation.relowner)
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_attribute col
                WHERE col.attrelid=relation.oid AND col.attnum>0 AND NOT col.attisdropped
                  AND col.attacl IS NOT NULL) THEN
            RAISE EXCEPTION 'Account-deletion support relation differs';
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM pg_catalog.pg_class c WHERE c.oid IN
        ('safety.account_deletion_support_challenges'::pg_catalog.regclass,
         'safety.account_deletion_support_claims'::pg_catalog.regclass))<>2
        OR pg_catalog.has_table_privilege(api.oid,
            'safety.account_deletion_support_challenges',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api.oid,
            'safety.account_deletion_support_claims',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_any_column_privilege(api.oid,
            'safety.account_deletion_support_challenges','SELECT,INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_any_column_privilege(api.oid,
            'safety.account_deletion_support_claims','SELECT,INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_table_privilege(api.oid,'auth.identities',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') THEN
        RAISE EXCEPTION 'Account-deletion support role has direct data authority';
    END IF;
END;
$feedme_support_grant_review$;

-- FEEDME_DELETION_SUPPORT_EXPLICIT_GRANT_BEGIN
DO $feedme_support_installer$
BEGIN
    IF current_user='feedme_api' THEN
        RAISE EXCEPTION 'Account-deletion support grant requires the separate installer';
    END IF;
END;
$feedme_support_installer$;
GRANT EXECUTE ON FUNCTION
    feedme_auth_access.google_identity_facts(uuid),
    safety.issue_account_deletion_support_challenge(text,uuid,text,text,uuid,uuid,text),
    safety.claim_account_deletion_support_ownership(text,uuid,uuid,text,text,text,uuid,uuid),
    safety.redact_expired_account_deletion_support_ownership(text,integer)
    TO feedme_api;

DO $feedme_support_grant_verify$
DECLARE
    api oid;
    candidate oid;
BEGIN
    SELECT oid INTO STRICT api FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    FOREACH candidate IN ARRAY ARRAY[
        'feedme_auth_access.google_identity_facts(uuid)'::pg_catalog.regprocedure,
        'safety.issue_account_deletion_support_challenge(text,uuid,text,text,uuid,uuid,text)'::pg_catalog.regprocedure,
        'safety.claim_account_deletion_support_ownership(text,uuid,uuid,text,text,text,uuid,uuid)'::pg_catalog.regprocedure,
        'safety.redact_expired_account_deletion_support_ownership(text,integer)'::pg_catalog.regprocedure
    ]::oid[] LOOP
        IF NOT pg_catalog.has_function_privilege(api,candidate,'EXECUTE')
            OR pg_catalog.has_function_privilege(api,candidate,'EXECUTE WITH GRANT OPTION')
            OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(COALESCE(
                (SELECT p.proacl FROM pg_catalog.pg_proc p WHERE p.oid=candidate),
                pg_catalog.acldefault('f',(SELECT p.proowner FROM pg_catalog.pg_proc p
                    WHERE p.oid=candidate)))) a
                WHERE a.grantee NOT IN (api,(SELECT p.proowner FROM pg_catalog.pg_proc p
                    WHERE p.oid=candidate))) THEN
            RAISE EXCEPTION 'Account-deletion support privilege differs';
        END IF;
    END LOOP;
    IF pg_catalog.has_table_privilege(api,'safety.account_deletion_support_challenges',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'safety.account_deletion_support_claims',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'auth.identities',
            'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') THEN
        RAISE EXCEPTION 'Account-deletion support direct privilege differs';
    END IF;
END;
$feedme_support_grant_verify$;
