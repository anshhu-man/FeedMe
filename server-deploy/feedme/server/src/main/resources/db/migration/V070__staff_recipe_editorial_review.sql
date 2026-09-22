-- Unpublished, independently recorded editorial review. This does not enroll a
-- reviewer, certify professional qualifications, publish a catalog or grant copies.
-- Preserve every V068 row and keep one monotonic recipe revision/ETag sequence.
-- Compare PostgreSQL's deparsed constraints with the exact reviewed V068 baseline.
-- Unknown additional/altered checks abort rather than silently dropping schema drift.
CREATE TEMPORARY TABLE feedme_review_expected_checks (
 environment varchar(40) NOT NULL, recipe_id uuid NOT NULL, draft_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), author_id uuid NOT NULL, editor_id uuid NOT NULL CHECK(editor_id=author_id),
 principal_scope varchar(200) NOT NULL, operation_id varchar(100) NOT NULL,
 command_key uuid NOT NULL, request_hash char(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 request_text text NOT NULL CHECK(octet_length(request_text) BETWEEN 2 AND 65536),
 snapshot_text text NOT NULL CHECK(octet_length(snapshot_text) BETWEEN 2 AND 131072),
 snapshot_sha256 char(64) NOT NULL CHECK(snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz NOT NULL CHECK(isfinite(recorded_at)),
 CHECK(principal_scope=environment||':staff:'||editor_id::text),
 CHECK((version=1 AND operation_id='adminCreateRecipe') OR (version>1 AND operation_id='adminUpdateRecipe')),
 CHECK((jsonb_typeof(request_text::jsonb)='object' AND jsonb_typeof(snapshot_text::jsonb)='object'
   AND snapshot_text::jsonb->>'id'=draft_id::text AND snapshot_text::jsonb->>'recipeId'=recipe_id::text
   AND (snapshot_text::jsonb->>'version')::bigint=version AND snapshot_text::jsonb->>'reviewStatus'='draft'
   AND NOT(snapshot_text::jsonb ?| ARRAY['reviewedAt','reviewerLabel','recallReasonCode','contentLicense'])) IS TRUE)
) ON COMMIT DROP;
DO $feedme_review_constraints$
DECLARE existing record;
BEGIN
 IF (SELECT array_agg(pg_get_constraintdef(oid) ORDER BY pg_get_constraintdef(oid)) FROM pg_catalog.pg_constraint
      WHERE conrelid='staff.recipe_draft_revisions'::regclass AND contype='c') IS DISTINCT FROM
    (SELECT array_agg(pg_get_constraintdef(oid) ORDER BY pg_get_constraintdef(oid)) FROM pg_catalog.pg_constraint
      WHERE conrelid='pg_temp.feedme_review_expected_checks'::regclass AND contype='c') THEN
  RAISE EXCEPTION 'Unexpected staff draft constraint baseline' USING ERRCODE='23514';
 END IF;
 FOR existing IN SELECT conname FROM pg_catalog.pg_constraint
  WHERE conrelid='staff.recipe_draft_revisions'::regclass AND contype='c'
 LOOP EXECUTE format('ALTER TABLE staff.recipe_draft_revisions DROP CONSTRAINT %I',existing.conname); END LOOP;
END;
$feedme_review_constraints$;
ALTER TABLE staff.recipe_draft_revisions
 ADD CONSTRAINT staff_revision_positive CHECK(version>0),
 ADD CONSTRAINT staff_revision_actor CHECK((operation_id='adminReviewRecipe' AND editor_id<>author_id)
   OR (operation_id IN ('adminCreateRecipe','adminUpdateRecipe','adminSubmitRecipe') AND editor_id=author_id)),
 ADD CONSTRAINT staff_revision_request_hash CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 ADD CONSTRAINT staff_revision_request_bounds CHECK(octet_length(request_text) BETWEEN 2 AND 65536),
 ADD CONSTRAINT staff_revision_snapshot_bounds CHECK(octet_length(snapshot_text) BETWEEN 2 AND 131072),
 ADD CONSTRAINT staff_revision_snapshot_hash CHECK(snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 ADD CONSTRAINT staff_revision_recorded_finite CHECK(isfinite(recorded_at)),
 ADD CONSTRAINT staff_revision_scope CHECK(principal_scope=environment||':staff:'||editor_id::text),
 ADD CONSTRAINT staff_revision_operation CHECK((version=1 AND operation_id='adminCreateRecipe')
   OR (version>1 AND operation_id IN ('adminUpdateRecipe','adminSubmitRecipe','adminReviewRecipe'))),
 ADD CONSTRAINT staff_revision_snapshot_identity CHECK((jsonb_typeof(request_text::jsonb)='object'
   AND jsonb_typeof(snapshot_text::jsonb)='object'
   AND snapshot_text::jsonb->>'id'=draft_id::text AND snapshot_text::jsonb->>'recipeId'=recipe_id::text
   AND (snapshot_text::jsonb->>'version')::bigint=version
   AND NOT(snapshot_text::jsonb ?| ARRAY['reviewedAt','reviewerLabel','recallReasonCode','contentLicense'])
   AND ((operation_id IN ('adminCreateRecipe','adminUpdateRecipe') AND snapshot_text::jsonb->>'reviewStatus'='draft'
      AND NOT(snapshot_text::jsonb ?| ARRAY['submission','latestReview']))
    OR (operation_id='adminSubmitRecipe' AND snapshot_text::jsonb->>'reviewStatus'='inReview'
      AND jsonb_typeof(snapshot_text::jsonb->'submission')='object' AND NOT(snapshot_text::jsonb ? 'latestReview'))
    OR (operation_id='adminReviewRecipe' AND snapshot_text::jsonb->>'reviewStatus' IN ('approved','changesRequested','rejected')
      AND jsonb_typeof(snapshot_text::jsonb->'submission')='object' AND jsonb_typeof(snapshot_text::jsonb->'latestReview')='object'))
   ) IS TRUE);

CREATE TABLE staff.recipe_submissions (
 environment varchar(40) NOT NULL, draft_id uuid NOT NULL, submission_id uuid NOT NULL,
 source_version bigint NOT NULL CHECK(source_version>0), submitted_version bigint NOT NULL CHECK(submitted_version=source_version+1),
 author_id uuid NOT NULL, material_sha256 char(64) NOT NULL CHECK(material_sha256 ~ '^[0-9a-f]{64}$'),
 submission_text text NOT NULL CHECK(octet_length(submission_text) BETWEEN 2 AND 16384),
 submission_sha256 char(64) NOT NULL CHECK(submission_sha256 ~ '^[0-9a-f]{64}$'),
 submitted_at timestamptz NOT NULL CHECK(isfinite(submitted_at)),
 PRIMARY KEY(environment,submission_id), UNIQUE(environment,draft_id,submitted_version),
 UNIQUE(environment,draft_id,submission_id,submitted_version,author_id),
 FOREIGN KEY(environment,draft_id,source_version) REFERENCES staff.recipe_draft_revisions(environment,draft_id,version),
 FOREIGN KEY(environment,draft_id,submitted_version) REFERENCES staff.recipe_draft_revisions(environment,draft_id,version)
  DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(environment,author_id) REFERENCES staff.actors(environment,actor_id),
 CHECK((jsonb_typeof(submission_text::jsonb)='object' AND submission_text::jsonb->>'id'=submission_id::text
   AND (submission_text::jsonb->>'sourceVersion')::bigint=source_version
   AND (submission_text::jsonb->>'submittedVersion')::bigint=submitted_version
   AND submission_text::jsonb->>'materialSha256'=material_sha256::text) IS TRUE)
);
CREATE TABLE staff.recipe_reviews (
 environment varchar(40) NOT NULL, draft_id uuid NOT NULL, review_id uuid NOT NULL,
 submission_id uuid NOT NULL, submitted_version bigint NOT NULL, result_version bigint NOT NULL CHECK(result_version=submitted_version+1),
 author_id uuid NOT NULL, reviewer_id uuid NOT NULL CHECK(reviewer_id<>author_id),
 decision varchar(32) NOT NULL CHECK(decision IN ('approve','changesRequested','reject')),
 material_sha256 char(64) NOT NULL CHECK(material_sha256 ~ '^[0-9a-f]{64}$'),
 review_text text NOT NULL CHECK(octet_length(review_text) BETWEEN 2 AND 65536),
 review_sha256 char(64) NOT NULL CHECK(review_sha256 ~ '^[0-9a-f]{64}$'),
 reviewed_at timestamptz NOT NULL CHECK(isfinite(reviewed_at)),
 PRIMARY KEY(environment,review_id), UNIQUE(environment,draft_id,result_version), UNIQUE(environment,submission_id),
 FOREIGN KEY(environment,draft_id,submission_id,submitted_version,author_id)
  REFERENCES staff.recipe_submissions(environment,draft_id,submission_id,submitted_version,author_id),
 FOREIGN KEY(environment,draft_id,result_version) REFERENCES staff.recipe_draft_revisions(environment,draft_id,version)
  DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(environment,reviewer_id) REFERENCES staff.actors(environment,actor_id),
 CHECK((jsonb_typeof(review_text::jsonb)='object' AND review_text::jsonb->>'id'=review_id::text
   AND review_text::jsonb->>'submissionId'=submission_id::text
   AND (review_text::jsonb->>'submittedVersion')::bigint=submitted_version
   AND (review_text::jsonb->>'version')::bigint=result_version
   AND review_text::jsonb->>'recipeVersionId'=draft_id::text
   AND review_text::jsonb->>'reviewerId'=reviewer_id::text AND review_text::jsonb->>'decision'=decision) IS TRUE)
);
ALTER TABLE staff.recipe_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.recipe_submissions FORCE ROW LEVEL SECURITY;
ALTER TABLE staff.recipe_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.recipe_reviews FORCE ROW LEVEL SECURITY;
REVOKE ALL ON staff.recipe_submissions,staff.recipe_reviews FROM PUBLIC;
CREATE TRIGGER staff_recipe_submissions_immutable BEFORE UPDATE OR DELETE ON staff.recipe_submissions
 FOR EACH ROW EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_recipe_submissions_retained BEFORE TRUNCATE ON staff.recipe_submissions
 FOR EACH STATEMENT EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_recipe_reviews_immutable BEFORE UPDATE OR DELETE ON staff.recipe_reviews
 FOR EACH ROW EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_recipe_reviews_retained BEFORE TRUNCATE ON staff.recipe_reviews
 FOR EACH STATEMENT EXECUTE FUNCTION staff.reject_evidence_mutation();

-- A direct SQL writer cannot attach a review to an unrelated submission/revision,
-- revise frozen material, or restore editing while a decision is pending.
CREATE FUNCTION staff.guard_recipe_editorial_revision() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp
AS $feedme_editorial_revision$
DECLARE prior record; submitted record; reviewed record;
BEGIN
 IF NEW.version=1 THEN RETURN NEW; END IF;
 SELECT * INTO STRICT prior FROM ONLY staff.recipe_draft_revisions
  WHERE environment=NEW.environment AND draft_id=NEW.draft_id AND version=NEW.version-1;
 IF NEW.recorded_at<prior.recorded_at THEN RAISE EXCEPTION 'Recipe review time moved backwards' USING ERRCODE='23514'; END IF;
 IF NEW.operation_id='adminUpdateRecipe' THEN
  IF prior.snapshot_text::jsonb->>'reviewStatus' NOT IN ('draft','changesRequested') THEN
   RAISE EXCEPTION 'Recipe material is frozen' USING ERRCODE='23514'; END IF;
 ELSIF NEW.operation_id='adminSubmitRecipe' THEN
  SELECT * INTO STRICT submitted FROM ONLY staff.recipe_submissions
   WHERE environment=NEW.environment AND draft_id=NEW.draft_id AND submitted_version=NEW.version;
  IF prior.snapshot_text::jsonb->>'reviewStatus'<>'draft' OR submitted.author_id<>NEW.author_id
   OR submitted.submitted_at<>NEW.recorded_at OR submitted.submission_text::jsonb<>NEW.snapshot_text::jsonb->'submission'
   OR (prior.snapshot_text::jsonb-ARRAY['version','updatedAt','reviewStatus'])<>
      (NEW.snapshot_text::jsonb-ARRAY['version','updatedAt','reviewStatus','submission']) THEN
   RAISE EXCEPTION 'Invalid exact recipe submission' USING ERRCODE='23514'; END IF;
 ELSIF NEW.operation_id='adminReviewRecipe' THEN
  SELECT * INTO STRICT reviewed FROM ONLY staff.recipe_reviews
   WHERE environment=NEW.environment AND draft_id=NEW.draft_id AND result_version=NEW.version;
  IF prior.snapshot_text::jsonb->>'reviewStatus'<>'inReview' OR reviewed.author_id<>NEW.author_id
   OR reviewed.reviewer_id<>NEW.editor_id OR reviewed.reviewed_at<>NEW.recorded_at
   OR reviewed.review_text::jsonb<>NEW.snapshot_text::jsonb->'latestReview'
   OR reviewed.submission_id::text<>prior.snapshot_text::jsonb->'submission'->>'id'
   OR reviewed.material_sha256::text<>prior.snapshot_text::jsonb->'submission'->>'materialSha256'
   OR NEW.snapshot_text::jsonb->>'reviewStatus'<>(CASE reviewed.decision WHEN 'approve' THEN 'approved'
       WHEN 'changesRequested' THEN 'changesRequested' ELSE 'rejected' END)
   OR (prior.snapshot_text::jsonb-ARRAY['version','updatedAt','reviewStatus'])<>
      (NEW.snapshot_text::jsonb-ARRAY['version','updatedAt','reviewStatus','latestReview']) THEN
   RAISE EXCEPTION 'Invalid independent recipe review' USING ERRCODE='23514'; END IF;
 ELSE RAISE EXCEPTION 'Invalid recipe editorial operation' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_editorial_revision$;
CREATE TRIGGER staff_recipe_editorial_revision BEFORE INSERT ON staff.recipe_draft_revisions
 FOR EACH ROW EXECUTE FUNCTION staff.guard_recipe_editorial_revision();
REVOKE ALL ON FUNCTION staff.guard_recipe_editorial_revision() FROM PUBLIC;
