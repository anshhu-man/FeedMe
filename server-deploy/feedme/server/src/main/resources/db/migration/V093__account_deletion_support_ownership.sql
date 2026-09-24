-- Registered additive platform migration. Depends on the separately reviewed,
-- still-ungranted
-- feedme_auth_access.google_identity_facts(uuid) candidate. No email address,
-- provider ID, identity metadata, support message or provider-session ID is retained.
-- This migration adds no HTTP/operator surface or GRANT. Its issuance function is
-- staff-only by design and independently rechecks current moderator/MFA authority.

CREATE TABLE safety.account_deletion_support_challenges (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 id uuid NOT NULL,
 token_sha256 char(64) NULL CHECK(token_sha256 ~ '^[0-9a-f]{64}$'),
 policy_version varchar(128) NOT NULL CHECK(policy_version='feedme-support-ownership-v1'),
 issued_by_actor_id uuid NOT NULL,
 issued_by_authority_revision char(64) NOT NULL CHECK(issued_by_authority_revision ~ '^[0-9a-f]{64}$'),
 issued_at timestamptz NOT NULL CHECK(isfinite(issued_at)),
 expires_at timestamptz NOT NULL CHECK(isfinite(expires_at) AND issued_at<expires_at AND expires_at<=issued_at+interval '10 minutes'),
 claim_id uuid NULL,
 claimed_at timestamptz NULL CHECK(claimed_at IS NULL OR isfinite(claimed_at)),
 secret_redacted_at timestamptz NULL CHECK(secret_redacted_at IS NULL OR isfinite(secret_redacted_at)),
 PRIMARY KEY(environment,id),
 UNIQUE(environment,claim_id),
 FOREIGN KEY(environment,issued_by_actor_id) REFERENCES staff.moderator_enrollments(environment,actor_id),
 CHECK(
   (claim_id IS NULL AND claimed_at IS NULL AND
     ((token_sha256 IS NOT NULL AND secret_redacted_at IS NULL) OR
      (token_sha256 IS NULL AND secret_redacted_at IS NOT NULL AND secret_redacted_at>=expires_at)))
   OR
   (claim_id IS NOT NULL AND claimed_at IS NOT NULL AND issued_at<=claimed_at AND claimed_at<expires_at
     AND token_sha256 IS NULL AND secret_redacted_at=claimed_at)
 )
);

CREATE TABLE safety.account_deletion_support_claims (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 claim_id uuid NOT NULL,
 challenge_id uuid NOT NULL,
 provider_issuer varchar(2048) NOT NULL CHECK(provider_issuer='https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1'),
 provider_subject uuid NULL,
 policy_version varchar(128) NOT NULL CHECK(policy_version='feedme-support-ownership-v1'),
 oauth_authenticated_at timestamptz NOT NULL CHECK(isfinite(oauth_authenticated_at)),
 google_observed_at timestamptz NOT NULL CHECK(isfinite(google_observed_at)),
 claimed_at timestamptz NOT NULL CHECK(isfinite(claimed_at)),
 subject_retention_until timestamptz NOT NULL CHECK(isfinite(subject_retention_until)),
 subject_redacted_at timestamptz NULL CHECK(subject_redacted_at IS NULL OR isfinite(subject_redacted_at)),
 PRIMARY KEY(environment,claim_id),
 UNIQUE(environment,challenge_id),
 FOREIGN KEY(environment,challenge_id) REFERENCES safety.account_deletion_support_challenges(environment,id),
 CHECK(oauth_authenticated_at<=google_observed_at AND google_observed_at<=claimed_at),
 CHECK(claimed_at<subject_retention_until AND subject_retention_until<=claimed_at+interval '10 minutes'),
 CHECK((provider_subject IS NOT NULL AND subject_redacted_at IS NULL) OR
       (provider_subject IS NULL AND subject_redacted_at IS NOT NULL AND subject_redacted_at>=subject_retention_until))
);

