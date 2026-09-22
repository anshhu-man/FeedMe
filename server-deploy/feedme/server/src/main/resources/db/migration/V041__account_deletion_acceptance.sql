-- Atomic account-deletion acceptance only. No HTTP route, runtime grant, Auth write,
-- erasure worker or completed-deletion claim is installed. The invoking store must
-- first verify D2's two provider sessions, acquire the existing subject/account locks,
-- and retain/revalidate that evidence through its final command/outbox writes.
-- These original records deliberately have no FK to identities that a later reviewed
-- erasure worker removes. This is not an approved permanent-retention policy.

CREATE TABLE identity.account_deletion_jobs (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 id uuid NOT NULL, user_id uuid NOT NULL, principal_id uuid NOT NULL,
 provider_issuer varchar(2048) NOT NULL CHECK(octet_length(provider_issuer) BETWEEN 1 AND 2048 AND provider_issuer !~ '[[:cntrl:]]'),
 provider_subject uuid NOT NULL, device_session_id uuid NOT NULL, provider_session_id uuid NOT NULL,
 command_key uuid NOT NULL, request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 acknowledged_version varchar(256) NOT NULL CHECK(length(btrim(acknowledged_version))>0 AND acknowledged_version !~ '[[:cntrl:]]'),
 policy_revision varchar(128) NOT NULL CHECK(length(btrim(policy_revision))>0 AND policy_revision !~ '[[:cntrl:]]'),
 proof_session_id uuid NOT NULL,
 maximum_authentication_age_seconds bigint NOT NULL CHECK(maximum_authentication_age_seconds BETWEEN 1 AND 900),
 provider_session_created_at timestamptz NOT NULL, oauth_authenticated_at timestamptz NOT NULL,
 authorized_at timestamptz NOT NULL, valid_until timestamptz NOT NULL, accepted_at timestamptz NOT NULL,
 stage varchar(16) NOT NULL DEFAULT 'pending' CHECK(stage='pending'),
 PRIMARY KEY(environment,id), UNIQUE(environment,user_id), UNIQUE(environment,user_id,command_key),
 UNIQUE(environment,principal_id), UNIQUE(environment,provider_issuer,provider_subject),
 CHECK(user_id<>principal_id AND device_session_id<>provider_session_id AND proof_session_id<>provider_session_id),
 CHECK(isfinite(provider_session_created_at) AND isfinite(oauth_authenticated_at) AND isfinite(authorized_at)
   AND isfinite(valid_until) AND isfinite(accepted_at)),
 CHECK(provider_session_created_at<=oauth_authenticated_at AND oauth_authenticated_at<=authorized_at
   AND authorized_at<=accepted_at AND accepted_at<valid_until),
 CHECK(valid_until<=provider_session_created_at+maximum_authentication_age_seconds*interval '1 second'
   AND valid_until<=oauth_authenticated_at+maximum_authentication_age_seconds*interval '1 second')
);
CREATE TABLE identity.account_deletion_devices (
 environment varchar(40) NOT NULL, job_id uuid NOT NULL, device_session_id uuid NOT NULL,
 provider_session_id uuid NOT NULL, prior_version bigint NOT NULL CHECK(prior_version>0 AND prior_version<9223372036854775807),
 revoked_at timestamptz NOT NULL CHECK(isfinite(revoked_at)),
 PRIMARY KEY(environment,job_id,device_session_id), UNIQUE(environment,device_session_id),
 FOREIGN KEY(environment,job_id) REFERENCES identity.account_deletion_jobs(environment,id),
 CHECK(device_session_id<>provider_session_id)
);
ALTER TABLE identity.account_deletion_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.account_deletion_jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE identity.account_deletion_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.account_deletion_devices FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE identity.account_deletion_jobs,identity.account_deletion_devices FROM PUBLIC;

CREATE FUNCTION identity.keep_account_deletion_acceptance_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $feedme_immutable$
BEGIN
 RAISE EXCEPTION 'Account deletion acceptance is immutable' USING ERRCODE='23514';
END;
$feedme_immutable$;
REVOKE ALL ON FUNCTION identity.keep_account_deletion_acceptance_immutable() FROM PUBLIC;
CREATE TRIGGER account_deletion_job_immutable BEFORE UPDATE OR DELETE ON identity.account_deletion_jobs
 FOR EACH ROW EXECUTE FUNCTION identity.keep_account_deletion_acceptance_immutable();
