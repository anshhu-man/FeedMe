-- Private digest-bound materialization only. V027's Supabase READY and cleanup-completion
-- prohibitions remain unchanged. A deadline cannot prove that a remote late write is impossible.
ALTER TABLE platform.media_derivative_intents
 ADD COLUMN storage_protocol varchar(32) NOT NULL DEFAULT 'legacyVersioned',
 ADD COLUMN storage_bucket varchar(100) NULL,
 ADD COLUMN write_attempted_at timestamptz NULL;

-- Preserve the old version/ack equivalence in the legacy branch, without inventing a
-- provider version for a Supabase object. Resolve exactly the old two-column CHECK.
DO $$ DECLARE target record; removed integer := 0; version_column smallint; ack_column smallint;
BEGIN
 SELECT attnum INTO STRICT version_column FROM pg_attribute WHERE attrelid='platform.media_derivative_intents'::regclass AND attname='object_version_id';
 SELECT attnum INTO STRICT ack_column FROM pg_attribute WHERE attrelid='platform.media_derivative_intents'::regclass AND attname='acknowledged_at';
 FOR target IN SELECT conname FROM pg_constraint WHERE conrelid='platform.media_derivative_intents'::regclass AND contype='c'
   AND cardinality(conkey)=2 AND conkey @> ARRAY[version_column,ack_column]::smallint[]
 LOOP
   EXECUTE format('ALTER TABLE platform.media_derivative_intents DROP CONSTRAINT %I',target.conname);
   removed := removed+1;
 END LOOP;
 IF removed<>1 THEN RAISE EXCEPTION 'Unexpected derivative acknowledgement schema' USING ERRCODE='23514'; END IF;
END $$;
ALTER TABLE platform.media_derivative_intents ADD CONSTRAINT media_derivative_storage_mode CHECK (
 (storage_protocol='legacyVersioned' AND storage_bucket IS NULL AND write_attempted_at IS NULL AND
  ((object_version_id IS NULL)=(acknowledged_at IS NULL))) OR
 (storage_protocol='supabaseSignedUploadV1' AND storage_bucket IS NOT NULL AND storage_bucket ~ '^[a-z0-9][a-z0-9_-]{0,62}$' AND
  object_version_id IS NULL AND isfinite(created_at) AND isfinite(acceptance_deadline) AND
  (write_attempted_at IS NULL OR (isfinite(write_attempted_at) AND write_attempted_at>=created_at AND write_attempted_at<acceptance_deadline)) AND
  (acknowledged_at IS NULL OR (write_attempted_at IS NOT NULL AND isfinite(acknowledged_at) AND acknowledged_at>=write_attempted_at))));

CREATE FUNCTION platform.protect_media_derivative_storage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source record;
BEGIN
 IF TG_OP='TRUNCATE' THEN
   IF EXISTS(SELECT 1 FROM platform.media_derivative_intents WHERE storage_protocol='supabaseSignedUploadV1') THEN
     RAISE EXCEPTION 'Retained derivative attempt' USING ERRCODE='23514';
   END IF;
   RETURN NULL;
 END IF;
 IF TG_OP='DELETE' THEN
   IF OLD.storage_protocol='supabaseSignedUploadV1' THEN RAISE EXCEPTION 'Retained derivative attempt' USING ERRCODE='23514'; END IF;
   RETURN OLD;
 END IF;
 IF TG_OP='UPDATE' THEN
   -- No parent locks here: intent updates must never reverse media -> job -> intent order.
   IF ROW(NEW.storage_protocol,NEW.storage_bucket) IS DISTINCT FROM ROW(OLD.storage_protocol,OLD.storage_bucket) OR
      (OLD.write_attempted_at IS NOT NULL AND NEW.write_attempted_at IS DISTINCT FROM OLD.write_attempted_at) OR
      (OLD.acknowledged_at IS NOT NULL AND NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at) THEN
     RAISE EXCEPTION 'Immutable derivative storage evidence' USING ERRCODE='23514';
   END IF;
   RETURN NEW;
 END IF;
 SELECT j.environment,j.media_id,j.source,m.storage_protocol,m.storage_bucket INTO STRICT source
 FROM platform.media_processing_jobs j JOIN platform.media_assets m
 ON m.environment=j.environment AND m.owner_user_id=j.owner_user_id AND m.id=j.media_id
 WHERE j.id=NEW.job_id FOR SHARE OF j,m;
 IF ROW(NEW.storage_protocol,NEW.storage_bucket) IS DISTINCT FROM ROW(source.storage_protocol,source.storage_bucket) OR
    ROW(COALESCE(source.source->>'protocol','legacyVersioned'),source.source->>'bucket')
      IS DISTINCT FROM ROW(source.storage_protocol,source.storage_bucket) THEN
   RAISE EXCEPTION 'Derivative requires exact source mode' USING ERRCODE='23514';
 END IF;
 IF NEW.storage_protocol='supabaseSignedUploadV1' AND
   (NEW.write_attempted_at IS NOT NULL OR NEW.acknowledged_at IS NOT NULL OR
    NEW.object_key<>'derivatives/'||source.environment||'/'||source.media_id::text||'/'||NEW.id::text) THEN
   RAISE EXCEPTION 'Derivative requires original private destination' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER media_derivative_storage BEFORE INSERT OR UPDATE OR DELETE ON platform.media_derivative_intents
 FOR EACH ROW EXECUTE FUNCTION platform.protect_media_derivative_storage();
