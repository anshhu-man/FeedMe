-- LOCAL worker-only, exact-source observation foundation. No deployment, scheduler,
-- API grants, signed-upload settlement or full-account completion is installed.
-- A dispatched DELETE is never dispatched again, including after an unknown commit.
-- Explicit later inspections are observations only; V027/V048 holds remain intact.
CREATE TABLE erasure.media_source_deletions (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 job_id uuid NOT NULL, intent_id uuid NOT NULL, user_id uuid NOT NULL, media_id uuid NOT NULL,
 client_draft_id uuid NOT NULL, draft_generation bigint NOT NULL CHECK(draft_generation>0),
 media_version bigint NOT NULL CHECK(media_version=2), deletion_key uuid NOT NULL,
 cleanup_manifest_hash char(64) NOT NULL CHECK(cleanup_manifest_hash ~ '^[0-9a-f]{64}$'),
 bucket text NOT NULL CHECK(bucket ~ '^[a-z0-9][a-z0-9_-]{0,62}$'), object_key varchar(200) NOT NULL,
 reservation_expires_at timestamptz NOT NULL CHECK(isfinite(reservation_expires_at)),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 prepared_token uuid NOT NULL, prepared_generation bigint NOT NULL CHECK(prepared_generation>0),
 dispatched_at timestamptz NULL, dispatched_token uuid NULL, dispatched_generation bigint NULL,
 result varchar(32) NULL CHECK(result IN ('entry_acknowledged','outcome_unknown','invalid_target','not_configured')),
 observed_at timestamptz NULL,
 PRIMARY KEY(environment,job_id,media_id), UNIQUE(environment,intent_id),
 FOREIGN KEY(environment,job_id) REFERENCES identity.account_deletion_jobs(environment,id),
 FOREIGN KEY(environment,user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id),
 CHECK((num_nonnulls(dispatched_at,dispatched_token,dispatched_generation)=0)
   OR (num_nonnulls(dispatched_at,dispatched_token,dispatched_generation)=3 AND isfinite(dispatched_at)
     AND dispatched_generation>0 AND dispatched_at>=created_at)),
 CHECK((result IS NULL AND observed_at IS NULL) OR (result IS NOT NULL AND observed_at IS NOT NULL
   AND dispatched_at IS NOT NULL AND isfinite(observed_at) AND observed_at>=dispatched_at))
);
CREATE TABLE erasure.media_source_observations (
 environment varchar(40) NOT NULL, intent_id uuid NOT NULL, observation_id uuid NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal BETWEEN 1 AND 64),
 lease_token uuid NOT NULL, lease_generation bigint NOT NULL CHECK(lease_generation>0),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 result varchar(32) NULL CHECK(result IN ('present','not_found_reported','outcome_unknown','invalid_target','not_configured')),
 observed_at timestamptz NULL,
 PRIMARY KEY(environment,intent_id,ordinal), UNIQUE(environment,observation_id),
 FOREIGN KEY(environment,intent_id) REFERENCES erasure.media_source_deletions(environment,intent_id),
 CHECK((result IS NULL AND observed_at IS NULL) OR (result IS NOT NULL AND observed_at IS NOT NULL
   AND isfinite(observed_at) AND observed_at>=created_at))
);
ALTER TABLE erasure.media_source_deletions ENABLE ROW LEVEL SECURITY;
ALTER TABLE erasure.media_source_deletions FORCE ROW LEVEL SECURITY;
ALTER TABLE erasure.media_source_observations ENABLE ROW LEVEL SECURITY;
ALTER TABLE erasure.media_source_observations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE erasure.media_source_deletions,erasure.media_source_observations FROM PUBLIC;

