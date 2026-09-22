-- Exact worker-accepted safety history, not a provider, publication grant or rollout.
-- No existing READY/private-stage data is backfilled with invented approval.
CREATE TABLE platform.media_safety_records (
 job_id uuid PRIMARY KEY REFERENCES platform.media_processing_jobs(id),
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 owner_user_id uuid NOT NULL, media_id uuid NOT NULL,
 source_text text NOT NULL CHECK(octet_length(source_text)<=16384 AND jsonb_typeof(source_text::jsonb)='object'),
 source_sha256 char(64) NOT NULL CHECK(source_sha256=encode(sha256(convert_to(source_text,'UTF8')),'hex')),
 policy_revision varchar(80) NOT NULL CHECK(policy_revision ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$'),
 codec_revision varchar(80) NOT NULL CHECK(codec_revision ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$'),
 evidence_text text NOT NULL CHECK(octet_length(evidence_text)<=4096 AND jsonb_typeof(evidence_text::jsonb)='object'),
 evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256=encode(sha256(convert_to(evidence_text,'UTF8')),'hex')),
 outputs_text text NOT NULL CHECK(octet_length(outputs_text)<=32768 AND jsonb_typeof(outputs_text::jsonb)='array'),
 outputs_sha256 char(64) NOT NULL CHECK(outputs_sha256=encode(sha256(convert_to(outputs_text,'UTF8')),'hex')),
 stage varchar(16) NOT NULL CHECK(stage IN ('ready','privateHeld')),
 media_version bigint NOT NULL CHECK(media_version>0),
 record_text text NOT NULL CHECK(octet_length(record_text)<=65536 AND jsonb_typeof(record_text::jsonb)='object'),
 record_sha256 char(64) NOT NULL CHECK(record_sha256=encode(sha256(convert_to(record_text,'UTF8')),'hex')),
 recorded_at timestamptz NOT NULL CHECK(isfinite(recorded_at)),
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id),
 CHECK(record_text::jsonb=jsonb_build_object('formatVersion',1,'jobId',job_id,'environment',environment,
   'ownerId',owner_user_id,'mediaId',media_id,'source',source_text::jsonb,'policyRevision',policy_revision,
   'codecRevision',codec_revision,'evidence',evidence_text::jsonb,'outputs',outputs_text::jsonb,
   'stage',stage,'mediaVersion',media_version))
);
CREATE TABLE platform.media_safety_revocations (
 job_id uuid PRIMARY KEY REFERENCES platform.media_safety_records(job_id),
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 record_sha256 char(64) NOT NULL CHECK(record_sha256 ~ '^[0-9a-f]{64}$'),
 revocation_id uuid NOT NULL, operator_id uuid NOT NULL,
 authorization_reference varchar(256) NOT NULL CHECK(length(btrim(authorization_reference))>0 AND authorization_reference !~ '[[:cntrl:]]'),
 request_text text NOT NULL CHECK(octet_length(request_text)<=8192 AND jsonb_typeof(request_text::jsonb)='object'),
 request_sha256 char(64) NOT NULL CHECK(request_sha256=encode(sha256(convert_to(request_text,'UTF8')),'hex')),
 revoked_at timestamptz NOT NULL CHECK(isfinite(revoked_at)),
 UNIQUE(environment,revocation_id),
 CHECK(request_text::jsonb=jsonb_build_object('formatVersion',1,'environment',environment,'jobId',job_id,
   'recordSha256',record_sha256,'revocationId',revocation_id,'operatorId',operator_id,'authorizationReference',authorization_reference))
);
ALTER TABLE platform.media_safety_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.media_safety_records FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.media_safety_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.media_safety_revocations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE platform.media_safety_records,platform.media_safety_revocations FROM PUBLIC;

CREATE FUNCTION platform.protect_media_safety_original() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Media safety originals are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER media_safety_record_immutable BEFORE UPDATE OR DELETE ON platform.media_safety_records
 FOR EACH ROW EXECUTE FUNCTION platform.protect_media_safety_original();
