-- LOCAL worker-only capture of an accepted account's still-pending Supabase source.
-- This creates a logical tombstone and durable exact cleanup originals, not an HTTP
-- request, client command receipt, signed-upload settlement or deletion completion.
-- V049 and every existing publication/safety/provider completion hold are unchanged.
CREATE TABLE erasure.media_source_captures (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 job_id uuid NOT NULL, capture_id uuid NOT NULL, user_id uuid NOT NULL, media_id uuid NOT NULL,
 client_draft_id uuid NOT NULL, draft_generation bigint NOT NULL CHECK(draft_generation>0),
 original_version bigint NOT NULL CHECK(original_version=1), original_created_at timestamptz NOT NULL CHECK(isfinite(original_created_at)),
 bucket text NOT NULL CHECK(bucket ~ '^[a-z0-9][a-z0-9_-]{0,62}$'), object_key varchar(200) NOT NULL,
 expected_sha256 char(64) NOT NULL CHECK(expected_sha256 ~ '^[0-9a-f]{64}$'),
 expected_bytes bigint NOT NULL CHECK(expected_bytes BETWEEN 1 AND 10000000),
 content_type text NOT NULL CHECK(content_type IN ('image/png','image/jpeg')),
 reservation_expires_at timestamptz NOT NULL CHECK(isfinite(reservation_expires_at)),
 deletion_key uuid NOT NULL, source_intent_id uuid NOT NULL, cleanup_id uuid NOT NULL,
 manifest_text text NOT NULL CHECK(octet_length(manifest_text)<=2048 AND jsonb_typeof(manifest_text::jsonb)='object'),
 manifest_hash char(64) NOT NULL CHECK(manifest_hash=encode(sha256(convert_to(manifest_text,'UTF8')),'hex')),
 captured_token uuid NOT NULL, captured_generation bigint NOT NULL CHECK(captured_generation>0),
 captured_at timestamptz NOT NULL CHECK(isfinite(captured_at)),
 PRIMARY KEY(environment,job_id,media_id), UNIQUE(environment,capture_id),
 UNIQUE(environment,source_intent_id), UNIQUE(cleanup_id),
 FOREIGN KEY(environment,job_id) REFERENCES identity.account_deletion_jobs(environment,id),
 FOREIGN KEY(environment,user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id),
 FOREIGN KEY(environment,source_intent_id) REFERENCES erasure.media_source_deletions(environment,intent_id),
 FOREIGN KEY(cleanup_id) REFERENCES platform.media_processing_cleanup(id),
 CHECK(original_created_at<=captured_at AND reservation_expires_at>original_created_at)
);
ALTER TABLE erasure.media_source_captures ENABLE ROW LEVEL SECURITY;
ALTER TABLE erasure.media_source_captures FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE erasure.media_source_captures FROM PUBLIC;

