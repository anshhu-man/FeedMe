-- LOCAL, default-disabled controlled retry foundation. No live credential, scheduler,
-- runtime grant, Auth write or full-account completion is installed here.
-- At most three total HTTP dispatch intents, NOT exactly-once external effects.
-- The named policy assumes accepted provider UUIDs are never reused/restored. It is
-- an explicit operator contract, not tenant attestation or evidence of owner approval.
ALTER TABLE erasure.provider_deletions ADD CONSTRAINT provider_deletions_exact_attempt
 UNIQUE(environment,job_id,attempt_id);

CREATE TABLE erasure.provider_deletion_retries (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 job_id uuid NOT NULL, parent_attempt_id uuid NOT NULL, retry_id uuid NOT NULL,
 retry_ordinal integer NOT NULL CHECK(retry_ordinal BETWEEN 2 AND 3),
 policy_revision text NOT NULL CHECK(policy_revision='feedme-auth-erasure-no-subject-reuse-v1'),
 base_backoff_seconds integer NOT NULL CHECK(base_backoff_seconds BETWEEN 1 AND 3600),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 presence_observed_at timestamptz NOT NULL CHECK(isfinite(presence_observed_at)),
 stage varchar(24) NOT NULL DEFAULT 'prepared'
   CHECK(stage IN ('prepared','dispatched','acknowledged','outcome_unknown')),
 updated_at timestamptz NOT NULL CHECK(isfinite(updated_at)),
 dispatched_at timestamptz NULL, dispatched_token uuid NULL, dispatched_generation bigint NULL,
 dispatch_presence_observed_at timestamptz NULL, acknowledged_at timestamptz NULL,
 PRIMARY KEY(environment,job_id,retry_ordinal), UNIQUE(environment,retry_id),
 FOREIGN KEY(environment,job_id,parent_attempt_id)
   REFERENCES erasure.provider_deletions(environment,job_id,attempt_id),
 CHECK(retry_id<>parent_attempt_id AND presence_observed_at<=created_at AND created_at<=updated_at),
 CHECK((stage='prepared' AND num_nonnulls(dispatched_at,dispatched_token,dispatched_generation,dispatch_presence_observed_at)=0)
   OR (stage<>'prepared' AND num_nonnulls(dispatched_at,dispatched_token,dispatched_generation,dispatch_presence_observed_at)=4
     AND isfinite(dispatched_at) AND isfinite(dispatch_presence_observed_at)
     AND dispatched_generation>0 AND created_at<=dispatch_presence_observed_at
     AND dispatch_presence_observed_at<=dispatched_at AND dispatched_at<=updated_at)),
 CHECK((stage='acknowledged' AND acknowledged_at IS NOT NULL AND isfinite(acknowledged_at)
     AND acknowledged_at>=dispatched_at AND acknowledged_at<=updated_at)
   OR (stage<>'acknowledged' AND acknowledged_at IS NULL))
);
ALTER TABLE erasure.provider_deletion_retries ENABLE ROW LEVEL SECURITY;
ALTER TABLE erasure.provider_deletion_retries FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE erasure.provider_deletion_retries FROM PUBLIC;

