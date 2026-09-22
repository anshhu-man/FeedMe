-- Account Saved -> Make Mine root storage. Additive format 5; formats 1--4 and
-- every previous migration/function body remain unchanged. Exact Saved identity
-- and original command survive; the runtime still must prove actual source rights.
ALTER TABLE planning.plan_requests
    DROP CONSTRAINT plan_requests_storage_format_check,
    ADD CONSTRAINT plan_requests_storage_format_check CHECK (storage_format IN (1,2,3,4,5)),
    DROP CONSTRAINT plan_requests_derived_operation_check,
    ADD CONSTRAINT plan_requests_derived_operation_check CHECK (derived_operation IN ('adaptPlan','simplifyPlan','createPlan'));
ALTER TABLE planning.plan_requests DROP CONSTRAINT planning_request_representation;
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_request_representation CHECK (
    (storage_format=1 AND evidence_text IS NOT NULL AND evidence_hash IS NOT NULL AND ordered_ids IS NOT NULL
        AND manifest_id IS NULL AND create_command_key IS NULL AND command_request_sha256 IS NULL)
    OR (storage_format=2 AND actor_kind='guest' AND manifest_id IS NOT NULL AND id=manifest_id
        AND create_command_key IS NOT NULL AND command_request_sha256 IS NOT NULL
        AND evidence_text IS NULL AND evidence_hash IS NULL AND ordered_ids IS NULL)
    OR (storage_format IN (3,4,5) AND evidence_text IS NOT NULL AND evidence_hash IS NOT NULL AND ordered_ids IS NULL
        AND manifest_id IS NULL AND create_command_key IS NULL AND command_request_sha256 IS NULL)
);
ALTER TABLE planning.plan_requests DROP CONSTRAINT planning_derived_request_fields;
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_derived_request_fields CHECK (
    (storage_format IN (1,2) AND derived_operation IS NULL AND derived_command_key IS NULL
        AND derived_request_sha256 IS NULL AND derived_if_match IS NULL
        AND derived_parent_plan_id IS NULL AND derived_parent_version IS NULL)
    OR (storage_format=3 AND derived_operation IN ('adaptPlan','simplifyPlan') AND derived_operation IS NOT NULL
        AND derived_command_key IS NOT NULL AND derived_request_sha256 IS NOT NULL AND derived_if_match IS NOT NULL
        AND derived_parent_plan_id IS NOT NULL AND derived_parent_version IS NOT NULL
        AND current_plan_id IS NOT NULL AND current_plan_id<>derived_parent_plan_id AND version=1
        AND cursor_expires_at=expires_at)
    OR (storage_format IN (4,5) AND actor_kind='account' AND derived_operation='createPlan' AND derived_operation IS NOT NULL
        AND derived_command_key IS NOT NULL AND derived_request_sha256 IS NOT NULL
        AND derived_if_match IS NULL AND derived_parent_plan_id IS NULL AND derived_parent_version IS NULL
        AND current_plan_id IS NOT NULL AND version=1 AND cursor_expires_at=expires_at)
);
CREATE UNIQUE INDEX root_saved_original_command ON planning.plan_requests(
    environment,actor_kind,principal_id,derived_operation,derived_command_key) WHERE storage_format=5;

ALTER TABLE planning.plans
    DROP CONSTRAINT plans_storage_format_check,
    ADD CONSTRAINT plans_storage_format_check CHECK (storage_format IN (1,2,3,4,5));
ALTER TABLE planning.plans DROP CONSTRAINT plans_position_check;
ALTER TABLE planning.plans ADD CONSTRAINT plans_position_check CHECK (
    (storage_format=1 AND position BETWEEN -1 AND 128) OR (storage_format=2 AND position>=-1)
    OR (storage_format IN (3,4,5) AND position=(CASE WHEN status='ready' THEN 0 ELSE -1 END))
);
ALTER TABLE planning.plans ADD CONSTRAINT planning_root_saved_fields CHECK (
    storage_format<>5 OR (actor_kind='account' AND parent_plan_id IS NULL AND next_cursor_hash IS NULL)
);
CREATE UNIQUE INDEX root_saved_one_result ON planning.plans(environment,actor_kind,principal_id,request_id)
    WHERE storage_format=5;

CREATE FUNCTION planning.reject_root_saved_request_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Root Saved Plan original is immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER root_saved_request_immutable BEFORE UPDATE ON planning.plan_requests
    FOR EACH ROW WHEN (OLD.storage_format=5) EXECUTE FUNCTION planning.reject_root_saved_request_update();

