-- Registered additive platform migration. It adds no operator endpoint, runtime
-- grant or completion claim. Serving authority remains a separate reviewed grant.

CREATE TABLE safety.account_deletion_access_audit (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 id uuid NOT NULL,
 actor_id uuid NOT NULL,
 authority_revision char(64) NOT NULL CHECK(authority_revision ~ '^[0-9a-f]{64}$'),
 receipt_id uuid NOT NULL,
 purpose varchar(32) NOT NULL CHECK(purpose='receipt-observation'),
 trace_id uuid NOT NULL,
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 PRIMARY KEY(environment,id),
 UNIQUE(environment,trace_id),
 FOREIGN KEY(environment,actor_id) REFERENCES staff.moderator_enrollments(environment,actor_id)
);
ALTER TABLE safety.account_deletion_access_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.account_deletion_access_audit FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE safety.account_deletion_access_audit FROM PUBLIC;

CREATE FUNCTION safety.protect_account_deletion_access_audit() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $feedme_deletion_audit_guard$
BEGIN
 RAISE EXCEPTION 'Account deletion access audit is immutable' USING ERRCODE='23514';
END;
$feedme_deletion_audit_guard$;
REVOKE ALL ON FUNCTION safety.protect_account_deletion_access_audit() FROM PUBLIC;
CREATE TRIGGER account_deletion_access_audit_immutable
 BEFORE UPDATE OR DELETE ON safety.account_deletion_access_audit
 FOR EACH ROW EXECUTE FUNCTION safety.protect_account_deletion_access_audit();
CREATE TRIGGER account_deletion_access_audit_retained
 BEFORE TRUNCATE ON safety.account_deletion_access_audit
 FOR EACH STATEMENT EXECUTE FUNCTION safety.protect_account_deletion_access_audit();

-- One atomic staff-only observation primitive. It always appends the immutable audit,
-- including when the opaque receipt does not exist. Only already-redacted stage/reason
-- evidence leaves the function; account, principal, subject, email, device, provider
-- identity, timestamps and support content never do. The Kotlin projector still owns the
-- fixed operator state and currently receives durable_completion=false because no final
-- all-configured-stages checkpoint exists.
CREATE FUNCTION safety.observe_account_deletion_receipt(
 p_environment text,
 p_receipt uuid,
 p_audit_id uuid,
 p_actor_id uuid,
 p_provider_session_id uuid,
 p_authority_revision text,
 p_trace_id uuid
) RETURNS TABLE(
 accepted_stage text,
 work_stage text,
 last_reason text,
 provider_stage text,
 durable_completion boolean
)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_deletion_observe$
DECLARE
 policy_row staff.publication_policies%ROWTYPE;
 actor_row staff.actors%ROWTYPE;
 moderator_row staff.moderator_enrollments%ROWTYPE;
 authenticated_user_id uuid;
 authenticated_session_created_at timestamptz;
 authenticated_session_not_after timestamptz;
 authenticated_session_aal text;
 amr_count bigint;
 totp_authenticated_at timestamptz;
 current_factor_status text;
 current_factor_type text;
 observed_at timestamptz;
