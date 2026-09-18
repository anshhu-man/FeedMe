-- Additive shared Plan storage. Format 1 keeps its original defaults and finite evidence;
-- format 2 binds an already charged, sealed guest preparation, never a truncated v1 list.
-- Existing Plan, cooking and saved-recipe foreign keys are preserved. This migration is
-- structural: it supplies no current identity/content authority, HTTP route or cleanup grant.
ALTER TABLE planning.guest_preparations ADD CONSTRAINT guest_preparation_original_binding
    UNIQUE(environment,actor_kind,principal_id,manifest_id,command_key,request_sha256);

ALTER TABLE planning.plan_requests
    ADD COLUMN storage_format smallint NOT NULL DEFAULT 1 CHECK (storage_format IN (1,2)),
    ADD COLUMN manifest_id uuid NULL,
    ADD COLUMN create_command_key uuid NULL,
    ADD COLUMN command_request_sha256 char(64) NULL CHECK (command_request_sha256 ~ '^[0-9a-f]{64}$'),
    ALTER COLUMN evidence_text DROP NOT NULL,
    ALTER COLUMN evidence_hash DROP NOT NULL,
    ALTER COLUMN ordered_ids DROP NOT NULL;
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_request_representation CHECK (
    (storage_format=1 AND evidence_text IS NOT NULL AND evidence_hash IS NOT NULL AND ordered_ids IS NOT NULL
        AND manifest_id IS NULL AND create_command_key IS NULL AND command_request_sha256 IS NULL)
    OR (storage_format=2 AND actor_kind='guest' AND manifest_id IS NOT NULL AND id=manifest_id
        AND create_command_key IS NOT NULL AND command_request_sha256 IS NOT NULL
        AND evidence_text IS NULL AND evidence_hash IS NULL AND ordered_ids IS NULL)
);
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_request_format_key
    UNIQUE(environment,actor_kind,principal_id,id,storage_format);
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_request_preparation
    FOREIGN KEY(environment,actor_kind,principal_id,manifest_id,create_command_key,command_request_sha256)
    REFERENCES planning.guest_preparations(environment,actor_kind,principal_id,manifest_id,command_key,request_sha256);

ALTER TABLE planning.plans ADD COLUMN storage_format smallint NOT NULL DEFAULT 1 CHECK (storage_format IN (1,2));
ALTER TABLE planning.plans ALTER COLUMN position TYPE bigint;
ALTER TABLE planning.plans DROP CONSTRAINT plans_position_check;
ALTER TABLE planning.plans ADD CONSTRAINT plans_position_check CHECK (
    (storage_format=1 AND position BETWEEN -1 AND 128) OR (storage_format=2 AND position>=-1)
);
ALTER TABLE planning.plans ADD CONSTRAINT planning_plan_request_format
    FOREIGN KEY(environment,actor_kind,principal_id,request_id,storage_format)
    REFERENCES planning.plan_requests(environment,actor_kind,principal_id,id,storage_format);
CREATE UNIQUE INDEX manifest_plan_one_root ON planning.plans(environment,actor_kind,principal_id,request_id)
    WHERE storage_format=2 AND parent_plan_id IS NULL;
CREATE UNIQUE INDEX manifest_plan_one_child ON planning.plans(environment,actor_kind,principal_id,request_id,parent_plan_id)
    WHERE storage_format=2 AND parent_plan_id IS NOT NULL;
CREATE UNIQUE INDEX manifest_plan_one_position ON planning.plans(environment,actor_kind,principal_id,request_id,position)
    WHERE storage_format=2;

CREATE FUNCTION planning.guard_manifest_lineage_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original planning.guest_preparations%ROWTYPE; header planning.manifest_headers%ROWTYPE;
    seal planning.manifest_seals%ROWTYPE; document jsonb; expected_policy jsonb;