ALTER TABLE safety.account_deletion_support_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.account_deletion_support_challenges FORCE ROW LEVEL SECURITY;
ALTER TABLE safety.account_deletion_support_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.account_deletion_support_claims FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE safety.account_deletion_support_challenges,
 safety.account_deletion_support_claims FROM PUBLIC;

CREATE FUNCTION safety.protect_account_deletion_support_claim() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $feedme_support_claim_guard$
BEGIN
 IF TG_OP='UPDATE'
   AND OLD.provider_subject IS NOT NULL AND OLD.subject_redacted_at IS NULL
   AND NEW.provider_subject IS NULL AND NEW.subject_redacted_at IS NOT NULL
   AND NEW.subject_redacted_at>=OLD.subject_retention_until
   AND ROW(NEW.environment,NEW.claim_id,NEW.challenge_id,NEW.provider_issuer,
       NEW.policy_version,NEW.oauth_authenticated_at,NEW.google_observed_at,
       NEW.claimed_at,NEW.subject_retention_until)
     IS NOT DISTINCT FROM
       ROW(OLD.environment,OLD.claim_id,OLD.challenge_id,OLD.provider_issuer,
       OLD.policy_version,OLD.oauth_authenticated_at,OLD.google_observed_at,
       OLD.claimed_at,OLD.subject_retention_until) THEN
  RETURN NEW;
 END IF;
 RAISE EXCEPTION 'Account deletion support claim is immutable' USING ERRCODE='23514';
END;
$feedme_support_claim_guard$;
REVOKE ALL ON FUNCTION safety.protect_account_deletion_support_claim() FROM PUBLIC;
CREATE TRIGGER account_deletion_support_claim_immutable
 BEFORE UPDATE OR DELETE ON safety.account_deletion_support_claims
 FOR EACH ROW EXECUTE FUNCTION safety.protect_account_deletion_support_claim();
CREATE TRIGGER account_deletion_support_claim_retained
 BEFORE TRUNCATE ON safety.account_deletion_support_claims
 FOR EACH STATEMENT EXECUTE FUNCTION safety.protect_account_deletion_support_claim();

CREATE FUNCTION safety.guard_account_deletion_support_challenge() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $feedme_support_challenge_guard$
BEGIN
 IF TG_OP='UPDATE'
   AND OLD.claim_id IS NULL AND OLD.claimed_at IS NULL
   AND OLD.token_sha256 IS NOT NULL AND OLD.secret_redacted_at IS NULL
   AND NEW.claim_id IS NOT NULL AND NEW.claimed_at IS NOT NULL
   AND NEW.token_sha256 IS NULL AND NEW.secret_redacted_at=NEW.claimed_at
   AND ROW(NEW.environment,NEW.id,NEW.policy_version,
       NEW.issued_by_actor_id,NEW.issued_by_authority_revision,NEW.issued_at,NEW.expires_at)
     IS NOT DISTINCT FROM
       ROW(OLD.environment,OLD.id,OLD.policy_version,
       OLD.issued_by_actor_id,OLD.issued_by_authority_revision,OLD.issued_at,OLD.expires_at)
   AND EXISTS(SELECT 1 FROM safety.account_deletion_support_claims c
       WHERE c.environment=NEW.environment AND c.claim_id=NEW.claim_id
         AND c.challenge_id=NEW.id AND c.claimed_at=NEW.claimed_at) THEN
  RETURN NEW;
 END IF;
 IF TG_OP='UPDATE'
   AND OLD.claim_id IS NULL AND OLD.claimed_at IS NULL
   AND OLD.token_sha256 IS NOT NULL AND OLD.secret_redacted_at IS NULL
   AND NEW.claim_id IS NULL AND NEW.claimed_at IS NULL
   AND NEW.token_sha256 IS NULL AND NEW.secret_redacted_at IS NOT NULL
   AND NEW.secret_redacted_at>=OLD.expires_at
   AND ROW(NEW.environment,NEW.id,NEW.policy_version,NEW.issued_by_actor_id,
       NEW.issued_by_authority_revision,NEW.issued_at,NEW.expires_at)
     IS NOT DISTINCT FROM
       ROW(OLD.environment,OLD.id,OLD.policy_version,OLD.issued_by_actor_id,
       OLD.issued_by_authority_revision,OLD.issued_at,OLD.expires_at) THEN
  RETURN NEW;
 END IF;
 RAISE EXCEPTION 'Account deletion support challenge mutation is unavailable' USING ERRCODE='23514';
