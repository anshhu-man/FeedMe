-- One publication chain: preserve every V015 original and derive history from actual entries.
-- PlatformMigrations supplies bounded lock/statement timeouts and one atomic transaction.
-- Head first: an existing publisher can finish before any of its later tables are locked.
LOCK TABLE catalog.recipe_heads IN ACCESS EXCLUSIVE MODE;
LOCK TABLE catalog.recipe_releases, catalog.recipe_release_entries,
    catalog.recipe_release_compositions IN ACCESS EXCLUSIVE MODE;

CREATE TABLE catalog.recipe_version_history (
    environment varchar(40) NOT NULL,
    recipe_version_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    release_id uuid NOT NULL,
    PRIMARY KEY(environment,recipe_version_id,revision),
    FOREIGN KEY(environment,release_id,recipe_version_id)
        REFERENCES catalog.recipe_release_entries(environment,release_id,recipe_version_id),
    FOREIGN KEY(environment,release_id,revision)
        REFERENCES catalog.recipe_releases(environment,release_id,revision)
);
CREATE INDEX recipe_version_history_latest
    ON catalog.recipe_version_history(environment,recipe_version_id,revision DESC);

-- Refuse an incomplete or extraneous legacy projection instead of blessing it as history.
-- This is structural equality, not content review, rights or publication authorization.
DO $$
DECLARE source record; content jsonb;
BEGIN
    FOR source IN SELECT environment,release_id,exact_document::jsonb AS original
        FROM catalog.recipe_releases LOOP
        content := source.original->'content';
        IF content->'formatVersion' IS DISTINCT FROM '1'::jsonb
            OR jsonb_typeof(content->'entries') IS DISTINCT FROM 'array'
            OR jsonb_typeof(content->'ingredients') IS DISTINCT FROM 'array' THEN
            RAISE EXCEPTION 'Invalid legacy recipe projection' USING ERRCODE='23514';
        END IF;
        IF jsonb_array_length(content->'entries') NOT BETWEEN 1 AND 128
            OR jsonb_array_length(content->'ingredients') > 1024
            OR (SELECT count(*) FROM catalog.recipe_release_entries e
                WHERE e.environment=source.environment AND e.release_id=source.release_id)
                <> jsonb_array_length(content->'entries')
            OR EXISTS (SELECT 1 FROM catalog.recipe_release_entries e
                WHERE e.environment=source.environment AND e.release_id=source.release_id
                AND ((e.entry->'recipe'->>'id')::uuid IS DISTINCT FROM e.recipe_version_id
                    OR NOT EXISTS (SELECT 1 FROM jsonb_array_elements(content->'entries') AS original(entry)
                        WHERE original.entry=e.entry)))
            OR (SELECT count(*) FROM catalog.recipe_release_compositions c
                WHERE c.environment=source.environment AND c.release_id=source.release_id)
                <> jsonb_array_length(content->'ingredients')
            OR EXISTS (SELECT 1 FROM catalog.recipe_release_compositions c
                WHERE c.environment=source.environment AND c.release_id=source.release_id
                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(content->'ingredients') AS original(ingredient)
                    WHERE original.ingredient=jsonb_build_object(
                        'ingredientId',c.ingredient_id::text,'componentIds',c.component_ids))) THEN
            RAISE EXCEPTION 'Invalid legacy recipe projection' USING ERRCODE='23514';
        END IF;
    END LOOP;
END;
$$;

INSERT INTO catalog.recipe_version_history(environment,recipe_version_id,revision,release_id)
    SELECT e.environment,e.recipe_version_id,r.revision,e.release_id
    FROM catalog.recipe_release_entries e JOIN catalog.recipe_releases r
        ON r.environment=e.environment AND r.release_id=e.release_id;

-- A historical anchor cannot acquire additional entries or compositions after publication.
-- Both old full-snapshot and new changeset publishers already own this same head lock.
CREATE FUNCTION catalog.open_recipe_release_content(actual_environment varchar,actual_release uuid)
RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE head_revision bigint; release_revision bigint; content jsonb;
BEGIN
    SELECT revision INTO head_revision FROM catalog.recipe_heads
        WHERE environment=actual_environment FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Recipe release is not an open successor' USING ERRCODE='23514';
    END IF;
    SELECT revision,exact_document::jsonb->'content' INTO release_revision,content
        FROM catalog.recipe_releases
        WHERE environment=actual_environment AND release_id=actual_release;
    IF NOT FOUND OR release_revision-1 <> head_revision THEN
        RAISE EXCEPTION 'Recipe release is not an open successor' USING ERRCODE='23514';
    END IF;
    IF content->'formatVersion' IS DISTINCT FROM '1'::jsonb
        AND content->'formatVersion' IS DISTINCT FROM '2'::jsonb THEN
        RAISE EXCEPTION 'Invalid recipe release format' USING ERRCODE='23514';
    END IF;
    IF jsonb_typeof(content->'entries') IS DISTINCT FROM 'array'
        OR jsonb_typeof(content->'ingredients') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'Invalid recipe release projection' USING ERRCODE='23514';
    END IF;
    RETURN content;
