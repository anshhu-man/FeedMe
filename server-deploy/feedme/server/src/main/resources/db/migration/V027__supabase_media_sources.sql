-- Additive source-reference mode. Existing reservations, receipts, events and job JSON stay legacy.
ALTER TABLE platform.media_assets
 ADD COLUMN storage_protocol varchar(32) NOT NULL DEFAULT 'legacyVersioned',
 ADD COLUMN storage_bucket varchar(100) NULL,
 ADD CONSTRAINT media_storage_protocol CHECK (
   (storage_protocol='legacyVersioned' AND storage_bucket IS NULL) OR
   (storage_protocol='supabaseSignedUploadV1' AND storage_bucket IS NOT NULL AND storage_bucket ~ '^[a-z0-9][a-z0-9_-]{0,62}$'));

-- V007's three cross-column version-reference checks cannot describe a digest-only source.
-- Resolve their generated names by their exact referenced column sets, never alter old SQL bytes.
DO $$ DECLARE target record; removed integer := 0; version_column smallint; state_column smallint; completion_column smallint;
BEGIN
 SELECT attnum INTO STRICT version_column FROM pg_attribute WHERE attrelid='platform.media_assets'::regclass AND attname='quarantine_version_id';
 SELECT attnum INTO STRICT state_column FROM pg_attribute WHERE attrelid='platform.media_assets'::regclass AND attname='state';
 SELECT attnum INTO STRICT completion_column FROM pg_attribute WHERE attrelid='platform.media_assets'::regclass AND attname='completion_key';
 FOR target IN SELECT conname FROM pg_constraint WHERE conrelid='platform.media_assets'::regclass AND contype='c'
   AND cardinality(conkey)=2 AND conkey @> ARRAY[version_column]::smallint[]
   AND (conkey @> ARRAY[state_column]::smallint[] OR conkey @> ARRAY[completion_column]::smallint[])
 LOOP
   EXECUTE format('ALTER TABLE platform.media_assets DROP CONSTRAINT %I',target.conname);
   removed := removed+1;
 END LOOP;
 IF removed<>3 THEN RAISE EXCEPTION 'Unexpected media reference schema' USING ERRCODE='23514'; END IF;
END $$;
ALTER TABLE platform.media_assets ADD CONSTRAINT media_source_completion CHECK (
 (state<>'awaitingUpload' OR (quarantine_version_id IS NULL AND completion_key IS NULL)) AND
 (state NOT IN ('processing','ready','rejected') OR completion_key IS NOT NULL) AND
 ((storage_protocol='legacyVersioned' AND ((quarantine_version_id IS NOT NULL)=(completion_key IS NOT NULL))) OR
  (storage_protocol='supabaseSignedUploadV1' AND quarantine_version_id IS NULL)));
-- No Supabase derivative/safety adapter exists in this increment.
ALTER TABLE platform.media_assets ADD CONSTRAINT media_supabase_no_ready
 CHECK (storage_protocol<>'supabaseSignedUploadV1' OR state<>'ready');
CREATE FUNCTION platform.protect_media_source_mode() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.storage_protocol,NEW.storage_bucket) IS DISTINCT FROM ROW(OLD.storage_protocol,OLD.storage_bucket) THEN
   RAISE EXCEPTION 'Immutable media source mode' USING ERRCODE='23514';
 END IF;
 IF OLD.state='awaitingUpload' AND NEW.state='processing' AND NEW.storage_protocol='supabaseSignedUploadV1' THEN
   PERFORM 1 FROM platform.media_upload_issuances WHERE environment=NEW.environment AND owner_user_id=NEW.owner_user_id AND media_id=NEW.id FOR SHARE;
   IF NOT FOUND THEN RAISE EXCEPTION 'Completion requires retained issuance intent' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER media_source_mode_immutable BEFORE UPDATE ON platform.media_assets FOR EACH ROW EXECUTE FUNCTION platform.protect_media_source_mode();

