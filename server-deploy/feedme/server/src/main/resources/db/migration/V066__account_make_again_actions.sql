-- Account-only explicit Save + Make Again linkage. V023 guest actions are untouched.
-- No serving grants, deployment, copy permission, projection or automatic preference.
CREATE TABLE memory.account_make_again_actions (
 environment varchar(40) NOT NULL,
 actor_kind varchar(8) NOT NULL DEFAULT 'account' CHECK(actor_kind='account'),
 principal_id uuid NOT NULL, principal_scope varchar(200) NOT NULL,
 parent_operation varchar(100) NOT NULL DEFAULT 'saveRecipe' CHECK(parent_operation='saveRecipe'),
 parent_key uuid NOT NULL, parent_request_hash char(64) NOT NULL CHECK(parent_request_hash ~ '^[0-9a-f]{64}$'),
 saved_recipe_id uuid NOT NULL, saved_generation bigint NOT NULL CHECK(saved_generation>0),
 saved_version bigint NOT NULL CHECK(saved_version>0),
 saved_snapshot_sha256 char(64) NOT NULL CHECK(saved_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 created_save boolean NOT NULL,
 save_operation varchar(100) NOT NULL DEFAULT 'saveRecipe' CHECK(save_operation='saveRecipe'),
 save_key uuid NOT NULL, save_request_hash char(64) NOT NULL CHECK(save_request_hash ~ '^[0-9a-f]{64}$'),
 feedback_id uuid NOT NULL, feedback_version bigint NOT NULL DEFAULT 1 CHECK(feedback_version=1),
 feedback_operation varchar(100) NOT NULL DEFAULT 'createFeedback' CHECK(feedback_operation='createFeedback'),
 feedback_key uuid NOT NULL,
 feedback_context_sha256 char(64) NOT NULL CHECK(feedback_context_sha256 ~ '^[0-9a-f]{64}$'),
 feedback_request_hash char(64) NOT NULL CHECK(feedback_request_hash ~ '^[0-9a-f]{64}$'),
 feedback_snapshot_sha256 char(64) NOT NULL CHECK(feedback_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 feedback_provenance_sha256 char(64) NOT NULL CHECK(feedback_provenance_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(created_at)),
 PRIMARY KEY(principal_scope,parent_operation,parent_key),
 UNIQUE(principal_scope,save_key), UNIQUE(principal_scope,feedback_key),
 CHECK(principal_scope=environment||':account:'||principal_id::text),
 CHECK(parent_key=save_key AND parent_request_hash=save_request_hash),
 FOREIGN KEY(environment,actor_kind,principal_id,saved_recipe_id)
  REFERENCES memory.saved_recipes(environment,actor_kind,principal_id,id),
 FOREIGN KEY(environment,actor_kind,principal_id,save_key)
  REFERENCES memory.save_commands(environment,actor_kind,principal_id,command_id),
 FOREIGN KEY(environment,actor_kind,principal_id,feedback_id)
  REFERENCES memory.feedback(environment,actor_kind,principal_id,id),
 FOREIGN KEY(principal_scope,parent_operation,parent_key)
  REFERENCES platform.idempotency(principal_scope,operation_id,key),
 FOREIGN KEY(principal_scope,feedback_operation,feedback_key)
  REFERENCES memory.feedback_commands(principal_scope,operation_id,command_key)
);
CREATE INDEX account_make_again_saved_actions ON memory.account_make_again_actions(environment,principal_id,saved_recipe_id,saved_generation);
ALTER TABLE memory.account_make_again_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE memory.account_make_again_actions FORCE ROW LEVEL SECURITY;
REVOKE ALL ON memory.account_make_again_actions FROM PUBLIC;

CREATE FUNCTION memory.guard_account_make_again_action() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_account_make_again$
BEGIN
 IF TG_OP='DELETE' THEN
  -- Only the actual erasure worker's live transaction-local capability permits
  -- deletion. Neither an API-supplied UUID nor a BYPASSRLS role creates that scope.
  IF current_setting('session_replication_role')='origin'
    AND current_setting('transaction_isolation')='read committed'
    AND EXISTS(SELECT 1 FROM identity.account_erasure_scope s
      JOIN identity.account_deletion_jobs j ON j.environment=s.environment AND j.id=s.job_id
      JOIN identity.account_erasure_work w ON w.environment=s.environment AND w.job_id=s.job_id
      WHERE s.backend_pid=pg_backend_pid() AND s.transaction_id=txid_current()
        AND s.environment=OLD.environment AND s.principal_id=OLD.principal_id
        AND s.account_id=j.user_id AND s.principal_id=j.principal_id
        AND j.stage='pending' AND w.stage='inventory' AND w.lease_token=s.lease_token
        AND w.generation=s.generation AND w.lease_expires_at>clock_timestamp()) THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'Make Again linkage requires account erasure' USING ERRCODE='23514';
 END IF;
 IF TG_OP<>'INSERT' THEN
  RAISE EXCEPTION 'Make Again linkage is immutable' USING ERRCODE='23514';
 END IF;
 IF current_setting('session_replication_role')<>'origin' THEN
  RAISE EXCEPTION 'Make Again writer unavailable' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM identity.principals p JOIN identity.users u ON u.environment=p.environment AND u.id=p.user_id
  WHERE p.environment=NEW.environment AND p.id=NEW.principal_id AND p.kind='user' AND p.status='active'
    AND u.status='active' AND p.guest_session_id IS NULL
    AND NOT EXISTS(SELECT 1 FROM identity.account_deletion_jobs j WHERE j.environment=u.environment AND j.user_id=u.id)
  FOR SHARE OF p,u NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Make Again owner unavailable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$feedme_account_make_again$;
REVOKE ALL ON FUNCTION memory.guard_account_make_again_action() FROM PUBLIC;
CREATE TRIGGER account_make_again_action_guard BEFORE INSERT OR UPDATE OR DELETE ON memory.account_make_again_actions
 FOR EACH ROW EXECUTE FUNCTION memory.guard_account_make_again_action();
CREATE TRIGGER account_make_again_action_retained BEFORE TRUNCATE ON memory.account_make_again_actions
 FOR EACH STATEMENT EXECUTE FUNCTION memory.guard_account_make_again_action();
