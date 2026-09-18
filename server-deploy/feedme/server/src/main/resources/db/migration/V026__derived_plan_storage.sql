-- Independent derived-Plan storage only. No identity/input/source authority, route,
-- guest allowance, cooking permission, copy grant or deployment is activated here.
-- V001--V025 originals remain unchanged. Formats 1/2 retain their existing bytes,
-- same-request parent semantics and (for format 2) manifest/head transitions.
ALTER TABLE planning.plan_requests
    DROP CONSTRAINT plan_requests_storage_format_check,
    ADD CONSTRAINT plan_requests_storage_format_check CHECK (storage_format IN (1,2,3)),
    ADD COLUMN derived_operation varchar(16) NULL CHECK (derived_operation IN ('adaptPlan','simplifyPlan')),
    ADD COLUMN derived_command_key uuid NULL,
    ADD COLUMN derived_request_sha256 char(64) NULL CHECK (derived_request_sha256 ~ '^[0-9a-f]{64}$'),
    ADD COLUMN derived_if_match varchar(1024) NULL CHECK (
        derived_if_match ~ '^"[0-9]+"$' AND derived_if_match ~ '[1-9]'),
    ADD COLUMN derived_parent_plan_id uuid NULL,
    ADD COLUMN derived_parent_version bigint NULL CHECK (derived_parent_version > 0);
ALTER TABLE planning.plan_requests DROP CONSTRAINT planning_request_representation;
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_request_representation CHECK (
    (storage_format=1 AND evidence_text IS NOT NULL AND evidence_hash IS NOT NULL AND ordered_ids IS NOT NULL
        AND manifest_id IS NULL AND create_command_key IS NULL AND command_request_sha256 IS NULL)
    OR (storage_format=2 AND actor_kind='guest' AND manifest_id IS NOT NULL AND id=manifest_id
        AND create_command_key IS NOT NULL AND command_request_sha256 IS NOT NULL
        AND evidence_text IS NULL AND evidence_hash IS NULL AND ordered_ids IS NULL)
    OR (storage_format=3 AND evidence_text IS NOT NULL AND evidence_hash IS NOT NULL AND ordered_ids IS NULL
        AND manifest_id IS NULL AND create_command_key IS NULL AND command_request_sha256 IS NULL)
);
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_derived_request_fields CHECK (
    (storage_format IN (1,2) AND derived_operation IS NULL AND derived_command_key IS NULL
        AND derived_request_sha256 IS NULL AND derived_if_match IS NULL
        AND derived_parent_plan_id IS NULL AND derived_parent_version IS NULL)
    OR (storage_format=3 AND derived_operation IS NOT NULL AND derived_command_key IS NOT NULL
        AND derived_request_sha256 IS NOT NULL AND derived_if_match IS NOT NULL
        AND derived_parent_plan_id IS NOT NULL AND derived_parent_version IS NOT NULL
        AND current_plan_id IS NOT NULL AND current_plan_id<>derived_parent_plan_id AND version=1
        AND cursor_expires_at=expires_at)
);
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_derived_owned_parent
    FOREIGN KEY(environment,actor_kind,principal_id,derived_parent_plan_id)
    REFERENCES planning.plans(environment,actor_kind,principal_id,id);
CREATE UNIQUE INDEX derived_plan_original_command ON planning.plan_requests(
    environment,actor_kind,principal_id,derived_operation,derived_command_key) WHERE storage_format=3;

ALTER TABLE planning.plans
    DROP CONSTRAINT plans_storage_format_check,
    ADD CONSTRAINT plans_storage_format_check CHECK (storage_format IN (1,2,3)),
    ADD COLUMN legacy_parent_request_id uuid GENERATED ALWAYS AS
        (CASE WHEN storage_format IN (1,2) THEN request_id ELSE NULL END) STORED;
ALTER TABLE planning.plans DROP CONSTRAINT plans_position_check;
ALTER TABLE planning.plans ADD CONSTRAINT plans_position_check CHECK (
    (storage_format=1 AND position BETWEEN -1 AND 128) OR (storage_format=2 AND position>=-1)
    OR (storage_format=3 AND position=(CASE WHEN status='ready' THEN 0 ELSE -1 END))
);
ALTER TABLE planning.plans ADD CONSTRAINT planning_derived_plan_fields CHECK (
    storage_format<>3 OR (parent_plan_id IS NOT NULL AND parent_plan_id<>id AND next_cursor_hash IS NULL)
);

