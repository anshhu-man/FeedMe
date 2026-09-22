-- Same-transaction staff/catalog lifecycle bridge. No credential enrollment,
-- qualification, publication, recall, copy grant or external deployment is seeded.
CREATE TEMPORARY TABLE feedme_publication_expected_checks
 (LIKE staff.recipe_draft_revisions EXCLUDING CONSTRAINTS) ON COMMIT DROP;
ALTER TABLE feedme_publication_expected_checks
 ADD CONSTRAINT previous_actor CHECK((operation_id='adminReviewRecipe' AND editor_id<>author_id)
   OR (operation_id IN ('adminCreateRecipe','adminUpdateRecipe','adminSubmitRecipe') AND editor_id=author_id)),
 ADD CONSTRAINT previous_operation CHECK((version=1 AND operation_id='adminCreateRecipe')
   OR (version>1 AND operation_id IN ('adminUpdateRecipe','adminSubmitRecipe','adminReviewRecipe'))),
 ADD CONSTRAINT previous_snapshot CHECK((jsonb_typeof(request_text::jsonb)='object'
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
DO $feedme_publication_constraints$
BEGIN
 IF (SELECT array_agg(pg_get_constraintdef(oid) ORDER BY pg_get_constraintdef(oid)) FROM pg_catalog.pg_constraint
      WHERE conrelid='staff.recipe_draft_revisions'::regclass AND contype='c'
        AND conname IN ('staff_revision_actor','staff_revision_operation','staff_revision_snapshot_identity')) IS DISTINCT FROM
    (SELECT array_agg(pg_get_constraintdef(oid) ORDER BY pg_get_constraintdef(oid)) FROM pg_catalog.pg_constraint
      WHERE conrelid='pg_temp.feedme_publication_expected_checks'::regclass AND contype='c') THEN
  RAISE EXCEPTION 'Unexpected staff review constraint baseline' USING ERRCODE='23514';
 END IF;
END;
$feedme_publication_constraints$;
ALTER TABLE staff.recipe_draft_revisions
 DROP CONSTRAINT staff_revision_actor, DROP CONSTRAINT staff_revision_operation, DROP CONSTRAINT staff_revision_snapshot_identity,
 ADD CONSTRAINT staff_revision_actor CHECK((operation_id='adminReviewRecipe' AND editor_id<>author_id)
   OR (operation_id IN ('adminCreateRecipe','adminUpdateRecipe','adminSubmitRecipe') AND editor_id=author_id)
   OR operation_id IN ('adminPublishRecipe','adminRecallRecipe')),
 ADD CONSTRAINT staff_revision_operation CHECK((version=1 AND operation_id='adminCreateRecipe')
   OR (version>1 AND operation_id IN ('adminUpdateRecipe','adminSubmitRecipe','adminReviewRecipe','adminPublishRecipe','adminRecallRecipe'))),
 ADD CONSTRAINT staff_revision_snapshot_identity CHECK((jsonb_typeof(request_text::jsonb)='object' AND jsonb_typeof(snapshot_text::jsonb)='object'
   AND snapshot_text::jsonb->>'id'=draft_id::text AND snapshot_text::jsonb->>'recipeId'=recipe_id::text
   AND (snapshot_text::jsonb->>'version')::bigint=version AND NOT(snapshot_text::jsonb ? 'reviewerLabel')
   AND ((operation_id IN ('adminCreateRecipe','adminUpdateRecipe','adminSubmitRecipe','adminReviewRecipe')
     AND NOT(snapshot_text::jsonb ?| ARRAY['reviewedAt','recallReasonCode','contentLicense'])
     AND ((operation_id IN ('adminCreateRecipe','adminUpdateRecipe') AND snapshot_text::jsonb->>'reviewStatus'='draft'
       AND NOT(snapshot_text::jsonb ?| ARRAY['submission','latestReview']))
      OR (operation_id='adminSubmitRecipe' AND snapshot_text::jsonb->>'reviewStatus'='inReview'
       AND jsonb_typeof(snapshot_text::jsonb->'submission')='object' AND NOT(snapshot_text::jsonb ? 'latestReview'))
      OR (operation_id='adminReviewRecipe' AND snapshot_text::jsonb->>'reviewStatus' IN ('approved','changesRequested','rejected')
       AND jsonb_typeof(snapshot_text::jsonb->'submission')='object' AND jsonb_typeof(snapshot_text::jsonb->'latestReview')='object')))
    OR (operation_id IN ('adminPublishRecipe','adminRecallRecipe')
     AND jsonb_typeof(snapshot_text::jsonb->'submission')='object' AND jsonb_typeof(snapshot_text::jsonb->'latestReview')='object'
     AND jsonb_typeof(snapshot_text::jsonb->'reviewedAt')='string' AND jsonb_typeof(snapshot_text::jsonb->'contentLicense')='string'
     AND ((operation_id='adminPublishRecipe' AND snapshot_text::jsonb->>'reviewStatus'='published' AND NOT(snapshot_text::jsonb ? 'recallReasonCode'))
       OR (operation_id='adminRecallRecipe' AND snapshot_text::jsonb->>'reviewStatus'='recalled' AND jsonb_typeof(snapshot_text::jsonb->'recallReasonCode')='string'))))
   ) IS TRUE);

CREATE TABLE staff.recipe_publication_commands (
 environment varchar(40) NOT NULL, draft_id uuid NOT NULL,
 source_version bigint NOT NULL CHECK(source_version>0), result_version bigint NOT NULL CHECK(result_version=source_version+1),
 actor_id uuid NOT NULL, publisher_id uuid NOT NULL, reviewer_id uuid NOT NULL CHECK(reviewer_id<>publisher_id),
 operation_id varchar(100) NOT NULL CHECK(operation_id IN ('adminPublishRecipe','adminRecallRecipe')),
 release_id uuid NOT NULL, catalog_revision bigint NOT NULL CHECK(catalog_revision>0),
 catalog_request_hash char(64) NOT NULL CHECK(catalog_request_hash ~ '^[0-9a-f]{64}$'),
 public_recipe_text text NOT NULL CHECK(octet_length(public_recipe_text) BETWEEN 2 AND 65536),
 public_recipe_sha256 char(64) NOT NULL CHECK(public_recipe_sha256 ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz NOT NULL CHECK(isfinite(recorded_at)),
 PRIMARY KEY(environment,draft_id,result_version), UNIQUE(environment,release_id),
 FOREIGN KEY(environment,draft_id,source_version) REFERENCES staff.recipe_draft_revisions(environment,draft_id,version),
 FOREIGN KEY(environment,draft_id,result_version) REFERENCES staff.recipe_draft_revisions(environment,draft_id,version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(environment,actor_id) REFERENCES staff.actors(environment,actor_id),
 FOREIGN KEY(environment,publisher_id) REFERENCES staff.actors(environment,actor_id),
 FOREIGN KEY(environment,reviewer_id) REFERENCES staff.actors(environment,actor_id),
 FOREIGN KEY(environment,release_id,catalog_revision) REFERENCES catalog.recipe_releases(environment,release_id,revision),
 CHECK((jsonb_typeof(public_recipe_text::jsonb)='object' AND public_recipe_text::jsonb->>'id'=draft_id::text
   AND (public_recipe_text::jsonb->>'version')::bigint=result_version
   AND NOT(public_recipe_text::jsonb ?| ARRAY['submission','latestReview'])
   AND ((operation_id='adminPublishRecipe' AND actor_id=publisher_id AND public_recipe_text::jsonb->>'reviewStatus'='published')
    OR (operation_id='adminRecallRecipe' AND public_recipe_text::jsonb->>'reviewStatus'='recalled'))) IS TRUE)
);
ALTER TABLE staff.recipe_publication_commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.recipe_publication_commands FORCE ROW LEVEL SECURITY;
REVOKE ALL ON staff.recipe_publication_commands FROM PUBLIC;
CREATE TRIGGER staff_recipe_publications_immutable BEFORE UPDATE OR DELETE ON staff.recipe_publication_commands
 FOR EACH ROW EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_recipe_publications_retained BEFORE TRUNCATE ON staff.recipe_publication_commands
 FOR EACH STATEMENT EXECUTE FUNCTION staff.reject_evidence_mutation();

CREATE OR REPLACE FUNCTION staff.guard_recipe_editorial_revision() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog,pg_temp
AS $feedme_publication_revision$
DECLARE prior record; submitted record; reviewed record; published record;
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
 ELSIF NEW.operation_id IN ('adminPublishRecipe','adminRecallRecipe') THEN
  SELECT * INTO STRICT published FROM ONLY staff.recipe_publication_commands
   WHERE environment=NEW.environment AND draft_id=NEW.draft_id AND result_version=NEW.version;
  IF published.actor_id<>NEW.editor_id OR published.source_version<>NEW.version-1
   OR published.operation_id<>NEW.operation_id OR published.recorded_at<>NEW.recorded_at
   OR published.public_recipe_text::jsonb<>(NEW.snapshot_text::jsonb-ARRAY['submission','latestReview'])
   OR published.reviewer_id::text<>prior.snapshot_text::jsonb->'latestReview'->>'reviewerId' THEN
   RAISE EXCEPTION 'Invalid exact recipe catalog action' USING ERRCODE='23514'; END IF;
  IF NEW.operation_id='adminPublishRecipe' THEN
   IF prior.snapshot_text::jsonb->>'reviewStatus'<>'approved'
    OR NEW.snapshot_text::jsonb->>'reviewStatus'<>'published'
    OR published.publisher_id<>NEW.editor_id OR published.publisher_id=published.reviewer_id
    OR (prior.snapshot_text::jsonb-ARRAY['version','updatedAt','reviewStatus'])<>
       (NEW.snapshot_text::jsonb-ARRAY['version','updatedAt','reviewStatus','contentLicense','reviewedAt']) THEN
    RAISE EXCEPTION 'Invalid approved recipe publication' USING ERRCODE='23514'; END IF;
  ELSE
   IF prior.snapshot_text::jsonb->>'reviewStatus' NOT IN ('published','retired')
    OR NEW.snapshot_text::jsonb->>'reviewStatus'<>'recalled'
    OR (prior.snapshot_text::jsonb-ARRAY['version','updatedAt','reviewStatus'])<>
       (NEW.snapshot_text::jsonb-ARRAY['version','updatedAt','reviewStatus','recallReasonCode']) THEN
    RAISE EXCEPTION 'Invalid recipe safety withdrawal' USING ERRCODE='23514'; END IF;
  END IF;
 ELSE RAISE EXCEPTION 'Invalid recipe editorial operation' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_publication_revision$;
REVOKE ALL ON FUNCTION staff.guard_recipe_editorial_revision() FROM PUBLIC;

