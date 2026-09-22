-- Private JSON-only export jobs and encrypted upload originals. No provider grant,
-- worker activation, download capability, retention exception or physical settlement.
CREATE TABLE platform.account_export_jobs (
 environment varchar(40) NOT NULL, id uuid NOT NULL, account_id uuid NOT NULL, principal_id uuid NOT NULL,
 issuer text NOT NULL, provider_subject uuid NOT NULL, device_session_id uuid NOT NULL, provider_session_id uuid NOT NULL,
 command_key uuid NOT NULL, request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 policy_revision varchar(128) NOT NULL, format text NOT NULL CHECK(format='json'), include_media boolean NOT NULL CHECK(NOT include_media),
 state text NOT NULL DEFAULT 'pending' CHECK(state IN('pending','running','complete','failed','expired')),
 failure_code text CHECK(failure_code IN('EXPORT_TOO_LARGE','EXPORT_SOURCE_UNAVAILABLE','EXPORT_OBJECT_MISMATCH')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), expires_at timestamptz NOT NULL,
 retry_after timestamptz NOT NULL DEFAULT clock_timestamp(), lease_token uuid, lease_generation bigint NOT NULL DEFAULT 0 CHECK(lease_generation>=0), lease_until timestamptz,
 PRIMARY KEY(environment,id), UNIQUE(environment,account_id,command_key), UNIQUE(environment,id,account_id,principal_id),
 FOREIGN KEY(environment,account_id) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,principal_id) REFERENCES identity.principals(environment,id),
 CHECK((lease_token IS NULL)=(lease_until IS NULL)),
 CHECK(lease_token IS NULL OR state='running' AND lease_generation>0),
 CHECK((state='failed' AND failure_code IS NOT NULL) OR (state='expired') OR (state NOT IN('failed','expired') AND failure_code IS NULL)),
 CHECK(isfinite(created_at) AND isfinite(updated_at) AND isfinite(expires_at) AND isfinite(retry_after)
   AND expires_at>created_at AND expires_at<=created_at+interval '24 hours' AND updated_at>=created_at)
);
CREATE UNIQUE INDEX account_export_one_active ON platform.account_export_jobs(environment,account_id)
 WHERE state IN('pending','running','complete');
