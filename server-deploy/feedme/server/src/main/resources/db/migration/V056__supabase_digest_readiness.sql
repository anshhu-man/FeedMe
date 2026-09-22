-- Application digest binding, not a provider object version or a delivery grant.
-- V028 private materialization and V037 safety originals remain immutable history.
-- Cleanup completion/late-upload settlement prohibitions remain unchanged.
CREATE TABLE platform.media_digest_readiness (
 job_id uuid PRIMARY KEY REFERENCES platform.media_processing_jobs(id),
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, media_id uuid NOT NULL,
 media_version bigint NOT NULL CHECK(media_version=3), lease_token uuid NOT NULL,
 lease_generation bigint NOT NULL CHECK(lease_generation>0),
 safety_record_sha256 char(64) NOT NULL CHECK(safety_record_sha256 ~ '^[0-9a-f]{64}$'),
 manifest_text text NOT NULL CHECK(octet_length(manifest_text)<=65536 AND jsonb_typeof(manifest_text::jsonb)='object'),
 manifest_sha256 char(64) NOT NULL CHECK(manifest_sha256=encode(sha256(convert_to(manifest_text,'UTF8')),'hex')),
 inspection_started_at timestamptz NOT NULL CHECK(isfinite(inspection_started_at)),
 event_id uuid NOT NULL UNIQUE, ready_at timestamptz NOT NULL CHECK(isfinite(ready_at) AND ready_at>=inspection_started_at),
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id),
 FOREIGN KEY(job_id) REFERENCES platform.media_private_materializations(job_id),
 FOREIGN KEY(job_id) REFERENCES platform.media_safety_records(job_id)
);
ALTER TABLE platform.media_digest_readiness ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.media_digest_readiness FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.media_digest_readiness FROM PUBLIC;