END;
$feedme_support_challenge_guard$;
REVOKE ALL ON FUNCTION safety.guard_account_deletion_support_challenge() FROM PUBLIC;
CREATE TRIGGER account_deletion_support_challenge_guard
 BEFORE UPDATE OR DELETE ON safety.account_deletion_support_challenges
 FOR EACH ROW EXECUTE FUNCTION safety.guard_account_deletion_support_challenge();
CREATE TRIGGER account_deletion_support_challenge_retained
 BEFORE TRUNCATE ON safety.account_deletion_support_challenges
 FOR EACH STATEMENT EXECUTE FUNCTION safety.guard_account_deletion_support_challenge();

CREATE FUNCTION safety.issue_account_deletion_support_challenge(
 p_environment text,
 p_challenge_id uuid,
 p_token_sha256 text,
 p_policy_version text,
 p_actor_id uuid,
 p_provider_session_id uuid,
 p_authority_revision text
) RETURNS TABLE(
 challenge_id uuid,
 issued_at timestamptz,
 expires_at timestamptz
)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_support_issue$
DECLARE
 policy_row staff.publication_policies%ROWTYPE;
 actor_row staff.actors%ROWTYPE;
 moderator_row staff.moderator_enrollments%ROWTYPE;
 existing safety.account_deletion_support_challenges%ROWTYPE;
 authenticated_user_id uuid;
 authenticated_session_created_at timestamptz;
 authenticated_session_not_after timestamptz;
 authenticated_session_aal text;
 amr_count bigint;
 totp_authenticated_at timestamptz;
 current_factor_status text;
 current_factor_type text;
 at_time timestamptz;
