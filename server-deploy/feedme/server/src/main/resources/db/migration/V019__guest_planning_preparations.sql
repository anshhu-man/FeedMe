-- Inert guest-only preparation storage. This does not issue a Plan or enable an endpoint.
-- Original command mappings are NOT canonical HTTP success receipts. A later checked
-- reader/materializer must consume the SAME manifest/key, never create a replacement.
-- No deletion is exposed: guarded whole-guest erasure remains a release prerequisite.
CREATE TABLE identity.guest_planning_policies (
    environment varchar(40) NOT NULL,
    guest_session_id uuid NOT NULL,
    policy_sha256 varchar(64) NOT NULL CHECK (policy_sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(environment,guest_session_id),
    UNIQUE(environment,guest_session_id,policy_sha256),
    FOREIGN KEY(environment,guest_session_id) REFERENCES identity.guest_sessions(environment,id)
);
CREATE TABLE identity.guest_plan_windows (
    environment varchar(40) NOT NULL,
    guest_session_id uuid NOT NULL,
    window_date date NOT NULL CHECK (isfinite(window_date)),
    policy_sha256 varchar(64) NOT NULL,
    used_count integer NOT NULL CHECK (used_count BETWEEN 0 AND 10000),
    PRIMARY KEY(environment,guest_session_id,window_date),
    FOREIGN KEY(environment,guest_session_id,policy_sha256)
        REFERENCES identity.guest_planning_policies(environment,guest_session_id,policy_sha256)
);
CREATE TABLE planning.guest_preparations (
    environment varchar(40) NOT NULL,
    actor_kind varchar(10) NOT NULL CHECK (actor_kind='guest'),
    principal_id uuid NOT NULL,
    command_key uuid NOT NULL,
    request_sha256 varchar(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    guest_session_id uuid NOT NULL,
    policy_sha256 varchar(64) NOT NULL,
    manifest_id uuid NOT NULL,
    window_date date NOT NULL,
    PRIMARY KEY(environment,principal_id,command_key),
    UNIQUE(environment,actor_kind,principal_id,manifest_id),
    FOREIGN KEY(environment,principal_id) REFERENCES identity.principals(environment,id),
    FOREIGN KEY(environment,guest_session_id,policy_sha256)
        REFERENCES identity.guest_planning_policies(environment,guest_session_id,policy_sha256),
    FOREIGN KEY(environment,guest_session_id,window_date)
        REFERENCES identity.guest_plan_windows(environment,guest_session_id,window_date),
    FOREIGN KEY(environment,actor_kind,principal_id,manifest_id)
        REFERENCES planning.manifest_seals(environment,actor_kind,principal_id,manifest_id)
);
CREATE FUNCTION planning.guard_guest_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM identity.principals WHERE environment=NEW.environment
        AND id=NEW.principal_id AND kind='guest' AND guest_session_id=NEW.guest_session_id) THEN
        RAISE EXCEPTION 'Guest preparation owner unavailable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guest_preparation_owner BEFORE INSERT ON planning.guest_preparations
    FOR EACH ROW EXECUTE FUNCTION planning.guard_guest_preparation();
-- The session owner's last authority callback can wait AFTER the writer returns. A
-- deferred commit check closes that gap without accepting a caller currentness callback.
-- This writer creates millisecond-exact deadline strings; checks are conservative at commit.
CREATE FUNCTION planning.require_live_guest_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE header jsonb; at_time timestamptz;
BEGIN
    SELECT header_text::jsonb INTO header FROM planning.manifest_headers
        WHERE environment=NEW.environment AND actor_kind='guest'
            AND principal_id=NEW.principal_id AND manifest_id=NEW.manifest_id;
    at_time := clock_timestamp();
    IF NOT FOUND OR (header->>'expiresAt')::timestamptz <= at_time
        OR (header->>'cursorExpiresAt')::timestamptz <= at_time THEN
        RAISE EXCEPTION 'Guest preparation expired before commit' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER guest_preparation_live_at_commit AFTER INSERT ON planning.guest_preparations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION planning.require_live_guest_preparation();
CREATE TRIGGER guest_preparation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON planning.guest_preparations
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_mutation();
CREATE TRIGGER guest_planning_policy_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON identity.guest_planning_policies
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_mutation();
CREATE FUNCTION identity.guard_guest_plan_window() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.environment IS DISTINCT FROM OLD.environment OR NEW.guest_session_id IS DISTINCT FROM OLD.guest_session_id
        OR NEW.window_date IS DISTINCT FROM OLD.window_date OR NEW.policy_sha256 IS DISTINCT FROM OLD.policy_sha256
        OR NEW.used_count <> OLD.used_count + 1 THEN
        RAISE EXCEPTION 'Guest planning allowance unavailable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guest_plan_window_monotonic BEFORE UPDATE ON identity.guest_plan_windows
    FOR EACH ROW EXECUTE FUNCTION identity.guard_guest_plan_window();
CREATE TRIGGER guest_plan_window_retained BEFORE DELETE OR TRUNCATE ON identity.guest_plan_windows
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_mutation();