-- Locate only V003's exact self-FK, rather than depend on PostgreSQL's generated
-- truncated constraint name. Refuse an unexpected schema instead of dropping broadly.
DO $$
DECLARE old_parent_fk text; matching integer;
BEGIN
    SELECT count(*),min(c.conname::text) INTO matching,old_parent_fk
    FROM pg_constraint c WHERE c.conrelid='planning.plans'::regclass
        AND c.confrelid='planning.plans'::regclass AND c.contype='f'
        AND (SELECT array_agg(a.attname::text ORDER BY k.ordinality)
            FROM unnest(c.conkey) WITH ORDINALITY k(attnum,ordinality)
            JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.attnum)
            =ARRAY['environment','actor_kind','principal_id','request_id','parent_plan_id']::text[]
        AND (SELECT array_agg(a.attname::text ORDER BY k.ordinality)
            FROM unnest(c.confkey) WITH ORDINALITY k(attnum,ordinality)
            JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.attnum)
            =ARRAY['environment','actor_kind','principal_id','request_id','id']::text[];
    IF matching<>1 THEN RAISE EXCEPTION 'Original Plan parent constraint unavailable' USING ERRCODE='23514'; END IF;
    EXECUTE format('ALTER TABLE planning.plans DROP CONSTRAINT %I',old_parent_fk);
END;
$$;
ALTER TABLE planning.plans ADD CONSTRAINT planning_plan_owned_parent
    FOREIGN KEY(environment,actor_kind,principal_id,parent_plan_id)
    REFERENCES planning.plans(environment,actor_kind,principal_id,id);
ALTER TABLE planning.plans ADD CONSTRAINT planning_legacy_same_request_parent
    FOREIGN KEY(environment,actor_kind,principal_id,legacy_parent_request_id,parent_plan_id)
    REFERENCES planning.plans(environment,actor_kind,principal_id,request_id,id);
CREATE UNIQUE INDEX derived_plan_one_result ON planning.plans(environment,actor_kind,principal_id,request_id)
    WHERE storage_format=3;

-- Keep the original V020 function bodies. They apply to format 2 only, not every
-- future non-1 format. A separate unconditional guard preserves format immutability.
DROP TRIGGER manifest_lineage_original ON planning.plan_requests;
CREATE TRIGGER manifest_lineage_original BEFORE INSERT ON planning.plan_requests
    FOR EACH ROW WHEN (NEW.storage_format=2) EXECUTE FUNCTION planning.guard_manifest_lineage_insert();
DROP TRIGGER manifest_plan_original ON planning.plans;
CREATE TRIGGER manifest_plan_original BEFORE INSERT ON planning.plans
    FOR EACH ROW WHEN (NEW.storage_format=2) EXECUTE FUNCTION planning.guard_manifest_plan_insert();
DROP TRIGGER manifest_lineage_transition ON planning.plan_requests;
CREATE TRIGGER manifest_lineage_transition BEFORE UPDATE ON planning.plan_requests
    FOR EACH ROW WHEN (OLD.storage_format=2) EXECUTE FUNCTION planning.guard_manifest_lineage_update();
DROP TRIGGER manifest_lineage_requires_head ON planning.plan_requests;
CREATE CONSTRAINT TRIGGER manifest_lineage_requires_head AFTER INSERT OR UPDATE ON planning.plan_requests
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.storage_format=2)
    EXECUTE FUNCTION planning.require_manifest_lineage_head();
DROP TRIGGER manifest_plan_requires_head ON planning.plans;
CREATE CONSTRAINT TRIGGER manifest_plan_requires_head AFTER INSERT ON planning.plans
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.storage_format=2)
    EXECUTE FUNCTION planning.require_manifest_lineage_head();

CREATE FUNCTION planning.guard_derived_request_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.storage_format IS DISTINCT FROM OLD.storage_format OR OLD.storage_format=3 THEN
        RAISE EXCEPTION 'Plan request format or derived original is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER derived_request_immutable BEFORE UPDATE ON planning.plan_requests
    FOR EACH ROW EXECUTE FUNCTION planning.guard_derived_request_update();

-- Fresh creation alone requires a live parent. Historical child reads/replays use
-- their own lifetime and actual caller authority, not this INSERT-only helper.
CREATE FUNCTION planning.require_live_derived_parent(lineage planning.plan_requests)
    RETURNS planning.plans LANGUAGE plpgsql AS $$
