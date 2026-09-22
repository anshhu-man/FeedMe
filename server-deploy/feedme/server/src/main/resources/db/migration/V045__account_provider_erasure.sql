-- LOCAL worker foundation only: no scheduler, credential, runtime grant, Auth write,
-- external request or whole-account completion. HTTP acknowledgment is not absence.
-- The separate schema leaves V044's fixed private-data inventory unchanged.
CREATE SCHEMA erasure;
REVOKE ALL ON SCHEMA erasure FROM PUBLIC;

CREATE TABLE erasure.provider_deletions (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 job_id uuid NOT NULL, attempt_id uuid NOT NULL, user_id uuid NOT NULL, principal_id uuid NOT NULL,
 provider_issuer text NOT NULL CHECK(provider_issuer='https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1'),
 provider_subject uuid NOT NULL,
 core_erased_at timestamptz NOT NULL CHECK(isfinite(core_erased_at)),
 core_erased_token uuid NOT NULL, core_erased_generation bigint NOT NULL CHECK(core_erased_generation>0),
 stage varchar(24) NOT NULL DEFAULT 'prepared'
   CHECK(stage IN ('prepared','dispatched','acknowledged','outcome_unknown','provider_absent')),
 generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),
 lease_token uuid NULL, lease_expires_at timestamptz NULL,
 available_at timestamptz NOT NULL CHECK(isfinite(available_at)),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 updated_at timestamptz NOT NULL CHECK(isfinite(updated_at)),
 dispatched_at timestamptz NULL, dispatched_token uuid NULL, dispatched_generation bigint NULL,
 acknowledged_at timestamptz NULL, provider_present_at timestamptz NULL,
 provider_absent_at timestamptz NULL, provider_absent_token uuid NULL, provider_absent_generation bigint NULL,
 PRIMARY KEY(environment,job_id), UNIQUE(environment,attempt_id),
 FOREIGN KEY(environment,job_id) REFERENCES identity.account_deletion_jobs(environment,id),
 FOREIGN KEY(environment,job_id) REFERENCES identity.account_erasure_work(environment,job_id),
 CHECK(user_id<>principal_id AND core_erased_at<=created_at AND created_at<=updated_at),
 CHECK((lease_token IS NULL)=(lease_expires_at IS NULL)),
 CHECK(lease_expires_at IS NULL OR (generation>0 AND isfinite(lease_expires_at))),
 CHECK((stage='prepared' AND num_nonnulls(dispatched_at,dispatched_token,dispatched_generation)=0)
   OR (stage<>'prepared' AND num_nonnulls(dispatched_at,dispatched_token,dispatched_generation)=3
     AND isfinite(dispatched_at) AND dispatched_at>=created_at AND dispatched_at<=updated_at
     AND dispatched_generation>0 AND dispatched_generation<=generation)),
 CHECK((stage='acknowledged' AND acknowledged_at IS NOT NULL)
   OR (stage IN ('prepared','dispatched','outcome_unknown') AND acknowledged_at IS NULL)
   OR stage='provider_absent'),
 CHECK(acknowledged_at IS NULL OR (isfinite(acknowledged_at) AND acknowledged_at>=dispatched_at AND acknowledged_at<=updated_at)),
 CHECK(provider_present_at IS NULL OR (dispatched_at IS NOT NULL AND isfinite(provider_present_at) AND provider_present_at>=dispatched_at AND provider_present_at<=updated_at)),
 CHECK((stage<>'provider_absent' AND num_nonnulls(provider_absent_at,provider_absent_token,provider_absent_generation)=0)
   OR (stage='provider_absent' AND num_nonnulls(provider_absent_at,provider_absent_token,provider_absent_generation)=3
     AND isfinite(provider_absent_at) AND provider_absent_at>=dispatched_at AND provider_absent_at<=updated_at
     AND provider_absent_generation=generation AND provider_absent_generation>0
     AND lease_token IS NULL AND lease_expires_at IS NULL))
);
ALTER TABLE erasure.provider_deletions ENABLE ROW LEVEL SECURITY;
ALTER TABLE erasure.provider_deletions FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE erasure.provider_deletions FROM PUBLIC;
CREATE INDEX account_provider_erasure_available ON erasure.provider_deletions(environment,available_at,job_id)
 WHERE stage<>'provider_absent';

