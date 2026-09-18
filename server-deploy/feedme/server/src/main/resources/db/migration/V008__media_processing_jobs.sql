-- Configured photo processing only. No default worker, provider, safety verdict or publication.
CREATE TABLE platform.media_processing_jobs (
 id uuid PRIMARY KEY, environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, media_id uuid NOT NULL,
 source jsonb NOT NULL CHECK(jsonb_typeof(source)='object' AND octet_length(source::text)<=16384),
 policy_revision varchar(80) NOT NULL, codec_revision varchar(80) NOT NULL,
 state varchar(20) NOT NULL CHECK(state IN ('queued','working','retry','quarantined','ready','rejected','cancelled')),
 lease_token uuid NULL, lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation>=0),
 lease_expires_at timestamptz NULL, attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 available_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
 last_failure_code varchar(80) NULL,
 terminal_token uuid NULL, terminal_generation bigint NULL, terminal_media_version bigint NULL,
 terminal_event_id uuid NULL, terminal_at timestamptz NULL,
 UNIQUE(environment,owner_user_id,media_id),
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id),
 CHECK((state='working')=(lease_token IS NOT NULL)), CHECK((lease_token IS NULL)=(lease_expires_at IS NULL)),
 CHECK((state IN ('ready','rejected','cancelled'))=(terminal_at IS NOT NULL)),
 CHECK(terminal_media_version IS NULL OR terminal_media_version>0)
);
CREATE INDEX media_processing_available ON platform.media_processing_jobs(environment,state,available_at,id);
-- Separate from ConsumerInbox: exact normalized payload fingerprint is checked even on duplicates.
CREATE TABLE platform.media_processing_inbox (
 event_id uuid PRIMARY KEY, event_sha256 char(64) NOT NULL CHECK(event_sha256 ~ '^[0-9a-f]{64}$'),
 job_id uuid NOT NULL REFERENCES platform.media_processing_jobs(id), accepted_at timestamptz NOT NULL
);
CREATE TABLE platform.media_derivative_intents (
 id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES platform.media_processing_jobs(id),
 variant varchar(12) NOT NULL CHECK(variant IN ('thumbnail','display')),
 object_key varchar(200) NOT NULL UNIQUE,
 sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'), bytes bigint NOT NULL CHECK(bytes BETWEEN 1 AND 10000000),
 content_type varchar(40) NOT NULL CHECK(content_type IN ('image/png','image/jpeg')),
 width integer NOT NULL CHECK(width>0), height integer NOT NULL CHECK(height>0),
 acceptance_deadline timestamptz NOT NULL, created_at timestamptz NOT NULL,
 object_version_id text NULL CHECK(octet_length(object_version_id) BETWEEN 1 AND 4096),
 acknowledged_at timestamptz NULL, cleanup_required boolean NOT NULL DEFAULT false,
 UNIQUE(job_id,variant), CHECK(acceptance_deadline>created_at),
 CHECK((object_version_id IS NULL)=(acknowledged_at IS NULL))
);
-- Independent exact-object cleanup: NEVER modifies V007's immutable delete manifest receipt.
-- not_before is the last possible source POST/backend write acceptance, not merely worker lease expiry.
CREATE TABLE platform.media_processing_cleanup (
 id uuid PRIMARY KEY, environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, media_id uuid NOT NULL,
 object_key varchar(200) NOT NULL UNIQUE, derivative_intent_id uuid NULL REFERENCES platform.media_derivative_intents(id),
 not_before timestamptz NOT NULL, available_at timestamptz NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('pending','working','done','quarantined')),
 lease_token uuid NULL, lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation>=0),
 lease_expires_at timestamptz NULL, attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 settled_versions jsonb NULL CHECK(jsonb_typeof(settled_versions)='array' AND octet_length(settled_versions::text)<=1048576),
 completed_at timestamptz NULL,
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id),
 CHECK((state='working')=(lease_token IS NOT NULL)), CHECK((lease_token IS NULL)=(lease_expires_at IS NULL)),
 CHECK((state='done')=(completed_at IS NOT NULL)), CHECK(completed_at IS NULL OR completed_at>=not_before)
);
CREATE INDEX media_processing_cleanup_available ON platform.media_processing_cleanup(environment,state,available_at,id);
CREATE FUNCTION platform.protect_media_processing_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.environment,NEW.owner_user_id,NEW.media_id,NEW.source,NEW.policy_revision,NEW.codec_revision,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.environment,OLD.owner_user_id,OLD.media_id,OLD.source,OLD.policy_revision,OLD.codec_revision,OLD.created_at)
    OR NEW.lease_generation<OLD.lease_generation OR NEW.attempts<OLD.attempts
    OR (OLD.terminal_at IS NOT NULL AND ROW(NEW.state,NEW.terminal_token,NEW.terminal_generation,NEW.terminal_media_version,NEW.terminal_event_id,NEW.terminal_at)
       IS DISTINCT FROM ROW(OLD.state,OLD.terminal_token,OLD.terminal_generation,OLD.terminal_media_version,OLD.terminal_event_id,OLD.terminal_at)) THEN
  RAISE EXCEPTION 'Immutable media processing lineage' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER media_processing_identity BEFORE UPDATE ON platform.media_processing_jobs FOR EACH ROW EXECUTE FUNCTION platform.protect_media_processing_identity();
CREATE FUNCTION platform.protect_media_derivative_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.job_id,NEW.variant,NEW.object_key,NEW.sha256,NEW.bytes,NEW.content_type,NEW.width,NEW.height,NEW.acceptance_deadline,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.job_id,OLD.variant,OLD.object_key,OLD.sha256,OLD.bytes,OLD.content_type,OLD.width,OLD.height,OLD.acceptance_deadline,OLD.created_at)
    OR (OLD.object_version_id IS NOT NULL AND ROW(NEW.object_version_id,NEW.acknowledged_at) IS DISTINCT FROM ROW(OLD.object_version_id,OLD.acknowledged_at))
    OR (OLD.cleanup_required AND NOT NEW.cleanup_required) THEN
  RAISE EXCEPTION 'Immutable media derivative intent' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER media_derivative_identity BEFORE UPDATE ON platform.media_derivative_intents FOR EACH ROW EXECUTE FUNCTION platform.protect_media_derivative_intent();
CREATE FUNCTION platform.protect_media_processing_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.environment,NEW.owner_user_id,NEW.media_id,NEW.object_key,NEW.derivative_intent_id,NEW.not_before)
    IS DISTINCT FROM ROW(OLD.id,OLD.environment,OLD.owner_user_id,OLD.media_id,OLD.object_key,OLD.derivative_intent_id,OLD.not_before)
    OR NEW.lease_generation<OLD.lease_generation OR NEW.attempts<OLD.attempts
    OR (OLD.settled_versions IS NOT NULL AND NEW.settled_versions IS DISTINCT FROM OLD.settled_versions)
    OR (OLD.completed_at IS NOT NULL AND ROW(NEW.state,NEW.completed_at) IS DISTINCT FROM ROW(OLD.state,OLD.completed_at)) THEN
  RAISE EXCEPTION 'Immutable exact media cleanup target' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER media_processing_cleanup_identity BEFORE UPDATE ON platform.media_processing_cleanup FOR EACH ROW EXECUTE FUNCTION platform.protect_media_processing_cleanup();