-- Same accepted job -> inventory lease -> bounded NOWAIT writer fences -> roots
-- ordering as the core purge. No provider I/O occurs while these locks are held.
-- This is intentionally a table-level local foundation, not per-owner scalability.
CREATE FUNCTION erasure.lock_account_media_source(p_environment text,p_job uuid,p_token uuid,p_generation bigint,p_media uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_source_lock$
DECLARE accepted identity.account_deletion_jobs%ROWTYPE; work identity.account_erasure_work%ROWTYPE;
 asset platform.media_assets%ROWTYPE; cleanup platform.media_cleanup_jobs%ROWTYPE;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$' OR p_job IS NULL
   OR p_token IS NULL OR p_generation IS NULL OR p_generation<1 OR p_media IS NULL
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid source erasure transaction' USING ERRCODE='23514';
 END IF;
 SELECT j.* INTO accepted FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job FOR UPDATE NOWAIT;
 IF NOT FOUND OR accepted.stage<>'pending'
   OR accepted.provider_issuer<>'https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1' THEN RETURN false; END IF;
 SELECT w.* INTO work FROM identity.account_erasure_work w WHERE w.environment=p_environment AND w.job_id=p_job FOR UPDATE NOWAIT;
 IF NOT FOUND OR work.stage<>'inventory' OR work.lease_token IS DISTINCT FROM p_token OR work.generation<>p_generation
   OR work.lease_expires_at IS NULL OR work.lease_expires_at<=clock_timestamp() THEN RETURN false; END IF;
 LOCK TABLE platform.media_draft_lifecycles,platform.media_assets,platform.media_cleanup_jobs,
   platform.media_upload_issuances,platform.media_processing_jobs,platform.media_processing_inbox,
   platform.media_derivative_intents,platform.media_processing_cleanup,platform.media_private_materializations,
   platform.media_safety_records,platform.media_safety_revocations,platform.post_draft_discard_media,
   social.post_publications,social.post_media,social.post_publication_discard_media,
   safety.reports,safety.report_evidence,safety.moderation_cases IN SHARE ROW EXCLUSIVE MODE NOWAIT;
 PERFORM 1 FROM identity.users u WHERE u.environment=p_environment AND u.id=accepted.user_id
   AND u.status='deleting' AND u.provider_issuer=accepted.provider_issuer AND u.provider_subject=accepted.provider_subject FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN false; END IF;
 PERFORM 1 FROM identity.principals p WHERE p.environment=p_environment AND p.id=accepted.principal_id
   AND p.kind='user' AND p.user_id=accepted.user_id AND p.status='deleting' AND p.guest_session_id IS NULL FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT m.* INTO asset FROM platform.media_assets m WHERE m.environment=p_environment AND m.owner_user_id=accepted.user_id AND m.id=p_media FOR UPDATE NOWAIT;
 IF NOT FOUND OR asset.storage_protocol<>'supabaseSignedUploadV1' OR asset.storage_bucket IS NULL
   OR asset.storage_bucket !~ '^[a-z0-9][a-z0-9_-]{0,62}$' OR asset.state<>'deleted' OR asset.version<>2
   OR asset.completion_key IS NOT NULL OR asset.completion_version IS NOT NULL OR asset.completion_accepted_at IS NOT NULL
   OR asset.quarantine_version_id IS NOT NULL OR asset.derivative_set IS NOT NULL OR asset.deletion_key IS NULL
   OR asset.cleanup_manifest_hash IS NULL OR asset.content_type NOT IN ('image/png','image/jpeg')
   OR asset.quarantine_key !~ ('^quarantine/'||p_environment||'/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/'||p_media::text||'$') THEN RETURN false; END IF;
 PERFORM 1 FROM platform.media_draft_lifecycles d WHERE d.environment=p_environment AND d.owner_user_id=accepted.user_id
   AND d.client_draft_id=asset.client_draft_id AND d.generation=asset.draft_generation FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT c.* INTO cleanup FROM platform.media_cleanup_jobs c WHERE c.environment=p_environment AND c.owner_user_id=accepted.user_id AND c.media_id=p_media FOR UPDATE NOWAIT;
 IF NOT FOUND OR ROW(cleanup.media_version,cleanup.quarantine_key,cleanup.final_sweep_after,cleanup.manifest_hash,
     cleanup.storage_protocol,cleanup.storage_bucket) IS DISTINCT FROM ROW(asset.version,asset.quarantine_key,
     asset.reservation_expires_at,asset.cleanup_manifest_hash,asset.storage_protocol,asset.storage_bucket)
   OR cleanup.known_version_id IS NOT NULL OR cleanup.derivative_set IS NOT NULL OR cleanup.completed_at IS NOT NULL THEN RETURN false; END IF;
 -- A lost issuance acknowledgement is deliberately not treated as no issued token.
 -- Its original row remains retained. No deadline here asserts signed-write settlement.
 IF EXISTS(SELECT 1 FROM platform.media_processing_jobs WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM platform.media_safety_records WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM platform.post_draft_discard_media WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM social.post_publications WHERE environment=p_environment AND owner_user_id=accepted.user_id AND client_draft_id=asset.client_draft_id)
   OR EXISTS(SELECT 1 FROM social.post_media WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM social.post_publication_discard_media WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM safety.reports WHERE environment=p_environment AND (reporter_user_id=accepted.user_id OR (target_type='user' AND target_id=accepted.user_id)))
   OR EXISTS(SELECT 1 FROM safety.report_evidence WHERE environment=p_environment AND (reporter_user_id=accepted.user_id OR target_owner_id=accepted.user_id))
   OR EXISTS(SELECT 1 FROM safety.moderation_cases WHERE environment=p_environment AND target_type='user' AND target_id=accepted.user_id)
   OR NOT EXISTS(SELECT 1 FROM platform.media_processing_cleanup c WHERE c.environment=p_environment AND c.owner_user_id=accepted.user_id AND c.media_id=p_media
     AND c.object_key=asset.quarantine_key AND c.derivative_intent_id IS NULL AND c.not_before=asset.reservation_expires_at
     AND c.storage_protocol=asset.storage_protocol AND c.storage_bucket=asset.storage_bucket AND c.state='quarantined'
     AND c.settled_versions IS NULL AND c.completed_at IS NULL AND c.lease_token IS NULL)
   OR EXISTS(SELECT 1 FROM platform.media_processing_cleanup c WHERE c.environment=p_environment AND c.owner_user_id=accepted.user_id AND c.media_id=p_media
     AND (c.object_key<>asset.quarantine_key OR c.derivative_intent_id IS NOT NULL)) THEN RETURN false; END IF;
 RETURN work.lease_expires_at>clock_timestamp();
END;
$feedme_media_source_lock$;
REVOKE ALL ON FUNCTION erasure.lock_account_media_source(text,uuid,uuid,bigint,uuid) FROM PUBLIC;

CREATE FUNCTION erasure.guard_account_media_source() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_source_guard$
DECLARE asset platform.media_assets%ROWTYPE; token uuid; generation bigint;
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Source erasure evidence is retained' USING ERRCODE='23514'; END IF;
 token:=coalesce(NEW.dispatched_token,NEW.prepared_token); generation:=coalesce(NEW.dispatched_generation,NEW.prepared_generation);
 IF NOT erasure.lock_account_media_source(NEW.environment,NEW.job_id,token,generation,NEW.media_id) THEN
  RAISE EXCEPTION 'Source erasure requires exact current authority' USING ERRCODE='23514'; END IF;
 SELECT m.* INTO STRICT asset FROM platform.media_assets m WHERE m.environment=NEW.environment AND m.owner_user_id=NEW.user_id AND m.id=NEW.media_id;
 IF ROW(NEW.client_draft_id,NEW.draft_generation,NEW.media_version,NEW.deletion_key,NEW.cleanup_manifest_hash,NEW.bucket,NEW.object_key,NEW.reservation_expires_at)
   IS DISTINCT FROM ROW(asset.client_draft_id,asset.draft_generation,asset.version,asset.deletion_key,asset.cleanup_manifest_hash,asset.storage_bucket,asset.quarantine_key,asset.reservation_expires_at)
   OR NOT EXISTS(SELECT 1 FROM identity.account_deletion_jobs j WHERE j.environment=NEW.environment AND j.id=NEW.job_id AND j.user_id=NEW.user_id)
   OR NEW.created_at>clock_timestamp() OR NEW.dispatched_at>clock_timestamp() OR NEW.observed_at>clock_timestamp() THEN
  RAISE EXCEPTION 'Source erasure target differs' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.dispatched_at IS NOT NULL OR NEW.result IS NOT NULL THEN RAISE EXCEPTION 'Source intent must precede dispatch' USING ERRCODE='23514'; END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['dispatched_at','dispatched_token','dispatched_generation','result','observed_at'])
    IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['dispatched_at','dispatched_token','dispatched_generation','result','observed_at'])
    OR (OLD.dispatched_at IS NOT NULL AND ROW(NEW.dispatched_at,NEW.dispatched_token,NEW.dispatched_generation)
      IS DISTINCT FROM ROW(OLD.dispatched_at,OLD.dispatched_token,OLD.dispatched_generation))
    OR (OLD.result IS NOT NULL AND ROW(NEW.result,NEW.observed_at) IS DISTINCT FROM ROW(OLD.result,OLD.observed_at)) THEN
   RAISE EXCEPTION 'Source erasure original is immutable' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$feedme_media_source_guard$;
