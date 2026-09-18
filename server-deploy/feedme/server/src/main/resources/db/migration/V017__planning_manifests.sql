-- Inert, additive storage prerequisite: no writer, authority, Plan lineage or HTTP activation.
-- Existing V001--V016 data and schemas are unchanged. Exact codecs and the future owning
-- writer/reader must verify private authority, deadlines, original material, ranks and digest.
-- All deletion is refused here. A later guarded whole-manifest retention/erasure design is
-- mandatory before private production activation; this is not a forever-retention policy.

CREATE TABLE planning.manifest_headers (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    actor_kind varchar(10) NOT NULL CHECK (actor_kind IN ('account','guest')),
    principal_id uuid NOT NULL,
    manifest_id uuid NOT NULL,
    header_text text NOT NULL CHECK (octet_length(header_text) BETWEEN 2 AND 2097152
        AND jsonb_typeof(header_text::jsonb)='object'),
    header_sha256 varchar(64) NOT NULL CHECK (header_sha256 ~ '^[0-9a-f]{64}$'),
    catalog_release_id uuid NOT NULL,
    catalog_revision bigint NOT NULL CHECK (catalog_revision > 0),
    catalog_request_sha256 varchar(64) NOT NULL CHECK (catalog_request_sha256 ~ '^[0-9a-f]{64}$'),
    taxonomy_revision varchar(128) NOT NULL CHECK (btrim(taxonomy_revision) <> ''),
    taxonomy_sha256 varchar(64) NOT NULL CHECK (taxonomy_sha256 ~ '^[0-9a-f]{64}$'),
    version_count bigint NOT NULL CHECK (version_count >= 0),
    comparator varchar(32) NOT NULL CHECK (comparator='lexicographic-v1'),
    PRIMARY KEY(environment,actor_kind,principal_id,manifest_id),
    FOREIGN KEY(environment,catalog_release_id,catalog_revision)
        REFERENCES catalog.recipe_releases(environment,release_id,revision),
    CHECK (header_sha256=encode(sha256(convert_to(header_text,'UTF8')),'hex')),
    CHECK ((header_text::jsonb->'version') IS NOT DISTINCT FROM '2'::jsonb),
    CHECK ((header_text::jsonb->'comparator') IS NOT DISTINCT FROM to_jsonb(comparator)),
    CHECK ((header_text::jsonb->'owner') IS NOT DISTINCT FROM jsonb_build_object(
        'environment',environment,'actorKind',actor_kind,'principalId',principal_id::text,'manifestId',manifest_id::text)),
    CHECK ((header_text::jsonb->'catalogAnchor') IS NOT DISTINCT FROM jsonb_build_object(
        'releaseId',catalog_release_id::text,'revision',catalog_revision::text,'requestSha256',catalog_request_sha256,
        'taxonomyRevision',taxonomy_revision,'taxonomySha256',taxonomy_sha256,'versionCount',version_count::text)),
    CHECK (header_text::jsonb ?& ARRAY['version','owner','requestText','requestSha256','inputsText','inputsSha256',
        'policy','comparator','catalogAnchor','createdAt','expiresAt','cursorExpiresAt']
        AND header_text::jsonb - ARRAY['version','owner','requestText','requestSha256','inputsText','inputsSha256',
            'policy','comparator','catalogAnchor','createdAt','expiresAt','cursorExpiresAt']='{}'::jsonb)
);
-- Exact deadline strings stay in the header. Separate timestamptz projections would round
-- valid Instant nanoseconds; the future consumer must decode/recheck their exact values.