CREATE TRIGGER media_safety_record_no_truncate BEFORE TRUNCATE ON platform.media_safety_records
 FOR EACH STATEMENT EXECUTE FUNCTION platform.protect_media_safety_original();
CREATE TRIGGER media_safety_revocation_immutable BEFORE UPDATE OR DELETE ON platform.media_safety_revocations
 FOR EACH ROW EXECUTE FUNCTION platform.protect_media_safety_original();
CREATE TRIGGER media_safety_revocation_no_truncate BEFORE TRUNCATE ON platform.media_safety_revocations
 FOR EACH STATEMENT EXECUTE FUNCTION platform.protect_media_safety_original();

CREATE FUNCTION platform.bind_media_safety_record() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE job platform.media_processing_jobs%ROWTYPE; asset platform.media_assets%ROWTYPE;
 source jsonb; proof jsonb; outputs jsonb; output jsonb; derivative platform.media_derivative_intents%ROWTYPE;
 observed integer := 0; previous_variant text := ''; at timestamptz;
BEGIN
 -- Same worker order: asset -> job -> intents -> safety. The worker already owns
 -- these roots; NOWAIT rejects callers attempting to reverse another operation.
 SELECT * INTO STRICT asset FROM platform.media_assets
  WHERE environment=NEW.environment AND owner_user_id=NEW.owner_user_id AND id=NEW.media_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT job FROM platform.media_processing_jobs WHERE id=NEW.job_id FOR SHARE NOWAIT;
 source := NEW.source_text::jsonb; proof := NEW.evidence_text::jsonb; outputs := NEW.outputs_text::jsonb;
 IF ROW(job.environment,job.owner_user_id,job.media_id,job.source,job.policy_revision,job.codec_revision)
    IS DISTINCT FROM ROW(NEW.environment,NEW.owner_user_id,NEW.media_id,source,NEW.policy_revision,NEW.codec_revision)
    OR job.state<>'working' OR job.lease_token IS NULL OR job.terminal_at IS NOT NULL
    OR asset.state<>'processing' OR asset.version<>asset.completion_version
    OR NEW.media_version<>asset.version+(CASE WHEN NEW.stage='ready' THEN 1 ELSE 0 END)
    OR (NEW.stage='ready' AND asset.storage_protocol<>'legacyVersioned')
    OR (NEW.stage='privateHeld' AND asset.storage_protocol<>'supabaseSignedUploadV1')
    OR ROW(source->>'environment',source->>'owner',source->>'media',source->>'draft',
       (source->>'draftGeneration')::bigint,source->>'completion',(source->>'version')::bigint,
       (source->>'acceptedAt')::timestamptz,source->>'key',source->>'objectVersion',source->>'sha256',
       (source->>'bytes')::bigint,source->>'type',(source->>'deadline')::timestamptz,
       COALESCE(source->>'protocol','legacyVersioned'),source->>'bucket')
       IS DISTINCT FROM ROW(asset.environment,asset.owner_user_id::text,asset.id::text,asset.client_draft_id::text,
       asset.draft_generation,asset.completion_key::text,asset.completion_version,asset.completion_accepted_at,
       asset.quarantine_key,asset.quarantine_version_id,asset.expected_sha256::text,asset.expected_bytes,
       asset.content_type,asset.reservation_expires_at,asset.storage_protocol,asset.storage_bucket)
    OR (proof-ARRAY['receiptId','revision','sourceSha256','derivativeSha256','assessedAt','validUntil'])<>'{}'::jsonb
    OR NOT proof ?& ARRAY['receiptId','revision','sourceSha256','derivativeSha256','assessedAt','validUntil']
    OR jsonb_typeof(proof->'derivativeSha256') IS DISTINCT FROM 'object'
    OR ((proof->'derivativeSha256')-ARRAY['display','thumbnail'])<>'{}'::jsonb
    OR NOT (proof->'derivativeSha256') ?& ARRAY['display','thumbnail']
    OR jsonb_typeof(proof->'receiptId') IS DISTINCT FROM 'string' OR jsonb_typeof(proof->'revision') IS DISTINCT FROM 'string'
    OR jsonb_typeof(proof->'sourceSha256') IS DISTINCT FROM 'string'
    OR jsonb_typeof(proof->'assessedAt') IS DISTINCT FROM 'string' OR jsonb_typeof(proof->'validUntil') IS DISTINCT FROM 'string'
    OR proof->>'receiptId' !~ '^[A-Za-z0-9_-]{1,128}$' OR proof->>'revision' !~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}$'
    OR proof->>'sourceSha256' IS DISTINCT FROM asset.expected_sha256::text
    OR jsonb_array_length(outputs)<>2 THEN
  RAISE EXCEPTION 'Safety record requires exact working source and proof' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM platform.media_derivative_intents WHERE job_id=NEW.job_id ORDER BY variant FOR SHARE NOWAIT;
 IF (SELECT count(*) FROM platform.media_derivative_intents WHERE job_id=NEW.job_id)<>2 THEN
  RAISE EXCEPTION 'Safety record requires two exact derivatives' USING ERRCODE='23514';
 END IF;
 FOR output IN SELECT value FROM jsonb_array_elements(outputs) LOOP
  SELECT * INTO STRICT derivative FROM platform.media_derivative_intents WHERE job_id=NEW.job_id AND id=(output->>'id')::uuid;
  IF derivative.variant<=previous_variant OR derivative.cleanup_required OR derivative.acknowledged_at IS NULL
     OR output-ARRAY['id','jobId','variant','key','sha256','bytes','contentType','width','height','acceptanceDeadline',
       'objectVersionId','protocol','bucket','writeAttemptedAt','acknowledgedAt']<>'{}'::jsonb
     OR NOT output ?& ARRAY['id','jobId','variant','key','sha256','bytes','contentType','width','height','acceptanceDeadline',
       'objectVersionId','protocol','bucket','writeAttemptedAt','acknowledgedAt']
     OR ROW(output->>'jobId',output->>'variant',output->>'key',output->>'sha256',(output->>'bytes')::bigint,
       output->>'contentType',(output->>'width')::integer,(output->>'height')::integer,(output->>'acceptanceDeadline')::timestamptz,
       output->>'objectVersionId',output->>'protocol',output->>'bucket',(output->>'writeAttemptedAt')::timestamptz,
       (output->>'acknowledgedAt')::timestamptz)
       IS DISTINCT FROM ROW(derivative.job_id::text,derivative.variant,derivative.object_key,derivative.sha256::text,
       derivative.bytes,derivative.content_type,derivative.width,derivative.height,derivative.acceptance_deadline,
       derivative.object_version_id,derivative.storage_protocol,derivative.storage_bucket,derivative.write_attempted_at,derivative.acknowledged_at)
     OR derivative.storage_protocol<>asset.storage_protocol OR derivative.storage_bucket IS DISTINCT FROM asset.storage_bucket
     OR derivative.object_key<>'derivatives/'||NEW.environment||'/'||NEW.media_id::text||'/'||derivative.id::text
     OR proof->'derivativeSha256'->>derivative.variant IS DISTINCT FROM derivative.sha256::text
     OR (NEW.stage='ready' AND (derivative.object_version_id IS NULL OR derivative.write_attempted_at IS NOT NULL))
     OR (NEW.stage='privateHeld' AND (derivative.object_version_id IS NOT NULL OR derivative.write_attempted_at IS NULL)) THEN
   RAISE EXCEPTION 'Safety record requires exact acknowledged derivatives' USING ERRCODE='23514';
  END IF;
  previous_variant := derivative.variant; observed := observed+1;
 END LOOP;
 at := clock_timestamp();
 IF observed<>2 OR NOT isfinite((proof->>'assessedAt')::timestamptz) OR NOT isfinite((proof->>'validUntil')::timestamptz)
    OR (proof->>'assessedAt')::timestamptz>NEW.recorded_at OR NEW.recorded_at>at
    OR (proof->>'validUntil')::timestamptz<=at OR job.lease_expires_at<=at THEN
  RAISE EXCEPTION 'Safety record requires current accepted evidence and lease' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER media_safety_record_source BEFORE INSERT ON platform.media_safety_records
 FOR EACH ROW EXECUTE FUNCTION platform.bind_media_safety_record();

