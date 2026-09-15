-- Owner-only photo quarantine. No public origin, permissive identity, implicit social draft or READY worker.
CREATE TABLE platform.media_draft_lifecycles (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 owner_user_id uuid NOT NULL, client_draft_id uuid NOT NULL, generation bigint NOT NULL CHECK(generation>0),
 created_at timestamptz NOT NULL,
 PRIMARY KEY(environment,owner_user_id,client_draft_id)
);
CREATE TABLE platform.media_assets (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, id uuid NOT NULL,
 client_draft_id uuid NOT NULL, draft_generation bigint NOT NULL CHECK(draft_generation>0),
 kind varchar(10) NOT NULL CHECK(kind='photo'), state varchar(20) NOT NULL CHECK(state IN ('awaitingUpload','processing','ready','rejected','deleted')),
 version bigint NOT NULL CHECK(version>0), quarantine_key varchar(200) NOT NULL UNIQUE,
 expected_sha256 char(64) NOT NULL CHECK(expected_sha256 ~ '^[0-9a-f]{64}$'),
 expected_bytes bigint NOT NULL CHECK(expected_bytes BETWEEN 1 AND 10000000),
 content_type varchar(40) NOT NULL CHECK(content_type IN ('image/jpeg','image/png','image/heic')),
 reservation_expires_at timestamptz NOT NULL, quarantine_version_id text NULL CHECK(octet_length(quarantine_version_id)<=4096),
 -- Immutable original processing acknowledgement. Worker status/version changes never rewrite it.
 completion_key uuid NULL, completion_version bigint NULL, completion_accepted_at timestamptz NULL,
 derivative_set jsonb NULL CHECK(jsonb_typeof(derivative_set)='object' AND octet_length(derivative_set::text)<=65536),
 rejection_code varchar(80) NULL, deletion_key uuid NULL, cleanup_manifest_hash char(64) NULL CHECK(cleanup_manifest_hash ~ '^[0-9a-f]{64}$'), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,owner_user_id,id),
 FOREIGN KEY(environment,owner_user_id,client_draft_id) REFERENCES platform.media_draft_lifecycles(environment,owner_user_id,client_draft_id),
 CHECK(reservation_expires_at>created_at AND updated_at>=created_at),
 CHECK((state='awaitingUpload' AND quarantine_version_id IS NULL) OR state IN ('processing','ready','rejected','deleted')),
 CHECK(state NOT IN ('processing','ready','rejected') OR quarantine_version_id IS NOT NULL),
 CHECK((completion_key IS NULL AND completion_version IS NULL AND completion_accepted_at IS NULL) OR
       (completion_key IS NOT NULL AND completion_version IS NOT NULL AND completion_version=2 AND completion_accepted_at IS NOT NULL AND
        completion_version<=version AND completion_accepted_at>=created_at AND
        completion_accepted_at<=updated_at AND completion_accepted_at<reservation_expires_at)),
 CHECK((quarantine_version_id IS NOT NULL)=(completion_key IS NOT NULL)),
 CHECK((state='ready')=(derivative_set IS NOT NULL)),
 CHECK((state='rejected')=(rejection_code IS NOT NULL)),
 CHECK((state='deleted')=(deletion_key IS NOT NULL)),
 CHECK((state='deleted')=(cleanup_manifest_hash IS NOT NULL))
);
CREATE INDEX owned_media_draft ON platform.media_assets(environment,owner_user_id,client_draft_id,id);
CREATE INDEX pending_media_expiry ON platform.media_assets(state,reservation_expires_at,id);
-- Purpose-fixed durable cleanup, NOT a new public event or an assertion that any object was removed.
-- The worker must inspect exact key versions again after final_sweep_after: a previously issued POST
-- can create another version until that original deadline. It must never sweep an owner/bucket prefix.
CREATE TABLE platform.media_cleanup_jobs (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, media_id uuid NOT NULL,
 media_version bigint NOT NULL CHECK(media_version>0), quarantine_key varchar(200) NOT NULL,
 known_version_id text NULL CHECK(octet_length(known_version_id)<=4096),
 derivative_set jsonb NULL CHECK(jsonb_typeof(derivative_set)='object' AND octet_length(derivative_set::text)<=65536),
 manifest_hash char(64) NOT NULL CHECK(manifest_hash ~ '^[0-9a-f]{64}$'),
 final_sweep_after timestamptz NOT NULL, available_at timestamptz NOT NULL,
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0), completed_at timestamptz NULL,
 PRIMARY KEY(environment,owner_user_id,media_id),
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id),
 CHECK(completed_at IS NULL OR completed_at>=final_sweep_after)
);
CREATE FUNCTION platform.protect_media_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.environment,NEW.owner_user_id,NEW.id,NEW.client_draft_id,NEW.draft_generation,NEW.kind,
        NEW.quarantine_key,NEW.expected_sha256,NEW.expected_bytes,NEW.content_type,NEW.reservation_expires_at,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.environment,OLD.owner_user_id,OLD.id,OLD.client_draft_id,OLD.draft_generation,OLD.kind,
        OLD.quarantine_key,OLD.expected_sha256,OLD.expected_bytes,OLD.content_type,OLD.reservation_expires_at,OLD.created_at)
    OR NEW.version<>OLD.version+1 OR OLD.state='deleted'
    OR NOT ((OLD.state='awaitingUpload' AND NEW.state='processing') OR
            (OLD.state='processing' AND NEW.state IN ('ready','rejected')) OR NEW.state='deleted')
    OR (NOT (OLD.state='awaitingUpload' AND NEW.state='processing') AND NEW.quarantine_version_id IS DISTINCT FROM OLD.quarantine_version_id)
    OR ((OLD.state='awaitingUpload' AND NEW.state='processing') AND
        (NEW.completion_key IS NULL OR NEW.completion_version IS DISTINCT FROM NEW.version OR
         NEW.completion_accepted_at IS DISTINCT FROM NEW.updated_at))
    OR (NOT (OLD.state='awaitingUpload' AND NEW.state='processing') AND
        ROW(NEW.completion_key,NEW.completion_version,NEW.completion_accepted_at)
        IS DISTINCT FROM ROW(OLD.completion_key,OLD.completion_version,OLD.completion_accepted_at)) THEN
   RAISE EXCEPTION 'Media transition requires exact immutable reservation' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER media_reservation_immutable BEFORE UPDATE ON platform.media_assets FOR EACH ROW EXECUTE FUNCTION platform.protect_media_reservation();