CREATE FUNCTION erasure.guard_account_media_capture() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_capture_guard$
DECLARE asset platform.media_assets%ROWTYPE; original erasure.media_source_deletions%ROWTYPE;
 utc_time timestamp; fraction text; deadline_text text; canonical text;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Account source capture is immutable' USING ERRCODE='23514'; END IF;
 IF NOT erasure.lock_account_media_source(NEW.environment,NEW.job_id,NEW.captured_token,NEW.captured_generation,NEW.media_id) THEN
  RAISE EXCEPTION 'Account source capture requires exact live authority' USING ERRCODE='23514'; END IF;
 SELECT m.* INTO STRICT asset FROM platform.media_assets m WHERE m.environment=NEW.environment AND m.owner_user_id=NEW.user_id AND m.id=NEW.media_id;
 SELECT d.* INTO STRICT original FROM erasure.media_source_deletions d WHERE d.environment=NEW.environment AND d.intent_id=NEW.source_intent_id;
 IF ROW(original.job_id,original.user_id,original.media_id,original.prepared_token,original.prepared_generation)
   IS DISTINCT FROM ROW(NEW.job_id,NEW.user_id,NEW.media_id,NEW.captured_token,NEW.captured_generation)
   OR original.dispatched_at IS NOT NULL OR original.created_at<NEW.captured_at
   OR ROW(NEW.client_draft_id,NEW.draft_generation,NEW.original_created_at,NEW.bucket,NEW.object_key,
     NEW.expected_sha256,NEW.expected_bytes,NEW.content_type,NEW.reservation_expires_at,NEW.deletion_key,NEW.manifest_hash,NEW.captured_at)
   IS DISTINCT FROM ROW(asset.client_draft_id,asset.draft_generation,asset.created_at,asset.storage_bucket::text,asset.quarantine_key,
     asset.expected_sha256,asset.expected_bytes,asset.content_type::text,asset.reservation_expires_at,asset.deletion_key,asset.cleanup_manifest_hash,asset.updated_at)
   OR NEW.captured_at>clock_timestamp() OR NOT EXISTS(SELECT 1 FROM platform.media_processing_cleanup c
     WHERE c.id=NEW.cleanup_id AND c.environment=NEW.environment AND c.owner_user_id=NEW.user_id AND c.media_id=NEW.media_id
       AND c.object_key=NEW.object_key AND c.derivative_intent_id IS NULL AND c.not_before=NEW.reservation_expires_at
       AND c.available_at=NEW.reservation_expires_at AND c.state='quarantined' AND c.lease_token IS NULL
       AND c.settled_versions IS NULL AND c.completed_at IS NULL) THEN
  RAISE EXCEPTION 'Account source capture original differs' USING ERRCODE='23514'; END IF;
 -- Match MediaCleanupManifest exactly, including Java Instant's 0/3/6 fraction
 -- groups at PostgreSQL microsecond precision. jsonb::text is NOT canonical bytes.
 utc_time:=asset.reservation_expires_at AT TIME ZONE 'UTC';
 IF extract(year FROM utc_time) NOT BETWEEN 1 AND 9999 THEN RAISE EXCEPTION 'Unsupported source deadline' USING ERRCODE='23514'; END IF;
 fraction:=to_char(utc_time,'US');
 deadline_text:=to_char(utc_time,'YYYY-MM-DD"T"HH24:MI:SS')||CASE WHEN fraction='000000' THEN ''
   WHEN right(fraction,3)='000' THEN '.'||left(fraction,3) ELSE '.'||fraction END||'Z';
 canonical:='{"bucket":'||to_jsonb(asset.storage_bucket)::text||',"derivatives":null,"finalSweepAfter":'||to_jsonb(deadline_text)::text||
   ',"knownVersionId":null,"mediaId":'||to_jsonb(asset.id::text)::text||',"protocol":"supabaseSignedUploadV1","quarantineKey":'||
   to_jsonb(asset.quarantine_key)::text||',"sha256":'||to_jsonb(asset.expected_sha256::text)::text||'}';
 IF NEW.manifest_text<>canonical THEN RAISE EXCEPTION 'Account source capture manifest differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$feedme_media_capture_guard$;
REVOKE ALL ON FUNCTION erasure.guard_account_media_capture() FROM PUBLIC;
CREATE TRIGGER account_media_capture_guard BEFORE INSERT OR UPDATE OR DELETE ON erasure.media_source_captures
 FOR EACH ROW EXECUTE FUNCTION erasure.guard_account_media_capture();
CREATE TRIGGER account_media_capture_retained BEFORE TRUNCATE ON erasure.media_source_captures
 FOR EACH STATEMENT EXECUTE FUNCTION erasure.guard_account_media_capture();