REVOKE ALL ON FUNCTION erasure.guard_account_media_source() FROM PUBLIC;
CREATE TRIGGER account_media_source_guard BEFORE INSERT OR UPDATE OR DELETE ON erasure.media_source_deletions FOR EACH ROW EXECUTE FUNCTION erasure.guard_account_media_source();
CREATE TRIGGER account_media_source_retained BEFORE TRUNCATE ON erasure.media_source_deletions FOR EACH STATEMENT EXECUTE FUNCTION erasure.guard_account_media_source();

CREATE FUNCTION erasure.guard_account_media_observation() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_observation_guard$
DECLARE original erasure.media_source_deletions%ROWTYPE;
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Source observations are retained' USING ERRCODE='23514'; END IF;
 SELECT d.* INTO STRICT original FROM erasure.media_source_deletions d WHERE d.environment=NEW.environment AND d.intent_id=NEW.intent_id;
 IF original.dispatched_at IS NULL OR NEW.created_at<original.dispatched_at OR NEW.created_at>clock_timestamp()
   OR NEW.observed_at>clock_timestamp() OR NOT erasure.lock_account_media_source(NEW.environment,original.job_id,NEW.lease_token,NEW.lease_generation,original.media_id) THEN
  RAISE EXCEPTION 'Source observation requires current exact authority' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.result IS NOT NULL OR NEW.ordinal<>(SELECT coalesce(max(o.ordinal),0)+1 FROM erasure.media_source_observations o WHERE o.environment=NEW.environment AND o.intent_id=NEW.intent_id) THEN
   RAISE EXCEPTION 'Source observation must retain its bounded original' USING ERRCODE='23514'; END IF;
 ELSIF (to_jsonb(NEW)-ARRAY['result','observed_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['result','observed_at'])
   OR (OLD.result IS NOT NULL AND ROW(NEW.result,NEW.observed_at) IS DISTINCT FROM ROW(OLD.result,OLD.observed_at)) THEN
  RAISE EXCEPTION 'Source observation is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_media_observation_guard$;
REVOKE ALL ON FUNCTION erasure.guard_account_media_observation() FROM PUBLIC;
CREATE TRIGGER account_media_observation_guard BEFORE INSERT OR UPDATE OR DELETE ON erasure.media_source_observations FOR EACH ROW EXECUTE FUNCTION erasure.guard_account_media_observation();
CREATE TRIGGER account_media_observation_retained BEFORE TRUNCATE ON erasure.media_source_observations FOR EACH STATEMENT EXECUTE FUNCTION erasure.guard_account_media_observation();

CREATE FUNCTION erasure.prepare_account_media_source(p_environment text,p_job uuid,p_token uuid,p_generation bigint,p_media uuid,p_intent uuid)
RETURNS TABLE(intent_id uuid,user_id uuid,bucket text,object_key text,already_dispatched boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_source_prepare$
DECLARE original erasure.media_source_deletions%ROWTYPE; owner_id uuid;
BEGIN
 IF p_intent IS NULL THEN RAISE EXCEPTION 'Source intent required' USING ERRCODE='23514'; END IF;
 IF NOT erasure.lock_account_media_source(p_environment,p_job,p_token,p_generation,p_media) THEN RETURN; END IF;
 SELECT j.user_id INTO STRICT owner_id FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job;
 INSERT INTO erasure.media_source_deletions(environment,job_id,intent_id,user_id,media_id,client_draft_id,draft_generation,
   media_version,deletion_key,cleanup_manifest_hash,bucket,object_key,reservation_expires_at,created_at,prepared_token,prepared_generation)
 SELECT p_environment,p_job,p_intent,owner_id,p_media,m.client_draft_id,m.draft_generation,m.version,m.deletion_key,
   m.cleanup_manifest_hash,m.storage_bucket,m.quarantine_key,m.reservation_expires_at,clock_timestamp(),p_token,p_generation
 FROM platform.media_assets m WHERE m.environment=p_environment AND m.owner_user_id=owner_id AND m.id=p_media
 ON CONFLICT(environment,job_id,media_id) DO NOTHING;
 SELECT d.* INTO STRICT original FROM erasure.media_source_deletions d WHERE d.environment=p_environment AND d.job_id=p_job AND d.media_id=p_media;
 RETURN QUERY SELECT original.intent_id,original.user_id,original.bucket,original.object_key::text,original.dispatched_at IS NOT NULL;
END;
$feedme_media_source_prepare$;
REVOKE ALL ON FUNCTION erasure.prepare_account_media_source(text,uuid,uuid,bigint,uuid,uuid) FROM PUBLIC;

CREATE FUNCTION erasure.dispatch_account_media_source(p_environment text,p_job uuid,p_token uuid,p_generation bigint,p_media uuid,p_intent uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_source_dispatch$
BEGIN
 IF NOT erasure.lock_account_media_source(p_environment,p_job,p_token,p_generation,p_media) THEN RETURN false; END IF;
 UPDATE erasure.media_source_deletions SET dispatched_at=clock_timestamp(),dispatched_token=p_token,dispatched_generation=p_generation
 WHERE environment=p_environment AND job_id=p_job AND media_id=p_media AND intent_id=p_intent AND dispatched_at IS NULL;
 RETURN FOUND;
END;
$feedme_media_source_dispatch$;
REVOKE ALL ON FUNCTION erasure.dispatch_account_media_source(text,uuid,uuid,bigint,uuid,uuid) FROM PUBLIC;

CREATE FUNCTION erasure.record_account_media_source(p_environment text,p_job uuid,p_token uuid,p_generation bigint,p_media uuid,p_intent uuid,p_result text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_source_record$
BEGIN
 IF p_result IS NULL OR p_result NOT IN ('entry_acknowledged','outcome_unknown','invalid_target','not_configured') THEN
  RAISE EXCEPTION 'Invalid source observation' USING ERRCODE='23514'; END IF;
 IF NOT erasure.lock_account_media_source(p_environment,p_job,p_token,p_generation,p_media) THEN RETURN false; END IF;
 UPDATE erasure.media_source_deletions SET result=p_result,observed_at=clock_timestamp()
 WHERE environment=p_environment AND job_id=p_job AND media_id=p_media AND intent_id=p_intent
   AND dispatched_token=p_token AND dispatched_generation=p_generation AND result IS NULL;
 RETURN FOUND;
END;
$feedme_media_source_record$;
REVOKE ALL ON FUNCTION erasure.record_account_media_source(text,uuid,uuid,bigint,uuid,uuid,text) FROM PUBLIC;

CREATE FUNCTION erasure.prepare_account_media_observation(p_environment text,p_job uuid,p_token uuid,p_generation bigint,p_media uuid,p_intent uuid,p_observation uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_observation_prepare$
DECLARE next_ordinal integer;
BEGIN
 IF p_observation IS NULL THEN RAISE EXCEPTION 'Observation identity required' USING ERRCODE='23514'; END IF;
 IF NOT erasure.lock_account_media_source(p_environment,p_job,p_token,p_generation,p_media) THEN RETURN false; END IF;
 PERFORM 1 FROM erasure.media_source_deletions WHERE environment=p_environment AND job_id=p_job AND media_id=p_media AND intent_id=p_intent AND dispatched_at IS NOT NULL FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT coalesce(max(o.ordinal),0)+1 INTO next_ordinal FROM erasure.media_source_observations o WHERE o.environment=p_environment AND o.intent_id=p_intent;
 IF next_ordinal>64 OR EXISTS(SELECT 1 FROM erasure.media_source_observations o WHERE o.environment=p_environment AND o.observation_id=p_observation) THEN RETURN false; END IF;
 INSERT INTO erasure.media_source_observations(environment,intent_id,observation_id,ordinal,lease_token,lease_generation,created_at)
 VALUES(p_environment,p_intent,p_observation,next_ordinal,p_token,p_generation,clock_timestamp());
 RETURN true;
END;
$feedme_media_observation_prepare$;
REVOKE ALL ON FUNCTION erasure.prepare_account_media_observation(text,uuid,uuid,bigint,uuid,uuid,uuid) FROM PUBLIC;

CREATE FUNCTION erasure.record_account_media_observation(p_environment text,p_job uuid,p_token uuid,p_generation bigint,p_media uuid,p_intent uuid,p_observation uuid,p_result text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_observation_record$
BEGIN
 IF p_result IS NULL OR p_result NOT IN ('present','not_found_reported','outcome_unknown','invalid_target','not_configured') THEN
  RAISE EXCEPTION 'Invalid source presence observation' USING ERRCODE='23514'; END IF;
 IF NOT erasure.lock_account_media_source(p_environment,p_job,p_token,p_generation,p_media) THEN RETURN false; END IF;
 PERFORM 1 FROM erasure.media_source_deletions WHERE environment=p_environment AND job_id=p_job AND media_id=p_media AND intent_id=p_intent AND dispatched_at IS NOT NULL;
 IF NOT FOUND THEN RETURN false; END IF;
 UPDATE erasure.media_source_observations SET result=p_result,observed_at=clock_timestamp()
 WHERE environment=p_environment AND intent_id=p_intent AND observation_id=p_observation AND lease_token=p_token AND lease_generation=p_generation AND result IS NULL;
 RETURN FOUND;
END;
$feedme_media_observation_record$;
REVOKE ALL ON FUNCTION erasure.record_account_media_observation(text,uuid,uuid,bigint,uuid,uuid,uuid,text) FROM PUBLIC;