CREATE TRIGGER account_deletion_job_retained BEFORE TRUNCATE ON identity.account_deletion_jobs
 FOR EACH STATEMENT EXECUTE FUNCTION identity.keep_account_deletion_acceptance_immutable();
CREATE TRIGGER account_deletion_device_immutable BEFORE UPDATE OR DELETE ON identity.account_deletion_devices
 FOR EACH ROW EXECUTE FUNCTION identity.keep_account_deletion_acceptance_immutable();
CREATE TRIGGER account_deletion_device_retained BEFORE TRUNCATE ON identity.account_deletion_devices
 FOR EACH STATEMENT EXECUTE FUNCTION identity.keep_account_deletion_acceptance_immutable();

-- Definer guards can inspect the two private tables without widening the ordinary
-- runtime's ACL. They never confer provider identity or perform arbitrary writes.
-- Normal callers retain the existing provider -> subject -> account -> device order.
CREATE FUNCTION identity.guard_deleting_account_root() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_root_guard$
DECLARE held identity.account_deletion_jobs%ROWTYPE;
BEGIN
 IF TG_OP='UPDATE' AND OLD.status IN ('deleting','deleted') THEN
  IF NEW.status NOT IN ('deleting','deleted') OR (OLD.status='deleted' AND NEW.status<>'deleted')
    OR ROW(NEW.environment,NEW.id,NEW.provider_issuer,NEW.provider_subject)
       IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.provider_issuer,OLD.provider_subject) THEN
   RAISE EXCEPTION 'Deleting account identity cannot be restored' USING ERRCODE='23514';
  END IF;
 END IF;
 SELECT * INTO held FROM identity.account_deletion_jobs j
 WHERE (j.environment=NEW.environment AND (j.user_id=NEW.id OR
   (j.provider_issuer=NEW.provider_issuer AND j.provider_subject=NEW.provider_subject)))
   OR (TG_OP='UPDATE' AND j.environment=OLD.environment AND j.user_id=OLD.id)
 LIMIT 1;
 IF FOUND AND (TG_OP='INSERT' OR NEW.status NOT IN ('deleting','deleted')
   OR ROW(NEW.environment,NEW.id,NEW.provider_issuer,NEW.provider_subject)
      IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.provider_issuer,OLD.provider_subject)
   OR ROW(NEW.environment,NEW.id,NEW.provider_issuer,NEW.provider_subject)
      IS DISTINCT FROM ROW(held.environment,held.user_id,held.provider_issuer,held.provider_subject)) THEN
  RAISE EXCEPTION 'Accepted deletion fences account identity' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_root_guard$;
REVOKE ALL ON FUNCTION identity.guard_deleting_account_root() FROM PUBLIC;
CREATE TRIGGER account_deletion_root_fence BEFORE INSERT OR UPDATE ON identity.users
 FOR EACH ROW EXECUTE FUNCTION identity.guard_deleting_account_root();

CREATE FUNCTION identity.guard_deleting_principal() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_principal_guard$
DECLARE held identity.account_deletion_jobs%ROWTYPE;
BEGIN
 IF TG_OP='UPDATE' AND OLD.status IN ('deleting','deleted') THEN
  IF NEW.status NOT IN ('deleting','deleted') OR (OLD.status='deleted' AND NEW.status<>'deleted')
    OR ROW(NEW.environment,NEW.id,NEW.user_id,NEW.kind,NEW.guest_session_id)
       IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.user_id,OLD.kind,OLD.guest_session_id) THEN
   RAISE EXCEPTION 'Deleting principal identity cannot be restored' USING ERRCODE='23514';
  END IF;
 END IF;
 IF NEW.user_id IS NOT NULL THEN
  PERFORM 1 FROM identity.users u WHERE u.environment=NEW.environment AND u.id=NEW.user_id FOR KEY SHARE;
 END IF;
 SELECT * INTO held FROM identity.account_deletion_jobs j
 WHERE (j.environment=NEW.environment AND (j.principal_id=NEW.id OR j.user_id=NEW.user_id))
   OR (TG_OP='UPDATE' AND j.environment=OLD.environment AND (j.principal_id=OLD.id OR j.user_id=OLD.user_id))
 LIMIT 1;
 IF FOUND AND (TG_OP='INSERT' OR NEW.status NOT IN ('deleting','deleted') OR NEW.kind<>'user'
   OR ROW(NEW.environment,NEW.id,NEW.user_id,NEW.kind,NEW.guest_session_id)
      IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.user_id,OLD.kind,OLD.guest_session_id)
   OR NEW.guest_session_id IS NOT NULL OR ROW(NEW.environment,NEW.id,NEW.user_id)
      IS DISTINCT FROM ROW(held.environment,held.principal_id,held.user_id)) THEN
  RAISE EXCEPTION 'Accepted deletion fences private principal' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_principal_guard$;
