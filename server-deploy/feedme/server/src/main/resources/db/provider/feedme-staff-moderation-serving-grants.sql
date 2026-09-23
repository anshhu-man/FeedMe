-- OPTIONAL staff-moderation serving privileges. Apply explicitly, inside an
-- installer-owned transaction, after the canonical account runtime grants and
-- migrations V001--V091. This file creates no role, password, staff actor,
-- moderator enrollment, policy, incident, feature flag or public route.
--
-- The application role remains unable to edit workforce or moderator authority.
-- It receives the exact provider/MFA observations needed for staff admission,
-- read/write access to the reviewed moderation workflow, read-only source content
-- needed for a removal decision, and three aggregate health columns. Keep auth,
-- staff and safety outside the client Data API.

SELECT pg_catalog.pg_advisory_xact_lock(x'464545444d450091'::bit(64)::bigint);

LOCK TABLE ONLY staff.publication_policies, ONLY staff.actors,
    ONLY staff.moderator_enrollments, ONLY auth.sessions, ONLY auth.mfa_factors,
    ONLY safety.reports, ONLY safety.report_evidence, ONLY safety.moderation_cases,
    ONLY safety.moderation_actions, ONLY safety.moderation_access_audit,
    ONLY safety.moderation_removals, ONLY social.posts, ONLY social.thread_messages,
    ONLY platform.media_processing_jobs, ONLY platform.feature_flags,
    ONLY platform.feature_flag_actions IN ACCESS SHARE MODE;

