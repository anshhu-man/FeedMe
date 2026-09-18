-- Explicit private signals only. No personalization, medical inference, saved copy,
-- completion, ranking projection, worker, positive authority or accepting policy seed.
CREATE TABLE memory.feedback (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    actor_kind varchar(8) NOT NULL CHECK (actor_kind IN ('account','guest')),
    principal_id uuid NOT NULL, id uuid NOT NULL, version bigint NOT NULL CHECK (version > 0),
    cook_session_id uuid NULL,
    context_text text NULL CHECK (context_text IS NULL OR octet_length(context_text)<=2048),
    context_sha256 char(64) NULL CHECK (context_sha256 IS NULL OR context_sha256 ~ '^[0-9a-f]{64}$'),
    provenance_text text NULL CHECK (provenance_text IS NULL OR octet_length(provenance_text)<=16384),
    provenance_sha256 char(64) NULL CHECK (provenance_sha256 IS NULL OR provenance_sha256 ~ '^[0-9a-f]{64}$'),
    snapshot jsonb NULL CHECK (snapshot IS NULL OR (jsonb_typeof(snapshot)='object' AND octet_length(snapshot::text)<=262144)),
    deleted boolean NOT NULL DEFAULT false, deletion_key uuid NULL,
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    PRIMARY KEY(environment,actor_kind,principal_id,id),
    FOREIGN KEY(environment,principal_id) REFERENCES identity.principals(environment,id),
    CHECK (updated_at>=created_at),
    CHECK ((NOT deleted AND context_text IS NOT NULL AND context_sha256 IS NOT NULL AND provenance_text IS NOT NULL
        AND provenance_sha256 IS NOT NULL AND snapshot IS NOT NULL AND deletion_key IS NULL)
        OR (deleted AND cook_session_id IS NULL AND context_text IS NULL AND context_sha256 IS NULL
        AND provenance_text IS NULL AND provenance_sha256 IS NULL AND snapshot IS NULL AND deletion_key IS NOT NULL))
);
-- A deliberate edit replaces a session's current response; another POST is not an implicit
-- edit. After deletion a NEW action receives a new UUID rather than reviving a tombstone.
CREATE UNIQUE INDEX feedback_live_cook ON memory.feedback(environment,actor_kind,principal_id,cook_session_id)
    WHERE NOT deleted AND cook_session_id IS NOT NULL;
CREATE INDEX feedback_owner_live ON memory.feedback(environment,actor_kind,principal_id,id) WHERE NOT deleted;
CREATE FUNCTION memory.protect_feedback_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.id,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.environment,OLD.actor_kind,OLD.principal_id,OLD.id,OLD.created_at)
        OR OLD.deleted OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
        RAISE EXCEPTION 'Invalid feedback transition' USING ERRCODE='23514';
    END IF;
    IF NOT NEW.deleted AND ROW(NEW.cook_session_id,NEW.context_text,NEW.context_sha256,NEW.provenance_text,NEW.provenance_sha256)
        IS DISTINCT FROM ROW(OLD.cook_session_id,OLD.context_text,OLD.context_sha256,OLD.provenance_text,OLD.provenance_sha256) THEN
        RAISE EXCEPTION 'Feedback context and provenance are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER feedback_transition BEFORE UPDATE ON memory.feedback FOR EACH ROW EXECUTE FUNCTION memory.protect_feedback_transition();

-- Exact command linkage permits immediate removal of superseded/deleted response notes,
-- without storing signals here, forgetting command keys, or allowing retry resurrection.
CREATE TABLE memory.feedback_commands (
    environment varchar(40) NOT NULL, actor_kind varchar(8) NOT NULL, principal_id uuid NOT NULL,
    feedback_id uuid NOT NULL, feedback_version bigint NOT NULL CHECK (feedback_version > 0),
    principal_scope varchar(200) NOT NULL,
    operation_id varchar(100) NOT NULL CHECK (operation_id IN ('createFeedback','updateFeedback','deleteFeedback')),
    command_key uuid NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    response_sha256 char(64) NOT NULL CHECK (response_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(principal_scope,operation_id,command_key),
    UNIQUE(environment,actor_kind,principal_id,feedback_id,feedback_version),
    FOREIGN KEY(environment,actor_kind,principal_id,feedback_id)
        REFERENCES memory.feedback(environment,actor_kind,principal_id,id),
    FOREIGN KEY(principal_scope,operation_id,command_key)
        REFERENCES platform.idempotency(principal_scope,operation_id,key),
    CHECK (principal_scope=environment || ':' || actor_kind || ':' || principal_id::text),
    CHECK ((feedback_version=1 AND operation_id='createFeedback') OR
        (feedback_version>1 AND operation_id IN ('updateFeedback','deleteFeedback')))
);
CREATE FUNCTION memory.protect_feedback_command() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Feedback command linkage is immutable' USING ERRCODE='23514';
END;
$$;
-- No purge worker is enabled here. A future explicit owner-erasure transaction may delete
-- child links before their feedback/receipt/principal; normal feedback commands never do.
CREATE TRIGGER feedback_command_immutable BEFORE UPDATE ON memory.feedback_commands
    FOR EACH ROW EXECUTE FUNCTION memory.protect_feedback_command();