-- Actual canonical recipe times are nonnegative integers. Retain the exact PlanningDecimal
-- coefficient/scale domain: <=9 significant digits, stripped scale -6..0, zero special.
-- Unconstrained NUMERIC is essential: NUMERIC(p,s) could round before a CHECK sees input.
CREATE FUNCTION planning.valid_manifest_minutes(value numeric) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE digits text; coefficient text; stripped_scale integer;
BEGIN
    IF value IS NULL THEN RETURN true; END IF;
    IF value::text IN ('NaN','Infinity','-Infinity') THEN RETURN false; END IF;
    IF value < 0 OR value > 999999999000000 OR value <> trunc(value) THEN RETURN false; END IF;
    IF value=0 THEN RETURN true; END IF;
    digits := replace(value::text,'.','');
    coefficient := rtrim(digits,'0');
    stripped_scale := scale(value) - (length(digits) - length(coefficient));
    coefficient := ltrim(coefficient,'0');
    RETURN length(coefficient) BETWEEN 1 AND 9 AND stripped_scale BETWEEN -6 AND 0;
END;
$$;

CREATE TABLE planning.manifest_ranks (
    environment varchar(40) NOT NULL,
    actor_kind varchar(10) NOT NULL,
    principal_id uuid NOT NULL,
    manifest_id uuid NOT NULL,
    recipe_version_id uuid NOT NULL,
    recipe_id uuid NOT NULL,
    source_release_id uuid NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision > 0),
    source_request_sha256 varchar(64) NOT NULL CHECK (source_request_sha256 ~ '^[0-9a-f]{64}$'),
    recipe_sha256 varchar(64) NOT NULL CHECK (recipe_sha256 ~ '^[0-9a-f]{64}$'),
    review_sha256 varchar(64) NOT NULL CHECK (review_sha256 ~ '^[0-9a-f]{64}$'),
    -- Integer typmods can coerce fractional assignments before CHECK. Plain numeric plus
    -- integral/range checks rejects them; the finite upper bounds also exclude NaN/infinity.
    taste_matches numeric NOT NULL CHECK (taste_matches BETWEEN 0 AND 4 AND taste_matches=trunc(taste_matches)),
    disliked_ingredients numeric NOT NULL CHECK (disliked_ingredients BETWEEN 0 AND 128 AND disliked_ingredients=trunc(disliked_ingredients)),
    confirmed_ingredients numeric NOT NULL CHECK (confirmed_ingredients BETWEEN 0 AND 128 AND confirmed_ingredients=trunc(confirmed_ingredients)),
    active_minutes numeric NULL CHECK (planning.valid_manifest_minutes(active_minutes)),
    cleanup_minutes numeric NULL CHECK (planning.valid_manifest_minutes(cleanup_minutes)),
    PRIMARY KEY(environment,actor_kind,principal_id,manifest_id,recipe_version_id),
    FOREIGN KEY(environment,actor_kind,principal_id,manifest_id)
        REFERENCES planning.manifest_headers(environment,actor_kind,principal_id,manifest_id),
    FOREIGN KEY(environment,recipe_version_id,source_revision)
        REFERENCES catalog.recipe_version_history(environment,recipe_version_id,revision),
    FOREIGN KEY(environment,source_release_id,source_revision)
        REFERENCES catalog.recipe_releases(environment,release_id,revision),
    FOREIGN KEY(environment,source_release_id,recipe_version_id)
        REFERENCES catalog.recipe_release_entries(environment,release_id,recipe_version_id)
);
CREATE INDEX manifest_rank_order ON planning.manifest_ranks(
    environment,actor_kind,principal_id,manifest_id,
    taste_matches DESC,disliked_ingredients ASC,confirmed_ingredients DESC,
    active_minutes ASC NULLS FIRST,cleanup_minutes ASC NULLS LAST,recipe_id ASC,recipe_version_id ASC);
-- The PK is the independent source-version scan/digest order, NOT this rank order.
-- Null times exist only for structural ordering compatibility. The future writer must use
-- actual eligible engine ranks, whose active time is present, and recheck original hashes.