END;
$$;

CREATE FUNCTION catalog.guard_recipe_entry_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE content jsonb; identities bigint; exact_entries bigint;
BEGIN
    content := catalog.open_recipe_release_content(NEW.environment,NEW.release_id);
    SELECT count(*),count(*) FILTER (WHERE original.entry=NEW.entry)
        INTO identities,exact_entries FROM jsonb_array_elements(content->'entries') AS original(entry)
        WHERE (original.entry->'recipe'->>'id')::uuid=NEW.recipe_version_id;
    IF identities <> 1 OR exact_entries <> 1 THEN
        RAISE EXCEPTION 'Recipe entry differs from original' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER recipe_entry_original BEFORE INSERT ON catalog.recipe_release_entries
    FOR EACH ROW EXECUTE FUNCTION catalog.guard_recipe_entry_insert();

CREATE FUNCTION catalog.guard_recipe_composition_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE content jsonb; identities bigint; exact_compositions bigint;
BEGIN
    content := catalog.open_recipe_release_content(NEW.environment,NEW.release_id);
    SELECT count(*),count(*) FILTER (WHERE original.ingredient=jsonb_build_object(
        'ingredientId',NEW.ingredient_id::text,'componentIds',NEW.component_ids))
        INTO identities,exact_compositions FROM jsonb_array_elements(content->'ingredients') AS original(ingredient)
        WHERE (original.ingredient->>'ingredientId')::uuid=NEW.ingredient_id;
    IF identities <> 1 OR exact_compositions <> 1 THEN
        RAISE EXCEPTION 'Recipe composition differs from original' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER recipe_composition_original BEFORE INSERT ON catalog.recipe_release_compositions
    FOR EACH ROW EXECUTE FUNCTION catalog.guard_recipe_composition_insert();

CREATE FUNCTION catalog.index_recipe_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO catalog.recipe_version_history(environment,recipe_version_id,revision,release_id)
        SELECT NEW.environment,NEW.recipe_version_id,r.revision,NEW.release_id
        FROM catalog.recipe_releases r
        WHERE r.environment=NEW.environment AND r.release_id=NEW.release_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Recipe history source is missing' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER recipe_entry_history AFTER INSERT ON catalog.recipe_release_entries
    FOR EACH ROW EXECUTE FUNCTION catalog.index_recipe_entry();

-- Only a nested source-entry trigger inserts projection rows. Exact FKs additionally bind
-- source ID and release revision; this is not protection from an owner disabling triggers.
CREATE FUNCTION catalog.guard_recipe_history_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF pg_trigger_depth() <> 2 THEN
        RAISE EXCEPTION 'Recipe history is derived from entries' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER recipe_history_source BEFORE INSERT ON catalog.recipe_version_history
    FOR EACH ROW EXECUTE FUNCTION catalog.guard_recipe_history_insert();
CREATE TRIGGER recipe_history_immutable BEFORE UPDATE OR DELETE ON catalog.recipe_version_history
    FOR EACH ROW EXECUTE FUNCTION catalog.reject_recipe_release_mutation();
CREATE TRIGGER recipe_history_no_truncate BEFORE TRUNCATE ON catalog.recipe_version_history
    FOR EACH STATEMENT EXECUTE FUNCTION catalog.reject_recipe_release_mutation();
-- V015's row-level UPDATE/DELETE guards do not fire for TRUNCATE. Preserve historical
-- source rows too, including compositions which have no incoming history foreign key.
CREATE TRIGGER recipe_releases_no_truncate BEFORE TRUNCATE ON catalog.recipe_releases
    FOR EACH STATEMENT EXECUTE FUNCTION catalog.reject_recipe_release_mutation();
CREATE TRIGGER recipe_entries_no_truncate BEFORE TRUNCATE ON catalog.recipe_release_entries
    FOR EACH STATEMENT EXECUTE FUNCTION catalog.reject_recipe_release_mutation();
CREATE TRIGGER recipe_compositions_no_truncate BEFORE TRUNCATE ON catalog.recipe_release_compositions
    FOR EACH STATEMENT EXECUTE FUNCTION catalog.reject_recipe_release_mutation();