CREATE FUNCTION planning.guard_root_saved_request_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE document jsonb; request jsonb; source jsonb; saved jsonb; identity jsonb;
    context_keys text[] := ARRAY['version','owner','operationId','commandKey','commandRequestHash','requestText',
        'requestSha256','inputsText','policy','catalogAnchor','traversedCount','eligibleCount','provenance',
        'planId','createdAt','expiresAt'];
    source_keys text[] := ARRAY['recipeVersionId','materialSha256','releaseId','revision','requestSha256',
        'recipeText','reviewText','rightsReference'];
    identity_keys text[] := ARRAY['environment','principalId','savedRecipeId','generation','version',
        'recipeVersionId','recipeHash','sourceType','sourceId','originPlanId','contentLicense','copyEvidence','allowReviewedScaling'];
BEGIN
    document := NEW.evidence_text::jsonb; request := NEW.request_text::jsonb;
    source := document->'provenance'->'source';
    saved := document->'provenance'->'savedSource'; identity := saved->'identity';
    IF NEW.request_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.request_text,'UTF8')),'hex')
        OR NEW.evidence_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.evidence_text,'UTF8')),'hex')
        OR NOT (document ?& context_keys) OR (document-context_keys) IS DISTINCT FROM '{}'::jsonb
        OR document->'version' IS DISTINCT FROM '1'::jsonb
        OR document->'owner' IS DISTINCT FROM jsonb_build_object(
            'environment',NEW.environment,'actorKind',NEW.actor_kind,'principalId',NEW.principal_id::text)
        OR document->>'operationId' IS DISTINCT FROM 'createPlan'
        OR document->>'operationId' IS DISTINCT FROM NEW.derived_operation
        OR document->>'commandKey' IS DISTINCT FROM NEW.derived_command_key::text
        OR document->>'commandRequestHash' IS DISTINCT FROM NEW.derived_request_sha256
        OR document->>'planId' IS DISTINCT FROM NEW.current_plan_id::text
        OR document->>'requestText' IS DISTINCT FROM NEW.request_text
        OR document->>'requestSha256' IS DISTINCT FROM NEW.request_hash
        OR document->'policy' IS DISTINCT FROM NEW.policy_text::jsonb
        OR jsonb_typeof(document->'inputsText') IS DISTINCT FROM 'string'
        OR jsonb_typeof(document->'catalogAnchor') IS DISTINCT FROM 'object'
        OR jsonb_typeof(document->'provenance') IS DISTINCT FROM 'object'
        OR document->'provenance'->>'kind' IS DISTINCT FROM 'savedRootAdaptation'
        OR jsonb_typeof(source) IS DISTINCT FROM 'object'
        OR NOT coalesce(source ?& source_keys,false) OR (source-source_keys) IS DISTINCT FROM '{}'::jsonb
        OR request->>'intent' IS DISTINCT FROM 'makeMine'
        OR jsonb_typeof(request->'savedRecipeId') IS DISTINCT FROM 'string'
        OR NOT coalesce(request->>'savedRecipeId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',false)
        OR request ?| ARRAY['sourcePostId','sourceRecipeVersionId']
        OR jsonb_typeof(saved) IS DISTINCT FROM 'object'
        OR NOT coalesce(saved ?& ARRAY['identity','recipeText','originalSource'],false)
        OR (saved-ARRAY['identity','recipeText','originalSource']) IS DISTINCT FROM '{}'::jsonb
        OR jsonb_typeof(identity) IS DISTINCT FROM 'object'
        OR NOT coalesce(identity ?& identity_keys,false) OR (identity-identity_keys) IS DISTINCT FROM '{}'::jsonb
        OR identity->>'environment' IS DISTINCT FROM NEW.environment
        OR identity->>'principalId' IS DISTINCT FROM NEW.principal_id::text
        OR identity->>'savedRecipeId' IS DISTINCT FROM request->>'savedRecipeId'
        OR jsonb_typeof(identity->'generation') IS DISTINCT FROM 'string'
        OR NOT coalesce(identity->>'generation' ~ '^[1-9][0-9]*$',false)
        OR jsonb_typeof(identity->'version') IS DISTINCT FROM 'string'
        OR NOT coalesce(identity->>'version' ~ '^[1-9][0-9]*$',false)
        OR source->>'recipeVersionId' IS DISTINCT FROM identity->>'recipeVersionId'
        OR NOT coalesce(identity->>'recipeHash' ~ '^[0-9a-f]{64}$',false)
        OR identity->>'contentLicense' NOT IN ('catalogRedistributable','privateCopyOnly')
        OR jsonb_typeof(identity->'contentLicense') IS DISTINCT FROM 'string'
        OR jsonb_typeof(identity->'allowReviewedScaling') IS DISTINCT FROM 'boolean'
        OR jsonb_typeof(identity->'copyEvidence') IS DISTINCT FROM 'object'
        OR NOT coalesce(identity->'copyEvidence' ?& ARRAY['formatVersion','grant','recipeHash'],false)
        OR ((identity->'copyEvidence')-ARRAY['formatVersion','grant','recipeHash']) IS DISTINCT FROM '{}'::jsonb
        OR identity->'copyEvidence'->'formatVersion' IS DISTINCT FROM '1'::jsonb
        OR identity->'copyEvidence'->'recipeHash' IS DISTINCT FROM identity->'recipeHash'
        OR jsonb_typeof(identity->'copyEvidence'->'grant') IS DISTINCT FROM 'object'
        OR identity->'copyEvidence'->'grant'->'recipeVersionId' IS DISTINCT FROM identity->'recipeVersionId'
        OR identity->>'sourceType' NOT IN ('catalog','ownPlan')
        OR jsonb_typeof(identity->'sourceType') IS DISTINCT FROM 'string'
        OR NOT coalesce(identity->>'sourceId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',false)
        OR (identity->>'sourceType'='catalog' AND (identity->'originPlanId' IS DISTINCT FROM 'null'::jsonb
            OR identity->'sourceId' IS DISTINCT FROM identity->'recipeVersionId'))
        OR (identity->>'sourceType'='ownPlan' AND (identity->'originPlanId' IS DISTINCT FROM identity->'sourceId'
            OR jsonb_typeof(identity->'originPlanId') IS DISTINCT FROM 'string'))
        OR jsonb_typeof(saved->'recipeText') IS DISTINCT FROM 'string'
        OR jsonb_typeof((saved->>'recipeText')::jsonb) IS DISTINCT FROM 'object'
        OR (saved->>'recipeText')::jsonb->'id' IS DISTINCT FROM identity->'recipeVersionId'
        OR jsonb_typeof(saved->'originalSource') IS DISTINCT FROM 'object'
        OR NOT coalesce(saved->'originalSource' ?& source_keys,false)
        OR ((saved->'originalSource')-source_keys) IS DISTINCT FROM '{}'::jsonb
        OR saved->'originalSource'->'recipeVersionId' IS DISTINCT FROM identity->'recipeVersionId'
        OR jsonb_typeof(request->'constraints') IS DISTINCT FROM 'object'
        OR NOT coalesce(request->>'preferenceVersion' ~ '^[1-9][0-9]*$',false)
        OR NOT coalesce(document->>'createdAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3})?Z$',false)
        OR NOT coalesce(document->>'expiresAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3})?Z$',false)
        OR (document->>'createdAt')::timestamptz IS DISTINCT FROM NEW.created_at
        OR (document->>'expiresAt')::timestamptz IS DISTINCT FROM NEW.expires_at
        OR NOT isfinite(NEW.created_at) OR NOT isfinite(NEW.expires_at) OR NOT isfinite(NEW.cursor_expires_at)
        OR NEW.created_at<>date_trunc('milliseconds',NEW.created_at)
        OR NEW.expires_at<>date_trunc('milliseconds',NEW.expires_at)
        OR NEW.created_at>clock_timestamp() OR NEW.expires_at<=clock_timestamp() THEN
        RAISE EXCEPTION 'Root Saved Plan request differs from original' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER root_saved_request_original BEFORE INSERT ON planning.plan_requests
    FOR EACH ROW WHEN (NEW.storage_format=5) EXECUTE FUNCTION planning.guard_root_saved_request_insert();