CREATE TABLE planning.manifest_seals (
    environment varchar(40) NOT NULL,
    actor_kind varchar(10) NOT NULL,
    principal_id uuid NOT NULL,
    manifest_id uuid NOT NULL,
    header_sha256 varchar(64) NOT NULL CHECK (header_sha256 ~ '^[0-9a-f]{64}$'),
    traversed_count bigint NOT NULL CHECK (traversed_count >= 0),
    eligible_count bigint NOT NULL CHECK (eligible_count BETWEEN 0 AND traversed_count),
    rows_sha256 varchar(64) NOT NULL CHECK (rows_sha256 ~ '^[0-9a-f]{64}$'),
    first_decision_text text NOT NULL CHECK (octet_length(first_decision_text) BETWEEN 2 AND 262144
        AND jsonb_typeof(first_decision_text::jsonb)='object'),
    first_decision_sha256 varchar(64) NOT NULL CHECK (first_decision_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(environment,actor_kind,principal_id,manifest_id),
    FOREIGN KEY(environment,actor_kind,principal_id,manifest_id)
        REFERENCES planning.manifest_headers(environment,actor_kind,principal_id,manifest_id),
    CHECK (first_decision_sha256=encode(sha256(convert_to(first_decision_text,'UTF8')),'hex'))
);
-- The future application verifies framed row digest and a distinct bounded decision codec.
-- Count equality is necessary, not proof that an authorized scan reached actual EOF.

CREATE FUNCTION planning.guard_manifest_header_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_revision bigint; source catalog.recipe_releases%ROWTYPE; actual_count bigint;
BEGIN
    SELECT revision INTO head_revision FROM catalog.recipe_heads
        WHERE environment=NEW.environment FOR SHARE;
    IF NOT FOUND OR NEW.catalog_revision > head_revision THEN
        RAISE EXCEPTION 'Planning manifest anchor unavailable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO source FROM catalog.recipe_releases
        WHERE environment=NEW.environment AND release_id=NEW.catalog_release_id AND revision=NEW.catalog_revision FOR SHARE;
    IF NOT FOUND OR source.request_sha256 IS DISTINCT FROM NEW.catalog_request_sha256
        OR source.taxonomy_revision IS DISTINCT FROM NEW.taxonomy_revision
        OR source.taxonomy_sha256 IS DISTINCT FROM NEW.taxonomy_sha256 THEN
        RAISE EXCEPTION 'Planning manifest anchor unavailable' USING ERRCODE='23514';
    END IF;
    SELECT count(DISTINCT recipe_version_id) INTO actual_count FROM catalog.recipe_version_history
        WHERE environment=NEW.environment AND revision<=NEW.catalog_revision;
    IF actual_count <> NEW.version_count THEN
        RAISE EXCEPTION 'Planning manifest anchor unavailable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER manifest_header_anchor BEFORE INSERT ON planning.manifest_headers
    FOR EACH ROW EXECUTE FUNCTION planning.guard_manifest_header_insert();

-- Every rank/seal insertion takes the SAME owned header lock before inspecting completion.
-- No unlocked pre-check can race a seal. Committed headers cannot be incomplete because
-- of the deferred constraint below; no mutation/default grant opens them again.
CREATE FUNCTION planning.open_manifest_header(actual_environment varchar,actual_actor varchar,
    actual_principal uuid,actual_manifest uuid) RETURNS planning.manifest_headers LANGUAGE plpgsql AS $$
DECLARE owned planning.manifest_headers%ROWTYPE;
BEGIN
    SELECT * INTO owned FROM planning.manifest_headers
        WHERE environment=actual_environment AND actor_kind=actual_actor
            AND principal_id=actual_principal AND manifest_id=actual_manifest FOR UPDATE;
    IF NOT FOUND OR EXISTS (SELECT 1 FROM planning.manifest_seals
        WHERE environment=actual_environment AND actor_kind=actual_actor
            AND principal_id=actual_principal AND manifest_id=actual_manifest) THEN
        RAISE EXCEPTION 'Planning manifest is not open' USING ERRCODE='23514';
    END IF;
    RETURN owned;
END;
$$;

CREATE FUNCTION planning.guard_manifest_rank_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owned planning.manifest_headers%ROWTYPE; latest catalog.recipe_version_history%ROWTYPE;
    actual_hash text; actual_recipe uuid;
BEGIN
    owned := planning.open_manifest_header(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.manifest_id);
    SELECT * INTO latest FROM catalog.recipe_version_history
        WHERE environment=NEW.environment AND recipe_version_id=NEW.recipe_version_id
            AND revision<=owned.catalog_revision ORDER BY revision DESC LIMIT 1;
    IF NOT FOUND OR latest.revision IS DISTINCT FROM NEW.source_revision
        OR latest.release_id IS DISTINCT FROM NEW.source_release_id THEN
        RAISE EXCEPTION 'Planning rank source unavailable' USING ERRCODE='23514';
    END IF;
    SELECT r.request_sha256,(e.entry->'recipe'->>'recipeId')::uuid INTO actual_hash,actual_recipe
        FROM catalog.recipe_releases r JOIN catalog.recipe_release_entries e
            ON e.environment=r.environment AND e.release_id=r.release_id
        WHERE r.environment=NEW.environment AND r.release_id=NEW.source_release_id
            AND r.revision=NEW.source_revision AND e.recipe_version_id=NEW.recipe_version_id FOR SHARE OF r,e;
    IF NOT FOUND OR actual_hash IS DISTINCT FROM NEW.source_request_sha256 OR actual_recipe IS DISTINCT FROM NEW.recipe_id THEN
        RAISE EXCEPTION 'Planning rank source unavailable' USING ERRCODE='23514';
    END IF;
    -- Raw original recipe/review UTF-8 hashes cannot be recomputed from JSONB normalized
    -- projections here. The future writer/reader must verify them against checked originals.
    RETURN NEW;
END;
$$;
CREATE TRIGGER manifest_rank_source BEFORE INSERT ON planning.manifest_ranks
    FOR EACH ROW EXECUTE FUNCTION planning.guard_manifest_rank_insert();

CREATE FUNCTION planning.guard_manifest_seal_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owned planning.manifest_headers%ROWTYPE; actual_count bigint;
BEGIN
    owned := planning.open_manifest_header(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.manifest_id);
    SELECT count(*) INTO actual_count FROM planning.manifest_ranks
        WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind
            AND principal_id=NEW.principal_id AND manifest_id=NEW.manifest_id;
    IF NEW.header_sha256 IS DISTINCT FROM owned.header_sha256 OR NEW.traversed_count IS DISTINCT FROM owned.version_count
        OR NEW.eligible_count IS DISTINCT FROM actual_count THEN
        RAISE EXCEPTION 'Planning manifest seal mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER manifest_seal_complete BEFORE INSERT ON planning.manifest_seals
    FOR EACH ROW EXECUTE FUNCTION planning.guard_manifest_seal_insert();

CREATE FUNCTION planning.require_manifest_seal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM planning.manifest_seals
        WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind
            AND principal_id=NEW.principal_id AND manifest_id=NEW.manifest_id) THEN
        RAISE EXCEPTION 'Planning manifest is incomplete' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER manifest_requires_seal AFTER INSERT ON planning.manifest_headers
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION planning.require_manifest_seal();

CREATE FUNCTION planning.reject_manifest_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Planning manifests are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER manifest_header_immutable BEFORE UPDATE OR DELETE ON planning.manifest_headers
    FOR EACH ROW EXECUTE FUNCTION planning.reject_manifest_mutation();
CREATE TRIGGER manifest_rank_immutable BEFORE UPDATE OR DELETE ON planning.manifest_ranks
    FOR EACH ROW EXECUTE FUNCTION planning.reject_manifest_mutation();
CREATE TRIGGER manifest_seal_immutable BEFORE UPDATE OR DELETE ON planning.manifest_seals
    FOR EACH ROW EXECUTE FUNCTION planning.reject_manifest_mutation();
CREATE TRIGGER manifest_header_no_truncate BEFORE TRUNCATE ON planning.manifest_headers
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_mutation();
CREATE TRIGGER manifest_rank_no_truncate BEFORE TRUNCATE ON planning.manifest_ranks
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_mutation();
CREATE TRIGGER manifest_seal_no_truncate BEFORE TRUNCATE ON planning.manifest_seals
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_mutation();
