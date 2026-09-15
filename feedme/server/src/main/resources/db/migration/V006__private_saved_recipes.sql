-- Basic private saves/default cookbook only. No permissive identity, content, copy or feedback grant.
CREATE SCHEMA memory;
CREATE TABLE memory.library_heads (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 actor_kind varchar(10) NOT NULL CHECK(actor_kind IN ('account','guest')),
 principal_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>0),
 PRIMARY KEY(environment,actor_kind,principal_id)
);
CREATE TABLE memory.saved_recipes (
 environment varchar(40) NOT NULL, actor_kind varchar(10) NOT NULL, principal_id uuid NOT NULL,
 id uuid NOT NULL, generation bigint NOT NULL CHECK(generation>0), version bigint NOT NULL CHECK(version>0),
 recipe_version_id uuid NOT NULL, recipe_hash char(64) NOT NULL CHECK(recipe_hash ~ '^[0-9a-f]{64}$'),
 source_type varchar(16) NOT NULL CHECK(source_type IN ('ownPlan','catalog')), source_id uuid NOT NULL,
 origin_plan_id uuid NULL, content_license varchar(32) NOT NULL CHECK(content_license IN ('catalogRedistributable','privateCopyOnly')),
 snapshot jsonb NULL CHECK(jsonb_typeof(snapshot)='object' AND octet_length(snapshot::text)<=262144),
 copy_evidence jsonb NULL CHECK(jsonb_typeof(copy_evidence)='object' AND octet_length(copy_evidence::text)<=32768),
 deleted boolean NOT NULL DEFAULT false, deletion_key uuid NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,actor_kind,principal_id,id),
 UNIQUE(environment,actor_kind,principal_id,recipe_version_id,recipe_hash,generation),
 FOREIGN KEY(environment,actor_kind,principal_id) REFERENCES memory.library_heads(environment,actor_kind,principal_id),
 CHECK((source_type='ownPlan')=(origin_plan_id IS NOT NULL)),
 CHECK(source_id=coalesce(origin_plan_id,recipe_version_id)),
 CHECK((deleted AND snapshot IS NULL AND copy_evidence IS NULL AND deletion_key IS NOT NULL) OR
       (NOT deleted AND snapshot IS NOT NULL AND copy_evidence IS NOT NULL AND deletion_key IS NULL)),
 CHECK(updated_at>=created_at)
);
-- Normalize ownPlan/catalog into one appRecipe lineage; scaled material is a distinct hash.
CREATE UNIQUE INDEX saved_current_app_recipe ON memory.saved_recipes(environment,actor_kind,principal_id,recipe_version_id,recipe_hash) WHERE NOT deleted;
CREATE UNIQUE INDEX saved_current_source_recipe ON memory.saved_recipes(environment,actor_kind,principal_id,source_type,source_id,recipe_hash) WHERE NOT deleted;
CREATE INDEX saved_owner_page ON memory.saved_recipes(environment,actor_kind,principal_id,id) WHERE NOT deleted;
CREATE TABLE memory.collections (
 environment varchar(40) NOT NULL, actor_kind varchar(10) NOT NULL, principal_id uuid NOT NULL,
 id uuid NOT NULL, version bigint NOT NULL CHECK(version>0), is_default boolean NOT NULL,
 name varchar(60) NOT NULL, description varchar(300) NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,actor_kind,principal_id,id),
 FOREIGN KEY(environment,actor_kind,principal_id) REFERENCES memory.library_heads(environment,actor_kind,principal_id),
 CHECK(updated_at>=created_at)
);
CREATE UNIQUE INDEX one_default_collection ON memory.collections(environment,actor_kind,principal_id) WHERE is_default;
CREATE TABLE memory.collection_items (
 environment varchar(40) NOT NULL, actor_kind varchar(10) NOT NULL, principal_id uuid NOT NULL,
 collection_id uuid NOT NULL, saved_recipe_id uuid NOT NULL, position bigint NOT NULL CHECK(position>0),
 PRIMARY KEY(environment,actor_kind,principal_id,collection_id,saved_recipe_id),
 UNIQUE(environment,actor_kind,principal_id,collection_id,position),
 FOREIGN KEY(environment,actor_kind,principal_id,collection_id) REFERENCES memory.collections(environment,actor_kind,principal_id,id),
 FOREIGN KEY(environment,actor_kind,principal_id,saved_recipe_id) REFERENCES memory.saved_recipes(environment,actor_kind,principal_id,id)
);
-- Exact saved identity/generation and membership selected by each acknowledged Save command.
CREATE TABLE memory.save_commands (
 environment varchar(40) NOT NULL, actor_kind varchar(10) NOT NULL, principal_id uuid NOT NULL,
 command_id uuid NOT NULL, saved_recipe_id uuid NOT NULL, collection_id uuid NOT NULL, generation bigint NOT NULL CHECK(generation>0),
 PRIMARY KEY(environment,actor_kind,principal_id,command_id),
 FOREIGN KEY(environment,actor_kind,principal_id,saved_recipe_id) REFERENCES memory.saved_recipes(environment,actor_kind,principal_id,id),
 FOREIGN KEY(environment,actor_kind,principal_id,collection_id) REFERENCES memory.collections(environment,actor_kind,principal_id,id)
);
-- Content/provenance cannot be edited in place. A deletion retains only bounded identity fencing.
CREATE FUNCTION memory.protect_saved_copy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.id,NEW.generation,NEW.recipe_version_id,NEW.recipe_hash,
        NEW.source_type,NEW.source_id,NEW.origin_plan_id,NEW.content_license,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.environment,OLD.actor_kind,OLD.principal_id,OLD.id,OLD.generation,OLD.recipe_version_id,OLD.recipe_hash,
        OLD.source_type,OLD.source_id,OLD.origin_plan_id,OLD.content_license,OLD.created_at)
    OR OLD.deleted OR NOT NEW.deleted OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION 'Saved copy mutation requires exact deletion' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER immutable_saved_copy BEFORE UPDATE ON memory.saved_recipes FOR EACH ROW EXECUTE FUNCTION memory.protect_saved_copy();