BEGIN
 IF current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed'
   OR current_setting('transaction_read_only')<>'off'
   OR p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_challenge_id IS NULL OR p_challenge_id='00000000-0000-0000-0000-000000000000'::uuid
   OR p_token_sha256 IS NULL OR p_token_sha256 !~ '^[0-9a-f]{64}$'
   OR p_policy_version<>'feedme-support-ownership-v1'
   OR p_actor_id IS NULL OR p_provider_session_id IS NULL OR p_actor_id=p_provider_session_id
   OR p_authority_revision IS NULL OR p_authority_revision !~ '^[0-9a-f]{64}$' THEN
  RAISE EXCEPTION 'Account deletion support challenge input unavailable' USING ERRCODE='23514';
 END IF;

 SELECT p.* INTO policy_row FROM ONLY staff.publication_policies p
 WHERE p.environment=p_environment FOR SHARE;
 SELECT a.* INTO actor_row FROM ONLY staff.actors a
 WHERE a.environment=p_environment AND a.actor_id=p_actor_id FOR SHARE;
 SELECT m.* INTO moderator_row FROM ONLY staff.moderator_enrollments m
 WHERE m.environment=p_environment AND m.actor_id=p_actor_id FOR SHARE;
 at_time:=clock_timestamp();
 IF policy_row.environment IS NULL OR actor_row.actor_id IS NULL OR moderator_row.actor_id IS NULL
   OR NOT policy_row.enabled OR NOT actor_row.enabled OR NOT moderator_row.enabled
   OR policy_row.version<>moderator_row.policy_version
   OR actor_row.issuer<>policy_row.issuer
   OR actor_row.subject !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
   OR at_time<policy_row.not_before OR at_time>=policy_row.valid_until
   OR at_time<actor_row.not_before OR at_time>=actor_row.valid_until
   OR at_time<moderator_row.not_before OR at_time>=moderator_row.valid_until THEN
  RAISE EXCEPTION 'Account deletion support challenge authority unavailable' USING ERRCODE='42501';
 END IF;

 SELECT s.user_id,s.created_at,s.not_after,s.aal
 INTO authenticated_user_id,authenticated_session_created_at,authenticated_session_not_after,
      authenticated_session_aal
 FROM feedme_auth_access.session_facts(p_provider_session_id) s;
 SELECT count(*),max(a.updated_at) FILTER(WHERE a.authentication_method='totp')
 INTO amr_count,totp_authenticated_at
 FROM feedme_auth_access.amr_facts(p_provider_session_id) a;
 SELECT f.status::text,f.factor_type::text INTO current_factor_status,current_factor_type
 FROM ONLY auth.sessions s JOIN ONLY auth.mfa_factors f
   ON f.id=s.factor_id AND f.user_id=s.user_id
 WHERE s.id=p_provider_session_id AND s.user_id=authenticated_user_id FOR SHARE OF s,f;
 IF authenticated_user_id IS NULL OR authenticated_user_id<>actor_row.subject::uuid
   OR authenticated_session_aal<>'aal2' OR authenticated_session_created_at IS NULL
   OR authenticated_session_created_at>at_time
   OR (authenticated_session_not_after IS NOT NULL AND authenticated_session_not_after<=at_time)
   OR amr_count IS NULL OR amr_count<1 OR amr_count>32
   OR totp_authenticated_at IS NULL OR NOT isfinite(totp_authenticated_at)
   OR totp_authenticated_at<actor_row.token_valid_after OR totp_authenticated_at>at_time
   OR current_factor_status<>'verified' OR current_factor_type<>'totp' THEN
  RAISE EXCEPTION 'Account deletion support challenge session unavailable' USING ERRCODE='42501';
 END IF;

 SELECT c.* INTO existing FROM ONLY safety.account_deletion_support_challenges c
 WHERE c.environment=p_environment AND c.id=p_challenge_id FOR UPDATE;
 IF existing.id IS NULL THEN
  INSERT INTO safety.account_deletion_support_challenges(
    environment,id,token_sha256,policy_version,issued_by_actor_id,
    issued_by_authority_revision,issued_at,expires_at
  ) VALUES(
    p_environment,p_challenge_id,p_token_sha256,p_policy_version,p_actor_id,
    p_authority_revision,at_time,at_time+interval '10 minutes'
  ) RETURNING * INTO existing;
 ELSIF existing.token_sha256<>p_token_sha256
   OR existing.policy_version<>p_policy_version
   OR existing.issued_by_actor_id<>p_actor_id
   OR existing.issued_by_authority_revision<>p_authority_revision
   OR existing.claim_id IS NOT NULL OR existing.claimed_at IS NOT NULL
   OR at_time>=existing.expires_at THEN
  RAISE EXCEPTION 'Account deletion support challenge unavailable' USING ERRCODE='42501';
 END IF;

 challenge_id:=existing.id;
 issued_at:=existing.issued_at;
 expires_at:=existing.expires_at;
 RETURN NEXT;
END;
$feedme_support_issue$;
REVOKE ALL ON FUNCTION safety.issue_account_deletion_support_challenge(
 text,uuid,text,text,uuid,uuid,text) FROM PUBLIC;

CREATE FUNCTION safety.claim_account_deletion_support_ownership(
 p_environment text,
 p_challenge_id uuid,
 p_claim_id uuid,
 p_token_sha256 text,
 p_policy_version text,
 p_provider_issuer text,
 p_provider_subject uuid,
 p_provider_session_id uuid
) RETURNS TABLE(
 claim_id uuid,
 challenge_id uuid,
 provider_issuer text,
 provider_subject uuid,
 claimed_at timestamptz
)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_support_claim$
DECLARE
 challenge safety.account_deletion_support_challenges%ROWTYPE;
 existing safety.account_deletion_support_claims%ROWTYPE;
 authenticated_user_id uuid;
 session_created timestamptz;
 session_not_after timestamptz;
 session_aal text;
 session_oauth_client uuid;
 amr_count bigint;
 oauth_count bigint;
 oauth_at timestamptz;
 google_count bigint;
 google_user uuid;
 google_provider text;
 google_at timestamptz;
 at_time timestamptz;