CREATE FUNCTION erasure.guard_account_provider_deletion() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_guard$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Provider erasure evidence is retained' USING ERRCODE='23514';
 END IF;
 IF current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' THEN
  RAISE EXCEPTION 'Provider erasure transaction is unavailable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.stage<>'prepared' OR NEW.generation<>0 OR NEW.lease_token IS NOT NULL
    OR NEW.lease_expires_at IS NOT NULL OR NEW.provider_present_at IS NOT NULL
    OR NOT EXISTS(SELECT 1 FROM identity.account_deletion_jobs j
       JOIN identity.account_erasure_work w ON w.environment=j.environment AND w.job_id=j.id
       WHERE j.environment=NEW.environment AND j.id=NEW.job_id AND j.stage='pending'
         AND ROW(j.user_id,j.principal_id,j.provider_issuer::text,j.provider_subject)
             IS NOT DISTINCT FROM ROW(NEW.user_id,NEW.principal_id,NEW.provider_issuer,NEW.provider_subject)
         AND w.stage='core_erased' AND ROW(w.core_erased_at,w.core_erased_token,w.core_erased_generation)
             IS NOT DISTINCT FROM ROW(NEW.core_erased_at,NEW.core_erased_token,NEW.core_erased_generation)) THEN
   RAISE EXCEPTION 'Provider erasure requires the exact core checkpoint' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END IF;
 IF OLD.stage='provider_absent' OR ROW(NEW.environment,NEW.job_id,NEW.attempt_id,NEW.user_id,NEW.principal_id,
      NEW.provider_issuer,NEW.provider_subject,NEW.core_erased_at,NEW.core_erased_token,NEW.core_erased_generation,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.environment,OLD.job_id,OLD.attempt_id,OLD.user_id,OLD.principal_id,
      OLD.provider_issuer,OLD.provider_subject,OLD.core_erased_at,OLD.core_erased_token,OLD.core_erased_generation,OLD.created_at)
   OR NEW.updated_at<OLD.updated_at OR NEW.generation<OLD.generation THEN
  RAISE EXCEPTION 'Provider erasure original is immutable' USING ERRCODE='23514';
 END IF;
 IF NEW.generation<>OLD.generation THEN
  IF OLD.generation=9223372036854775807 OR NEW.generation<>OLD.generation+1 OR NEW.stage<>OLD.stage THEN
   RAISE EXCEPTION 'Provider erasure lease generation is invalid' USING ERRCODE='23514';
  END IF;
 END IF;
 IF OLD.dispatched_at IS NOT NULL AND ROW(NEW.dispatched_at,NEW.dispatched_token,NEW.dispatched_generation)
     IS DISTINCT FROM ROW(OLD.dispatched_at,OLD.dispatched_token,OLD.dispatched_generation)
   OR OLD.acknowledged_at IS NOT NULL AND NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at
   OR OLD.provider_present_at IS NOT NULL AND (NEW.provider_present_at IS NULL OR NEW.provider_present_at<OLD.provider_present_at) THEN
  RAISE EXCEPTION 'Provider erasure observation cannot be rewritten' USING ERRCODE='23514';
 END IF;
 IF NOT (NEW.stage=OLD.stage OR (OLD.stage='prepared' AND NEW.stage='dispatched')
   OR (OLD.stage='dispatched' AND NEW.stage IN ('acknowledged','outcome_unknown'))
   OR (OLD.stage IN ('dispatched','acknowledged','outcome_unknown') AND NEW.stage='provider_absent')) THEN
  RAISE EXCEPTION 'Provider erasure stage cannot be restored' USING ERRCODE='23514';
 END IF;
 IF NEW.stage<>OLD.stage THEN
  IF OLD.lease_token IS NULL OR OLD.lease_expires_at IS NULL OR OLD.lease_expires_at<=clock_timestamp()
    OR NEW.generation<>OLD.generation THEN
   RAISE EXCEPTION 'Provider erasure stage requires a live lease' USING ERRCODE='23514';
  END IF;
  IF NEW.stage='dispatched' AND (NEW.dispatched_token IS DISTINCT FROM OLD.lease_token
    OR NEW.dispatched_generation IS DISTINCT FROM OLD.generation OR NEW.dispatched_at IS NULL
    OR NEW.dispatched_at>clock_timestamp() OR NEW.dispatched_at>=OLD.lease_expires_at) THEN
   RAISE EXCEPTION 'Provider dispatch is not the original live attempt' USING ERRCODE='23514';
  END IF;
  IF NEW.stage='provider_absent' AND (NEW.provider_absent_token IS DISTINCT FROM OLD.lease_token
    OR NEW.provider_absent_generation IS DISTINCT FROM OLD.generation OR NEW.provider_absent_at IS NULL
    OR NEW.provider_absent_at>clock_timestamp() OR NEW.provider_absent_at>=OLD.lease_expires_at) THEN
   RAISE EXCEPTION 'Provider absence is not the current observation' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END;