CREATE TRIGGER media_derivative_storage_no_truncate BEFORE TRUNCATE ON platform.media_derivative_intents
 FOR EACH STATEMENT EXECUTE FUNCTION platform.protect_media_derivative_storage();

-- This is a historical private-stage checkpoint, not a READY event or available/public asset.
CREATE TABLE platform.media_private_materializations (
 job_id uuid PRIMARY KEY REFERENCES platform.media_processing_jobs(id),
 lease_token uuid NOT NULL, lease_generation bigint NOT NULL CHECK(lease_generation>0),
 attempt integer NOT NULL CHECK(attempt>0), acknowledged_at timestamptz NOT NULL CHECK(isfinite(acknowledged_at))
);
CREATE FUNCTION platform.protect_media_private_materialization() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE job platform.media_processing_jobs%ROWTYPE; total integer; accepted integer;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Immutable private materialization checkpoint' USING ERRCODE='23514'; END IF;
 SELECT * INTO STRICT job FROM platform.media_processing_jobs WHERE id=NEW.job_id FOR SHARE;
 IF job.state<>'working' OR job.source->>'protocol' IS DISTINCT FROM 'supabaseSignedUploadV1' OR
    ROW(job.lease_token,job.lease_generation,job.attempts) IS DISTINCT FROM ROW(NEW.lease_token,NEW.lease_generation,NEW.attempt) OR
    job.lease_expires_at<=NEW.acknowledged_at OR NEW.acknowledged_at<job.created_at THEN
   RAISE EXCEPTION 'Private materialization requires exact working lease' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM platform.media_derivative_intents WHERE job_id=NEW.job_id ORDER BY variant FOR SHARE;
 SELECT count(*),count(*) FILTER (WHERE storage_protocol='supabaseSignedUploadV1' AND storage_bucket=job.source->>'bucket'
   AND write_attempted_at IS NOT NULL AND acknowledged_at IS NOT NULL AND acknowledged_at<=NEW.acknowledged_at
   AND object_version_id IS NULL AND NOT cleanup_required) INTO total,accepted
 FROM platform.media_derivative_intents WHERE job_id=NEW.job_id;
 IF total<>2 OR accepted<>2 THEN RAISE EXCEPTION 'Private materialization requires exact digest acknowledgements' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER media_private_materialization_immutable BEFORE INSERT OR UPDATE OR DELETE ON platform.media_private_materializations
 FOR EACH ROW EXECUTE FUNCTION platform.protect_media_private_materialization();
CREATE TRIGGER media_private_materialization_no_truncate BEFORE TRUNCATE ON platform.media_private_materializations
 FOR EACH STATEMENT EXECUTE FUNCTION platform.protect_media_private_materialization();

CREATE FUNCTION platform.require_media_private_materialization_hold() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM platform.media_processing_jobs WHERE id=NEW.job_id AND state='quarantined'
   AND last_failure_code='PRIVATE_DERIVATIVES_HELD' AND lease_token IS NULL AND lease_expires_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Private checkpoint requires atomic operational hold' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER media_private_materialization_held AFTER INSERT ON platform.media_private_materializations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.require_media_private_materialization_hold();