REVOKE ALL ON FUNCTION identity.guard_deleting_principal() FROM PUBLIC;
CREATE TRIGGER account_deletion_principal_fence BEFORE INSERT OR UPDATE ON identity.principals
 FOR EACH ROW EXECUTE FUNCTION identity.guard_deleting_principal();

CREATE FUNCTION identity.guard_deleting_device() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_device_guard$
BEGIN
 -- Serialize a new device with acceptance before observing its immutable job.
 PERFORM 1 FROM identity.users u WHERE u.environment=NEW.environment AND u.id=NEW.user_id FOR KEY SHARE;
 IF EXISTS(SELECT 1 FROM identity.account_deletion_jobs j
   WHERE (j.environment=NEW.environment AND j.user_id=NEW.user_id)
     OR (TG_OP='UPDATE' AND j.environment=OLD.environment AND j.user_id=OLD.user_id)) THEN
  IF TG_OP='INSERT' OR NEW.revoked_at IS NULL OR
    ROW(NEW.environment,NEW.id,NEW.user_id,NEW.provider_session_id,NEW.installation_id_hash,NEW.platform)
      IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.user_id,OLD.provider_session_id,OLD.installation_id_hash,OLD.platform)
    OR (OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at) THEN
   RAISE EXCEPTION 'Accepted deletion fences device access' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END;
$feedme_device_guard$;
REVOKE ALL ON FUNCTION identity.guard_deleting_device() FROM PUBLIC;
CREATE TRIGGER account_deletion_device_fence BEFORE INSERT OR UPDATE ON identity.device_sessions
 FOR EACH ROW EXECUTE FUNCTION identity.guard_deleting_device();

CREATE FUNCTION identity.guard_deleting_profile() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_profile_guard$
BEGIN
 PERFORM 1 FROM identity.users u WHERE u.environment=NEW.environment AND u.id=NEW.user_id FOR KEY SHARE;
 IF EXISTS(SELECT 1 FROM identity.account_deletion_jobs j
   WHERE (j.environment=NEW.environment AND j.user_id=NEW.user_id)
     OR (TG_OP='UPDATE' AND j.environment=OLD.environment AND j.user_id=OLD.user_id)) THEN
  IF TG_OP='INSERT' OR NEW.live OR
    ROW(NEW.environment,NEW.user_id) IS DISTINCT FROM ROW(OLD.environment,OLD.user_id) THEN
   RAISE EXCEPTION 'Accepted deletion fences profile access' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END;
$feedme_profile_guard$;
REVOKE ALL ON FUNCTION identity.guard_deleting_profile() FROM PUBLIC;
CREATE TRIGGER account_deletion_profile_fence BEFORE INSERT OR UPDATE ON profile.profiles
 FOR EACH ROW EXECUTE FUNCTION identity.guard_deleting_profile();