BEGIN
    IF NEW.storage_format=1 THEN RETURN NEW; END IF;
    -- The real caller already owns installation/principal/guest before its receipt. Only
    -- already sealed immutable originals are referenced; no quota increment occurs here.
    SELECT * INTO original FROM planning.guest_preparations WHERE environment=NEW.environment
        AND actor_kind=NEW.actor_kind AND principal_id=NEW.principal_id AND manifest_id=NEW.manifest_id
        AND command_key=NEW.create_command_key AND request_sha256=NEW.command_request_sha256 FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Manifest original unavailable' USING ERRCODE='23514'; END IF;
    SELECT * INTO header FROM planning.manifest_headers WHERE environment=NEW.environment
        AND actor_kind=NEW.actor_kind AND principal_id=NEW.principal_id AND manifest_id=NEW.manifest_id FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Manifest header unavailable' USING ERRCODE='23514'; END IF;
    SELECT * INTO seal FROM planning.manifest_seals WHERE environment=NEW.environment
        AND actor_kind=NEW.actor_kind AND principal_id=NEW.principal_id AND manifest_id=NEW.manifest_id FOR SHARE;
    IF NOT FOUND OR seal.header_sha256 IS DISTINCT FROM header.header_sha256 THEN
        RAISE EXCEPTION 'Manifest seal unavailable' USING ERRCODE='23514';
    END IF;
    document := header.header_text::jsonb;
    expected_policy := jsonb_build_object('version',2,'guestPolicySha256',original.policy_sha256,'ranking',document->'policy');
    IF NEW.id IS DISTINCT FROM NEW.manifest_id OR NEW.version<>1 OR NEW.current_plan_id IS NOT NULL
        OR NEW.request_text IS DISTINCT FROM document->>'requestText'
        OR NEW.request_hash IS DISTINCT FROM document->>'requestSha256'
        OR NEW.request_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.request_text,'UTF8')),'hex')
        OR NEW.policy_text::jsonb IS DISTINCT FROM expected_policy
        -- The actual preparation writer emits millisecond-exact Instant strings. Refuse
        -- unsupported precision rather than round nanoseconds through timestamptz.
        OR NOT coalesce(document->>'createdAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3})?Z$',false)
        OR NOT coalesce(document->>'expiresAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3})?Z$',false)
        OR NOT coalesce(document->>'cursorExpiresAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3})?Z$',false)
        OR NEW.created_at IS DISTINCT FROM (document->>'createdAt')::timestamptz
        OR NEW.expires_at IS DISTINCT FROM (document->>'expiresAt')::timestamptz
        OR NEW.cursor_expires_at IS DISTINCT FROM (document->>'cursorExpiresAt')::timestamptz
        OR NOT isfinite(NEW.created_at) OR NOT isfinite(NEW.expires_at) OR NOT isfinite(NEW.cursor_expires_at)
        OR NEW.created_at>clock_timestamp() THEN
        RAISE EXCEPTION 'Manifest lineage differs from original' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER manifest_lineage_original BEFORE INSERT ON planning.plan_requests
    FOR EACH ROW EXECUTE FUNCTION planning.guard_manifest_lineage_insert();

CREATE FUNCTION planning.guard_manifest_plan_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lineage planning.plan_requests%ROWTYPE; parent planning.plans%ROWTYPE;
    seal planning.manifest_seals%ROWTYPE; selected uuid; body jsonb; initial_status text;