CREATE FUNCTION platform.require_media_safety_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE job platform.media_processing_jobs%ROWTYPE; asset platform.media_assets%ROWTYPE; at timestamptz;
BEGIN
 SELECT * INTO STRICT asset FROM platform.media_assets
  WHERE environment=NEW.environment AND owner_user_id=NEW.owner_user_id AND id=NEW.media_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT job FROM platform.media_processing_jobs WHERE id=NEW.job_id FOR SHARE NOWAIT;
 IF NEW.stage='ready' THEN
  IF asset.state<>'ready' OR asset.version<>NEW.media_version OR job.state<>'ready'
     OR job.terminal_media_version IS DISTINCT FROM NEW.media_version OR job.terminal_at IS NULL OR job.terminal_event_id IS NULL
     OR asset.derivative_set IS DISTINCT FROM jsonb_build_object('version',1,'variants',
       (SELECT jsonb_agg(jsonb_build_object('variant',value->'variant','key',value->'key','objectVersionId',value->'objectVersionId') ORDER BY value->>'variant')
        FROM jsonb_array_elements(NEW.outputs_text::jsonb))) THEN
   RAISE EXCEPTION 'Safety record requires atomic READY checkpoint' USING ERRCODE='23514';
  END IF;
 ELSE
  PERFORM 1 FROM platform.media_private_materializations WHERE job_id=NEW.job_id FOR SHARE NOWAIT;
  IF NOT FOUND OR asset.state<>'processing' OR asset.version<>NEW.media_version OR job.state<>'quarantined'
     OR job.last_failure_code IS DISTINCT FROM 'PRIVATE_DERIVATIVES_HELD' OR job.lease_token IS NOT NULL OR job.lease_expires_at IS NOT NULL THEN
   RAISE EXCEPTION 'Safety record requires atomic private hold' USING ERRCODE='23514';
  END IF;
 END IF;
 at := clock_timestamp();
 IF (NEW.evidence_text::jsonb->>'assessedAt')::timestamptz>NEW.recorded_at OR NEW.recorded_at>at
    OR (NEW.evidence_text::jsonb->>'validUntil')::timestamptz<=at THEN
  RAISE EXCEPTION 'Safety evidence expired before checkpoint commit' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER media_safety_checkpoint AFTER INSERT ON platform.media_safety_records
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.require_media_safety_checkpoint();

CREATE FUNCTION platform.bind_media_safety_revocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original platform.media_safety_records%ROWTYPE;
BEGIN
 -- Even a direct authorized INSERT shares the same actual record fence as readers.
 SELECT * INTO STRICT original FROM platform.media_safety_records WHERE job_id=NEW.job_id FOR UPDATE NOWAIT;
 IF ROW(NEW.environment,NEW.record_sha256) IS DISTINCT FROM ROW(original.environment,original.record_sha256)
    OR NEW.revoked_at<original.recorded_at OR NEW.revoked_at>clock_timestamp() THEN
  RAISE EXCEPTION 'Revocation requires original exact media safety record' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER media_safety_revocation_source BEFORE INSERT ON platform.media_safety_revocations
 FOR EACH ROW EXECUTE FUNCTION platform.bind_media_safety_revocation();