BEGIN
 IF current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed'
   OR current_setting('transaction_read_only')<>'off'
   OR p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_challenge_id IS NULL OR p_claim_id IS NULL OR p_challenge_id=p_claim_id
   OR p_token_sha256 IS NULL OR p_token_sha256 !~ '^[0-9a-f]{64}$'
   OR p_policy_version<>'feedme-support-ownership-v1'
   OR p_provider_issuer<>'https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1'
   OR p_provider_subject IS NULL OR p_provider_session_id IS NULL
   OR p_provider_subject=p_provider_session_id THEN
  RAISE EXCEPTION 'Account deletion support claim input unavailable' USING ERRCODE='23514';
 END IF;

 SELECT c.* INTO challenge FROM ONLY safety.account_deletion_support_challenges c
 WHERE c.environment=p_environment AND c.id=p_challenge_id FOR UPDATE;
 IF challenge.id IS NULL OR challenge.token_sha256<>p_token_sha256
   OR challenge.policy_version<>p_policy_version THEN
  RAISE EXCEPTION 'Account deletion support claim unavailable' USING ERRCODE='42501';
 END IF;

 SELECT s.user_id,s.created_at,s.not_after,s.aal,s.oauth_client_id
 INTO authenticated_user_id,session_created,session_not_after,session_aal,session_oauth_client
 FROM feedme_auth_access.session_facts(p_provider_session_id) s;
 SELECT count(*),count(*) FILTER(WHERE a.authentication_method='oauth'),
        max(a.updated_at) FILTER(WHERE a.authentication_method='oauth')
 INTO amr_count,oauth_count,oauth_at
 FROM feedme_auth_access.amr_facts(p_provider_session_id) a;
 google_count:=0;
 FOR google_user,google_provider,google_at IN
   SELECT g.user_id,g.provider,g.observed_at
   FROM feedme_auth_access.google_identity_facts(p_provider_subject) g
 LOOP
  google_count:=google_count+1;
 END LOOP;
 at_time:=clock_timestamp();

 IF authenticated_user_id IS NULL OR authenticated_user_id<>p_provider_subject
   OR session_created IS NULL OR session_created>at_time
   OR session_aal NOT IN ('aal1','aal2') OR session_oauth_client IS NOT NULL
   OR (session_not_after IS NOT NULL AND session_not_after<=at_time)
   OR amr_count IS NULL OR amr_count<1 OR amr_count>32
   OR oauth_count<>1 OR oauth_at IS NULL OR oauth_at<session_created OR oauth_at>at_time
   OR google_count<>1 OR google_user<>p_provider_subject OR google_provider<>'google'
   OR google_at IS NULL OR google_at<oauth_at OR google_at>at_time
   OR challenge.issued_at>oauth_at OR at_time>=challenge.expires_at
   OR at_time>=oauth_at+interval '5 minutes'
   OR at_time>=google_at+interval '1 minute' THEN
  RAISE EXCEPTION 'Account deletion support claim authority unavailable' USING ERRCODE='42501';
 END IF;

 IF challenge.claim_id IS NOT NULL OR challenge.claimed_at IS NOT NULL THEN
  SELECT c.* INTO existing FROM ONLY safety.account_deletion_support_claims c
  WHERE c.environment=p_environment AND c.challenge_id=p_challenge_id FOR SHARE;
  IF existing.claim_id IS NULL OR existing.claim_id<>p_claim_id
    OR existing.provider_issuer<>p_provider_issuer
    OR existing.provider_subject IS NULL OR existing.provider_subject<>p_provider_subject
    OR existing.policy_version<>p_policy_version
    OR existing.oauth_authenticated_at<>oauth_at
    OR challenge.claim_id<>existing.claim_id OR challenge.claimed_at<>existing.claimed_at THEN
   RAISE EXCEPTION 'Account deletion support claim unavailable' USING ERRCODE='42501';
  END IF;
 ELSE
  INSERT INTO safety.account_deletion_support_claims(
    environment,claim_id,challenge_id,provider_issuer,provider_subject,policy_version,
    oauth_authenticated_at,google_observed_at,claimed_at,subject_retention_until
  ) VALUES(
    p_environment,p_claim_id,p_challenge_id,p_provider_issuer,p_provider_subject,
    p_policy_version,oauth_at,google_at,at_time,
    least(challenge.expires_at,oauth_at+interval '5 minutes',google_at+interval '1 minute')
  ) RETURNING * INTO existing;
  UPDATE safety.account_deletion_support_challenges
  SET token_sha256=NULL,claim_id=p_claim_id,claimed_at=at_time,secret_redacted_at=at_time
  WHERE environment=p_environment AND id=p_challenge_id;
  IF NOT FOUND THEN
   RAISE EXCEPTION 'Account deletion support claim unavailable' USING ERRCODE='42501';
  END IF;
 END IF;

 claim_id:=existing.claim_id;
 challenge_id:=existing.challenge_id;
 provider_issuer:=existing.provider_issuer;
 provider_subject:=existing.provider_subject;
 claimed_at:=existing.claimed_at;
 RETURN NEXT;