DECLARE parent planning.plans%ROWTYPE; original planning.plan_requests%ROWTYPE; at_time timestamptz;
BEGIN
    SELECT * INTO parent FROM planning.plans WHERE environment=lineage.environment AND actor_kind=lineage.actor_kind
        AND principal_id=lineage.principal_id AND id=lineage.derived_parent_plan_id FOR SHARE;
    IF NOT FOUND OR parent.status<>'ready' OR parent.recipe_version_id IS NULL
        OR parent.version IS DISTINCT FROM lineage.derived_parent_version OR parent.request_id=lineage.id
        OR parent.id=lineage.current_plan_id OR parent.created_at>lineage.created_at
        OR ltrim(substring(lineage.derived_if_match FROM 2 FOR length(lineage.derived_if_match)-2),'0')
            IS DISTINCT FROM parent.version::text THEN
        RAISE EXCEPTION 'Derived Plan parent unavailable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO original FROM planning.plan_requests WHERE environment=lineage.environment AND actor_kind=lineage.actor_kind
        AND principal_id=lineage.principal_id AND id=parent.request_id FOR SHARE;
    at_time := clock_timestamp();
    IF NOT FOUND OR original.storage_format IS DISTINCT FROM parent.storage_format
        OR NOT isfinite(original.expires_at) OR original.expires_at<=at_time
        OR NOT isfinite(lineage.expires_at) OR lineage.expires_at<=at_time
        OR lineage.created_at>=original.expires_at THEN
        RAISE EXCEPTION 'Derived Plan creation lifetime unavailable' USING ERRCODE='23514';
    END IF;
    RETURN parent;
END;
$$;

CREATE FUNCTION planning.guard_derived_request_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent planning.plans%ROWTYPE; original planning.plan_requests%ROWTYPE; document jsonb;
BEGIN
    parent := planning.require_live_derived_parent(NEW);
    SELECT * INTO original FROM planning.plan_requests WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind
        AND principal_id=NEW.principal_id AND id=parent.request_id FOR SHARE;
    document := NEW.evidence_text::jsonb;
    IF NOT FOUND OR NEW.request_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.request_text,'UTF8')),'hex')
        OR NEW.evidence_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.evidence_text,'UTF8')),'hex')
        OR document->'version' IS DISTINCT FROM '1'::jsonb
        OR document->'owner' IS DISTINCT FROM jsonb_build_object(
            'environment',NEW.environment,'actorKind',NEW.actor_kind,'principalId',NEW.principal_id::text)
        OR document->>'operationId' IS DISTINCT FROM NEW.derived_operation
        OR document->>'commandKey' IS DISTINCT FROM NEW.derived_command_key::text
        OR document->>'commandRequestHash' IS DISTINCT FROM NEW.derived_request_sha256
        OR document->>'originalIfMatch' IS DISTINCT FROM NEW.derived_if_match
        OR document->>'parentId' IS DISTINCT FROM NEW.derived_parent_plan_id::text
        OR document->>'parentVersion' IS DISTINCT FROM NEW.derived_parent_version::text
        OR document->>'planId' IS DISTINCT FROM NEW.current_plan_id::text
        OR document->>'requestText' IS DISTINCT FROM NEW.request_text
        OR document->>'requestSha256' IS DISTINCT FROM NEW.request_hash
        OR document->>'parentSnapshotText' IS DISTINCT FROM parent.snapshot_text
        OR document->>'parentSnapshotHash' IS DISTINCT FROM parent.snapshot_hash
        OR document->>'parentProofText' IS DISTINCT FROM parent.proof_text
        OR document->>'parentProofHash' IS DISTINCT FROM parent.proof_hash
        OR document->>'parentRequestText' IS DISTINCT FROM original.request_text
        OR document->>'parentRequestHash' IS DISTINCT FROM original.request_hash
        OR document->'policy' IS DISTINCT FROM NEW.policy_text::jsonb
        OR NOT coalesce(document->>'createdAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3})?Z$',false)
        OR NOT coalesce(document->>'expiresAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3})?Z$',false)
        OR (document->>'createdAt')::timestamptz IS DISTINCT FROM NEW.created_at
        OR (document->>'expiresAt')::timestamptz IS DISTINCT FROM NEW.expires_at
        OR NOT isfinite(NEW.created_at) OR NOT isfinite(NEW.expires_at) OR NOT isfinite(NEW.cursor_expires_at)
        OR NEW.created_at<>date_trunc('milliseconds',NEW.created_at)
        OR NEW.expires_at<>date_trunc('milliseconds',NEW.expires_at)
        OR NEW.created_at>clock_timestamp() OR NEW.expires_at<=clock_timestamp() THEN
        RAISE EXCEPTION 'Derived Plan request differs from original' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER derived_request_original BEFORE INSERT ON planning.plan_requests
    FOR EACH ROW WHEN (NEW.storage_format=3) EXECUTE FUNCTION planning.guard_derived_request_insert();