CREATE FUNCTION erasure.guard_account_provider_retry() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_retry_guard$
DECLARE parent erasure.provider_deletions%ROWTYPE; prior erasure.provider_deletion_retries%ROWTYPE;
 due_at timestamptz; at_time timestamptz;
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Provider retry evidence is retained' USING ERRCODE='23514';
 END IF;
 IF current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' THEN
  RAISE EXCEPTION 'Provider retry transaction is unavailable' USING ERRCODE='23514';
 END IF;
 SELECT d.* INTO parent FROM erasure.provider_deletions d
  JOIN identity.account_deletion_jobs j ON j.environment=d.environment AND j.id=d.job_id
  JOIN identity.account_erasure_work w ON w.environment=j.environment AND w.job_id=j.id
  WHERE d.environment=NEW.environment AND d.job_id=NEW.job_id AND d.attempt_id=NEW.parent_attempt_id
    AND j.stage='pending' AND w.stage='core_erased'
    AND ROW(j.user_id,j.principal_id,j.provider_issuer::text,j.provider_subject)
      IS NOT DISTINCT FROM ROW(d.user_id,d.principal_id,d.provider_issuer,d.provider_subject)
    AND ROW(w.core_erased_at,w.core_erased_token,w.core_erased_generation)
      IS NOT DISTINCT FROM ROW(d.core_erased_at,d.core_erased_token,d.core_erased_generation);
 at_time:=clock_timestamp();
 IF NOT FOUND OR parent.stage NOT IN ('dispatched','acknowledged','outcome_unknown')
   OR parent.lease_token IS NULL OR parent.lease_expires_at IS NULL OR parent.lease_expires_at<=at_time THEN
  RAISE EXCEPTION 'Provider retry requires the exact live parent' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.stage<>'prepared' OR NEW.created_at>at_time OR NEW.updated_at IS DISTINCT FROM NEW.created_at
    OR NEW.presence_observed_at<parent.dispatched_at THEN
   RAISE EXCEPTION 'Provider retry preparation is invalid' USING ERRCODE='23514';
  END IF;
  IF NEW.retry_ordinal=2 THEN
   IF EXISTS(SELECT 1 FROM erasure.provider_deletion_retries r
      WHERE r.environment=NEW.environment AND r.job_id=NEW.job_id) THEN
    RAISE EXCEPTION 'Provider retry original already exists' USING ERRCODE='23514';
   END IF;
   due_at:=parent.dispatched_at+NEW.base_backoff_seconds*interval '1 second';
  ELSE
   SELECT r.* INTO prior FROM erasure.provider_deletion_retries r
    WHERE r.environment=NEW.environment AND r.job_id=NEW.job_id AND r.retry_ordinal=2;
   IF NOT FOUND OR prior.parent_attempt_id<>NEW.parent_attempt_id OR prior.stage='prepared'
     OR prior.policy_revision<>NEW.policy_revision OR prior.base_backoff_seconds<>NEW.base_backoff_seconds
     OR NEW.retry_id=prior.retry_id THEN
    RAISE EXCEPTION 'Provider retry predecessor is unavailable' USING ERRCODE='23514';
   END IF;
   due_at:=prior.dispatched_at+(2*NEW.base_backoff_seconds)*interval '1 second';
  END IF;
  IF due_at IS NULL OR NEW.created_at<due_at THEN
   RAISE EXCEPTION 'Provider retry backoff has not elapsed' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF ROW(NEW.environment,NEW.job_id,NEW.parent_attempt_id,NEW.retry_id,NEW.retry_ordinal,
      NEW.policy_revision,NEW.base_backoff_seconds,NEW.created_at,NEW.presence_observed_at)
    IS DISTINCT FROM ROW(OLD.environment,OLD.job_id,OLD.parent_attempt_id,OLD.retry_id,OLD.retry_ordinal,
      OLD.policy_revision,OLD.base_backoff_seconds,OLD.created_at,OLD.presence_observed_at)
   OR NEW.updated_at<OLD.updated_at OR NEW.updated_at>at_time THEN
  RAISE EXCEPTION 'Provider retry original is immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.dispatched_at IS NOT NULL AND ROW(NEW.dispatched_at,NEW.dispatched_token,NEW.dispatched_generation,NEW.dispatch_presence_observed_at)
     IS DISTINCT FROM ROW(OLD.dispatched_at,OLD.dispatched_token,OLD.dispatched_generation,OLD.dispatch_presence_observed_at)
   OR OLD.acknowledged_at IS NOT NULL AND NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at THEN
  RAISE EXCEPTION 'Provider retry dispatch evidence is immutable' USING ERRCODE='23514';
 END IF;
 IF NOT (NEW.stage=OLD.stage OR (OLD.stage='prepared' AND NEW.stage='dispatched')
   OR (OLD.stage='dispatched' AND NEW.stage IN ('acknowledged','outcome_unknown'))) THEN
  RAISE EXCEPTION 'Provider retry stage cannot be restored' USING ERRCODE='23514';
 END IF;
 IF NEW.stage<>OLD.stage THEN
  IF NEW.dispatched_token IS DISTINCT FROM parent.lease_token
    OR NEW.dispatched_generation IS DISTINCT FROM parent.generation THEN
   RAISE EXCEPTION 'Provider retry result is not the current dispatch lease' USING ERRCODE='23514';
  END IF;
  IF NEW.stage='dispatched' AND (NEW.dispatched_at IS NULL OR NEW.dispatched_at>at_time
    OR NEW.dispatched_at>=parent.lease_expires_at OR NEW.dispatch_presence_observed_at IS NULL) THEN
   RAISE EXCEPTION 'Provider retry dispatch is invalid' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END;
