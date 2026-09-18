-- Independently versioned, append-only substitution originals. No approved content,
-- accepting publication authority, runtime role, policy or remote activation is seeded.
-- V001--V024 stay unchanged. Kotlin strictly decodes/re-encodes each bounded original and
-- recomputes request/record/normalized-definition hashes; SQL binds structural projections.
CREATE TABLE catalog.substitution_publications (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    publication_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    predecessor_revision bigint NOT NULL CHECK (predecessor_revision >= 0 AND predecessor_revision = revision - 1),
    edge_id uuid NOT NULL,
    edge_version numeric(128,0) NOT NULL CHECK (edge_version > 0),
    definition_sha256 char(64) NOT NULL CHECK (definition_sha256 ~ '^[0-9a-f]{64}$'),
    record_sha256 char(64) NOT NULL CHECK (record_sha256 ~ '^[0-9a-f]{64}$'),
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    exact_document text NOT NULL CHECK (octet_length(exact_document) BETWEEN 1 AND 262144),
    catalog_revision bigint NOT NULL CHECK (catalog_revision > 0),
    catalog_release_id uuid NOT NULL,
    catalog_request_sha256 char(64) NOT NULL CHECK (catalog_request_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(environment,publication_id),
    UNIQUE(environment,revision),
    UNIQUE(environment,edge_id,edge_version),
    UNIQUE(environment,publication_id,revision),
    FOREIGN KEY(environment,catalog_release_id,catalog_revision)
        REFERENCES catalog.recipe_releases(environment,release_id,revision)
);
CREATE INDEX substitution_edge_history ON catalog.substitution_publications(environment,edge_id,revision DESC);

CREATE TABLE catalog.substitution_heads (
    environment varchar(40) PRIMARY KEY CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    revision bigint NOT NULL CHECK (revision >= 0),
    publication_id uuid NULL,
    CHECK ((revision=0 AND publication_id IS NULL) OR (revision>0 AND publication_id IS NOT NULL)),
    FOREIGN KEY(environment,publication_id,revision)
        REFERENCES catalog.substitution_publications(environment,publication_id,revision)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE FUNCTION catalog.substitution_exact_keys(value jsonb,expected text[]) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_typeof(value) IS NOT DISTINCT FROM 'object'
        AND (SELECT coalesce(array_agg(key ORDER BY key),'{}'::text[])
            FROM jsonb_object_keys(CASE WHEN jsonb_typeof(value)='object' THEN value ELSE '{}'::jsonb END) AS key)
            = (SELECT array_agg(key ORDER BY key) FROM unnest(expected) AS key);
$$;

CREATE FUNCTION catalog.guard_substitution_publication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original jsonb; retained jsonb; definition jsonb; previous catalog.substitution_publications%ROWTYPE;
    old_record jsonb; head_revision bigint; recipe_revision bigint; recipe_release uuid; recipe_request text;
    created_at timestamptz; updated_at timestamptz; effective_at timestamptz;
BEGIN
    original := NEW.exact_document::jsonb;
    IF NOT catalog.substitution_exact_keys(original,ARRAY['publicationId','expectedRevision','publisherId',
        'reviewerId','publicationReference','reviewId','record'])
        OR original->'publicationId' IS DISTINCT FROM to_jsonb(NEW.publication_id::text)
        OR original->'expectedRevision' IS DISTINCT FROM to_jsonb(NEW.predecessor_revision)
        OR jsonb_typeof(original->'expectedRevision') IS DISTINCT FROM 'number'
        OR coalesce(original->>'expectedRevision','') !~ '^(0|[1-9][0-9]{0,18})$'
        OR jsonb_typeof(original->'publicationReference') IS DISTINCT FROM 'string'
        OR length(original->>'publicationReference') NOT BETWEEN 1 AND 256
        OR btrim(original->>'publicationReference') = '' THEN
        RAISE EXCEPTION 'Invalid substitution original' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(ARRAY['publisherId','reviewerId','reviewId']) AS field
        WHERE jsonb_typeof(original->field) IS DISTINCT FROM 'string'
            OR coalesce(original->>field,'') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
        OR original->'publisherId'=original->'reviewerId' THEN
        RAISE EXCEPTION 'Invalid substitution review identity' USING ERRCODE='23514';
    END IF;
    retained := original->'record'; definition := retained->'definition';
    IF NOT catalog.substitution_exact_keys(retained,ARRAY['formatVersion','definition','version','status','createdAt','updatedAt','recall'])
        OR retained->'formatVersion' IS DISTINCT FROM '1'::jsonb
        OR jsonb_typeof(retained->'version') IS DISTINCT FROM 'number'
        OR coalesce(retained->>'version','') !~ '^[1-9][0-9]{0,127}$'
        OR retained->'version' IS DISTINCT FROM to_jsonb(NEW.edge_version)
        OR jsonb_typeof(retained->'status') IS DISTINCT FROM 'string'
        OR coalesce(retained->>'status','') NOT IN ('draft','reviewed','recalled')
        OR jsonb_typeof(retained->'createdAt') IS DISTINCT FROM 'string'
        OR jsonb_typeof(retained->'updatedAt') IS DISTINCT FROM 'string'
        OR NOT catalog.substitution_exact_keys(definition,ARRAY['id','fromIngredientId','toIngredientId','recipeVersionIds',
            'ratio','preservedTags','requiredStepChanges','sourceRecipeVersionId','targetRecipeVersionId',
            'sourceMaterialSha256','targetMaterialSha256','reviewReference','policyVersion','comparisonServings','explanation'])
        OR definition->'id' IS DISTINCT FROM to_jsonb(NEW.edge_id::text) THEN
        RAISE EXCEPTION 'Invalid substitution record projection' USING ERRCODE='23514';
    END IF;
    created_at := (retained->>'createdAt')::timestamptz;
    updated_at := (retained->>'updatedAt')::timestamptz;
    IF NOT isfinite(created_at) OR NOT isfinite(updated_at) OR updated_at < created_at THEN
        RAISE EXCEPTION 'Invalid substitution record time' USING ERRCODE='23514';
    END IF;
    IF retained->>'status'='recalled' THEN
        IF NOT catalog.substitution_exact_keys(retained->'recall',ARRAY['recallId','reasonCode','effectiveAt'])
            OR jsonb_typeof(retained->'recall'->'recallId') IS DISTINCT FROM 'string'
            OR coalesce(retained->'recall'->>'recallId','') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            OR jsonb_typeof(retained->'recall'->'reasonCode') IS DISTINCT FROM 'string'
            OR length(retained->'recall'->>'reasonCode') NOT BETWEEN 1 AND 128
            OR btrim(retained->'recall'->>'reasonCode')=''
            OR jsonb_typeof(retained->'recall'->'effectiveAt') IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Invalid substitution recall projection' USING ERRCODE='23514';
        END IF;
        effective_at := (retained->'recall'->>'effectiveAt')::timestamptz;
        IF NOT isfinite(effective_at) OR effective_at NOT BETWEEN created_at AND updated_at THEN
            RAISE EXCEPTION 'Invalid substitution recall time' USING ERRCODE='23514';
        END IF;
    ELSIF retained->'recall' IS DISTINCT FROM 'null'::jsonb THEN
        RAISE EXCEPTION 'Unexpected substitution recall' USING ERRCODE='23514';
    END IF;

    -- Global lock order is real recipe catalog head, then substitution head. A real catalog
    -- must exist first; this migration/store cannot manufacture an accepting catalog anchor.
    SELECT h.revision,h.release_id,r.request_sha256 INTO recipe_revision,recipe_release,recipe_request
        FROM catalog.recipe_heads h JOIN catalog.recipe_releases r
            ON r.environment=h.environment AND r.release_id=h.release_id AND r.revision=h.revision
        WHERE h.environment=NEW.environment FOR SHARE OF h;
    IF NOT FOUND OR ROW(recipe_revision,recipe_release,recipe_request) IS DISTINCT FROM
        ROW(NEW.catalog_revision,NEW.catalog_release_id,NEW.catalog_request_sha256::text) THEN
        RAISE EXCEPTION 'Substitution catalog anchor is not current' USING ERRCODE='23514';
    END IF;
    SELECT revision INTO head_revision FROM catalog.substitution_heads
        WHERE environment=NEW.environment FOR UPDATE;
    IF NOT FOUND OR head_revision <> NEW.predecessor_revision THEN
        RAISE EXCEPTION 'Substitution publication is not an open successor' USING ERRCODE='23514';
    END IF;
    SELECT * INTO previous FROM catalog.substitution_publications
        WHERE environment=NEW.environment AND edge_id=NEW.edge_id ORDER BY revision DESC LIMIT 1;
    IF NOT FOUND THEN
        IF NEW.edge_version<>1 OR retained->>'status' NOT IN ('draft','reviewed')
            OR retained->'createdAt' IS DISTINCT FROM retained->'updatedAt' THEN
            RAISE EXCEPTION 'Invalid first substitution revision' USING ERRCODE='23514';
        END IF;
    ELSE
        old_record := previous.exact_document::jsonb->'record';
        IF NEW.edge_version<>previous.edge_version+1 OR NEW.definition_sha256<>previous.definition_sha256
            OR definition IS DISTINCT FROM old_record->'definition'
            OR retained->'createdAt' IS DISTINCT FROM old_record->'createdAt'
            OR updated_at<(old_record->>'updatedAt')::timestamptz
            OR old_record->>'status'='recalled'
            OR (old_record->>'status'='draft' AND retained->>'status' NOT IN ('reviewed','recalled'))
            OR (old_record->>'status'='reviewed' AND retained->>'status'<>'recalled')
            OR (retained->>'status'='recalled' AND effective_at<(old_record->>'updatedAt')::timestamptz) THEN
            RAISE EXCEPTION 'Invalid substitution lifecycle successor' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER substitution_publication_original BEFORE INSERT ON catalog.substitution_publications
    FOR EACH ROW EXECUTE FUNCTION catalog.guard_substitution_publication();

CREATE FUNCTION catalog.guard_substitution_head() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.revision<>0 OR NEW.publication_id IS NOT NULL THEN
            RAISE EXCEPTION 'Invalid initial substitution head' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.environment<>OLD.environment OR OLD.revision=9223372036854775807 OR NEW.revision<>OLD.revision+1
            OR NOT EXISTS (SELECT 1 FROM catalog.substitution_publications p WHERE p.environment=NEW.environment
                AND p.publication_id=NEW.publication_id AND p.revision=NEW.revision AND p.predecessor_revision=OLD.revision) THEN
            RAISE EXCEPTION 'Invalid substitution head transition' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER substitution_head_transition BEFORE INSERT OR UPDATE ON catalog.substitution_heads
    FOR EACH ROW EXECUTE FUNCTION catalog.guard_substitution_head();

CREATE FUNCTION catalog.require_substitution_publication_committed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE head_revision bigint; latest_revision bigint;
BEGIN
    SELECT revision INTO head_revision FROM catalog.substitution_heads WHERE environment=NEW.environment;
    SELECT max(revision) INTO latest_revision FROM catalog.substitution_publications WHERE environment=NEW.environment;
    IF head_revision IS NULL OR head_revision IS DISTINCT FROM latest_revision THEN
        RAISE EXCEPTION 'Substitution publication has no committed head' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER substitution_publication_committed AFTER INSERT ON catalog.substitution_publications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION catalog.require_substitution_publication_committed();

CREATE FUNCTION catalog.reject_substitution_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Substitution history is immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER substitution_publications_immutable BEFORE UPDATE OR DELETE ON catalog.substitution_publications
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_substitution_mutation();
CREATE TRIGGER substitution_publications_no_truncate BEFORE TRUNCATE ON catalog.substitution_publications
    FOR EACH STATEMENT EXECUTE FUNCTION catalog.reject_substitution_mutation();
CREATE TRIGGER substitution_heads_no_delete BEFORE DELETE ON catalog.substitution_heads
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_substitution_mutation();
CREATE TRIGGER substitution_heads_no_truncate BEFORE TRUNCATE ON catalog.substitution_heads
    FOR EACH STATEMENT EXECUTE FUNCTION catalog.reject_substitution_mutation();

-- Deny-by-default, including ordinary table owners. No permissive policy/role/credential is
-- created. A real backend's separately approved privileged connection remains an operational
-- prerequisite; these structural tables never grant anonymous/authenticated direct API access.
ALTER TABLE catalog.substitution_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.substitution_heads FORCE ROW LEVEL SECURITY;
ALTER TABLE catalog.substitution_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.substitution_publications FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE catalog.substitution_heads,catalog.substitution_publications FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog.substitution_exact_keys(jsonb,text[]),catalog.guard_substitution_publication(),
    catalog.guard_substitution_head(),catalog.require_substitution_publication_committed(),catalog.reject_substitution_mutation() FROM PUBLIC;
DO $$
DECLARE target_role text;
BEGIN
    FOREACH target_role IN ARRAY ARRAY['anon','authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=target_role) THEN
            EXECUTE format('REVOKE ALL ON TABLE catalog.substitution_heads,catalog.substitution_publications FROM %I',target_role);
        END IF;
    END LOOP;
END;
$$;
