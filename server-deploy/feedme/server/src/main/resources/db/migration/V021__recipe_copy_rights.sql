-- Positive copy rights are independent from catalog publication/license labels. No seeds,
-- operators or accepting policy. V001--V020 remain immutable.
CREATE TABLE catalog.recipe_copy_grants (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    grant_id uuid NOT NULL, recipe_version_id uuid NOT NULL,
    source_release_id uuid NOT NULL, source_revision bigint NOT NULL CHECK (source_revision > 0),
    source_request_sha256 char(64) NOT NULL CHECK (source_request_sha256 ~ '^[0-9a-f]{64}$'),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    exact_document text NOT NULL CHECK (octet_length(exact_document) <= 8192),
    content_license varchar(32) NOT NULL CHECK (content_license IN ('catalogRedistributable','privateCopyOnly')),
    allow_guest boolean NOT NULL, allow_reviewed_scaling boolean NOT NULL,
    not_before timestamptz NOT NULL, new_copies_until timestamptz NOT NULL, retained_copies_until timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,grant_id),
    FOREIGN KEY(environment,source_release_id,source_revision)
        REFERENCES catalog.recipe_releases(environment,release_id,revision),
    FOREIGN KEY(environment,source_release_id,recipe_version_id)
        REFERENCES catalog.recipe_release_entries(environment,release_id,recipe_version_id),
    CHECK (not_before < new_copies_until AND new_copies_until <= retained_copies_until),
    CHECK (date_trunc('milliseconds',not_before)=not_before AND date_trunc('milliseconds',new_copies_until)=new_copies_until
        AND date_trunc('milliseconds',retained_copies_until)=retained_copies_until)
);
CREATE INDEX recipe_copy_current_grants ON catalog.recipe_copy_grants(environment,recipe_version_id,created_at DESC,grant_id);
CREATE TABLE catalog.recipe_copy_revocations (
    environment varchar(40) NOT NULL, grant_id uuid NOT NULL, revocation_id uuid NOT NULL,
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    exact_document text NOT NULL CHECK (octet_length(exact_document) <= 8192),
    revoked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,grant_id), UNIQUE(environment,revocation_id),
    FOREIGN KEY(environment,grant_id) REFERENCES catalog.recipe_copy_grants(environment,grant_id)
);
CREATE FUNCTION catalog.reject_recipe_copy_rights_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Recipe copy rights originals are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER recipe_copy_grants_immutable BEFORE UPDATE OR DELETE ON catalog.recipe_copy_grants
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_recipe_copy_rights_mutation();
CREATE TRIGGER recipe_copy_revocations_immutable BEFORE UPDATE OR DELETE ON catalog.recipe_copy_revocations
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_recipe_copy_rights_mutation();