CREATE FUNCTION planning.guard_derived_plan_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lineage planning.plan_requests%ROWTYPE; body jsonb; context jsonb; proof jsonb; expected_proof jsonb;
BEGIN
    SELECT * INTO lineage FROM planning.plan_requests WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind
        AND principal_id=NEW.principal_id AND id=NEW.request_id FOR SHARE;
    IF NOT FOUND OR lineage.storage_format<>3 OR lineage.version<>1 OR lineage.current_plan_id IS DISTINCT FROM NEW.id
        OR lineage.derived_parent_plan_id IS DISTINCT FROM NEW.parent_plan_id
        OR NEW.created_at IS DISTINCT FROM lineage.created_at THEN
        RAISE EXCEPTION 'Derived Plan request unavailable' USING ERRCODE='23514';
    END IF;
    PERFORM planning.require_live_derived_parent(lineage);
    body := NEW.snapshot_text::jsonb; context := lineage.evidence_text::jsonb; proof := NEW.proof_text::jsonb;
    expected_proof := jsonb_build_object(
        'version',3,'kind','derivedPlan','owner',context->'owner','operationId',lineage.derived_operation,
        'commandKey',lineage.derived_command_key::text,'commandRequestHash',lineage.derived_request_sha256,
        'parentPlanId',lineage.derived_parent_plan_id::text,'parentVersion',lineage.derived_parent_version::text,
        'originalIfMatch',lineage.derived_if_match,'parentSnapshotHash',context->'parentSnapshotHash',
        'parentProofHash',context->'parentProofHash','parentRequestHash',context->'parentRequestHash',
        'requestSha256',lineage.request_hash,'contextHash',lineage.evidence_hash,'snapshotHash',NEW.snapshot_hash,
        'planId',NEW.id::text,'createdAt',context->'createdAt','expiresAt',context->'expiresAt',
        'status',NEW.status,'rankingVersion',context->'policy'->'version');
    IF NEW.snapshot_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.snapshot_text,'UTF8')),'hex')
        OR NEW.proof_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.proof_text,'UTF8')),'hex')
        OR proof IS DISTINCT FROM expected_proof
        OR body->'id' IS DISTINCT FROM to_jsonb(NEW.id::text) OR body->'version' IS DISTINCT FROM '1'::jsonb
        OR body->>'status' IS DISTINCT FROM NEW.status
        OR body->'parentPlanId' IS DISTINCT FROM to_jsonb(NEW.parent_plan_id::text)
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
        RAISE EXCEPTION 'Derived Plan bytes differ from row' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER derived_plan_original BEFORE INSERT ON planning.plans
    FOR EACH ROW WHEN (NEW.storage_format=3) EXECUTE FUNCTION planning.guard_derived_plan_insert();

CREATE FUNCTION planning.require_derived_plan_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lineage planning.plan_requests%ROWTYPE; child planning.plans%ROWTYPE; lineage_id uuid;
BEGIN
    IF TG_TABLE_NAME='plans' THEN lineage_id := NEW.request_id; ELSE lineage_id := NEW.id; END IF;
    SELECT * INTO lineage FROM planning.plan_requests WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind
        AND principal_id=NEW.principal_id AND id=lineage_id FOR SHARE;
    IF NOT FOUND OR lineage.storage_format<>3 OR lineage.version<>1 OR lineage.current_plan_id IS NULL THEN
        RAISE EXCEPTION 'Derived Plan request incomplete at commit' USING ERRCODE='23514';
    END IF;
    SELECT * INTO child FROM planning.plans WHERE environment=lineage.environment AND actor_kind=lineage.actor_kind
        AND principal_id=lineage.principal_id AND request_id=lineage.id AND id=lineage.current_plan_id FOR SHARE;
    IF NOT FOUND OR child.storage_format<>3 OR child.parent_plan_id IS DISTINCT FROM lineage.derived_parent_plan_id
        OR child.version<>1 OR child.created_at IS DISTINCT FROM lineage.created_at
        OR child.next_cursor_hash IS NOT NULL OR child.proof_text::jsonb->'version' IS DISTINCT FROM '3'::jsonb THEN
        RAISE EXCEPTION 'Derived Plan child incomplete at commit' USING ERRCODE='23514';
    END IF;
    -- The helper observes one database clock after every parent/child SQL wait,
    -- and checks BOTH parent and child deadlines against that same instant.
    -- Fresh-write completeness only; read/replay fences remain the kernel's job.
    PERFORM planning.require_live_derived_parent(lineage);
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER derived_request_requires_child AFTER INSERT ON planning.plan_requests
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.storage_format=3)
    EXECUTE FUNCTION planning.require_derived_plan_complete();
CREATE CONSTRAINT TRIGGER derived_plan_requires_request AFTER INSERT ON planning.plans
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.storage_format=3)
    EXECUTE FUNCTION planning.require_derived_plan_complete();

-- No new deletion ban or purge worker. Existing owned dependency FKs govern future
-- explicit erasure; format-2 retention/TRUNCATE safeguards stay exactly as installed.
