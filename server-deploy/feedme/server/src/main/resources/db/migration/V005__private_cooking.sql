-- Expand-only private cooking. No provider/catalog defaults, save, timer effects or public routes.
CREATE SCHEMA cooking;
CREATE TABLE cooking.cook_sessions (
    environment varchar(40) NOT NULL,
    actor_kind varchar(10) NOT NULL CHECK (actor_kind IN ('account','guest')),
    principal_id uuid NOT NULL, id uuid NOT NULL, plan_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    status varchar(12) NOT NULL CHECK (status IN ('active','paused','completed','abandoned')),
    device_sequence bigint NOT NULL CHECK (device_sequence >= 0),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot)='object' AND octet_length(snapshot::text)<=262144),
    plan_snapshot_text text NOT NULL CHECK (octet_length(plan_snapshot_text) BETWEEN 2 AND 262144 AND jsonb_typeof(plan_snapshot_text::jsonb)='object'),
    plan_snapshot_hash char(64) NOT NULL CHECK (plan_snapshot_hash ~ '^[0-9a-f]{64}$'),
    plan_proof_hash char(64) NOT NULL CHECK (plan_proof_hash ~ '^[0-9a-f]{64}$'),
    plan_evidence_hash char(64) NOT NULL CHECK (plan_evidence_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at AND updated_at >= created_at),
    CHECK ((snapshot->>'id')::uuid=id AND (snapshot->>'planId')::uuid=plan_id
        AND (snapshot->>'version')::bigint=version AND snapshot->>'status'=status
        AND (snapshot->>'deviceSequence')::bigint=device_sequence),
    CHECK ((status='completed') = (snapshot ? 'completedAt')),
    PRIMARY KEY(environment,actor_kind,principal_id,id),
    FOREIGN KEY(environment,actor_kind,principal_id,plan_id) REFERENCES planning.plans(environment,actor_kind,principal_id,id)
);
CREATE INDEX cooking_owner_status ON cooking.cook_sessions(environment,actor_kind,principal_id,status,updated_at DESC);
CREATE INDEX cooking_owned_plan_pin ON cooking.cook_sessions(environment,actor_kind,principal_id,plan_id,expires_at);
CREATE INDEX cooking_expiry ON cooking.cook_sessions(expires_at);

CREATE TABLE cooking.device_cursors (
    environment varchar(40) NOT NULL, actor_kind varchar(10) NOT NULL, principal_id uuid NOT NULL,
    session_id uuid NOT NULL, device_identity uuid NOT NULL, device_sequence bigint NOT NULL CHECK (device_sequence >= 0),
    PRIMARY KEY(environment,actor_kind,principal_id,session_id,device_identity),
    FOREIGN KEY(environment,actor_kind,principal_id,session_id) REFERENCES cooking.cook_sessions(environment,actor_kind,principal_id,id)
);
CREATE TABLE cooking.step_events (
    environment varchar(40) NOT NULL, actor_kind varchar(10) NOT NULL, principal_id uuid NOT NULL,
    session_id uuid NOT NULL, command_id uuid NOT NULL, device_identity uuid NOT NULL,
    device_sequence bigint NOT NULL CHECK (device_sequence >= 0), session_version bigint NOT NULL CHECK (session_version > 0),
    kind varchar(16) NOT NULL CHECK (kind IN ('started','progressed','completed')),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object' AND octet_length(payload::text)<=262144),
    accepted_at timestamptz NOT NULL,
    PRIMARY KEY(environment,actor_kind,principal_id,session_id,command_id),
    UNIQUE(environment,actor_kind,principal_id,session_id,device_sequence),
    UNIQUE(environment,actor_kind,principal_id,session_id,session_version),
    FOREIGN KEY(environment,actor_kind,principal_id,session_id,device_identity)
        REFERENCES cooking.device_cursors(environment,actor_kind,principal_id,session_id,device_identity)
);
CREATE INDEX cooking_step_history ON cooking.step_events(environment,actor_kind,principal_id,session_id,accepted_at);

CREATE FUNCTION cooking.guard_session_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.id,NEW.plan_id,NEW.plan_snapshot_text,
        NEW.plan_snapshot_hash,NEW.plan_proof_hash,NEW.plan_evidence_hash,NEW.created_at,NEW.expires_at)
        IS DISTINCT FROM ROW(OLD.environment,OLD.actor_kind,OLD.principal_id,OLD.id,OLD.plan_id,OLD.plan_snapshot_text,
        OLD.plan_snapshot_hash,OLD.plan_proof_hash,OLD.plan_evidence_hash,OLD.created_at,OLD.expires_at)
        OR OLD.status IN ('completed','abandoned') OR NEW.version<>OLD.version+1
        OR NEW.device_sequence<>OLD.device_sequence+1 THEN
        RAISE EXCEPTION 'Cooking pin or transition is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_cooking_pin BEFORE UPDATE ON cooking.cook_sessions
    FOR EACH ROW EXECUTE FUNCTION cooking.guard_session_update();
CREATE FUNCTION cooking.reject_step_event_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Cooking events are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_cooking_event BEFORE UPDATE ON cooking.step_events
    FOR EACH ROW EXECUTE FUNCTION cooking.reject_step_event_update();
-- Explicit account-erasure/retention workers must delete owned events/cursors/sessions before plans.
-- No worker or tombstone purge is enabled by this migration.