CREATE FUNCTION identity.accept_account_deletion(
 p_environment text,p_account_id uuid,p_principal_id uuid,p_provider_issuer text,p_provider_subject uuid,
 p_device_id uuid,p_provider_session_id uuid,p_command_key uuid,p_request_sha256 text,p_job_id uuid,
 p_acknowledged_version text,p_policy_revision text,p_proof_session_id uuid,p_max_age_seconds bigint,
 p_session_created_at timestamptz,p_oauth_authenticated_at timestamptz,p_authorized_at timestamptz,p_valid_until timestamptz
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_accept$
DECLARE account_row identity.users%ROWTYPE; principal_row identity.principals%ROWTYPE;
 original_device identity.device_sessions%ROWTYPE; profile_row profile.profiles%ROWTYPE;
 accepted timestamptz; changed bigint; active_devices bigint;
BEGIN
 -- This function is not a JWT verifier. A future explicit grant must remain limited
 -- to the reviewed store that holds D2 evidence and owns the surrounding transaction.
 IF current_setting('session_replication_role')<>'origin' OR current_setting('transaction_isolation')<>'read committed'
   OR current_setting('transaction_read_only')<>'off'
   OR array_position(ARRAY[p_environment,p_provider_issuer,p_request_sha256,p_acknowledged_version,p_policy_revision],NULL) IS NOT NULL
   OR array_position(ARRAY[p_account_id,p_principal_id,p_provider_subject,p_device_id,p_provider_session_id,p_command_key,p_job_id,p_proof_session_id],NULL) IS NOT NULL
   OR p_max_age_seconds IS NULL OR p_max_age_seconds NOT BETWEEN 1 AND 900
   OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$' OR p_request_sha256 !~ '^[0-9a-f]{64}$'
   OR octet_length(p_provider_issuer) NOT BETWEEN 1 AND 2048 OR p_provider_issuer ~ '[[:cntrl:]]'
   OR length(p_acknowledged_version) NOT BETWEEN 1 AND 256 OR btrim(p_acknowledged_version)=''
   OR p_acknowledged_version ~ '[[:cntrl:]]' OR length(p_policy_revision) NOT BETWEEN 1 AND 128
   OR btrim(p_policy_revision)='' OR p_policy_revision ~ '[[:cntrl:]]'
   OR p_account_id=p_principal_id OR p_device_id=p_provider_session_id OR p_proof_session_id=p_provider_session_id
   OR array_position(ARRAY[p_session_created_at,p_oauth_authenticated_at,p_authorized_at,p_valid_until],NULL) IS NOT NULL
   OR NOT isfinite(p_session_created_at) OR NOT isfinite(p_oauth_authenticated_at)
   OR NOT isfinite(p_authorized_at) OR NOT isfinite(p_valid_until)
   OR p_session_created_at>p_oauth_authenticated_at OR p_oauth_authenticated_at>p_authorized_at
   OR p_authorized_at>=p_valid_until
   OR p_valid_until>p_session_created_at+p_max_age_seconds*interval '1 second'
   OR p_valid_until>p_oauth_authenticated_at+p_max_age_seconds*interval '1 second' THEN
  RAISE EXCEPTION 'Account deletion acceptance input unavailable' USING ERRCODE='23514';
 END IF;
 SELECT * INTO account_row FROM identity.users u
 WHERE u.environment=p_environment AND u.id=p_account_id FOR UPDATE;
 IF NOT FOUND OR account_row.provider_issuer<>p_provider_issuer OR account_row.provider_subject<>p_provider_subject
   OR account_row.status NOT IN ('active','suspended') OR account_row.version=9223372036854775807 THEN
  RAISE EXCEPTION 'Account deletion owner unavailable' USING ERRCODE='23514';
 END IF;
 SELECT * INTO principal_row FROM identity.principals p
 WHERE p.environment=p_environment AND p.id=p_principal_id FOR UPDATE;
 IF NOT FOUND OR principal_row.user_id IS DISTINCT FROM p_account_id OR principal_row.kind<>'user'
   OR principal_row.guest_session_id IS NOT NULL OR principal_row.status NOT IN ('active','suspended')
   OR principal_row.version=9223372036854775807 THEN
  RAISE EXCEPTION 'Account deletion principal unavailable' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM identity.device_sessions d WHERE d.environment=p_environment AND d.user_id=p_account_id ORDER BY d.id FOR UPDATE;
 SELECT * INTO original_device FROM identity.device_sessions d
 WHERE d.environment=p_environment AND d.user_id=p_account_id AND d.id=p_device_id;
 IF NOT FOUND OR original_device.provider_session_id<>p_provider_session_id OR original_device.revoked_at IS NOT NULL
   OR original_device.logout_command_key IS NOT NULL THEN
  RAISE EXCEPTION 'Account deletion original device unavailable' USING ERRCODE='23514';
 END IF;
 SELECT count(*) INTO active_devices FROM identity.device_sessions d
 WHERE d.environment=p_environment AND d.user_id=p_account_id AND d.revoked_at IS NULL;
 IF active_devices=0 OR EXISTS(SELECT 1 FROM identity.device_sessions d WHERE d.environment=p_environment
   AND d.user_id=p_account_id AND d.revoked_at IS NULL AND d.version=9223372036854775807)
   OR EXISTS(SELECT 1 FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND
     (j.user_id=p_account_id OR j.principal_id=p_principal_id OR
       (j.provider_issuer=p_provider_issuer AND j.provider_subject=p_provider_subject))) THEN
  RAISE EXCEPTION 'Account deletion original state unavailable' USING ERRCODE='23514';
 END IF;
 SELECT * INTO profile_row FROM profile.profiles p WHERE p.environment=p_environment AND p.user_id=p_account_id FOR UPDATE;
 IF NOT FOUND OR profile_row.version=9223372036854775807 THEN
  RAISE EXCEPTION 'Account deletion profile unavailable' USING ERRCODE='23514';
 END IF;
 accepted:=clock_timestamp();
 IF p_authorized_at>accepted OR accepted>=p_valid_until THEN
  RAISE EXCEPTION 'Account deletion authentication expired' USING ERRCODE='23514';
 END IF;
 INSERT INTO identity.account_deletion_jobs(environment,id,user_id,principal_id,provider_issuer,provider_subject,
   device_session_id,provider_session_id,command_key,request_sha256,acknowledged_version,policy_revision,proof_session_id,
   maximum_authentication_age_seconds,provider_session_created_at,oauth_authenticated_at,authorized_at,valid_until,accepted_at,stage)
 VALUES(p_environment,p_job_id,p_account_id,p_principal_id,p_provider_issuer,p_provider_subject,p_device_id,p_provider_session_id,
   p_command_key,p_request_sha256,p_acknowledged_version,p_policy_revision,p_proof_session_id,p_max_age_seconds,
   p_session_created_at,p_oauth_authenticated_at,p_authorized_at,p_valid_until,accepted,'pending');
 INSERT INTO identity.account_deletion_devices(environment,job_id,device_session_id,provider_session_id,prior_version,revoked_at)
 SELECT p_environment,p_job_id,d.id,d.provider_session_id,d.version,accepted FROM identity.device_sessions d
 WHERE d.environment=p_environment AND d.user_id=p_account_id AND d.revoked_at IS NULL;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>active_devices THEN RAISE EXCEPTION 'Account deletion device inventory changed' USING ERRCODE='23514'; END IF;
 UPDATE identity.users SET status='deleting',version=version+1,updated_at=accepted
 WHERE environment=p_environment AND id=p_account_id AND version=account_row.version;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Account deletion owner changed' USING ERRCODE='23514'; END IF;
 UPDATE identity.principals SET status='deleting',version=version+1,updated_at=accepted
 WHERE environment=p_environment AND id=p_principal_id AND version=principal_row.version;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Account deletion principal changed' USING ERRCODE='23514'; END IF;
 UPDATE identity.device_sessions SET revoked_at=accepted,version=version+1,updated_at=accepted
 WHERE environment=p_environment AND user_id=p_account_id AND revoked_at IS NULL;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>active_devices THEN RAISE EXCEPTION 'Account deletion device revocation changed' USING ERRCODE='23514'; END IF;
 UPDATE profile.profiles SET live=false,version=version+1,updated_at=accepted
 WHERE environment=p_environment AND user_id=p_account_id AND version=profile_row.version;
 GET DIAGNOSTICS changed=ROW_COUNT;
 IF changed<>1 THEN RAISE EXCEPTION 'Account deletion profile changed' USING ERRCODE='23514'; END IF;
 RETURN p_job_id;
END;
$feedme_accept$;
REVOKE ALL ON FUNCTION identity.accept_account_deletion(text,uuid,uuid,text,uuid,uuid,uuid,uuid,text,uuid,text,text,uuid,bigint,timestamptz,timestamptz,timestamptz,timestamptz) FROM PUBLIC;