$feedme_provider_retry_guard$;
REVOKE ALL ON FUNCTION erasure.guard_account_provider_retry() FROM PUBLIC;
CREATE TRIGGER account_provider_retry_guard BEFORE INSERT OR UPDATE OR DELETE ON erasure.provider_deletion_retries
 FOR EACH ROW EXECUTE FUNCTION erasure.guard_account_provider_retry();
CREATE TRIGGER account_provider_retry_retained BEFORE TRUNCATE ON erasure.provider_deletion_retries
 FOR EACH STATEMENT EXECUTE FUNCTION erasure.guard_account_provider_retry();

CREATE FUNCTION erasure.prepare_account_provider_retry(p_environment text,p_job uuid,p_parent_attempt uuid,
 p_token uuid,p_generation bigint,p_retry uuid,p_policy_revision text,p_base_backoff_seconds integer)
RETURNS TABLE(outcome text,retry_id uuid,retry_ordinal integer)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_retry_prepare$
DECLARE accepted identity.account_deletion_jobs%ROWTYPE; parent erasure.provider_deletions%ROWTYPE;
 latest erasure.provider_deletion_retries%ROWTYPE; provider_present boolean;
 observed_at timestamptz; at_time timestamptz; due_at timestamptz; next_ordinal integer;
 observation text; delay_seconds integer;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_job IS NULL OR p_parent_attempt IS NULL OR p_token IS NULL OR p_generation IS NULL OR p_generation<1
   OR p_retry IS NULL OR p_retry=p_parent_attempt OR p_policy_revision IS NULL
   OR p_policy_revision<>'feedme-auth-erasure-no-subject-reuse-v1'
   OR p_base_backoff_seconds IS NULL OR p_base_backoff_seconds NOT BETWEEN 1 AND 3600
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid provider retry preparation' USING ERRCODE='23514';
 END IF;
 SELECT j.* INTO accepted FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job;
 IF NOT FOUND OR accepted.stage<>'pending'
   OR accepted.provider_issuer<>'https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1' THEN
  RETURN QUERY SELECT 'lease_lost'::text,NULL::uuid,NULL::integer; RETURN;
 END IF;
 SELECT d.* INTO parent FROM erasure.provider_deletions d WHERE d.environment=p_environment AND d.job_id=p_job;
 IF NOT FOUND OR parent.attempt_id<>p_parent_attempt THEN
  RETURN QUERY SELECT 'lease_lost'::text,NULL::uuid,NULL::integer; RETURN;
 END IF;
 IF parent.stage='provider_absent' THEN
  IF parent.provider_absent_token=p_token AND parent.provider_absent_generation=p_generation THEN
   RETURN QUERY SELECT 'provider_absent'::text,NULL::uuid,NULL::integer; RETURN;
  END IF;
  RETURN QUERY SELECT 'lease_lost'::text,NULL::uuid,NULL::integer; RETURN;
 END IF;
 IF parent.stage NOT IN ('dispatched','acknowledged','outcome_unknown')
   OR parent.lease_token IS DISTINCT FROM p_token OR parent.generation<>p_generation
   OR parent.lease_expires_at IS NULL OR parent.lease_expires_at<=clock_timestamp()
   OR ROW(parent.user_id,parent.principal_id,parent.provider_issuer,parent.provider_subject)
     IS DISTINCT FROM ROW(accepted.user_id,accepted.principal_id,accepted.provider_issuer::text,accepted.provider_subject) THEN
  RETURN QUERY SELECT 'lease_lost'::text,NULL::uuid,NULL::integer; RETURN;
 END IF;
 -- Immutable target first, then actual provider lock, then job -> parent -> retry rows.
 -- Caller pins provider projector/schema authority before and after in this transaction.
 SELECT EXISTS(SELECT 1 FROM feedme_auth_access.user_facts(accepted.provider_subject)) INTO provider_present;
 observed_at:=clock_timestamp();
 IF NOT provider_present THEN
  observation:=erasure.reconcile_account_provider_erasure(p_environment,p_job,p_parent_attempt,p_token,p_generation,p_base_backoff_seconds);
  RETURN QUERY SELECT CASE observation WHEN 'provider_absent' THEN 'provider_absent'
    WHEN 'provider_present' THEN 'waiting' ELSE 'lease_lost' END,NULL::uuid,NULL::integer; RETURN;
 END IF;
 PERFORM 1 FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job FOR UPDATE;
 IF NOT FOUND THEN RETURN QUERY SELECT 'lease_lost'::text,NULL::uuid,NULL::integer; RETURN; END IF;
 SELECT d.* INTO parent FROM erasure.provider_deletions d
  WHERE d.environment=p_environment AND d.job_id=p_job FOR UPDATE;
 at_time:=clock_timestamp();
 IF NOT FOUND OR parent.attempt_id<>p_parent_attempt OR parent.stage NOT IN ('dispatched','acknowledged','outcome_unknown')
   OR parent.lease_token IS DISTINCT FROM p_token OR parent.generation<>p_generation
   OR parent.lease_expires_at IS NULL OR parent.lease_expires_at<=at_time
   OR ROW(parent.user_id,parent.principal_id,parent.provider_issuer,parent.provider_subject)
     IS DISTINCT FROM ROW(accepted.user_id,accepted.principal_id,accepted.provider_issuer::text,accepted.provider_subject) THEN
  RETURN QUERY SELECT 'lease_lost'::text,NULL::uuid,NULL::integer; RETURN;
 END IF;
 PERFORM 1 FROM erasure.provider_deletion_retries r WHERE r.environment=p_environment AND r.job_id=p_job
  ORDER BY r.retry_ordinal FOR UPDATE;
 SELECT r.* INTO latest FROM erasure.provider_deletion_retries r
  WHERE r.environment=p_environment AND r.job_id=p_job ORDER BY r.retry_ordinal DESC LIMIT 1;
 IF FOUND AND (latest.parent_attempt_id<>p_parent_attempt OR latest.policy_revision<>p_policy_revision
   OR latest.base_backoff_seconds<>p_base_backoff_seconds) THEN
  RAISE EXCEPTION 'Provider retry policy cannot change' USING ERRCODE='23514';
 END IF;
 IF latest.retry_id IS NOT NULL AND latest.stage='prepared' THEN
  -- An unknown preparation COMMIT recovers this same immutable candidate, not a new attempt.
  UPDATE erasure.provider_deletions d SET provider_present_at=observed_at,updated_at=clock_timestamp()
   WHERE d.environment=p_environment AND d.job_id=p_job;
  IF parent.lease_expires_at<=clock_timestamp() THEN
   RAISE EXCEPTION 'Provider retry lease expired during preparation' USING ERRCODE='23514';
  END IF;
  RETURN QUERY SELECT 'ready'::text,latest.retry_id,latest.retry_ordinal; RETURN;
 END IF;
 IF latest.retry_id IS NULL THEN
  next_ordinal:=2; due_at:=parent.dispatched_at+p_base_backoff_seconds*interval '1 second';
 ELSIF latest.retry_ordinal=2 THEN
  next_ordinal:=3; due_at:=latest.dispatched_at+(2*p_base_backoff_seconds)*interval '1 second';
 ELSE
  observation:=erasure.reconcile_account_provider_erasure(p_environment,p_job,p_parent_attempt,p_token,p_generation,
    least(86400,2*p_base_backoff_seconds));
  RETURN QUERY SELECT CASE observation WHEN 'provider_absent' THEN 'provider_absent'
    WHEN 'provider_present' THEN 'exhausted' ELSE 'lease_lost' END,NULL::uuid,NULL::integer; RETURN;
 END IF;
 at_time:=clock_timestamp();
 IF parent.lease_expires_at<=at_time THEN
  RETURN QUERY SELECT 'lease_lost'::text,NULL::uuid,NULL::integer; RETURN;
 END IF;
 IF due_at IS NULL THEN RAISE EXCEPTION 'Provider retry predecessor is invalid' USING ERRCODE='23514'; END IF;
 IF due_at>at_time THEN
  delay_seconds:=greatest(1,least(86400,ceil(extract(epoch FROM due_at-at_time))::integer));
  observation:=erasure.reconcile_account_provider_erasure(p_environment,p_job,p_parent_attempt,p_token,p_generation,delay_seconds);
  RETURN QUERY SELECT CASE observation WHEN 'provider_absent' THEN 'provider_absent'
    WHEN 'provider_present' THEN 'waiting' ELSE 'lease_lost' END,NULL::uuid,NULL::integer; RETURN;
 END IF;
 INSERT INTO erasure.provider_deletion_retries(environment,job_id,parent_attempt_id,retry_id,retry_ordinal,
   policy_revision,base_backoff_seconds,created_at,presence_observed_at,updated_at)
 VALUES(p_environment,p_job,p_parent_attempt,p_retry,next_ordinal,p_policy_revision,p_base_backoff_seconds,
   at_time,observed_at,at_time);
 UPDATE erasure.provider_deletions d SET provider_present_at=observed_at,updated_at=clock_timestamp()
  WHERE d.environment=p_environment AND d.job_id=p_job;
 IF parent.lease_expires_at<=clock_timestamp() THEN
  RAISE EXCEPTION 'Provider retry lease expired during preparation' USING ERRCODE='23514';
 END IF;
 RETURN QUERY SELECT 'ready'::text,p_retry,next_ordinal;
