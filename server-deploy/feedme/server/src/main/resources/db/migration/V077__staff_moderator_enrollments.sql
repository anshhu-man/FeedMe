-- Explicit owner-managed moderation authority. Catalog roles do not imply it.
-- No enrollment, staff identity, provider setting or serving grant is created.
CREATE TABLE staff.moderator_enrollments (
 environment varchar(40) NOT NULL,
 actor_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),
 policy_version varchar(128) NOT NULL CHECK(length(btrim(policy_version))>0 AND policy_version !~ '[[:cntrl:]]'),
 enabled boolean NOT NULL DEFAULT false,
 not_before timestamptz NOT NULL,
 valid_until timestamptz NOT NULL,
 PRIMARY KEY(environment,actor_id),
 FOREIGN KEY(environment,actor_id) REFERENCES staff.actors(environment,actor_id),
 CHECK(isfinite(not_before) AND isfinite(valid_until) AND not_before<valid_until)
);
ALTER TABLE staff.moderator_enrollments ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.moderator_enrollments FORCE ROW LEVEL SECURITY;
REVOKE ALL ON staff.moderator_enrollments FROM PUBLIC;

CREATE FUNCTION staff.guard_moderator_enrollment() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp
AS $feedme_moderator_guard$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Moderator enrollment identity is retained' USING ERRCODE='23514';
 END IF;
 IF NEW.version IS NULL OR NEW.version<1 OR NEW.policy_version IS NULL
   OR length(btrim(NEW.policy_version)) NOT BETWEEN 1 AND 128 OR NEW.policy_version ~ '[[:cntrl:]]'
   OR NEW.enabled IS NULL OR NEW.not_before IS NULL OR NEW.valid_until IS NULL
   OR NOT isfinite(NEW.not_before) OR NOT isfinite(NEW.valid_until) OR NEW.not_before>=NEW.valid_until THEN
  RAISE EXCEPTION 'Invalid moderator enrollment' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' AND NEW.version<>1 THEN
  RAISE EXCEPTION 'Moderator enrollment begins at version one' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.actor_id) IS DISTINCT FROM ROW(OLD.environment,OLD.actor_id)
   OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1) THEN
  RAISE EXCEPTION 'Moderator enrollment requires original identity and next version' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_moderator_guard$;
CREATE TRIGGER staff_moderator_enrollment_guard BEFORE INSERT OR UPDATE OR DELETE ON staff.moderator_enrollments
 FOR EACH ROW EXECUTE FUNCTION staff.guard_moderator_enrollment();
CREATE TRIGGER staff_moderator_enrollment_retained BEFORE TRUNCATE ON staff.moderator_enrollments
 FOR EACH STATEMENT EXECUTE FUNCTION staff.guard_moderator_enrollment();
REVOKE ALL ON FUNCTION staff.guard_moderator_enrollment() FROM PUBLIC;

-- Locks only: serving needs no UPDATE privilege on enrollment, identity or policy.
-- The caller must still validate the actual provider, MFA and every observed row.
CREATE FUNCTION staff.lock_moderation_actor(p_environment text,p_issuer text,p_subject text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_moderator_lock$
BEGIN
 IF current_setting('transaction_isolation')<>'read committed'
   OR current_setting('session_replication_role')<>'origin'
   OR p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
   OR p_issuer IS NULL OR length(p_issuer) NOT BETWEEN 1 AND 2048
   OR p_subject IS NULL OR length(p_subject) NOT BETWEEN 1 AND 256 THEN RETURN false; END IF;
 PERFORM 1 FROM ONLY staff.publication_policies p JOIN ONLY staff.actors a ON a.environment=p.environment
  JOIN ONLY staff.moderator_enrollments m ON m.environment=a.environment AND m.actor_id=a.actor_id
  WHERE p.environment=p_environment AND p.issuer=p_issuer AND a.issuer=p_issuer AND a.subject=p_subject
  FOR SHARE OF p,a,m;
 RETURN FOUND;
END;
$feedme_moderator_lock$;
REVOKE ALL ON FUNCTION staff.lock_moderation_actor(text,text,text) FROM PUBLIC;