BEGIN
    IF NEW.storage_format=1 THEN RETURN NEW; END IF;
    -- Every insertion/head transition serializes on the exact same owned lineage row.
    SELECT * INTO lineage FROM planning.plan_requests WHERE environment=NEW.environment
        AND actor_kind=NEW.actor_kind AND principal_id=NEW.principal_id AND id=NEW.request_id FOR UPDATE;
    IF NOT FOUND OR lineage.storage_format<>2 THEN
        RAISE EXCEPTION 'Manifest lineage unavailable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO seal FROM planning.manifest_seals WHERE environment=NEW.environment
        AND actor_kind=NEW.actor_kind AND principal_id=NEW.principal_id AND manifest_id=lineage.manifest_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Manifest seal unavailable' USING ERRCODE='23514'; END IF;
    IF NEW.parent_plan_id IS NULL THEN
        initial_status := CASE seal.first_decision_text::jsonb->>'status'
            WHEN 'READY' THEN 'ready' WHEN 'NEEDS_CONFIRMATION' THEN 'needsConfirmation' WHEN 'NO_MATCH' THEN 'noMatch' END;
        IF lineage.current_plan_id IS NOT NULL OR lineage.version<>1 OR initial_status IS NULL
            OR NEW.status IS DISTINCT FROM initial_status
            OR NEW.position IS DISTINCT FROM (CASE WHEN NEW.status='ready' THEN 0::bigint ELSE -1::bigint END)
            OR (NEW.status='ready' AND NEW.recipe_version_id IS DISTINCT FROM
                ((seal.first_decision_text::jsonb->>'recipeText')::jsonb->>'id')::uuid) THEN
            RAISE EXCEPTION 'Manifest first Plan differs from original' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT * INTO parent FROM planning.plans WHERE environment=NEW.environment
            AND actor_kind=NEW.actor_kind AND principal_id=NEW.principal_id AND request_id=NEW.request_id AND id=NEW.parent_plan_id;
        IF NOT FOUND OR parent.storage_format<>2 OR parent.status<>'ready' OR parent.next_cursor_hash IS NULL
            OR lineage.current_plan_id IS DISTINCT FROM parent.id OR NEW.position<=parent.position THEN
            RAISE EXCEPTION 'Manifest parent unavailable' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.status='ready' THEN
        IF NEW.position<0 OR NEW.position>=seal.eligible_count THEN
            RAISE EXCEPTION 'Manifest rank position unavailable' USING ERRCODE='23514';
        END IF;
        SELECT recipe_version_id INTO selected FROM planning.manifest_ranks WHERE environment=NEW.environment
            AND actor_kind=NEW.actor_kind AND principal_id=NEW.principal_id AND manifest_id=lineage.manifest_id
            ORDER BY taste_matches DESC,disliked_ingredients ASC,confirmed_ingredients DESC,
                active_minutes ASC NULLS FIRST,cleanup_minutes ASC NULLS LAST,recipe_id ASC,recipe_version_id ASC
            OFFSET NEW.position LIMIT 1;
        IF NOT FOUND OR NEW.recipe_version_id IS DISTINCT FROM selected THEN
            RAISE EXCEPTION 'Manifest rank differs from Plan' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.parent_plan_id IS NOT NULL AND NEW.position<>seal.eligible_count THEN
        RAISE EXCEPTION 'Manifest exhaustion position differs' USING ERRCODE='23514';
    END IF;
    body := NEW.snapshot_text::jsonb;
    IF NEW.snapshot_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.snapshot_text,'UTF8')),'hex')
        OR NEW.proof_hash IS DISTINCT FROM encode(sha256(convert_to(NEW.proof_text,'UTF8')),'hex')
        OR (body->>'id')::uuid IS DISTINCT FROM NEW.id OR (body->>'version')::numeric IS DISTINCT FROM 1::numeric
        OR body->>'status' IS DISTINCT FROM NEW.status
        OR (body->>'recipeVersionId')::uuid IS DISTINCT FROM NEW.recipe_version_id
        OR (body->>'parentPlanId')::uuid IS DISTINCT FROM NEW.parent_plan_id
        OR (body->>'createdAt')::timestamptz IS DISTINCT FROM NEW.created_at
        OR (body->>'updatedAt')::timestamptz IS DISTINCT FROM NEW.created_at
        OR NEW.created_at<lineage.created_at OR NEW.created_at>=lineage.expires_at
        OR NEW.created_at>clock_timestamp() OR NOT isfinite(NEW.created_at)
        OR NEW.created_at<>date_trunc('milliseconds',NEW.created_at)
        OR NEW.proof_text::jsonb->'version' IS DISTINCT FROM '2'::jsonb
        OR NEW.next_cursor_hash IS DISTINCT FROM (CASE WHEN body->>'nextAlternativeCursor' IS NULL THEN NULL
            ELSE encode(sha256(convert_to(body->>'nextAlternativeCursor','UTF8')),'hex') END) THEN
        RAISE EXCEPTION 'Manifest Plan bytes differ from row' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER manifest_plan_original BEFORE INSERT ON planning.plans
    FOR EACH ROW EXECUTE FUNCTION planning.guard_manifest_plan_insert();