$feedme_provider_guard$;
REVOKE ALL ON FUNCTION erasure.guard_account_provider_deletion() FROM PUBLIC;
CREATE TRIGGER account_provider_deletion_guard BEFORE INSERT OR UPDATE OR DELETE ON erasure.provider_deletions
 FOR EACH ROW EXECUTE FUNCTION erasure.guard_account_provider_deletion();
CREATE TRIGGER account_provider_deletion_retained BEFORE TRUNCATE ON erasure.provider_deletions
 FOR EACH STATEMENT EXECUTE FUNCTION erasure.guard_account_provider_deletion();

CREATE FUNCTION erasure.claim_account_provider_erasure(p_environment text,p_attempt uuid,p_token uuid,p_lease_seconds integer)
RETURNS TABLE(job_id uuid,attempt_id uuid,user_id uuid,provider_issuer text,provider_subject uuid,
 generation bigint,lease_expires_at timestamptz,action text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_claim$
DECLARE accepted identity.account_deletion_jobs%ROWTYPE; core identity.account_erasure_work%ROWTYPE;
 current_attempt erasure.provider_deletions%ROWTYPE; at_time timestamptz;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$' OR p_attempt IS NULL OR p_token IS NULL
   OR p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 1 AND 300
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid provider erasure claim' USING ERRCODE='23514';
 END IF;
 -- Accepted job first, then provider ledger. The core checkpoint is already immutable.
 SELECT j.* INTO accepted FROM identity.account_deletion_jobs j
 JOIN identity.account_erasure_work w ON w.environment=j.environment AND w.job_id=j.id
 LEFT JOIN erasure.provider_deletions d ON d.environment=j.environment AND d.job_id=j.id
 WHERE j.environment=p_environment AND j.stage='pending'
   AND j.provider_issuer='https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1' AND w.stage='core_erased'
   AND (d.job_id IS NULL OR (d.stage<>'provider_absent' AND d.generation<9223372036854775807
     AND d.available_at<=clock_timestamp() AND (d.lease_expires_at IS NULL OR d.lease_expires_at<=clock_timestamp())))
 ORDER BY coalesce(d.available_at,w.core_erased_at),w.core_erased_at,j.id
 LIMIT 1 FOR UPDATE OF j SKIP LOCKED;
 IF NOT FOUND THEN RETURN; END IF;
 SELECT w.* INTO STRICT core FROM identity.account_erasure_work w
  WHERE w.environment=accepted.environment AND w.job_id=accepted.id;
 IF core.stage<>'core_erased' THEN RAISE EXCEPTION 'Core checkpoint unavailable' USING ERRCODE='23514'; END IF;
 at_time:=clock_timestamp();
 INSERT INTO erasure.provider_deletions(environment,job_id,attempt_id,user_id,principal_id,provider_issuer,provider_subject,
   core_erased_at,core_erased_token,core_erased_generation,available_at,created_at,updated_at)
 VALUES(accepted.environment,accepted.id,p_attempt,accepted.user_id,accepted.principal_id,accepted.provider_issuer,accepted.provider_subject,
   core.core_erased_at,core.core_erased_token,core.core_erased_generation,at_time,at_time,at_time)
 ON CONFLICT ON CONSTRAINT provider_deletions_pkey DO NOTHING;
 SELECT d.* INTO STRICT current_attempt FROM erasure.provider_deletions d
  WHERE d.environment=accepted.environment AND d.job_id=accepted.id FOR UPDATE;
 at_time:=clock_timestamp();
 IF current_attempt.stage='provider_absent' OR current_attempt.generation=9223372036854775807
   OR current_attempt.available_at>at_time OR current_attempt.lease_expires_at>at_time THEN RETURN; END IF;
 UPDATE erasure.provider_deletions d SET generation=d.generation+1,lease_token=p_token,
   lease_expires_at=at_time+p_lease_seconds*interval '1 second',updated_at=at_time
 WHERE d.environment=accepted.environment AND d.job_id=accepted.id RETURNING d.* INTO STRICT current_attempt;
 RETURN QUERY SELECT accepted.id,current_attempt.attempt_id,accepted.user_id,accepted.provider_issuer::text,
   accepted.provider_subject,current_attempt.generation,current_attempt.lease_expires_at,
   CASE WHEN current_attempt.stage='prepared' THEN 'dispatch'::text ELSE 'reconcile'::text END;
END;
$feedme_provider_claim$;
REVOKE ALL ON FUNCTION erasure.claim_account_provider_erasure(text,uuid,uuid,integer) FROM PUBLIC;

CREATE FUNCTION erasure.dispatch_account_provider_erasure(p_environment text,p_job uuid,p_attempt uuid,p_token uuid,p_generation bigint)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_dispatch$
DECLARE current_attempt erasure.provider_deletions%ROWTYPE; at_time timestamptz;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_job IS NULL OR p_attempt IS NULL OR p_token IS NULL OR p_generation IS NULL OR p_generation<1
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid provider dispatch' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT d.* INTO current_attempt FROM erasure.provider_deletions d
  WHERE d.environment=p_environment AND d.job_id=p_job FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 at_time:=clock_timestamp();
 IF current_attempt.attempt_id<>p_attempt OR current_attempt.stage<>'prepared'
   OR current_attempt.lease_token IS DISTINCT FROM p_token OR current_attempt.generation<>p_generation
   OR current_attempt.lease_expires_at IS NULL OR current_attempt.lease_expires_at<=at_time THEN RETURN false; END IF;
 UPDATE erasure.provider_deletions d SET stage='dispatched',dispatched_at=at_time,
   dispatched_token=p_token,dispatched_generation=p_generation,updated_at=at_time
 WHERE d.environment=p_environment AND d.job_id=p_job;
 -- Only a KNOWN COMMITTED true permits one external call. Repeating this function
 -- after a lost acknowledgment never grants another DELETE for a dispatched attempt.
 RETURN true;
END;
$feedme_provider_dispatch$;
REVOKE ALL ON FUNCTION erasure.dispatch_account_provider_erasure(text,uuid,uuid,uuid,bigint) FROM PUBLIC;

CREATE FUNCTION erasure.record_account_provider_erasure(p_environment text,p_job uuid,p_attempt uuid,p_token uuid,p_generation bigint,p_result text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_record$
DECLARE current_attempt erasure.provider_deletions%ROWTYPE; at_time timestamptz;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_job IS NULL OR p_attempt IS NULL OR p_token IS NULL OR p_generation IS NULL OR p_generation<1
   OR p_result IS NULL OR p_result NOT IN ('acknowledged','outcome_unknown')
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid provider result' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT d.* INTO current_attempt FROM erasure.provider_deletions d
  WHERE d.environment=p_environment AND d.job_id=p_job FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 at_time:=clock_timestamp();
 IF current_attempt.attempt_id<>p_attempt OR current_attempt.lease_token IS DISTINCT FROM p_token
   OR current_attempt.generation<>p_generation OR current_attempt.dispatched_token IS DISTINCT FROM p_token
   OR current_attempt.dispatched_generation IS DISTINCT FROM p_generation OR current_attempt.lease_expires_at IS NULL
   OR current_attempt.lease_expires_at<=at_time THEN RETURN false; END IF;
 IF current_attempt.stage=p_result THEN RETURN true; END IF;
 IF current_attempt.stage<>'dispatched' THEN RETURN false; END IF;
 UPDATE erasure.provider_deletions d SET stage=p_result,
   acknowledged_at=CASE WHEN p_result='acknowledged' THEN at_time ELSE NULL END,updated_at=at_time
 WHERE d.environment=p_environment AND d.job_id=p_job;
 -- Preserve this lease for immediate, separately committed provider reconciliation.
 RETURN true;
END;
$feedme_provider_record$;
REVOKE ALL ON FUNCTION erasure.record_account_provider_erasure(text,uuid,uuid,uuid,bigint,text) FROM PUBLIC;

CREATE FUNCTION erasure.reconcile_account_provider_erasure(p_environment text,p_job uuid,p_attempt uuid,p_token uuid,p_generation bigint,p_retry_delay_seconds integer)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_provider_reconcile$
DECLARE accepted identity.account_deletion_jobs%ROWTYPE; current_attempt erasure.provider_deletions%ROWTYPE;
 provider_present boolean; at_time timestamptz;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_job IS NULL OR p_attempt IS NULL OR p_token IS NULL OR p_generation IS NULL OR p_generation<1
   OR p_retry_delay_seconds IS NULL OR p_retry_delay_seconds NOT BETWEEN 1 AND 86400
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid provider reconciliation' USING ERRCODE='23514';
 END IF;
 -- Read only immutable target material before taking any app row lock. The pinned
 -- provider projection locks the actual Auth user if present; missing helper/permission
 -- or a failed query aborts, and must never be converted to an absent observation.
 SELECT j.* INTO accepted FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job;
 IF NOT FOUND OR accepted.provider_issuer<>'https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1' THEN RETURN 'lease_lost'; END IF;
 SELECT d.* INTO current_attempt FROM erasure.provider_deletions d WHERE d.environment=p_environment AND d.job_id=p_job;
 IF NOT FOUND OR current_attempt.attempt_id<>p_attempt THEN RETURN 'lease_lost'; END IF;
 IF current_attempt.stage='provider_absent' THEN
  IF current_attempt.provider_absent_token=p_token AND current_attempt.provider_absent_generation=p_generation THEN RETURN 'provider_absent'; END IF;
  RETURN 'lease_lost';
 END IF;
 -- Refuse a stale/wrong capability before taking an Auth lock; repeat this check
 -- under the app row locks because a lease can change or expire while waiting.
 IF current_attempt.stage NOT IN ('dispatched','acknowledged','outcome_unknown')
   OR current_attempt.lease_token IS DISTINCT FROM p_token OR current_attempt.generation<>p_generation
   OR current_attempt.lease_expires_at IS NULL OR current_attempt.lease_expires_at<=clock_timestamp()
   OR ROW(current_attempt.user_id,current_attempt.principal_id,current_attempt.provider_issuer,current_attempt.provider_subject)
       IS DISTINCT FROM ROW(accepted.user_id,accepted.principal_id,accepted.provider_issuer::text,accepted.provider_subject) THEN RETURN 'lease_lost'; END IF;
 SELECT EXISTS(SELECT 1 FROM feedme_auth_access.user_facts(accepted.provider_subject)) INTO provider_present;
 -- Caller must bind the actual provider compatibility review before AND after this
 -- SQL call in the same transaction. It performs no Auth INSERT/UPDATE/DELETE.
 PERFORM 1 FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job FOR UPDATE;
 IF NOT FOUND THEN RETURN 'lease_lost'; END IF;
 SELECT d.* INTO current_attempt FROM erasure.provider_deletions d
  WHERE d.environment=p_environment AND d.job_id=p_job FOR UPDATE;
 IF NOT FOUND THEN RETURN 'lease_lost'; END IF;
 IF current_attempt.stage='provider_absent' THEN
  IF current_attempt.attempt_id=p_attempt AND current_attempt.provider_absent_token=p_token
    AND current_attempt.provider_absent_generation=p_generation THEN RETURN 'provider_absent'; END IF;
  RETURN 'lease_lost';
 END IF;
 at_time:=clock_timestamp();
 IF current_attempt.attempt_id<>p_attempt OR current_attempt.stage NOT IN ('dispatched','acknowledged','outcome_unknown')
   OR current_attempt.lease_token IS DISTINCT FROM p_token OR current_attempt.generation<>p_generation
   OR current_attempt.lease_expires_at IS NULL OR current_attempt.lease_expires_at<=at_time
   OR ROW(current_attempt.user_id,current_attempt.principal_id,current_attempt.provider_issuer,current_attempt.provider_subject)
       IS DISTINCT FROM ROW(accepted.user_id,accepted.principal_id,accepted.provider_issuer::text,accepted.provider_subject) THEN RETURN 'lease_lost'; END IF;
 IF provider_present THEN
  UPDATE erasure.provider_deletions d SET lease_token=NULL,lease_expires_at=NULL,
    provider_present_at=at_time,available_at=at_time+p_retry_delay_seconds*interval '1 second',updated_at=at_time
  WHERE d.environment=p_environment AND d.job_id=p_job;
  RETURN 'provider_present';
 END IF;
 UPDATE erasure.provider_deletions d SET stage='provider_absent',provider_absent_at=at_time,
   provider_absent_token=p_token,provider_absent_generation=p_generation,
   lease_token=NULL,lease_expires_at=NULL,updated_at=at_time
 WHERE d.environment=p_environment AND d.job_id=p_job;
 RETURN 'provider_absent';
END;
$feedme_provider_reconcile$;
REVOKE ALL ON FUNCTION erasure.reconcile_account_provider_erasure(text,uuid,uuid,uuid,bigint,integer) FROM PUBLIC;