CREATE FUNCTION platform.guard_media_digest_readiness() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_digest_ready$
DECLARE asset platform.media_assets%ROWTYPE; job platform.media_processing_jobs%ROWTYPE;
 safety platform.media_safety_records%ROWTYPE; held platform.media_private_materializations%ROWTYPE;
 manifest jsonb; actual jsonb; at timestamptz;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Digest readiness is immutable' USING ERRCODE='23514'; END IF;
 SELECT * INTO STRICT asset FROM platform.media_assets WHERE environment=NEW.environment AND owner_user_id=NEW.owner_user_id AND id=NEW.media_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT job FROM platform.media_processing_jobs WHERE id=NEW.job_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT held FROM platform.media_private_materializations WHERE job_id=NEW.job_id FOR SHARE NOWAIT;
 PERFORM 1 FROM platform.media_derivative_intents WHERE job_id=NEW.job_id ORDER BY variant FOR SHARE NOWAIT;
 SELECT * INTO STRICT safety FROM platform.media_safety_records WHERE job_id=NEW.job_id FOR SHARE NOWAIT;
 at:=clock_timestamp(); manifest:=NEW.manifest_text::jsonb;
 IF ROW(job.environment,job.owner_user_id,job.media_id,job.policy_revision,job.codec_revision)
    IS DISTINCT FROM ROW(NEW.environment,NEW.owner_user_id,NEW.media_id,safety.policy_revision,safety.codec_revision)
   OR asset.storage_protocol<>'supabaseSignedUploadV1' OR asset.storage_bucket IS NULL OR asset.quarantine_version_id IS NOT NULL
   OR asset.state<>'processing' OR asset.version<>asset.completion_version OR asset.version<>2 OR asset.derivative_set IS NOT NULL
   OR asset.deletion_key IS NOT NULL OR asset.cleanup_manifest_hash IS NOT NULL OR asset.rejection_code IS NOT NULL
   OR job.state<>'working' OR job.last_failure_code IS DISTINCT FROM 'SUPABASE_READY_PROMOTION' OR job.terminal_at IS NOT NULL
   OR ROW(job.lease_token,job.lease_generation) IS DISTINCT FROM ROW(NEW.lease_token,NEW.lease_generation)
   OR job.lease_expires_at<=at OR NEW.ready_at>at OR NEW.inspection_started_at<held.acknowledged_at
   OR held.lease_generation>=NEW.lease_generation OR job.attempts<=held.attempt
   OR safety.stage<>'privateHeld' OR safety.media_version<>asset.version OR safety.record_sha256<>NEW.safety_record_sha256
   OR ROW(safety.environment,safety.owner_user_id,safety.media_id) IS DISTINCT FROM ROW(NEW.environment,NEW.owner_user_id,NEW.media_id)
   OR safety.source_text::jsonb IS DISTINCT FROM job.source
   OR (safety.evidence_text::jsonb->>'assessedAt')::timestamptz>NEW.inspection_started_at
   OR (safety.evidence_text::jsonb->>'validUntil')::timestamptz<=at
   OR EXISTS(SELECT 1 FROM platform.media_safety_revocations WHERE job_id=NEW.job_id)
   OR ROW(job.source->>'environment',job.source->>'owner',job.source->>'media',job.source->>'draft',
      (job.source->>'draftGeneration')::bigint,job.source->>'completion',(job.source->>'version')::bigint,
      (job.source->>'acceptedAt')::timestamptz,job.source->>'key',job.source->>'sha256',(job.source->>'bytes')::bigint,
      job.source->>'type',(job.source->>'deadline')::timestamptz,job.source->>'protocol',job.source->>'bucket')
     IS DISTINCT FROM ROW(asset.environment,asset.owner_user_id::text,asset.id::text,asset.client_draft_id::text,asset.draft_generation,
      asset.completion_key::text,asset.completion_version,asset.completion_accepted_at,asset.quarantine_key,asset.expected_sha256::text,
      asset.expected_bytes,asset.content_type,asset.reservation_expires_at,asset.storage_protocol,asset.storage_bucket)
 THEN RAISE EXCEPTION 'Digest readiness requires exact held source and current lease/safety' USING ERRCODE='23514'; END IF;
 IF (SELECT count(*) FROM platform.media_derivative_intents WHERE job_id=NEW.job_id)<>2 OR EXISTS(
  SELECT 1 FROM platform.media_derivative_intents d WHERE d.job_id=NEW.job_id AND
   (d.cleanup_required OR d.storage_protocol<>'supabaseSignedUploadV1' OR d.storage_bucket IS DISTINCT FROM asset.storage_bucket
    OR d.object_version_id IS NOT NULL OR d.write_attempted_at IS NULL OR d.acknowledged_at IS NULL
    OR d.acknowledged_at>held.acknowledged_at OR d.content_type<>'image/png' OR d.width NOT BETWEEN 1 AND 65535 OR d.height NOT BETWEEN 1 AND 65535
    OR d.object_key<>'derivatives/'||NEW.environment||'/'||NEW.media_id::text||'/'||d.id::text
    OR safety.evidence_text::jsonb->'derivativeSha256'->>d.variant IS DISTINCT FROM d.sha256::text))
 THEN RAISE EXCEPTION 'Digest readiness requires exact one-shot acknowledged PNG outputs' USING ERRCODE='23514'; END IF;
 SELECT jsonb_build_object('version',2,'protocol','supabaseSignedUploadV1','bucket',asset.storage_bucket,'variants',
  jsonb_agg(jsonb_build_object('variant',variant,'key',object_key,'sha256',sha256::text,'bytes',bytes,'contentType',content_type,'width',width,'height',height) ORDER BY variant))
 INTO actual FROM platform.media_derivative_intents WHERE job_id=NEW.job_id;
 IF manifest IS DISTINCT FROM actual THEN RAISE EXCEPTION 'Digest manifest differs from exact outputs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$feedme_digest_ready$;
REVOKE ALL ON FUNCTION platform.guard_media_digest_readiness() FROM PUBLIC;
CREATE TRIGGER media_digest_ready_original BEFORE INSERT OR UPDATE OR DELETE ON platform.media_digest_readiness
 FOR EACH ROW EXECUTE FUNCTION platform.guard_media_digest_readiness();
