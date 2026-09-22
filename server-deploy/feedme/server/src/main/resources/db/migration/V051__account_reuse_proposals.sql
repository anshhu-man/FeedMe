-- Reviewed reuse evidence and private account proposals only. No relationship, reviewer,
-- permission, serving grant or rollout is seeded. Existing V048 erasure inventory deliberately
-- returns related_data for these new planning relations until its exact cleanup is extended.
CREATE TABLE catalog.reuse_relationships (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 id uuid NOT NULL, definition_text text NOT NULL CHECK(octet_length(definition_text) BETWEEN 2 AND 16384),
 definition_sha256 char(64) NOT NULL CHECK(definition_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(created_at)),
 PRIMARY KEY(environment,id), CHECK(jsonb_typeof(definition_text::jsonb)='object')
);
CREATE TABLE catalog.reuse_revocations (
 environment varchar(40) NOT NULL, relationship_id uuid NOT NULL, id uuid NOT NULL,
 reason varchar(4000) NOT NULL CHECK(length(btrim(reason))>0),
 revoked_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(revoked_at)),
 PRIMARY KEY(environment,relationship_id), UNIQUE(environment,id),
 FOREIGN KEY(environment,relationship_id) REFERENCES catalog.reuse_relationships(environment,id)
);
CREATE TABLE planning.reuse_requests (
 environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL CHECK(actor_kind='account'), principal_id uuid NOT NULL,
 id uuid NOT NULL, request_text text NOT NULL CHECK(octet_length(request_text) BETWEEN 2 AND 16384),
 request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 evidence_text text NOT NULL CHECK(octet_length(evidence_text) BETWEEN 2 AND 1048576),
 evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 PRIMARY KEY(environment,actor_kind,principal_id,id),
 FOREIGN KEY(environment,principal_id) REFERENCES identity.principals(environment,id),
 CHECK(isfinite(created_at) AND isfinite(expires_at) AND created_at<expires_at),
 CHECK(jsonb_typeof(request_text::jsonb)='object' AND jsonb_typeof(evidence_text::jsonb)='object')
);
CREATE TABLE planning.reuse_pages (
 environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL CHECK(actor_kind='account'), principal_id uuid NOT NULL,
 command_key uuid NOT NULL, principal_scope varchar(200) NOT NULL,
 operation_id varchar(100) NOT NULL CHECK(operation_id='createReuseOptions'),
 request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 proposal_id uuid NOT NULL, page_offset integer NOT NULL CHECK(page_offset>=0),
 page_limit integer NOT NULL CHECK(page_limit BETWEEN 1 AND 50),
 response_text text NOT NULL CHECK(octet_length(response_text) BETWEEN 2 AND 262144),
 response_sha256 char(64) NOT NULL CHECK(response_sha256 ~ '^[0-9a-f]{64}$'),
 PRIMARY KEY(environment,actor_kind,principal_id,command_key),
 FOREIGN KEY(environment,actor_kind,principal_id,proposal_id) REFERENCES planning.reuse_requests(environment,actor_kind,principal_id,id),
 FOREIGN KEY(principal_scope,operation_id,command_key) REFERENCES platform.idempotency(principal_scope,operation_id,key),
 CHECK(principal_scope=environment||':account:'||principal_id::text), CHECK(jsonb_typeof(response_text::jsonb)='object')
);
CREATE TABLE planning.reuse_windows (
 environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL CHECK(actor_kind='account'), principal_id uuid NOT NULL,
 window_date date NOT NULL, policy_sha256 char(64) NOT NULL CHECK(policy_sha256 ~ '^[0-9a-f]{64}$'),
 maximum integer NOT NULL CHECK(maximum BETWEEN 1 AND 10000), used integer NOT NULL CHECK(used BETWEEN 1 AND maximum),
 PRIMARY KEY(environment,actor_kind,principal_id,window_date),
 FOREIGN KEY(environment,principal_id) REFERENCES identity.principals(environment,id)
);
CREATE FUNCTION planning.reject_reuse_evidence_mutation() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $feedme_reuse_immutable$
BEGIN RAISE EXCEPTION 'Reuse evidence is immutable' USING ERRCODE='23514'; END;
$feedme_reuse_immutable$;
DO $feedme_reuse_guards$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['catalog.reuse_relationships','catalog.reuse_revocations','planning.reuse_requests','planning.reuse_pages'] LOOP
  EXECUTE format('CREATE TRIGGER reuse_evidence_immutable BEFORE UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION planning.reject_reuse_evidence_mutation()',t);
  EXECUTE format('CREATE TRIGGER reuse_evidence_no_truncate BEFORE TRUNCATE ON %s FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_reuse_evidence_mutation()',t);
 END LOOP;