END;
$feedme_provider_retry_prepare$;
REVOKE ALL ON FUNCTION erasure.prepare_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid,text,integer) FROM PUBLIC;

CREATE FUNCTION erasure.dispatch_account_provider_retry(p_environment text,p_job uuid,p_parent_attempt uuid,
 p_token uuid,p_generation bigint,p_retry uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_retry_dispatch$
DECLARE accepted identity.account_deletion_jobs%ROWTYPE; parent erasure.provider_deletions%ROWTYPE;
 candidate erasure.provider_deletion_retries%ROWTYPE; provider_present boolean;
 observed_at timestamptz; at_time timestamptz;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_job IS NULL OR p_parent_attempt IS NULL OR p_token IS NULL OR p_generation IS NULL OR p_generation<1 OR p_retry IS NULL
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid provider retry dispatch' USING ERRCODE='23514';
 END IF;
 SELECT j.* INTO accepted FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job;
 IF NOT FOUND OR accepted.stage<>'pending'
   OR accepted.provider_issuer<>'https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1' THEN RETURN false; END IF;
 SELECT d.* INTO parent FROM erasure.provider_deletions d WHERE d.environment=p_environment AND d.job_id=p_job;
 IF NOT FOUND OR parent.attempt_id<>p_parent_attempt OR parent.stage NOT IN ('dispatched','acknowledged','outcome_unknown')
   OR parent.lease_token IS DISTINCT FROM p_token OR parent.generation<>p_generation
   OR parent.lease_expires_at IS NULL OR parent.lease_expires_at<=clock_timestamp()
   OR ROW(parent.user_id,parent.principal_id,parent.provider_issuer,parent.provider_subject)
     IS DISTINCT FROM ROW(accepted.user_id,accepted.principal_id,accepted.provider_issuer::text,accepted.provider_subject) THEN RETURN false; END IF;
 SELECT r.* INTO candidate FROM erasure.provider_deletion_retries r
  WHERE r.environment=p_environment AND r.job_id=p_job AND r.parent_attempt_id=p_parent_attempt AND r.retry_id=p_retry;
 IF NOT FOUND OR candidate.stage<>'prepared' THEN RETURN false; END IF;
 SELECT EXISTS(SELECT 1 FROM feedme_auth_access.user_facts(accepted.provider_subject)) INTO provider_present;
 observed_at:=clock_timestamp();
 IF NOT provider_present THEN RETURN false; END IF;
 PERFORM 1 FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT d.* INTO parent FROM erasure.provider_deletions d WHERE d.environment=p_environment AND d.job_id=p_job FOR UPDATE;
 at_time:=clock_timestamp();
 IF NOT FOUND OR parent.attempt_id<>p_parent_attempt OR parent.stage NOT IN ('dispatched','acknowledged','outcome_unknown')
   OR parent.lease_token IS DISTINCT FROM p_token OR parent.generation<>p_generation
   OR parent.lease_expires_at IS NULL OR parent.lease_expires_at<=at_time
   OR ROW(parent.user_id,parent.principal_id,parent.provider_issuer,parent.provider_subject)
     IS DISTINCT FROM ROW(accepted.user_id,accepted.principal_id,accepted.provider_issuer::text,accepted.provider_subject) THEN RETURN false; END IF;
 SELECT r.* INTO candidate FROM erasure.provider_deletion_retries r
  WHERE r.environment=p_environment AND r.job_id=p_job AND r.parent_attempt_id=p_parent_attempt AND r.retry_id=p_retry FOR UPDATE;
 at_time:=clock_timestamp();
 IF NOT FOUND OR candidate.stage<>'prepared' OR parent.lease_expires_at<=at_time THEN RETURN false; END IF;
 UPDATE erasure.provider_deletion_retries r SET stage='dispatched',dispatched_at=at_time,dispatched_token=p_token,
   dispatched_generation=p_generation,dispatch_presence_observed_at=observed_at,updated_at=at_time
  WHERE r.environment=p_environment AND r.job_id=p_job AND r.retry_id=p_retry;
 -- Only known COMMITTED true authorizes one HTTP call. Never re-authorize an
 -- already-dispatched retry after a timeout, crash or lost commit acknowledgment.
 RETURN true;
END;
$feedme_provider_retry_dispatch$;
REVOKE ALL ON FUNCTION erasure.dispatch_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid) FROM PUBLIC;