CREATE TRIGGER media_digest_ready_retained BEFORE TRUNCATE ON platform.media_digest_readiness
 FOR EACH STATEMENT EXECUTE FUNCTION platform.guard_media_digest_readiness();

CREATE FUNCTION platform.require_media_digest_ready_checkpoint() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_digest_checkpoint$
DECLARE asset platform.media_assets%ROWTYPE; job platform.media_processing_jobs%ROWTYPE; safety platform.media_safety_records%ROWTYPE;
BEGIN
 SELECT * INTO STRICT asset FROM platform.media_assets WHERE environment=NEW.environment AND owner_user_id=NEW.owner_user_id AND id=NEW.media_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT job FROM platform.media_processing_jobs WHERE id=NEW.job_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT safety FROM platform.media_safety_records WHERE job_id=NEW.job_id FOR SHARE NOWAIT;
 IF asset.state<>'ready' OR asset.version<>NEW.media_version OR asset.derivative_set IS DISTINCT FROM NEW.manifest_text::jsonb
  OR job.state<>'ready' OR job.lease_token IS NOT NULL OR job.lease_expires_at IS NOT NULL
  OR ROW(job.terminal_token,job.terminal_generation,job.terminal_media_version,job.terminal_event_id)
    IS DISTINCT FROM ROW(NEW.lease_token,NEW.lease_generation,NEW.media_version,NEW.event_id)
  OR job.terminal_at IS NULL OR job.terminal_at<NEW.ready_at
  OR safety.record_sha256<>NEW.safety_record_sha256 OR (safety.evidence_text::jsonb->>'validUntil')::timestamptz<=clock_timestamp()
  OR EXISTS(SELECT 1 FROM platform.media_safety_revocations WHERE job_id=NEW.job_id)
 THEN RAISE EXCEPTION 'Digest readiness requires atomic current checkpoint' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END;
$feedme_digest_checkpoint$;
REVOKE ALL ON FUNCTION platform.require_media_digest_ready_checkpoint() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER media_digest_ready_checkpoint AFTER INSERT ON platform.media_digest_readiness
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.require_media_digest_ready_checkpoint();

-- Replace ONLY the obsolete blanket prohibition, with exact checkpoint admission.
ALTER TABLE platform.media_assets DROP CONSTRAINT media_supabase_no_ready;
CREATE FUNCTION platform.guard_supabase_ready_asset() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_digest_asset$
BEGIN
 IF NEW.storage_protocol='supabaseSignedUploadV1' AND NEW.state='ready' THEN
  IF TG_OP='INSERT' THEN RAISE EXCEPTION 'Media cannot begin ready' USING ERRCODE='23514'; END IF;
  IF OLD.state NOT IN('processing','ready') OR NEW.version<>3 OR NEW.quarantine_version_id IS NOT NULL
   OR NEW.completion_version<>2 OR NEW.rejection_code IS NOT NULL OR NEW.deletion_key IS NOT NULL OR NEW.cleanup_manifest_hash IS NOT NULL THEN
   RAISE EXCEPTION 'Invalid digest-ready media transition' USING ERRCODE='23514'; END IF;
  PERFORM 1 FROM platform.media_digest_readiness d WHERE d.environment=NEW.environment AND d.owner_user_id=NEW.owner_user_id
   AND d.media_id=NEW.id AND d.media_version=NEW.version AND d.manifest_text::jsonb=NEW.derivative_set FOR SHARE NOWAIT;
  IF NOT FOUND THEN RAISE EXCEPTION 'Supabase READY requires retained digest checkpoint' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$feedme_digest_asset$;
REVOKE ALL ON FUNCTION platform.guard_supabase_ready_asset() FROM PUBLIC;
CREATE TRIGGER media_supabase_ready BEFORE INSERT OR UPDATE ON platform.media_assets
 FOR EACH ROW EXECUTE FUNCTION platform.guard_supabase_ready_asset();
