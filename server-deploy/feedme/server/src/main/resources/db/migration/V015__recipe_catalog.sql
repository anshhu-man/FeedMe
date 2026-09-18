-- Bounded recipe publication persistence; no content, staff authority, rights or policy seed.
-- V001--V014 are unchanged. The existing catalog schema is owned by V013.
CREATE TABLE catalog.recipe_releases (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    release_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    predecessor_revision bigint NOT NULL CHECK (predecessor_revision >= 0 AND predecessor_revision = revision - 1),
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    taxonomy_revision varchar(128) NOT NULL CHECK (btrim(taxonomy_revision) <> ''),
    taxonomy_sha256 char(64) NOT NULL CHECK (taxonomy_sha256 ~ '^[0-9a-f]{64}$'),
    exact_document text NOT NULL CHECK (octet_length(exact_document) <= 1048576),
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,release_id), UNIQUE(environment,revision), UNIQUE(environment,release_id,revision)
);
CREATE TABLE catalog.recipe_heads (
    environment varchar(40) PRIMARY KEY CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    revision bigint NOT NULL CHECK (revision >= 0), release_id uuid NULL,
    CHECK ((revision=0 AND release_id IS NULL) OR (revision>0 AND release_id IS NOT NULL)),
    FOREIGN KEY(environment,release_id,revision) REFERENCES catalog.recipe_releases(environment,release_id,revision)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE catalog.recipe_release_entries (
    environment varchar(40) NOT NULL, release_id uuid NOT NULL, recipe_version_id uuid NOT NULL,
    entry jsonb NOT NULL CHECK (jsonb_typeof(entry)='object' AND octet_length(entry::text)<=131072),
    PRIMARY KEY(environment,release_id,recipe_version_id),
    FOREIGN KEY(environment,release_id) REFERENCES catalog.recipe_releases(environment,release_id),
    CHECK (recipe_version_id=(entry->'recipe'->>'id')::uuid)
);
CREATE TABLE catalog.recipe_release_compositions (
    environment varchar(40) NOT NULL, release_id uuid NOT NULL, ingredient_id uuid NOT NULL,
    component_ids jsonb NOT NULL CHECK (jsonb_typeof(component_ids) IN ('array','null') AND octet_length(component_ids::text)<=8192),
    PRIMARY KEY(environment,release_id,ingredient_id),
    FOREIGN KEY(environment,release_id) REFERENCES catalog.recipe_releases(environment,release_id)
);
CREATE INDEX recipe_taxonomy_history ON catalog.recipe_releases(environment,taxonomy_revision);
CREATE FUNCTION catalog.reject_recipe_release_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Recipe releases are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER recipe_releases_immutable BEFORE UPDATE OR DELETE ON catalog.recipe_releases
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_recipe_release_mutation();
CREATE TRIGGER recipe_entries_immutable BEFORE UPDATE OR DELETE ON catalog.recipe_release_entries
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_recipe_release_mutation();
CREATE TRIGGER recipe_compositions_immutable BEFORE UPDATE OR DELETE ON catalog.recipe_release_compositions
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_recipe_release_mutation();