CREATE FUNCTION planning.guard_manifest_lineage_update() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE next_plan planning.plans%ROWTYPE;
BEGIN
    IF NEW.storage_format<>OLD.storage_format THEN
        RAISE EXCEPTION 'Plan lineage format is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.storage_format=1 THEN RETURN NEW; END IF;
    IF ROW(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.id,NEW.request_text,NEW.request_hash,
        NEW.evidence_text,NEW.evidence_hash,NEW.ordered_ids,NEW.policy_text,NEW.created_at,NEW.expires_at,
        NEW.cursor_expires_at,NEW.manifest_id,NEW.create_command_key,NEW.command_request_sha256)
        IS DISTINCT FROM ROW(OLD.environment,OLD.actor_kind,OLD.principal_id,OLD.id,OLD.request_text,OLD.request_hash,
        OLD.evidence_text,OLD.evidence_hash,OLD.ordered_ids,OLD.policy_text,OLD.created_at,OLD.expires_at,
        OLD.cursor_expires_at,OLD.manifest_id,OLD.create_command_key,OLD.command_request_sha256)
        OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.current_plan_id IS NULL
        OR NEW.current_plan_id IS NOT DISTINCT FROM OLD.current_plan_id THEN
        RAISE EXCEPTION 'Manifest lineage basis is immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO next_plan FROM planning.plans WHERE environment=OLD.environment AND actor_kind=OLD.actor_kind
        AND principal_id=OLD.principal_id AND request_id=OLD.id AND id=NEW.current_plan_id;
    IF NOT FOUND OR next_plan.storage_format<>2 OR next_plan.parent_plan_id IS DISTINCT FROM OLD.current_plan_id THEN
        RAISE EXCEPTION 'Manifest head is not the exact child' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER manifest_lineage_transition BEFORE UPDATE ON planning.plan_requests
    FOR EACH ROW EXECUTE FUNCTION planning.guard_manifest_lineage_update();

CREATE FUNCTION planning.require_manifest_lineage_head() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lineage planning.plan_requests%ROWTYPE; head planning.plans%ROWTYPE; at_time timestamptz; lineage_id uuid;
BEGIN
    IF NEW.storage_format=1 THEN RETURN NULL; END IF;
    IF TG_TABLE_NAME='plans' THEN lineage_id := NEW.request_id; ELSE lineage_id := NEW.id; END IF;
    SELECT * INTO lineage FROM planning.plan_requests WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind
        AND principal_id=NEW.principal_id AND id=lineage_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Manifest lineage missing at commit' USING ERRCODE='23514'; END IF;
    at_time := clock_timestamp();
    IF lineage.current_plan_id IS NULL OR lineage.version<2 OR lineage.expires_at<=at_time OR lineage.cursor_expires_at<=at_time THEN
        RAISE EXCEPTION 'Manifest lineage incomplete or expired at commit' USING ERRCODE='23514';
    END IF;
    SELECT * INTO head FROM planning.plans WHERE environment=lineage.environment AND actor_kind=lineage.actor_kind
        AND principal_id=lineage.principal_id AND request_id=lineage.id AND id=lineage.current_plan_id;
    IF NOT FOUND OR head.storage_format<>2 OR EXISTS (SELECT 1 FROM planning.plans p WHERE p.environment=lineage.environment
        AND p.actor_kind=lineage.actor_kind AND p.principal_id=lineage.principal_id AND p.request_id=lineage.id
        AND p.parent_plan_id=head.id) THEN
        RAISE EXCEPTION 'Manifest current head differs at commit' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER manifest_lineage_requires_head AFTER INSERT OR UPDATE ON planning.plan_requests
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION planning.require_manifest_lineage_head();
CREATE CONSTRAINT TRIGGER manifest_plan_requires_head AFTER INSERT ON planning.plans
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION planning.require_manifest_lineage_head();

-- No new retention permission: refuse removing format2 state while keeping legacy deletes.
CREATE FUNCTION planning.reject_manifest_lineage_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.storage_format=2 THEN RAISE EXCEPTION 'Manifest Plan retention is not configured' USING ERRCODE='23514'; END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER manifest_lineage_retained BEFORE DELETE ON planning.plan_requests
    FOR EACH ROW EXECUTE FUNCTION planning.reject_manifest_lineage_delete();
CREATE TRIGGER manifest_plan_retained BEFORE DELETE ON planning.plans
    FOR EACH ROW EXECUTE FUNCTION planning.reject_manifest_lineage_delete();
CREATE FUNCTION planning.reject_manifest_lineage_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM planning.plan_requests WHERE storage_format=2)
        OR EXISTS (SELECT 1 FROM planning.plans WHERE storage_format=2) THEN
        RAISE EXCEPTION 'Manifest Plan retention is not configured' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER manifest_lineage_no_truncate BEFORE TRUNCATE ON planning.plan_requests
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_lineage_truncate();
CREATE TRIGGER manifest_plan_no_truncate BEFORE TRUNCATE ON planning.plans
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_lineage_truncate();