BEGIN
 IF current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed'
   OR current_setting('transaction_read_only')<>'off'
   OR p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_receipt IS NULL OR p_audit_id IS NULL OR p_actor_id IS NULL
   OR p_provider_session_id IS NULL OR p_authority_revision IS NULL
   OR p_authority_revision !~ '^[0-9a-f]{64}$'
   OR p_trace_id IS NULL THEN
  RAISE EXCEPTION 'Account deletion observation input unavailable' USING ERRCODE='23514';
 END IF;

 SELECT p.* INTO policy_row FROM ONLY staff.publication_policies p
 WHERE p.environment=p_environment FOR SHARE;
 SELECT a.* INTO actor_row FROM ONLY staff.actors a
 WHERE a.environment=p_environment AND a.actor_id=p_actor_id FOR SHARE;
 SELECT m.* INTO moderator_row FROM ONLY staff.moderator_enrollments m
 WHERE m.environment=p_environment AND m.actor_id=p_actor_id FOR SHARE;
 observed_at:=clock_timestamp();
 IF policy_row.environment IS NULL OR actor_row.actor_id IS NULL OR moderator_row.actor_id IS NULL
   OR NOT policy_row.enabled OR NOT actor_row.enabled OR NOT moderator_row.enabled
   OR policy_row.version<>moderator_row.policy_version
   OR actor_row.issuer<>policy_row.issuer
   OR actor_row.subject !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
   OR observed_at<policy_row.not_before OR observed_at>=policy_row.valid_until
   OR observed_at<actor_row.not_before OR observed_at>=actor_row.valid_until
   OR observed_at<moderator_row.not_before OR observed_at>=moderator_row.valid_until THEN
  RAISE EXCEPTION 'Account deletion observation authority unavailable' USING ERRCODE='42501';
 END IF;

 SELECT s.user_id,s.created_at,s.not_after,s.aal
 INTO authenticated_user_id,authenticated_session_created_at,authenticated_session_not_after,authenticated_session_aal
 FROM feedme_auth_access.session_facts(p_provider_session_id) s;
 SELECT count(*),max(a.updated_at) FILTER(WHERE a.authentication_method='totp')
 INTO amr_count,totp_authenticated_at
 FROM feedme_auth_access.amr_facts(p_provider_session_id) a;
 SELECT f.status::text,f.factor_type::text INTO current_factor_status,current_factor_type
 FROM ONLY auth.sessions s JOIN ONLY auth.mfa_factors f ON f.id=s.factor_id AND f.user_id=s.user_id
 WHERE s.id=p_provider_session_id AND s.user_id=authenticated_user_id FOR SHARE OF s,f;
 IF authenticated_user_id IS NULL OR authenticated_user_id<>actor_row.subject::uuid
   OR authenticated_session_aal<>'aal2' OR authenticated_session_created_at IS NULL
   OR authenticated_session_created_at>observed_at
   OR (authenticated_session_not_after IS NOT NULL AND authenticated_session_not_after<=observed_at)
   OR amr_count IS NULL OR amr_count<1 OR amr_count>32
   OR totp_authenticated_at IS NULL OR NOT isfinite(totp_authenticated_at)
   OR totp_authenticated_at<actor_row.token_valid_after OR totp_authenticated_at>observed_at
   OR current_factor_status<>'verified' OR current_factor_type<>'totp' THEN
  RAISE EXCEPTION 'Account deletion observation session unavailable' USING ERRCODE='42501';
 END IF;

 SELECT j.stage::text INTO accepted_stage
 FROM ONLY identity.account_deletion_jobs j
 WHERE j.environment=p_environment AND j.id=p_receipt FOR SHARE;
 IF accepted_stage IS NOT NULL THEN
  SELECT w.stage::text,w.last_reason::text INTO work_stage,last_reason
  FROM ONLY identity.account_erasure_work w
  WHERE w.environment=p_environment AND w.job_id=p_receipt FOR SHARE;
  IF work_stage='core_erased' THEN
   SELECT d.stage::text INTO provider_stage
   FROM ONLY erasure.provider_deletions d
   WHERE d.environment=p_environment AND d.job_id=p_receipt FOR SHARE;
  END IF;
 END IF;
 durable_completion:=false;

 INSERT INTO safety.account_deletion_access_audit(
   environment,id,actor_id,authority_revision,receipt_id,purpose,trace_id,created_at
 ) VALUES(
   p_environment,p_audit_id,p_actor_id,p_authority_revision,p_receipt,
   'receipt-observation',p_trace_id,observed_at
 );

 RETURN NEXT;
END;
$feedme_deletion_observe$;
REVOKE ALL ON FUNCTION safety.observe_account_deletion_receipt(text,uuid,uuid,uuid,uuid,text,uuid) FROM PUBLIC;

-- Deliberately no GRANT. A separately reviewed rollout must grant only EXECUTE on the
-- combined observe-and-audit function; no serving role should receive direct table reads.
