-- Canonical F30 post grants remain immutable private saved copies. No social/media rows,
-- credentials, grants, legacy migration bodies or existing copy authority are changed.
DO $feedme_post_copy_constraints$
DECLARE source_constraint text; source_column smallint; recipe_column smallint; plan_column smallint;
BEGIN
 SELECT attnum INTO source_column FROM pg_attribute WHERE attrelid='memory.saved_recipes'::regclass AND attname='source_id' AND NOT attisdropped;
 SELECT attnum INTO recipe_column FROM pg_attribute WHERE attrelid='memory.saved_recipes'::regclass AND attname='recipe_version_id' AND NOT attisdropped;
 SELECT attnum INTO plan_column FROM pg_attribute WHERE attrelid='memory.saved_recipes'::regclass AND attname='origin_plan_id' AND NOT attisdropped;
 SELECT conname INTO STRICT source_constraint FROM pg_constraint WHERE conrelid='memory.saved_recipes'::regclass
  AND contype='c' AND conkey @> ARRAY[source_column,recipe_column,plan_column]::smallint[]
  AND cardinality(conkey)=3;
 EXECUTE format('ALTER TABLE memory.saved_recipes DROP CONSTRAINT %I',source_constraint);
END;
$feedme_post_copy_constraints$;
ALTER TABLE memory.saved_recipes DROP CONSTRAINT saved_recipes_source_type_check;
ALTER TABLE memory.saved_recipes ADD CONSTRAINT saved_recipes_source_type_check
 CHECK(source_type IN ('ownPlan','catalog','postGrant'));
ALTER TABLE memory.saved_recipes ADD CONSTRAINT saved_recipes_exact_source
 CHECK((source_type='postGrant' AND actor_kind='account' AND origin_plan_id IS NULL AND content_license='privateCopyOnly')
   OR (source_type IN ('ownPlan','catalog') AND source_id=coalesce(origin_plan_id,recipe_version_id)));
ALTER TABLE memory.saved_recipes ADD CONSTRAINT saved_recipes_post_grant_shape
 CHECK(source_type<>'postGrant' OR deleted OR (
   snapshot->>'sourceType'='postGrant' AND snapshot->>'sourcePostId'=source_id::text
   AND snapshot->>'contentLicense'='privateCopyOnly'
   AND copy_evidence->'formatVersion'='2'::jsonb AND copy_evidence->>'recipeHash'=recipe_hash::text
   AND jsonb_typeof(copy_evidence->'postGrant')='object'
   AND copy_evidence->'postGrant'->>'postId'=source_id::text
   AND copy_evidence->'postGrant'->>'id'=snapshot->>'grantId'
   AND copy_evidence->'postGrant'->>'creatorLabel'=snapshot->>'creatorLabel'
   AND copy_evidence->'postGrant'->>'rights'='PRIVATE_RECIPE_COPY'
   AND copy_evidence->'postGrant' ?& ARRAY['id','postId','postVersion','authorId','creatorLabel','policyVersion','disclosureVersion','authorizedAt','rights']
   AND snapshot ?& ARRAY['sourcePostId','grantId','creatorLabel']) IS TRUE);
