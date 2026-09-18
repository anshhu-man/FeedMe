-- Persisted ingredient publication foundation only. No ingredient content, authorizer,
-- dietary/equipment/consent rules or staff identity is seeded by this migration.
CREATE SCHEMA catalog;
CREATE TABLE catalog.ingredient_releases (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    release_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    predecessor_revision bigint NOT NULL CHECK (predecessor_revision >= 0 AND predecessor_revision = revision - 1),
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    exact_document text NOT NULL CHECK (octet_length(exact_document) <= 1048576),
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment, release_id),
    UNIQUE(environment, revision),
    UNIQUE(environment, release_id, revision)
);
CREATE TABLE catalog.ingredient_heads (
    environment varchar(40) PRIMARY KEY CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    revision bigint NOT NULL CHECK (revision >= 0),
    release_id uuid NULL,
    CHECK ((revision = 0 AND release_id IS NULL) OR (revision > 0 AND release_id IS NOT NULL)),
    FOREIGN KEY(environment, release_id, revision) REFERENCES catalog.ingredient_releases(environment, release_id, revision)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE catalog.ingredient_release_items (
    environment varchar(40) NOT NULL,
    release_id uuid NOT NULL,
    ingredient_id uuid NOT NULL,
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object' AND octet_length(body::text) <= 1048576),
    reviewed boolean NOT NULL,
    published boolean NOT NULL,
    free_access boolean NOT NULL,
    PRIMARY KEY(environment, release_id, ingredient_id),
    FOREIGN KEY(environment, release_id) REFERENCES catalog.ingredient_releases(environment, release_id),
    CHECK (ingredient_id = (body->>'id')::uuid)
);
CREATE TABLE catalog.ingredient_release_aliases (
    environment varchar(40) NOT NULL,
    release_id uuid NOT NULL,
    ingredient_id uuid NOT NULL,
    position integer NOT NULL CHECK (position BETWEEN 0 AND 256),
    literal_text text NOT NULL CHECK (octet_length(literal_text) <= 4096),
    PRIMARY KEY(environment, release_id, ingredient_id, position),
    FOREIGN KEY(environment, release_id, ingredient_id) REFERENCES catalog.ingredient_release_items(environment, release_id, ingredient_id)
);
-- Every writer uses the head lock. Frozen releases are never edited/deleted or implicitly
-- promoted again. A new release is required for publication/retirement or changed evidence.
CREATE FUNCTION catalog.reject_ingredient_release_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Ingredient releases are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER ingredient_releases_immutable BEFORE UPDATE OR DELETE ON catalog.ingredient_releases
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_ingredient_release_mutation();
CREATE TRIGGER ingredient_items_immutable BEFORE UPDATE OR DELETE ON catalog.ingredient_release_items
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_ingredient_release_mutation();
CREATE TRIGGER ingredient_aliases_immutable BEFORE UPDATE OR DELETE ON catalog.ingredient_release_aliases
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_ingredient_release_mutation();