END; $feedme_reuse_guards$;
CREATE FUNCTION planning.guard_reuse_window() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $feedme_reuse_window$
BEGIN
 IF TG_OP<>'UPDATE' OR ROW(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.window_date,NEW.policy_sha256,NEW.maximum)
  IS DISTINCT FROM ROW(OLD.environment,OLD.actor_kind,OLD.principal_id,OLD.window_date,OLD.policy_sha256,OLD.maximum)
  OR OLD.used>=OLD.maximum OR NEW.used<>OLD.used+1 THEN
  RAISE EXCEPTION 'Invalid reuse quota transition' USING ERRCODE='23514';
 END IF; RETURN NEW;
END; $feedme_reuse_window$;
CREATE TRIGGER reuse_window_transition BEFORE UPDATE OR DELETE ON planning.reuse_windows FOR EACH ROW EXECUTE FUNCTION planning.guard_reuse_window();
CREATE TRIGGER reuse_window_no_truncate BEFORE TRUNCATE ON planning.reuse_windows FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_reuse_evidence_mutation();
-- No principal insertion/resurrection after account deletion. Resolve the real account,
-- then lock user -> principal in the normal authority order, with no global-session bypass.
CREATE FUNCTION planning.guard_reuse_owner_insert() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_reuse_owner$
DECLARE account_id uuid;
BEGIN
 SELECT user_id INTO account_id FROM identity.principals WHERE environment=NEW.environment AND id=NEW.principal_id AND kind='account';
 IF account_id IS NULL THEN RAISE EXCEPTION 'Reuse owner unavailable' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM identity.users WHERE environment=NEW.environment AND id=account_id AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reuse owner unavailable' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM identity.principals WHERE environment=NEW.environment AND id=NEW.principal_id AND user_id=account_id AND kind='account' AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND OR EXISTS(SELECT 1 FROM identity.account_deletion_jobs WHERE environment=NEW.environment AND user_id=account_id) THEN
  RAISE EXCEPTION 'Reuse owner unavailable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $feedme_reuse_owner$;
CREATE TRIGGER reuse_owner_insert BEFORE INSERT ON planning.reuse_requests FOR EACH ROW EXECUTE FUNCTION planning.guard_reuse_owner_insert();
CREATE TRIGGER reuse_owner_insert BEFORE INSERT ON planning.reuse_pages FOR EACH ROW EXECUTE FUNCTION planning.guard_reuse_owner_insert();
CREATE TRIGGER reuse_owner_insert BEFORE INSERT ON planning.reuse_windows FOR EACH ROW EXECUTE FUNCTION planning.guard_reuse_owner_insert();
DO $feedme_reuse_rls$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['catalog.reuse_relationships','catalog.reuse_revocations','planning.reuse_requests','planning.reuse_pages','planning.reuse_windows'] LOOP
  EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('REVOKE ALL ON %s FROM PUBLIC',t);
 END LOOP;
END; $feedme_reuse_rls$;
REVOKE ALL ON FUNCTION planning.reject_reuse_evidence_mutation(),planning.guard_reuse_window(),planning.guard_reuse_owner_insert() FROM PUBLIC;