-- One durable attempt BEFORE external minting. No URL, bearer token or signed fields are stored.
-- Uncertain mint/ack means this row remains; replay never creates another capability.
CREATE TABLE platform.media_upload_issuances (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, media_id uuid NOT NULL,
 requested_expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
 acknowledged_expires_at timestamptz NULL,
 PRIMARY KEY(environment,owner_user_id,media_id),
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id),
 CHECK(requested_expires_at>created_at),
 CHECK(acknowledged_expires_at IS NULL OR (acknowledged_expires_at>created_at AND acknowledged_expires_at<=requested_expires_at))
);
CREATE FUNCTION platform.protect_media_upload_issuance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE media platform.media_assets%ROWTYPE;
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Immutable media issuance' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.owner_user_id,NEW.media_id,NEW.requested_expires_at,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.environment,OLD.owner_user_id,OLD.media_id,OLD.requested_expires_at,OLD.created_at)
       OR OLD.acknowledged_expires_at IS NOT NULL OR NEW.acknowledged_expires_at IS NULL) THEN
   RAISE EXCEPTION 'Immutable media issuance' USING ERRCODE='23514';
 END IF;
 SELECT * INTO STRICT media FROM platform.media_assets WHERE environment=NEW.environment AND owner_user_id=NEW.owner_user_id AND id=NEW.media_id FOR SHARE;
 IF media.storage_protocol<>'supabaseSignedUploadV1' OR media.state<>'awaitingUpload' OR media.version<>1
    OR NEW.requested_expires_at>media.reservation_expires_at OR NEW.created_at<media.created_at THEN
   RAISE EXCEPTION 'Media issuance requires original live reservation' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER media_issuance_immutable BEFORE INSERT OR UPDATE OR DELETE ON platform.media_upload_issuances FOR EACH ROW EXECUTE FUNCTION platform.protect_media_upload_issuance();
CREATE TRIGGER media_issuance_no_truncate BEFORE TRUNCATE ON platform.media_upload_issuances FOR EACH STATEMENT EXECUTE FUNCTION platform.protect_media_upload_issuance();

-- Keep digest-mode cleanup explicit and blocked; never call legacy version settlement/deletion.
ALTER TABLE platform.media_cleanup_jobs ADD COLUMN storage_protocol varchar(32) NOT NULL DEFAULT 'legacyVersioned', ADD COLUMN storage_bucket varchar(100) NULL;
ALTER TABLE platform.media_processing_cleanup ADD COLUMN storage_protocol varchar(32) NOT NULL DEFAULT 'legacyVersioned', ADD COLUMN storage_bucket varchar(100) NULL;
CREATE FUNCTION platform.bind_media_cleanup_mode() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE media platform.media_assets%ROWTYPE;
BEGIN
 -- Claim/update owns the cleanup row first. Never invert lifecycle -> media -> cleanup locks.
 IF TG_OP='UPDATE' THEN
   IF ROW(NEW.environment,NEW.owner_user_id,NEW.media_id,NEW.storage_protocol,NEW.storage_bucket)
      IS DISTINCT FROM ROW(OLD.environment,OLD.owner_user_id,OLD.media_id,OLD.storage_protocol,OLD.storage_bucket)
      OR (NEW.storage_protocol='supabaseSignedUploadV1' AND NEW.completed_at IS NOT NULL) THEN
     RAISE EXCEPTION 'Immutable unsupported cleanup source mode' USING ERRCODE='23514';
   END IF;
   RETURN NEW;
 END IF;
 SELECT * INTO STRICT media FROM platform.media_assets WHERE environment=NEW.environment AND owner_user_id=NEW.owner_user_id AND id=NEW.media_id FOR SHARE;
 IF ROW(NEW.storage_protocol,NEW.storage_bucket) IS DISTINCT FROM ROW(media.storage_protocol,media.storage_bucket)
    OR (NEW.storage_protocol='supabaseSignedUploadV1' AND NEW.completed_at IS NOT NULL) THEN
   RAISE EXCEPTION 'Cleanup requires exact supported source mode' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER media_cleanup_mode BEFORE INSERT OR UPDATE ON platform.media_cleanup_jobs FOR EACH ROW EXECUTE FUNCTION platform.bind_media_cleanup_mode();
CREATE TRIGGER media_processing_cleanup_mode BEFORE INSERT OR UPDATE ON platform.media_processing_cleanup FOR EACH ROW EXECUTE FUNCTION platform.bind_media_cleanup_mode();