DO $feedme_staff_serving$
DECLARE api pg_catalog.pg_roles%ROWTYPE; wanted record; relation record;
BEGIN
    SELECT * INTO STRICT api FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    IF api.rolsuper OR api.rolcreatedb OR api.rolcreaterole OR api.rolreplication
        OR api.rolinherit OR NOT api.rolbypassrls OR current_user='feedme_api'
        OR pg_catalog.current_setting('session_replication_role')<>'origin'
        OR pg_catalog.current_setting('transaction_isolation')<>'read committed'
        OR pg_catalog.has_parameter_privilege(api.oid,'session_replication_role','SET')
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_class WHERE relowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc WHERE proowner=api.oid)
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_namespace
            WHERE nspname !~ '^pg_temp_' AND nspname !~ '^pg_toast_temp_'
                AND pg_catalog.has_schema_privilege(api.oid,oid,'CREATE')) THEN
        RAISE EXCEPTION 'Unsafe staff-moderation serving role';
    END IF;
    FOR wanted IN SELECT * FROM (VALUES
        (10,'post_publication','eade81f849fa47bc5bc3e70034b24cf609637a8e9abbccbd118e2ff9d6641b74'),
        (38,'account_reports','2ecb4816298e7d5784e004bfd4c9de51a74216530eb8f1552bd279830919a1bd'),
        (40,'staff_publication_approvals','bda1e378cf5b444845e1fa9de10df1b14c129f84771859197119e9c03e813f7a'),
        (52,'account_direct_conversations','1cdd657447707fadd38b235124c123aa63faecc6b8d788db25e1520abc2f01aa'),
        (77,'staff_moderator_enrollments','7370ae2a9ad96dc639512d5c858a880661689ac11ac2f21fdb795b83f6121329'),
        (78,'staff_moderation_workflow','103771dbf7e1bdcedf3753d3960e5f87bf2639503b96d662b6b969a89fd2f405'),
        (80,'staff_moderation_removal','3ab00b252bec17c04f2bb13e3ff470c51edc3bc54fb13b279078ae78dc5c2fa6'),
        (90,'staff_operational_health_reader','13b093289a20928b91bbf439714a788525ea179bbe3ec52a87e5ce47e8d20b0d'),
        (91,'restrictive_staff_feature_flags','5d80a16f31b52bb031731f9b895181e6309ba2936b382cf12d7eb85bf4f1f3d8')
    ) x(version,description,checksum) LOOP
        IF NOT EXISTS (SELECT 1 FROM platform.schema_migrations m
            WHERE m.version=wanted.version AND m.description=wanted.description
                AND m.checksum=wanted.checksum) THEN
            RAISE EXCEPTION 'Required staff-moderation migration differs: %',wanted.version;
        END IF;
    END LOOP;
    FOR wanted IN SELECT * FROM (VALUES
        ('staff','publication_policies'),('staff','actors'),('staff','moderator_enrollments'),
        ('safety','reports'),('safety','report_evidence'),('safety','moderation_cases'),
        ('safety','moderation_actions'),('safety','moderation_access_audit'),('safety','moderation_removals'),
        ('platform','feature_flags'),('platform','feature_flag_actions')
    ) x(schema_name,table_name) LOOP
        SELECT c.* INTO STRICT relation FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname=wanted.schema_name AND c.relname=wanted.table_name;
        IF relation.relkind<>'r' OR NOT relation.relrowsecurity OR NOT relation.relforcerowsecurity
            OR relation.relowner=api.oid
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i
                WHERE i.inhrelid=relation.oid OR i.inhparent=relation.oid) THEN
            RAISE EXCEPTION 'Staff-moderation relation boundary differs: %.%',wanted.schema_name,wanted.table_name;
        END IF;
    END LOOP;
    IF pg_catalog.to_regprocedure('staff.lock_moderation_actor(text,text,text)') IS NULL
        OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc p
            WHERE p.oid='staff.lock_moderation_actor(text,text,text)'::pg_catalog.regprocedure
                AND (NOT p.prosecdef OR p.prokind<>'f' OR p.proretset OR p.pronargs<>3
                    OR p.prorettype<>'pg_catalog.bool'::pg_catalog.regtype
                    OR p.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, pg_temp','row_security=off']::text[])) THEN
        RAISE EXCEPTION 'Moderator locking helper differs from reviewed boundary';
    END IF;
    IF pg_catalog.has_schema_privilege(api.oid,'auth','CREATE')
        OR pg_catalog.has_schema_privilege(api.oid,'staff','CREATE')
        OR pg_catalog.has_schema_privilege(api.oid,'safety','CREATE')
        OR pg_catalog.has_schema_privilege(api.oid,'social','CREATE')
        OR pg_catalog.has_table_privilege(api.oid,'staff.publication_policies','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api.oid,'staff.actors','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api.oid,'staff.moderator_enrollments','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_any_column_privilege(api.oid,'staff.publication_policies','INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_any_column_privilege(api.oid,'staff.actors','INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_any_column_privilege(api.oid,'staff.moderator_enrollments','INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_any_column_privilege(api.oid,'auth.sessions','INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_any_column_privilege(api.oid,'auth.mfa_factors','INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_table_privilege(api.oid,'auth.sessions','DELETE,TRUNCATE,TRIGGER')
        OR pg_catalog.has_table_privilege(api.oid,'auth.mfa_factors','DELETE,TRUNCATE,TRIGGER') THEN
        RAISE EXCEPTION 'Staff-moderation role already has authority-changing privileges';
    END IF;
    IF pg_catalog.has_table_privilege(api.oid,'platform.feature_flags','INSERT,DELETE,TRUNCATE,TRIGGER')
        OR pg_catalog.has_table_privilege(api.oid,'platform.feature_flags','UPDATE WITH GRANT OPTION')
        OR pg_catalog.has_table_privilege(api.oid,'platform.feature_flag_actions','UPDATE,DELETE,TRUNCATE,TRIGGER')
        OR pg_catalog.has_table_privilege(api.oid,'platform.feature_flag_actions','INSERT WITH GRANT OPTION') THEN
        RAISE EXCEPTION 'Staff feature-flag role already has prohibited privileges';
    END IF;
END;
$feedme_staff_serving$;

-- FEEDME_STAFF_MODERATION_SERVING_EXPLICIT_GRANTS_BEGIN
GRANT USAGE ON SCHEMA auth,staff,safety,social TO feedme_api;
GRANT SELECT(description) ON platform.schema_migrations TO feedme_api;
-- Runtime compatibility pins the complete shapes of these three narrow registry
-- tables, so whole-row reads cannot silently expose a future unreviewed column.
GRANT SELECT ON staff.publication_policies,staff.actors,staff.moderator_enrollments TO feedme_api;
GRANT SELECT(id,user_id,factor_id) ON auth.sessions TO feedme_api;
GRANT SELECT(id,user_id,status,factor_type) ON auth.mfa_factors TO feedme_api;
GRANT EXECUTE ON FUNCTION staff.lock_moderation_actor(text,text,text) TO feedme_api;

GRANT SELECT ON safety.reports,safety.report_evidence,safety.moderation_cases,
    safety.moderation_actions,safety.moderation_access_audit,safety.moderation_removals,
    social.posts,social.thread_messages TO feedme_api;
GRANT UPDATE(version,status,updated_at) ON safety.reports TO feedme_api;
GRANT UPDATE(version,status,assignee_staff_id,reason_code,updated_at,action)
    ON safety.moderation_cases TO feedme_api;
GRANT INSERT(environment,id,case_id,report_id,case_version,report_version,actor_id,
    provider_session_id,authority_revision,action,reason_code,notes,operation_id,
    command_key,request_sha256,request_text,if_match,response_text,response_sha256,
    event_id,trace_id,created_at,target_version) ON safety.moderation_actions TO feedme_api;
GRANT INSERT(environment,id,actor_id,provider_session_id,authority_revision,purpose,
    observed_cases,trace_id,created_at) ON safety.moderation_access_audit TO feedme_api;
GRANT INSERT(environment,id,case_id,report_id,action_id,target_type,target_id,
    target_owner_id,target_version,target_sha256,state,created_at)
    ON safety.moderation_removals TO feedme_api;
-- PostgreSQL row locks require a matching UPDATE capability. Each column below is
-- an immutable identity guarded by the pinned migration/trigger source; the grant
-- exists only so SELECT ... FOR SHARE can lock the exact original row.
GRANT UPDATE(report_id) ON safety.report_evidence TO feedme_api;
GRANT UPDATE(id) ON safety.moderation_actions,safety.moderation_removals,
    social.posts,social.thread_messages TO feedme_api;
GRANT SELECT(environment,state,created_at) ON platform.media_processing_jobs TO feedme_api;
GRANT SELECT ON platform.feature_flags,platform.feature_flag_actions TO feedme_api;
GRANT UPDATE(enabled,rollout_percent,revision,last_action_id,updated_at)
    ON platform.feature_flags TO feedme_api;
GRANT INSERT(environment,id,flag_key,flag_revision,actor_id,provider_session_id,
    authority_revision,operation_id,command_key,request_sha256,request_text,if_match,
    reason,response_text,response_sha256,event_id,trace_id,created_at)
    ON platform.feature_flag_actions TO feedme_api;
GRANT UPDATE(id) ON platform.feature_flag_actions TO feedme_api;

DO $feedme_staff_serving_verify$
DECLARE api oid; wanted record;
BEGIN
    SELECT oid INTO STRICT api FROM pg_catalog.pg_roles WHERE rolname='feedme_api';
    IF NOT pg_catalog.has_schema_privilege(api,'auth','USAGE')
        OR NOT pg_catalog.has_schema_privilege(api,'staff','USAGE')
        OR NOT pg_catalog.has_schema_privilege(api,'safety','USAGE')
        OR NOT pg_catalog.has_schema_privilege(api,'social','USAGE')
        OR NOT pg_catalog.has_function_privilege(api,'staff.lock_moderation_actor(text,text,text)','EXECUTE')
        OR pg_catalog.has_function_privilege(api,'staff.lock_moderation_actor(text,text,text)','EXECUTE WITH GRANT OPTION') THEN
        RAISE EXCEPTION 'Staff-moderation serving privileges were not installed exactly';
    END IF;
    FOR wanted IN SELECT * FROM (VALUES
        ('staff.publication_policies','SELECT'),('staff.actors','SELECT'),('staff.moderator_enrollments','SELECT'),
        ('safety.reports','SELECT'),('safety.report_evidence','SELECT'),('safety.moderation_cases','SELECT'),
        ('safety.moderation_actions','SELECT'),('safety.moderation_access_audit','SELECT'),
        ('safety.moderation_removals','SELECT'),('social.posts','SELECT'),('social.thread_messages','SELECT'),
        ('platform.feature_flags','SELECT'),('platform.feature_flag_actions','SELECT')
    ) x(relation_name,privilege_name) LOOP
        IF NOT pg_catalog.has_table_privilege(api,wanted.relation_name,wanted.privilege_name)
            OR pg_catalog.has_table_privilege(api,wanted.relation_name,wanted.privilege_name||' WITH GRANT OPTION') THEN
            RAISE EXCEPTION 'Missing or grantable staff-moderation read: %',wanted.relation_name;
        END IF;
    END LOOP;
    FOR wanted IN SELECT * FROM (VALUES
        ('platform.schema_migrations','SELECT',ARRAY['description']::text[]),
        ('auth.sessions','SELECT',ARRAY['id','user_id','factor_id']::text[]),
        ('auth.mfa_factors','SELECT',ARRAY['id','user_id','status','factor_type']::text[]),
        ('platform.media_processing_jobs','SELECT',ARRAY['environment','state','created_at']::text[]),
        ('safety.reports','UPDATE',ARRAY['version','status','updated_at']::text[]),
        ('safety.moderation_cases','UPDATE',ARRAY['version','status','assignee_staff_id','reason_code','updated_at','action']::text[]),
        ('safety.moderation_actions','INSERT',ARRAY['environment','id','case_id','report_id','case_version','report_version','actor_id',
            'provider_session_id','authority_revision','action','reason_code','notes','operation_id','command_key','request_sha256',
            'request_text','if_match','response_text','response_sha256','event_id','trace_id','created_at','target_version']::text[]),
        ('safety.moderation_access_audit','INSERT',ARRAY['environment','id','actor_id','provider_session_id','authority_revision',
            'purpose','observed_cases','trace_id','created_at']::text[]),
        ('safety.moderation_removals','INSERT',ARRAY['environment','id','case_id','report_id','action_id','target_type','target_id',
            'target_owner_id','target_version','target_sha256','state','created_at']::text[]),
        ('safety.report_evidence','UPDATE',ARRAY['report_id']::text[]),
        ('safety.moderation_actions','UPDATE',ARRAY['id']::text[]),
        ('safety.moderation_removals','UPDATE',ARRAY['id']::text[]),
        ('social.posts','UPDATE',ARRAY['id']::text[]),
        ('social.thread_messages','UPDATE',ARRAY['id']::text[]),
        ('platform.feature_flags','UPDATE',ARRAY['enabled','rollout_percent','revision','last_action_id','updated_at']::text[]),
        ('platform.feature_flag_actions','INSERT',ARRAY['environment','id','flag_key','flag_revision','actor_id',
            'provider_session_id','authority_revision','operation_id','command_key','request_sha256','request_text','if_match',
            'reason','response_text','response_sha256','event_id','trace_id','created_at']::text[]),
        ('platform.feature_flag_actions','UPDATE',ARRAY['id']::text[])
    ) x(relation_name,privilege_name,columns) LOOP
        IF EXISTS (SELECT 1 FROM unnest(wanted.columns) column_name
            WHERE NOT pg_catalog.has_column_privilege(api,wanted.relation_name,column_name,wanted.privilege_name)
                OR pg_catalog.has_column_privilege(api,wanted.relation_name,column_name,wanted.privilege_name||' WITH GRANT OPTION')) THEN
            RAISE EXCEPTION 'Missing or grantable staff-moderation column privilege: % %',wanted.relation_name,wanted.privilege_name;
        END IF;
    END LOOP;
    IF pg_catalog.has_table_privilege(api,'staff.publication_policies','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'staff.actors','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'staff.moderator_enrollments','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR pg_catalog.has_any_column_privilege(api,'staff.publication_policies','INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_any_column_privilege(api,'staff.actors','INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_any_column_privilege(api,'staff.moderator_enrollments','INSERT,UPDATE,REFERENCES')
        OR pg_catalog.has_table_privilege(api,'safety.moderation_actions','DELETE,TRUNCATE,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'safety.moderation_access_audit','UPDATE,DELETE,TRUNCATE,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'safety.moderation_removals','UPDATE,DELETE,TRUNCATE,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'platform.feature_flags','INSERT,DELETE,TRUNCATE,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'platform.feature_flags','UPDATE WITH GRANT OPTION')
        OR pg_catalog.has_table_privilege(api,'platform.feature_flag_actions','UPDATE,DELETE,TRUNCATE,TRIGGER')
        OR pg_catalog.has_table_privilege(api,'platform.feature_flag_actions','INSERT WITH GRANT OPTION') THEN
        RAISE EXCEPTION 'Staff-moderation serving role has prohibited authority';
    END IF;
END;
$feedme_staff_serving_verify$;