CREATE FUNCTION planning.guard_root_saved_plan_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lineage planning.plan_requests%ROWTYPE; body jsonb; context jsonb; proof jsonb; expected_proof jsonb;
BEGIN
    SELECT * INTO lineage FROM planning.plan_requests WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind
        AND principal_id=NEW.principal_id AND id=NEW.request_id FOR SHARE;
    IF NOT FOUND OR lineage.storage_format<>5 OR lineage.actor_kind<>'account' OR lineage.version<>1
        OR lineage.current_plan_id IS DISTINCT FROM NEW.id OR NEW.parent_plan_id IS NOT NULL
        OR NEW.created_at IS DISTINCT FROM lineage.created_at THEN
        RAISE EXCEPTION 'Root Saved Plan request unavailable' USING ERRCODE='23514';
    END IF;
    body := NEW.snapshot_text::jsonb; context := lineage.evidence_text::jsonb; proof := NEW.proof_text::jsonb;
    expected_proof := jsonb_build_object(
        'version',5,'kind','rootSavedPlan','owner',context->'owner','operationId',lineage.derived_operation,
        'commandKey',lineage.derived_command_key::text,'commandRequestHash',lineage.derived_request_sha256,
        'requestSha256',lineage.request_hash,'contextHash',lineage.evidence_hash,'snapshotHash',NEW.snapshot_hash,
        'planId',NEW.id::text,'createdAt',context->'createdAt','expiresAt',context->'expiresAt',
        'status',NEW.status,'rankingVersion',context->'policy'->'version');
    IF NEW.snapshot_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.snapshot_text,'UTF8')),'hex')
        OR NEW.proof_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.proof_text,'UTF8')),'hex')
        OR proof IS DISTINCT FROM expected_proof
        OR body->'id' IS DISTINCT FROM to_jsonb(NEW.id::text) OR body->'version' IS DISTINCT FROM '1'::jsonb
        OR body->>'status' IS DISTINCT FROM NEW.status
        OR body ?| ARRAY['parentPlanId','sourcePostId']
        OR (body->>'recipeVersionId')::uuid IS DISTINCT FROM NEW.recipe_version_id
        OR body->'nextAlternativeCursor' IS DISTINCT FROM 'null'::jsonb
        OR body->'createdAt' IS DISTINCT FROM context->'createdAt'
        OR body->'updatedAt' IS DISTINCT FROM context->'createdAt'
        OR (NEW.status='ready' AND (jsonb_typeof(body->'recipeSnapshot') IS DISTINCT FROM 'object'
            OR body->'recipeSnapshot'->>'id' IS DISTINCT FROM NEW.recipe_version_id::text))
        OR (NEW.status<>'ready' AND coalesce(body->'recipeSnapshot','null'::jsonb) IS DISTINCT FROM 'null'::jsonb)
        OR NEW.version<>1 OR NOT isfinite(NEW.created_at)
        OR NEW.created_at<>date_trunc('milliseconds',NEW.created_at)
        OR NEW.created_at>clock_timestamp() OR lineage.expires_at<=clock_timestamp() THEN
        RAISE EXCEPTION 'Root Saved Plan bytes differ from row' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER root_saved_plan_original BEFORE INSERT ON planning.plans
    FOR EACH ROW WHEN (NEW.storage_format=5) EXECUTE FUNCTION planning.guard_root_saved_plan_insert();