END;
$feedme_support_claim$;
REVOKE ALL ON FUNCTION safety.claim_account_deletion_support_ownership(
 text,uuid,uuid,text,text,text,uuid,uuid) FROM PUBLIC;

CREATE FUNCTION safety.redact_expired_account_deletion_support_ownership(
 p_environment text,
 p_limit integer
) RETURNS TABLE(redacted_claims bigint,redacted_challenges bigint)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_support_redact$
DECLARE
 at_time timestamptz;
 claim_count bigint;
 challenge_count bigint;
BEGIN
 IF current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed'
   OR current_setting('transaction_read_only')<>'off'
   OR p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_limit IS NULL OR p_limit<1 OR p_limit>1000 THEN
  RAISE EXCEPTION 'Account deletion support redaction input unavailable' USING ERRCODE='23514';
 END IF;
 at_time:=clock_timestamp();

 WITH due AS (
   SELECT c.claim_id FROM ONLY safety.account_deletion_support_claims c
   WHERE c.environment=p_environment AND c.provider_subject IS NOT NULL
     AND c.subject_redacted_at IS NULL AND c.subject_retention_until<=at_time
   ORDER BY c.subject_retention_until,c.claim_id
   LIMIT p_limit FOR UPDATE SKIP LOCKED
 )
 UPDATE safety.account_deletion_support_claims c
 SET provider_subject=NULL,subject_redacted_at=at_time
 FROM due
 WHERE c.environment=p_environment AND c.claim_id=due.claim_id;
 GET DIAGNOSTICS claim_count=ROW_COUNT;

 WITH due AS (
   SELECT c.id FROM ONLY safety.account_deletion_support_challenges c
   WHERE c.environment=p_environment AND c.claim_id IS NULL
     AND c.token_sha256 IS NOT NULL AND c.secret_redacted_at IS NULL
     AND c.expires_at<=at_time
   ORDER BY c.expires_at,c.id
   LIMIT p_limit FOR UPDATE SKIP LOCKED
 )
 UPDATE safety.account_deletion_support_challenges c
 SET token_sha256=NULL,secret_redacted_at=at_time
 FROM due
 WHERE c.environment=p_environment AND c.id=due.id;
 GET DIAGNOSTICS challenge_count=ROW_COUNT;

 redacted_claims:=claim_count;
 redacted_challenges:=challenge_count;
 RETURN NEXT;
END;
$feedme_support_redact$;
REVOKE ALL ON FUNCTION safety.redact_expired_account_deletion_support_ownership(text,integer) FROM PUBLIC;

-- Deliberately no GRANT. Final review must add exact EXECUTE-only authority after
-- the Google-identity projection, schema/FK checks and issuance/claim paths pass
-- real-PostgreSQL and retention review.