CREATE FUNCTION erasure.record_account_provider_retry(p_environment text,p_job uuid,p_parent_attempt uuid,
 p_token uuid,p_generation bigint,p_retry uuid,p_result text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_retry_record$
DECLARE parent erasure.provider_deletions%ROWTYPE; candidate erasure.provider_deletion_retries%ROWTYPE;
 at_time timestamptz;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_job IS NULL OR p_parent_attempt IS NULL OR p_token IS NULL OR p_generation IS NULL OR p_generation<1 OR p_retry IS NULL
   OR p_result IS NULL OR p_result NOT IN ('acknowledged','outcome_unknown')
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid provider retry result' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job AND j.stage='pending' FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT d.* INTO parent FROM erasure.provider_deletions d
  WHERE d.environment=p_environment AND d.job_id=p_job FOR UPDATE;
 at_time:=clock_timestamp();
 IF NOT FOUND OR parent.attempt_id<>p_parent_attempt OR parent.stage NOT IN ('dispatched','acknowledged','outcome_unknown')
   OR parent.lease_token IS DISTINCT FROM p_token OR parent.generation<>p_generation
   OR parent.lease_expires_at IS NULL OR parent.lease_expires_at<=at_time THEN RETURN false; END IF;
 SELECT r.* INTO candidate FROM erasure.provider_deletion_retries r
  WHERE r.environment=p_environment AND r.job_id=p_job AND r.parent_attempt_id=p_parent_attempt AND r.retry_id=p_retry FOR UPDATE;
 at_time:=clock_timestamp();
 IF NOT FOUND OR candidate.dispatched_token IS DISTINCT FROM p_token OR candidate.dispatched_generation IS DISTINCT FROM p_generation
   OR parent.lease_expires_at<=at_time THEN RETURN false; END IF;
 IF candidate.stage=p_result THEN RETURN true; END IF;
 IF candidate.stage<>'dispatched' THEN RETURN false; END IF;
 UPDATE erasure.provider_deletion_retries r SET stage=p_result,
   acknowledged_at=CASE WHEN p_result='acknowledged' THEN at_time ELSE NULL END,updated_at=at_time
  WHERE r.environment=p_environment AND r.job_id=p_job AND r.retry_id=p_retry;
 -- Keep the parent lease for separately committed V045 exact-subject reconciliation.
 RETURN true;
END;
$feedme_provider_retry_record$;
REVOKE ALL ON FUNCTION erasure.record_account_provider_retry(text,uuid,uuid,uuid,bigint,uuid,text) FROM PUBLIC;
