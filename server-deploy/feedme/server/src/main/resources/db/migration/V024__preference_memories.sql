-- Private explicit-opinion projection and controls. No ranking activation, inferred
-- allergy/exclusion, accepting source authority, worker identity or policy seed.
CREATE TABLE memory.memory_heads (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    actor_kind varchar(8) NOT NULL CHECK (actor_kind IN ('account','guest')),
    principal_id uuid NOT NULL,
    source_revision bigint NOT NULL DEFAULT 0 CHECK (source_revision>=0),
    projected_revision bigint NOT NULL DEFAULT 0 CHECK (projected_revision>=0 AND projected_revision<=source_revision),
    memory_revision bigint NOT NULL DEFAULT 0 CHECK (memory_revision>=0),
    PRIMARY KEY(environment,actor_kind,principal_id),
    FOREIGN KEY(environment,principal_id) REFERENCES identity.principals(environment,id)
);
CREATE TABLE memory.memory_feedback_state (
    environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL, principal_id uuid NOT NULL,
    feedback_id uuid NOT NULL, feedback_version bigint NOT NULL CHECK (feedback_version>0),
    dirty boolean NOT NULL DEFAULT true,
    taste_epoch bigint NOT NULL CHECK (taste_epoch>=0),
    effort_epoch bigint NOT NULL CHECK (effort_epoch>=0),
    make_again_epoch bigint NOT NULL CHECK (make_again_epoch>=0),
    PRIMARY KEY(environment,actor_kind,principal_id,feedback_id),
    FOREIGN KEY(environment,actor_kind,principal_id) REFERENCES memory.memory_heads(environment,actor_kind,principal_id),
    FOREIGN KEY(environment,actor_kind,principal_id,feedback_id) REFERENCES memory.feedback(environment,actor_kind,principal_id,id)
);
CREATE INDEX memory_feedback_dirty ON memory.memory_feedback_state(environment,actor_kind,principal_id,feedback_id) WHERE dirty;