CREATE FUNCTION erasure.capture_account_media_source(p_environment text,p_job uuid,p_token uuid,p_generation bigint,p_media uuid,p_capture uuid)
RETURNS TABLE(capture_id uuid,intent_id uuid,user_id uuid,bucket text,object_key text,already_dispatched boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_media_capture$
DECLARE accepted identity.account_deletion_jobs%ROWTYPE; work identity.account_erasure_work%ROWTYPE;
 asset platform.media_assets%ROWTYPE; retained erasure.media_source_captures%ROWTYPE; source record;
 at_time timestamptz; utc_time timestamp; fraction text; deadline_text text; canonical text; hash text;
 deletion_id uuid; source_id uuid; cleanup_id uuid;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$' OR p_job IS NULL
   OR p_token IS NULL OR p_generation IS NULL OR p_generation<1 OR p_media IS NULL OR p_capture IS NULL
   OR current_setting('session_replication_role')<>'origin'
   OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
  RAISE EXCEPTION 'Invalid account source capture transaction' USING ERRCODE='23514'; END IF;
 SELECT j.* INTO accepted FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job FOR UPDATE NOWAIT;
 IF NOT FOUND OR accepted.stage<>'pending' OR accepted.provider_issuer<>'https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1' THEN RETURN; END IF;
 SELECT w.* INTO work FROM identity.account_erasure_work w WHERE w.environment=p_environment AND w.job_id=p_job FOR UPDATE NOWAIT;
 IF NOT FOUND OR work.stage<>'inventory' OR work.lease_token IS DISTINCT FROM p_token OR work.generation<>p_generation
   OR work.lease_expires_at IS NULL OR work.lease_expires_at<=clock_timestamp() THEN RETURN; END IF;
 LOCK TABLE platform.media_draft_lifecycles,platform.media_assets,platform.media_cleanup_jobs,
   platform.media_upload_issuances,platform.media_processing_jobs,platform.media_processing_inbox,
   platform.media_derivative_intents,platform.media_processing_cleanup,platform.media_private_materializations,
   platform.media_safety_records,platform.media_safety_revocations,platform.post_drafts,platform.post_draft_discard_media,
   social.post_publications,social.post_media,social.post_publication_discard_media,
   safety.reports,safety.report_evidence,safety.moderation_cases IN SHARE ROW EXCLUSIVE MODE NOWAIT;
 PERFORM 1 FROM identity.users u WHERE u.environment=p_environment AND u.id=accepted.user_id AND u.status='deleting'
   AND u.provider_issuer=accepted.provider_issuer AND u.provider_subject=accepted.provider_subject FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN; END IF;
 PERFORM 1 FROM identity.principals p WHERE p.environment=p_environment AND p.id=accepted.principal_id
   AND p.kind='user' AND p.user_id=accepted.user_id AND p.status='deleting' AND p.guest_session_id IS NULL FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN; END IF;
 SELECT m.* INTO asset FROM platform.media_assets m WHERE m.environment=p_environment AND m.owner_user_id=accepted.user_id AND m.id=p_media;
 IF NOT FOUND OR asset.storage_protocol<>'supabaseSignedUploadV1' OR asset.storage_bucket IS NULL
   OR asset.storage_bucket !~ '^[a-z0-9][a-z0-9_-]{0,62}$' OR asset.content_type NOT IN ('image/png','image/jpeg')
   OR asset.quarantine_key !~ ('^quarantine/'||p_environment||'/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/'||p_media::text||'$') THEN RETURN; END IF;
 PERFORM 1 FROM platform.media_draft_lifecycles d WHERE d.environment=p_environment AND d.owner_user_id=accepted.user_id
   AND d.client_draft_id=asset.client_draft_id AND d.generation=asset.draft_generation FOR UPDATE NOWAIT;
 IF NOT FOUND THEN RETURN; END IF;
 PERFORM 1 FROM platform.media_assets m WHERE m.environment=p_environment AND m.owner_user_id=accepted.user_id AND m.id=p_media FOR UPDATE NOWAIT;
 IF EXISTS(SELECT 1 FROM platform.post_drafts d WHERE d.environment=p_environment AND d.owner_user_id=accepted.user_id AND d.client_draft_id=asset.client_draft_id
   AND (d.draft_generation<>asset.draft_generation OR d.status NOT IN ('draft','expired') OR d.published_post_id IS NOT NULL)) THEN RETURN; END IF;
 SELECT c.* INTO retained FROM erasure.media_source_captures c WHERE c.environment=p_environment AND c.job_id=p_job AND c.media_id=p_media FOR UPDATE;
 IF FOUND THEN
  IF ROW(retained.user_id,retained.deletion_key,retained.manifest_hash,retained.object_key,retained.bucket,retained.reservation_expires_at)
    IS DISTINCT FROM ROW(accepted.user_id,asset.deletion_key,asset.cleanup_manifest_hash,asset.quarantine_key,asset.storage_bucket::text,asset.reservation_expires_at) THEN
   RAISE EXCEPTION 'Retained source capture differs' USING ERRCODE='23514'; END IF;
  SELECT s.* INTO source FROM erasure.prepare_account_media_source(p_environment,p_job,p_token,p_generation,p_media,retained.source_intent_id) s;
  IF NOT FOUND THEN RETURN; END IF;
  IF source.intent_id<>retained.source_intent_id OR source.user_id<>retained.user_id OR source.bucket<>retained.bucket OR source.object_key<>retained.object_key THEN
   RAISE EXCEPTION 'Retained source intent differs' USING ERRCODE='23514'; END IF;
  RETURN QUERY SELECT retained.capture_id,source.intent_id,source.user_id,source.bucket,source.object_key,source.already_dispatched; RETURN;
 END IF;
 -- Never manufacture worker provenance for an existing client tombstone, processing
 -- source, or unexpected cleanup lineage. The older V049 path remains separate.
 IF asset.state<>'awaitingUpload' OR asset.version<>1 OR asset.completion_key IS NOT NULL OR asset.completion_version IS NOT NULL
   OR asset.completion_accepted_at IS NOT NULL OR asset.quarantine_version_id IS NOT NULL OR asset.derivative_set IS NOT NULL
   OR asset.rejection_code IS NOT NULL OR asset.deletion_key IS NOT NULL OR asset.cleanup_manifest_hash IS NOT NULL
   OR EXISTS(SELECT 1 FROM platform.media_cleanup_jobs WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM platform.media_processing_cleanup WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM platform.media_processing_jobs WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM erasure.media_source_deletions WHERE environment=p_environment AND job_id=p_job AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM platform.media_safety_records WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM platform.post_draft_discard_media WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM social.post_publications WHERE environment=p_environment AND owner_user_id=accepted.user_id AND client_draft_id=asset.client_draft_id)
   OR EXISTS(SELECT 1 FROM social.post_media WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM social.post_publication_discard_media WHERE environment=p_environment AND owner_user_id=accepted.user_id AND media_id=p_media)
   OR EXISTS(SELECT 1 FROM safety.reports WHERE environment=p_environment AND (reporter_user_id=accepted.user_id OR (target_type='user' AND target_id=accepted.user_id)))
   OR EXISTS(SELECT 1 FROM safety.report_evidence WHERE environment=p_environment AND (reporter_user_id=accepted.user_id OR target_owner_id=accepted.user_id))
   OR EXISTS(SELECT 1 FROM safety.moderation_cases WHERE environment=p_environment AND target_type='user' AND target_id=accepted.user_id) THEN RETURN; END IF;
 utc_time:=asset.reservation_expires_at AT TIME ZONE 'UTC';
 IF extract(year FROM utc_time) NOT BETWEEN 1 AND 9999 THEN RETURN; END IF;
 fraction:=to_char(utc_time,'US');
 deadline_text:=to_char(utc_time,'YYYY-MM-DD"T"HH24:MI:SS')||CASE WHEN fraction='000000' THEN ''
   WHEN right(fraction,3)='000' THEN '.'||left(fraction,3) ELSE '.'||fraction END||'Z';
 canonical:='{"bucket":'||to_jsonb(asset.storage_bucket)::text||',"derivatives":null,"finalSweepAfter":'||to_jsonb(deadline_text)::text||
   ',"knownVersionId":null,"mediaId":'||to_jsonb(asset.id::text)::text||',"protocol":"supabaseSignedUploadV1","quarantineKey":'||
   to_jsonb(asset.quarantine_key)::text||',"sha256":'||to_jsonb(asset.expected_sha256::text)::text||'}';
 hash:=encode(sha256(convert_to(canonical,'UTF8')),'hex'); at_time:=clock_timestamp();
 IF work.lease_expires_at<=at_time THEN RETURN; END IF;
 deletion_id:=gen_random_uuid(); source_id:=gen_random_uuid(); cleanup_id:=gen_random_uuid();
 UPDATE platform.media_assets SET state='deleted',version=2,deletion_key=deletion_id,cleanup_manifest_hash=hash,updated_at=at_time
 WHERE environment=p_environment AND owner_user_id=accepted.user_id AND id=p_media AND version=1 AND state='awaitingUpload';
 IF NOT FOUND THEN RAISE EXCEPTION 'Source capture lost original' USING ERRCODE='23514'; END IF;
 INSERT INTO platform.media_cleanup_jobs(environment,owner_user_id,media_id,media_version,quarantine_key,known_version_id,
   derivative_set,manifest_hash,final_sweep_after,available_at,storage_protocol,storage_bucket)
 VALUES(p_environment,accepted.user_id,p_media,2,asset.quarantine_key,NULL,NULL,hash,asset.reservation_expires_at,at_time,'supabaseSignedUploadV1',asset.storage_bucket);
 INSERT INTO platform.media_processing_cleanup(id,environment,owner_user_id,media_id,object_key,derivative_intent_id,not_before,
   available_at,state,storage_protocol,storage_bucket)
 VALUES(cleanup_id,p_environment,accepted.user_id,p_media,asset.quarantine_key,NULL,asset.reservation_expires_at,
   asset.reservation_expires_at,'quarantined','supabaseSignedUploadV1',asset.storage_bucket);
 SELECT s.* INTO source FROM erasure.prepare_account_media_source(p_environment,p_job,p_token,p_generation,p_media,source_id) s;
 IF NOT FOUND OR source.intent_id<>source_id OR source.user_id<>accepted.user_id OR source.bucket<>asset.storage_bucket
   OR source.object_key<>asset.quarantine_key OR source.already_dispatched THEN
  RAISE EXCEPTION 'Source capture intent is unavailable' USING ERRCODE='23514'; END IF;
 INSERT INTO erasure.media_source_captures(environment,job_id,capture_id,user_id,media_id,client_draft_id,draft_generation,original_version,
   original_created_at,bucket,object_key,expected_sha256,expected_bytes,content_type,reservation_expires_at,deletion_key,source_intent_id,
   cleanup_id,manifest_text,manifest_hash,captured_token,captured_generation,captured_at)
 VALUES(p_environment,p_job,p_capture,accepted.user_id,p_media,asset.client_draft_id,asset.draft_generation,1,asset.created_at,
   asset.storage_bucket,asset.quarantine_key,asset.expected_sha256,asset.expected_bytes,asset.content_type,asset.reservation_expires_at,
   deletion_id,source_id,cleanup_id,canonical,hash,p_token,p_generation,at_time);
 -- A late expiry must roll back every tombstone/cleanup/provenance write. Returning
 -- an empty result after a partial mutation would not be an atomic hold.
 IF work.lease_expires_at<=clock_timestamp() THEN RAISE EXCEPTION 'Source capture lease expired' USING ERRCODE='23514'; END IF;
 RETURN QUERY SELECT p_capture,source.intent_id,source.user_id,source.bucket,source.object_key,source.already_dispatched;
END;
$feedme_media_capture$;
REVOKE ALL ON FUNCTION erasure.capture_account_media_source(text,uuid,uuid,bigint,uuid,uuid) FROM PUBLIC;
