-- Unpublished staff authoring only. No publication, independent review, ingredient
-- approval, copy right, staff enrollment, runtime grant or external deployment.
CREATE TABLE staff.recipe_drafts (
 environment varchar(40) NOT NULL, recipe_id uuid NOT NULL, id uuid NOT NULL,
 author_id uuid NOT NULL, current_version bigint NOT NULL CHECK(current_version>0),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,id), UNIQUE(environment,recipe_id,id,author_id),
 FOREIGN KEY(environment,author_id) REFERENCES staff.actors(environment,actor_id),
 CHECK(isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at)
);
CREATE TABLE staff.recipe_draft_revisions (
 environment varchar(40) NOT NULL, recipe_id uuid NOT NULL, draft_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), author_id uuid NOT NULL, editor_id uuid NOT NULL CHECK(editor_id=author_id),
 principal_scope varchar(200) NOT NULL, operation_id varchar(100) NOT NULL,
 command_key uuid NOT NULL, request_hash char(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 request_text text NOT NULL CHECK(octet_length(request_text) BETWEEN 2 AND 65536),
 snapshot_text text NOT NULL CHECK(octet_length(snapshot_text) BETWEEN 2 AND 131072),
 snapshot_sha256 char(64) NOT NULL CHECK(snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz NOT NULL CHECK(isfinite(recorded_at)),
 PRIMARY KEY(environment,draft_id,version), UNIQUE(principal_scope,operation_id,command_key),
 FOREIGN KEY(environment,recipe_id,draft_id,author_id) REFERENCES staff.recipe_drafts(environment,recipe_id,id,author_id),
 FOREIGN KEY(environment,author_id) REFERENCES staff.actors(environment,actor_id),
 FOREIGN KEY(environment,editor_id) REFERENCES staff.actors(environment,actor_id),
 FOREIGN KEY(principal_scope,operation_id,command_key) REFERENCES platform.idempotency(principal_scope,operation_id,key),
 CHECK(principal_scope=environment||':staff:'||editor_id::text),
 CHECK((version=1 AND operation_id='adminCreateRecipe') OR (version>1 AND operation_id='adminUpdateRecipe')),
 CHECK((jsonb_typeof(request_text::jsonb)='object' AND jsonb_typeof(snapshot_text::jsonb)='object'
   AND snapshot_text::jsonb->>'id'=draft_id::text AND snapshot_text::jsonb->>'recipeId'=recipe_id::text
   AND (snapshot_text::jsonb->>'version')::bigint=version AND snapshot_text::jsonb->>'reviewStatus'='draft'
   AND NOT(snapshot_text::jsonb ?| ARRAY['reviewedAt','reviewerLabel','recallReasonCode','contentLicense'])) IS TRUE)
);
ALTER TABLE staff.recipe_drafts ADD CONSTRAINT staff_draft_current_revision
 FOREIGN KEY(environment,id,current_version) REFERENCES staff.recipe_draft_revisions(environment,draft_id,version)
 DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX staff_recipe_drafts_queue ON staff.recipe_drafts(environment,created_at,id);
ALTER TABLE staff.recipe_drafts ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.recipe_drafts FORCE ROW LEVEL SECURITY;
ALTER TABLE staff.recipe_draft_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.recipe_draft_revisions FORCE ROW LEVEL SECURITY;
REVOKE ALL ON staff.recipe_drafts,staff.recipe_draft_revisions FROM PUBLIC;

CREATE FUNCTION staff.guard_recipe_draft_head() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp
AS $feedme_draft_head$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Staff draft history is retained' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' AND (NEW.current_version<>1 OR NEW.updated_at<>NEW.created_at) THEN
  RAISE EXCEPTION 'Invalid first staff draft revision' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.recipe_id,NEW.id,NEW.author_id,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.environment,OLD.recipe_id,OLD.id,OLD.author_id,OLD.created_at)
   OR OLD.current_version=9223372036854775807 OR NEW.current_version<>OLD.current_version+1
   OR NEW.updated_at<OLD.updated_at) THEN
  RAISE EXCEPTION 'Invalid staff draft revision change' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_draft_head$;
CREATE TRIGGER staff_recipe_draft_head_guard BEFORE INSERT OR UPDATE OR DELETE ON staff.recipe_drafts
 FOR EACH ROW EXECUTE FUNCTION staff.guard_recipe_draft_head();
CREATE TRIGGER staff_recipe_draft_head_retained BEFORE TRUNCATE ON staff.recipe_drafts
 FOR EACH STATEMENT EXECUTE FUNCTION staff.guard_recipe_draft_head();
CREATE TRIGGER staff_recipe_draft_revision_immutable BEFORE UPDATE OR DELETE ON staff.recipe_draft_revisions
 FOR EACH ROW EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_recipe_draft_revision_retained BEFORE TRUNCATE ON staff.recipe_draft_revisions
 FOR EACH STATEMENT EXECUTE FUNCTION staff.reject_evidence_mutation();
REVOKE ALL ON FUNCTION staff.guard_recipe_draft_head() FROM PUBLIC;

-- A narrowly defined row-lock capability; never grant serving UPDATE on enrollment
-- merely to acquire FOR SHARE. Authentication and role/policy checks remain in the
-- caller, which must re-read the actual locked registry in this same transaction.
CREATE FUNCTION staff.lock_recipe_draft_actor(p_environment text,p_issuer text,p_subject text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_staff_draft_lock$
BEGIN
 IF current_setting('transaction_isolation')<>'read committed'
   OR current_setting('session_replication_role')<>'origin'
   OR p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_issuer IS NULL OR length(p_issuer) NOT BETWEEN 1 AND 2048
   OR p_subject IS NULL OR length(p_subject) NOT BETWEEN 1 AND 256 THEN RETURN false; END IF;
 PERFORM 1 FROM ONLY staff.publication_policies p JOIN ONLY staff.actors a ON a.environment=p.environment
  WHERE p.environment=p_environment AND p.issuer=p_issuer AND a.issuer=p_issuer AND a.subject=p_subject
  FOR SHARE OF p,a;
 RETURN FOUND;
END;
$feedme_staff_draft_lock$;
REVOKE ALL ON FUNCTION staff.lock_recipe_draft_actor(text,text,text) FROM PUBLIC;