CREATE FUNCTION planning.require_root_saved_plan_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lineage planning.plan_requests%ROWTYPE; result planning.plans%ROWTYPE; lineage_id uuid; at_time timestamptz;
BEGIN
    IF TG_TABLE_NAME='plans' THEN lineage_id := NEW.request_id; ELSE lineage_id := NEW.id; END IF;
    SELECT * INTO lineage FROM planning.plan_requests WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind
        AND principal_id=NEW.principal_id AND id=lineage_id FOR SHARE;
    IF NOT FOUND OR lineage.storage_format<>5 OR lineage.actor_kind<>'account' OR lineage.version<>1
        OR lineage.current_plan_id IS NULL THEN
        RAISE EXCEPTION 'Root Saved Plan request incomplete at commit' USING ERRCODE='23514';
    END IF;
    SELECT * INTO result FROM planning.plans WHERE environment=lineage.environment AND actor_kind=lineage.actor_kind
        AND principal_id=lineage.principal_id AND request_id=lineage.id AND id=lineage.current_plan_id FOR SHARE;
    at_time := clock_timestamp();
    IF NOT FOUND OR result.storage_format<>5 OR result.parent_plan_id IS NOT NULL OR result.version<>1
        OR result.created_at IS DISTINCT FROM lineage.created_at OR result.next_cursor_hash IS NOT NULL
        OR result.proof_text::jsonb->'version' IS DISTINCT FROM '5'::jsonb
        OR NOT isfinite(lineage.expires_at) OR lineage.expires_at<=at_time THEN
        RAISE EXCEPTION 'Root Saved Plan result incomplete at commit' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER root_saved_request_requires_result AFTER INSERT ON planning.plan_requests
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.storage_format=5)
    EXECUTE FUNCTION planning.require_root_saved_plan_complete();
CREATE CONSTRAINT TRIGGER root_saved_plan_requires_request AFTER INSERT ON planning.plans
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.storage_format=5)
    EXECUTE FUNCTION planning.require_root_saved_plan_complete();

-- Existing owned dependency FKs continue to govern future explicit erasure. This adds
-- no deletion or truncation worker and does not modify existing manifest retention.