-- Source refresh and materialization are separate bounded phases. Retain every affected
-- semantic group across commits; do not erase an explicit override on a transient empty
-- prefix while later dirty feedback may still contribute to the same group.
CREATE TABLE memory.memory_dirty_groups (
    environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL, principal_id uuid NOT NULL,
    kind varchar(8) NOT NULL CHECK (kind IN ('taste','effort','repeat')),
    semantic_key char(64) NOT NULL CHECK (semantic_key ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(environment,actor_kind,principal_id,kind,semantic_key),
    FOREIGN KEY(environment,actor_kind,principal_id) REFERENCES memory.memory_heads(environment,actor_kind,principal_id)
);

CREATE FUNCTION memory.valid_memory_context(value jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_typeof(value)='object' AND NOT EXISTS (
        SELECT 1 FROM jsonb_each(CASE WHEN jsonb_typeof(value)='object' THEN value ELSE '{}'::jsonb END) item
        WHERE jsonb_typeof(item.value)<>'string' OR NOT CASE item.key
            WHEN 'recipeVersionId' THEN item.value #>> '{}' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            WHEN 'ingredientId' THEN item.value #>> '{}' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            WHEN 'tasteTag' THEN item.value #>> '{}' IN ('crunch','fresh','creamy','heat')
            WHEN 'effortAspect' THEN item.value #>> '{}' IN ('chopping','activeCooking','cleanup')
            ELSE false END
    );
$$;

CREATE TABLE memory.memories (
    environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL, principal_id uuid NOT NULL,
    id uuid NOT NULL, generation bigint NOT NULL CHECK (generation>0), version bigint NOT NULL CHECK (version>0),
    kind varchar(8) NOT NULL CHECK (kind IN ('taste','effort','repeat')),
    semantic_key char(64) NOT NULL CHECK (semantic_key ~ '^[0-9a-f]{64}$'),
    original_context jsonb NOT NULL CHECK (memory.valid_memory_context(original_context)),
    snapshot jsonb NULL CHECK (snapshot IS NULL OR (jsonb_typeof(snapshot)='object' AND octet_length(snapshot::text)<=262144)),
    user_override jsonb NULL CHECK (user_override IS NULL OR (jsonb_typeof(user_override)='object' AND octet_length(user_override::text)<=4096)),
    deleted boolean NOT NULL DEFAULT false, deletion_key uuid NULL,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    PRIMARY KEY(environment,actor_kind,principal_id,id),
    UNIQUE(environment,actor_kind,principal_id,kind,semantic_key,generation),
    FOREIGN KEY(environment,actor_kind,principal_id) REFERENCES memory.memory_heads(environment,actor_kind,principal_id),
    CHECK (updated_at>=created_at),
    CHECK ((NOT deleted AND snapshot IS NOT NULL AND deletion_key IS NULL) OR
        (deleted AND snapshot IS NULL AND user_override IS NULL))
);
CREATE UNIQUE INDEX memories_live_semantic ON memory.memories(environment,actor_kind,principal_id,kind,semantic_key) WHERE NOT deleted;
CREATE INDEX memories_owner_live ON memory.memories(environment,actor_kind,principal_id,id) WHERE NOT deleted;

CREATE TABLE memory.memory_sources (
    environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL, principal_id uuid NOT NULL,
    semantic_key char(64) NOT NULL CHECK (semantic_key ~ '^[0-9a-f]{64}$'),
    feedback_id uuid NOT NULL, signal_key varchar(12) NOT NULL CHECK (signal_key IN ('taste','effort','makeAgain')),
    signal_epoch bigint NOT NULL CHECK (signal_epoch>0),
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    kind varchar(8) NOT NULL CHECK (kind IN ('taste','effort','repeat')),
    value varchar(12) NOT NULL CHECK (value IN ('prefer','neutral','show_less')),
    context jsonb NOT NULL CHECK (memory.valid_memory_context(context)),
    source_version bigint NOT NULL CHECK (source_version>0),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(environment,actor_kind,principal_id,semantic_key,feedback_id,signal_key),
    UNIQUE(environment,actor_kind,principal_id,feedback_id,signal_key),
    FOREIGN KEY(environment,actor_kind,principal_id,feedback_id) REFERENCES memory.feedback(environment,actor_kind,principal_id,id),
    FOREIGN KEY(environment,actor_kind,principal_id) REFERENCES memory.memory_heads(environment,actor_kind,principal_id),
    CHECK ((signal_key='taste' AND kind='taste') OR (signal_key='effort' AND kind='effort') OR (signal_key='makeAgain' AND kind='repeat'))
);
CREATE INDEX memory_sources_group ON memory.memory_sources(environment,actor_kind,principal_id,semantic_key,signal_epoch,feedback_id);

-- Suppressions outlive materialized rows/rule revisions. They contain no note or response.
CREATE TABLE memory.memory_suppressions (
    environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL, principal_id uuid NOT NULL,
    semantic_key char(64) NOT NULL CHECK (semantic_key ~ '^[0-9a-f]{64}$'),
    feedback_id uuid NOT NULL, signal_key varchar(12) NOT NULL CHECK (signal_key IN ('taste','effort','makeAgain')),
    signal_epoch bigint NOT NULL CHECK (signal_epoch>0), fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY(environment,actor_kind,principal_id,semantic_key,feedback_id,signal_key,signal_epoch),
    FOREIGN KEY(environment,actor_kind,principal_id,feedback_id) REFERENCES memory.feedback(environment,actor_kind,principal_id,id)
);
CREATE TABLE memory.memory_commands (
    environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL, principal_id uuid NOT NULL,
    memory_id uuid NOT NULL, memory_version bigint NOT NULL CHECK (memory_version>0),
    principal_scope varchar(200) NOT NULL,
    operation_id varchar(100) NOT NULL CHECK (operation_id IN ('updateMemory','deleteMemory')),
    command_key uuid NOT NULL, request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    response_sha256 char(64) NOT NULL CHECK (response_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(principal_scope,operation_id,command_key),
    UNIQUE(environment,actor_kind,principal_id,memory_id,memory_version),
    FOREIGN KEY(environment,actor_kind,principal_id,memory_id) REFERENCES memory.memories(environment,actor_kind,principal_id,id),
    FOREIGN KEY(principal_scope,operation_id,command_key) REFERENCES platform.idempotency(principal_scope,operation_id,key),
    CHECK (principal_scope=environment || ':' || actor_kind || ':' || principal_id::text)
);
CREATE TABLE memory.memory_projection_events (
    environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL, principal_id uuid NOT NULL,
    event_id uuid NOT NULL, feedback_id uuid NOT NULL, source_version bigint NOT NULL CHECK (source_version>0),
    rule_version varchar(128) NOT NULL CHECK (rule_version<>''), processed_at timestamptz NOT NULL,
    PRIMARY KEY(environment,actor_kind,principal_id,rule_version,event_id),
    FOREIGN KEY(event_id) REFERENCES platform.outbox(event_id),
    FOREIGN KEY(environment,actor_kind,principal_id,feedback_id) REFERENCES memory.feedback(environment,actor_kind,principal_id,id)
);

CREATE FUNCTION memory.protect_memory_head() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.environment,NEW.actor_kind,NEW.principal_id) IS DISTINCT FROM ROW(OLD.environment,OLD.actor_kind,OLD.principal_id)
        OR NEW.source_revision<OLD.source_revision OR NEW.projected_revision<OLD.projected_revision
        OR NEW.memory_revision<OLD.memory_revision
        OR (NEW.source_revision<>OLD.source_revision AND
            (OLD.source_revision=9223372036854775807 OR NEW.source_revision<>OLD.source_revision+1 OR
             NEW.projected_revision<>OLD.projected_revision OR NEW.memory_revision<>OLD.memory_revision)) THEN
        RAISE EXCEPTION 'Invalid memory head transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER memory_head_transition BEFORE UPDATE ON memory.memory_heads FOR EACH ROW EXECUTE FUNCTION memory.protect_memory_head();

CREATE FUNCTION memory.protect_memory_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.feedback_id) IS DISTINCT FROM
        ROW(OLD.environment,OLD.actor_kind,OLD.principal_id,OLD.feedback_id)
        OR NEW.feedback_version<OLD.feedback_version OR NEW.taste_epoch<OLD.taste_epoch OR
        NEW.effort_epoch<OLD.effort_epoch OR NEW.make_again_epoch<OLD.make_again_epoch
        OR (NEW.feedback_version=OLD.feedback_version AND ROW(NEW.taste_epoch,NEW.effort_epoch,NEW.make_again_epoch)
            IS DISTINCT FROM ROW(OLD.taste_epoch,OLD.effort_epoch,OLD.make_again_epoch))
        OR (NEW.feedback_version<>OLD.feedback_version AND
            (OLD.feedback_version=9223372036854775807 OR NEW.feedback_version<>OLD.feedback_version+1 OR NOT NEW.dirty)) THEN
        RAISE EXCEPTION 'Invalid memory source transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER memory_state_transition BEFORE UPDATE ON memory.memory_feedback_state FOR EACH ROW EXECUTE FUNCTION memory.protect_memory_state();

CREATE FUNCTION memory.mark_feedback_memory_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE revision bigint; old_taste jsonb; old_effort jsonb; old_repeat jsonb;
BEGIN
    -- Actual guest/account owners lock the principal before source/head work. This write is
    -- in the same feedback transaction: source revision invalidates ranking before ACK.
    INSERT INTO memory.memory_heads(environment,actor_kind,principal_id,source_revision)
        VALUES(NEW.environment,NEW.actor_kind,NEW.principal_id,1)
        ON CONFLICT(environment,actor_kind,principal_id) DO UPDATE SET source_revision=memory.memory_heads.source_revision+1
        RETURNING source_revision INTO revision;
    IF TG_OP='INSERT' THEN
        INSERT INTO memory.memory_feedback_state(environment,actor_kind,principal_id,feedback_id,feedback_version,dirty,
            taste_epoch,effort_epoch,make_again_epoch)
        VALUES(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.id,NEW.version,true,
            CASE WHEN NEW.snapshot ? 'taste' THEN revision ELSE 0 END,
            CASE WHEN NEW.snapshot ? 'effort' THEN revision ELSE 0 END,
            CASE WHEN NEW.snapshot ? 'makeAgain' THEN revision ELSE 0 END);
    ELSE
        old_taste:=OLD.snapshot->'taste'; old_effort:=OLD.snapshot->'effort'; old_repeat:=OLD.snapshot->'makeAgain';
        UPDATE memory.memory_feedback_state SET feedback_version=NEW.version,dirty=true,
            taste_epoch=CASE WHEN old_taste IS DISTINCT FROM NEW.snapshot->'taste' THEN revision ELSE taste_epoch END,
            effort_epoch=CASE WHEN old_effort IS DISTINCT FROM NEW.snapshot->'effort' THEN revision ELSE effort_epoch END,
            make_again_epoch=CASE WHEN old_repeat IS DISTINCT FROM NEW.snapshot->'makeAgain' THEN revision ELSE make_again_epoch END
            WHERE environment=NEW.environment AND actor_kind=NEW.actor_kind AND principal_id=NEW.principal_id AND feedback_id=NEW.id;
        IF NOT FOUND THEN RAISE EXCEPTION 'Missing memory source state' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Earlier compacted receipts cannot reconstruct true pre-migration signal-change order.
-- Initialization is explicitly deterministic created_at/id order, not a claim about history.
-- No personalization existed before this format, and no prior suppressions are discarded.
DO $$
DECLARE source record; revision bigint;
BEGIN
    FOR source IN SELECT * FROM memory.feedback ORDER BY environment,actor_kind,principal_id,created_at,id LOOP
        INSERT INTO memory.memory_heads(environment,actor_kind,principal_id,source_revision)
            VALUES(source.environment,source.actor_kind,source.principal_id,1)
            ON CONFLICT(environment,actor_kind,principal_id) DO UPDATE SET source_revision=memory.memory_heads.source_revision+1
            RETURNING source_revision INTO revision;
        INSERT INTO memory.memory_feedback_state(environment,actor_kind,principal_id,feedback_id,feedback_version,dirty,
            taste_epoch,effort_epoch,make_again_epoch)
        VALUES(source.environment,source.actor_kind,source.principal_id,source.id,source.version,true,
            CASE WHEN source.snapshot ? 'taste' THEN revision ELSE 0 END,
            CASE WHEN source.snapshot ? 'effort' THEN revision ELSE 0 END,
            CASE WHEN source.snapshot ? 'makeAgain' THEN revision ELSE 0 END);
    END LOOP;
END;
$$;
CREATE TRIGGER feedback_memory_dirty AFTER INSERT OR UPDATE ON memory.feedback FOR EACH ROW EXECUTE FUNCTION memory.mark_feedback_memory_dirty();

CREATE FUNCTION memory.protect_memory_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.id,NEW.generation,NEW.kind,NEW.semantic_key,NEW.original_context,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.environment,OLD.actor_kind,OLD.principal_id,OLD.id,OLD.generation,OLD.kind,OLD.semantic_key,OLD.original_context,OLD.created_at)
        OR OLD.deleted OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
        RAISE EXCEPTION 'Invalid memory transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER memory_transition BEFORE UPDATE ON memory.memories FOR EACH ROW EXECUTE FUNCTION memory.protect_memory_transition();
CREATE FUNCTION memory.protect_memory_link() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Memory linkage is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER memory_command_immutable BEFORE UPDATE ON memory.memory_commands FOR EACH ROW EXECUTE FUNCTION memory.protect_memory_link();
CREATE TRIGGER memory_suppression_immutable BEFORE UPDATE ON memory.memory_suppressions FOR EACH ROW EXECUTE FUNCTION memory.protect_memory_link();
CREATE TRIGGER memory_projection_event_immutable BEFORE UPDATE ON memory.memory_projection_events FOR EACH ROW EXECUTE FUNCTION memory.protect_memory_link();
CREATE TRIGGER memory_dirty_group_immutable BEFORE UPDATE ON memory.memory_dirty_groups FOR EACH ROW EXECUTE FUNCTION memory.protect_memory_link();
-- No erasure worker is introduced. Explicit future owner erasure deletes command/source/
-- suppression/projection/dirty-group links before memories/feedback/head, then parent principal/receipts.
