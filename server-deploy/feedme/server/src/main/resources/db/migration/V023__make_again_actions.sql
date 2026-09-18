-- Atomic Make Again linkage only. No positive copy policy, API, undo or projection.
CREATE TABLE memory.make_again_actions (
    environment varchar(40) NOT NULL,
    actor_kind varchar(8) NOT NULL CHECK(actor_kind='guest'),
    principal_id uuid NOT NULL, principal_scope varchar(200) NOT NULL,
    parent_operation varchar(100) NOT NULL CHECK(parent_operation IN ('saveRecipe','completeCookSession')),
    parent_key uuid NOT NULL, parent_request_hash char(64) NOT NULL CHECK(parent_request_hash ~ '^[0-9a-f]{64}$'),
    saved_recipe_id uuid NOT NULL, saved_generation bigint NOT NULL CHECK(saved_generation>0),
    saved_version bigint NOT NULL CHECK(saved_version>0),
    saved_snapshot_sha256 char(64) NOT NULL CHECK(saved_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    created_save boolean NOT NULL,
    save_operation varchar(100) NOT NULL DEFAULT 'saveRecipe' CHECK(save_operation='saveRecipe'),
    save_key uuid NOT NULL, save_request_hash char(64) NOT NULL CHECK(save_request_hash ~ '^[0-9a-f]{64}$'),
    feedback_id uuid NOT NULL, feedback_version bigint NOT NULL DEFAULT 1 CHECK(feedback_version=1),
    feedback_operation varchar(100) NOT NULL DEFAULT 'createFeedback' CHECK(feedback_operation='createFeedback'),
    feedback_key uuid NOT NULL,
    feedback_context_sha256 char(64) NOT NULL CHECK(feedback_context_sha256 ~ '^[0-9a-f]{64}$'),
    feedback_request_hash char(64) NOT NULL CHECK(feedback_request_hash ~ '^[0-9a-f]{64}$'),
    feedback_snapshot_sha256 char(64) NOT NULL CHECK(feedback_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    feedback_provenance_sha256 char(64) NOT NULL CHECK(feedback_provenance_sha256 ~ '^[0-9a-f]{64}$'),
    cook_session_id uuid NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(principal_scope,parent_operation,parent_key),
    UNIQUE(principal_scope,save_key), UNIQUE(principal_scope,feedback_key),
    CHECK(principal_scope=environment || ':guest:' || principal_id::text),
    CHECK((parent_operation='completeCookSession')=(cook_session_id IS NOT NULL)),
    CHECK(parent_operation<>'saveRecipe' OR (parent_key=save_key AND parent_request_hash=save_request_hash)),
    FOREIGN KEY(environment,actor_kind,principal_id,saved_recipe_id)
        REFERENCES memory.saved_recipes(environment,actor_kind,principal_id,id),
    FOREIGN KEY(environment,actor_kind,principal_id,save_key)
        REFERENCES memory.save_commands(environment,actor_kind,principal_id,command_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(environment,actor_kind,principal_id,feedback_id)
        REFERENCES memory.feedback(environment,actor_kind,principal_id,id),
    FOREIGN KEY(environment,actor_kind,principal_id,cook_session_id)
        REFERENCES cooking.cook_sessions(environment,actor_kind,principal_id,id),
    FOREIGN KEY(principal_scope,parent_operation,parent_key)
        REFERENCES platform.idempotency(principal_scope,operation_id,key),
    FOREIGN KEY(principal_scope,save_operation,save_key)
        REFERENCES platform.idempotency(principal_scope,operation_id,key),
    FOREIGN KEY(principal_scope,feedback_operation,feedback_key)
        REFERENCES memory.feedback_commands(principal_scope,operation_id,command_key)
);
CREATE INDEX make_again_saved_actions ON memory.make_again_actions(environment,principal_id,saved_recipe_id,saved_generation);
CREATE FUNCTION memory.protect_make_again_action() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Make Again action linkage is immutable' USING ERRCODE='23514'; END;
$$;
-- Future explicit owner erasure can delete these child links before referenced resources.
-- Normal Make Again/replay never deletes or rewrites the original action.
CREATE TRIGGER make_again_action_immutable BEFORE UPDATE ON memory.make_again_actions
    FOR EACH ROW EXECUTE FUNCTION memory.protect_make_again_action();