CREATE TABLE platform.account_export_artifacts (
 environment varchar(40) NOT NULL, job_id uuid NOT NULL, account_id uuid NOT NULL, principal_id uuid NOT NULL,
 artifact_id uuid NOT NULL, bucket varchar(63) NOT NULL, object_key text NOT NULL, key_id varchar(128) NOT NULL,
 wrapped_key bytea, nonce bytea NOT NULL CHECK(octet_length(nonce)=12), ciphertext bytea,
 plaintext_sha256 char(64) NOT NULL CHECK(plaintext_sha256 ~ '^[0-9a-f]{64}$'),
 plaintext_bytes integer NOT NULL CHECK(plaintext_bytes BETWEEN 1 AND 4194304),
 cipher_sha256 char(64) NOT NULL CHECK(cipher_sha256 ~ '^[0-9a-f]{64}$'),
 cipher_bytes integer NOT NULL CHECK(cipher_bytes=plaintext_bytes+16),
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 write_attempted_at timestamptz, dispatch_token uuid, dispatch_generation bigint,
 verified_at timestamptz, expired_at timestamptz, cleanup_attempted_at timestamptz,
 PRIMARY KEY(environment,job_id), UNIQUE(environment,artifact_id), UNIQUE(bucket,object_key),
 FOREIGN KEY(environment,job_id,account_id,principal_id) REFERENCES platform.account_export_jobs(environment,id,account_id,principal_id),
 CHECK(bucket ~ '^[a-z0-9][a-z0-9_-]{0,62}$' AND key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
 CHECK(object_key='exports/'||environment||'/'||job_id::text||'/'||artifact_id::text),
 CHECK((expired_at IS NULL AND octet_length(wrapped_key)=60 AND wrapped_key IS NOT NULL AND ciphertext IS NOT NULL
     AND octet_length(ciphertext)=cipher_bytes AND encode(sha256(ciphertext),'hex')=cipher_sha256)
   OR (expired_at IS NOT NULL AND wrapped_key IS NULL AND ciphertext IS NULL)),
 CHECK((write_attempted_at IS NULL AND dispatch_token IS NULL AND dispatch_generation IS NULL)
   OR (write_attempted_at IS NOT NULL AND dispatch_token IS NOT NULL AND dispatch_generation>0)),
 CHECK(verified_at IS NULL OR write_attempted_at IS NOT NULL AND verified_at>=write_attempted_at),
 CHECK(isfinite(created_at) AND isfinite(expires_at) AND expires_at>created_at)
);
ALTER TABLE platform.account_export_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.account_export_jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.account_export_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.account_export_artifacts FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.account_export_jobs,platform.account_export_artifacts FROM PUBLIC;

CREATE FUNCTION platform.guard_account_export_jobs() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $feedme_export_jobs$
BEGIN
 IF TG_OP IN('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Export history requires explicit erasure' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF ROW(NEW.environment,NEW.id,NEW.account_id,NEW.principal_id,NEW.issuer,NEW.provider_subject,NEW.device_session_id,
   NEW.provider_session_id,NEW.command_key,NEW.request_sha256,NEW.policy_revision,NEW.format,NEW.include_media,NEW.created_at,NEW.expires_at)
   IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.account_id,OLD.principal_id,OLD.issuer,OLD.provider_subject,OLD.device_session_id,
   OLD.provider_session_id,OLD.command_key,OLD.request_sha256,OLD.policy_revision,OLD.format,OLD.include_media,OLD.created_at,OLD.expires_at)
   OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at OR NEW.lease_generation<OLD.lease_generation
   OR (OLD.state IN('complete','failed','expired') AND NEW.state NOT IN(OLD.state,'expired'))
   OR (OLD.failure_code IS NOT NULL AND NEW.failure_code IS DISTINCT FROM OLD.failure_code)
   OR (NEW.lease_token IS DISTINCT FROM OLD.lease_token AND NEW.lease_token IS NOT NULL AND NEW.lease_generation<>OLD.lease_generation+1)
   OR (NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token AND NEW.lease_generation<>OLD.lease_generation) THEN
   RAISE EXCEPTION 'Export original or transition differs' USING ERRCODE='23514';
  END IF;
 ELSE
  IF NEW.version<>1 OR NEW.state<>'pending' OR NEW.lease_token IS NOT NULL OR NEW.lease_generation<>0 THEN
   RAISE EXCEPTION 'Export must begin pending' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.state NOT IN('expired','failed') THEN
  PERFORM 1 FROM identity.users u JOIN identity.principals p ON p.environment=u.environment AND p.user_id=u.id
   WHERE u.environment=NEW.environment AND u.id=NEW.account_id AND p.id=NEW.principal_id AND p.kind='user'
   AND u.status IN('active','suspended') AND p.status IN('active','suspended') AND u.provider_issuer=NEW.issuer AND u.provider_subject=NEW.provider_subject
   FOR SHARE OF u,p NOWAIT;
  IF NOT FOUND OR EXISTS(SELECT 1 FROM identity.account_deletion_jobs WHERE environment=NEW.environment AND user_id=NEW.account_id) THEN
   RAISE EXCEPTION 'Export owner unavailable' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.state='complete' AND NOT EXISTS(SELECT 1 FROM platform.account_export_artifacts a
   WHERE a.environment=NEW.environment AND a.job_id=NEW.id AND a.verified_at IS NOT NULL AND a.expired_at IS NULL
   AND a.expires_at>clock_timestamp()) THEN RAISE EXCEPTION 'Export artifact unavailable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$feedme_export_jobs$;
CREATE TRIGGER account_export_jobs_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.account_export_jobs
 FOR EACH ROW EXECUTE FUNCTION platform.guard_account_export_jobs();
CREATE TRIGGER account_export_jobs_truncate BEFORE TRUNCATE ON platform.account_export_jobs
 FOR EACH STATEMENT EXECUTE FUNCTION platform.guard_account_export_jobs();

CREATE FUNCTION platform.guard_account_export_artifacts() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp AS $feedme_export_artifacts$
DECLARE job platform.account_export_jobs%ROWTYPE;
BEGIN
 IF TG_OP IN('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Export artifact requires explicit erasure' USING ERRCODE='23514'; END IF;
 SELECT * INTO job FROM platform.account_export_jobs WHERE environment=NEW.environment AND id=NEW.job_id FOR UPDATE NOWAIT;
 IF NOT FOUND OR NEW.expires_at>job.expires_at OR NEW.account_id<>job.account_id OR NEW.principal_id<>job.principal_id THEN
  RAISE EXCEPTION 'Export artifact job differs' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF ROW(NEW.environment,NEW.job_id,NEW.account_id,NEW.principal_id,NEW.artifact_id,NEW.bucket,NEW.object_key,NEW.key_id,NEW.nonce,
    NEW.plaintext_sha256,NEW.plaintext_bytes,NEW.cipher_sha256,NEW.cipher_bytes,NEW.expires_at,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.environment,OLD.job_id,OLD.account_id,OLD.principal_id,OLD.artifact_id,OLD.bucket,OLD.object_key,OLD.key_id,OLD.nonce,
    OLD.plaintext_sha256,OLD.plaintext_bytes,OLD.cipher_sha256,OLD.cipher_bytes,OLD.expires_at,OLD.created_at)
    OR (OLD.write_attempted_at IS NOT NULL AND ROW(NEW.write_attempted_at,NEW.dispatch_token,NEW.dispatch_generation)
      IS DISTINCT FROM ROW(OLD.write_attempted_at,OLD.dispatch_token,OLD.dispatch_generation))
    OR (OLD.verified_at IS NOT NULL AND NEW.verified_at IS DISTINCT FROM OLD.verified_at)
    OR (OLD.expired_at IS NOT NULL AND NEW.expired_at IS DISTINCT FROM OLD.expired_at)
    OR (NEW.expired_at IS NULL AND ROW(NEW.wrapped_key,NEW.ciphertext) IS DISTINCT FROM ROW(OLD.wrapped_key,OLD.ciphertext)) THEN
    RAISE EXCEPTION 'Encrypted export original is immutable' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.write_attempted_at IS NOT NULL OR NEW.verified_at IS NOT NULL OR NEW.expired_at IS NOT NULL THEN
   RAISE EXCEPTION 'Export upload begins undispatched' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.expired_at IS NOT NULL THEN
  IF NEW.expires_at>clock_timestamp() THEN RAISE EXCEPTION 'Export has not expired' USING ERRCODE='23514'; END IF;
 ELSE
  IF job.state<>'running' OR job.lease_token IS NULL OR job.lease_until<=clock_timestamp() OR job.expires_at<=clock_timestamp() THEN
   RAISE EXCEPTION 'Export lease unavailable' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND OLD.write_attempted_at IS NULL AND NEW.write_attempted_at IS NOT NULL AND
   (NEW.dispatch_token IS DISTINCT FROM job.lease_token OR NEW.dispatch_generation IS DISTINCT FROM job.lease_generation) THEN
   RAISE EXCEPTION 'Export dispatch differs from lease' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$feedme_export_artifacts$;
CREATE TRIGGER account_export_artifacts_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.account_export_artifacts
 FOR EACH ROW EXECUTE FUNCTION platform.guard_account_export_artifacts();
CREATE TRIGGER account_export_artifacts_truncate BEFORE TRUNCATE ON platform.account_export_artifacts
 FOR EACH STATEMENT EXECUTE FUNCTION platform.guard_account_export_artifacts();
REVOKE ALL ON FUNCTION platform.guard_account_export_jobs(),platform.guard_account_export_artifacts() FROM PUBLIC;
