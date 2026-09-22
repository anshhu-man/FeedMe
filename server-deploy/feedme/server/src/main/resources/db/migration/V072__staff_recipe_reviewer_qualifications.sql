-- Owner-managed evidence only. No staff enrollment, professional credentials,
-- attestation, qualification row, serving grant or publication is seeded here.
CREATE TABLE staff.reviewer_qualifications (
 environment varchar(40) NOT NULL,
 qualification_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),
 actor_id uuid NOT NULL,
 scope varchar(64) NOT NULL CHECK(scope='nutritionReview'),
 evidence_reference varchar(256) NOT NULL CHECK(length(btrim(evidence_reference))>0 AND evidence_reference !~ '[[:cntrl:]]'),
 policy_version varchar(128) NOT NULL CHECK(length(btrim(policy_version))>0 AND policy_version !~ '[[:cntrl:]]'),
 verified_at timestamptz NOT NULL,
 not_before timestamptz NOT NULL,
 valid_until timestamptz NOT NULL,
 enabled boolean NOT NULL,
 PRIMARY KEY(environment,qualification_id),
 FOREIGN KEY(environment,actor_id) REFERENCES staff.actors(environment,actor_id),
 CHECK(isfinite(verified_at) AND isfinite(not_before) AND isfinite(valid_until)
   AND not_before<valid_until AND verified_at<valid_until)
);
ALTER TABLE staff.reviewer_qualifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.reviewer_qualifications FORCE ROW LEVEL SECURITY;
REVOKE ALL ON staff.reviewer_qualifications FROM PUBLIC;

-- The owner may disable an existing qualification, never rewrite its evidence or
-- restore a revoked identity. A corrected/renewed qualification needs a new ID.
-- Serving UPDATE(qualification_id) is only for FOR SHARE and cannot mutate a row.
CREATE FUNCTION staff.guard_recipe_reviewer_qualification() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp
AS $feedme_reviewer_qualification_guard$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Reviewer qualification evidence is retained' USING ERRCODE='23514';
 END IF;
 IF TG_OP<>'UPDATE' OR NOT OLD.enabled OR NEW.enabled
   OR ROW(NEW.environment,NEW.qualification_id,NEW.version,NEW.actor_id,NEW.scope,
      NEW.evidence_reference,NEW.policy_version,NEW.verified_at,NEW.not_before,NEW.valid_until)
     IS DISTINCT FROM ROW(OLD.environment,OLD.qualification_id,OLD.version,OLD.actor_id,OLD.scope,
      OLD.evidence_reference,OLD.policy_version,OLD.verified_at,OLD.not_before,OLD.valid_until) THEN
  RAISE EXCEPTION 'Reviewer qualification evidence is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_reviewer_qualification_guard$;
CREATE TRIGGER staff_reviewer_qualification_immutable BEFORE UPDATE OR DELETE ON staff.reviewer_qualifications
 FOR EACH ROW EXECUTE FUNCTION staff.guard_recipe_reviewer_qualification();
CREATE TRIGGER staff_reviewer_qualification_retained BEFORE TRUNCATE ON staff.reviewer_qualifications
 FOR EACH STATEMENT EXECUTE FUNCTION staff.guard_recipe_reviewer_qualification();
REVOKE ALL ON FUNCTION staff.guard_recipe_reviewer_qualification() FROM PUBLIC;
