-- Explicit private optional-prompt provenance. No legacy inference or readiness grant.
CREATE TABLE profile.onboarding_decisions (
    environment varchar(40) NOT NULL,
    user_id uuid NOT NULL,
    command_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    device_session_id uuid NOT NULL,
    prompt varchar(16) NOT NULL CHECK (prompt IN ('preferences','equipment')),
    disposition varchar(8) NOT NULL CHECK (disposition IN ('answered','skipped')),
    next_step varchar(16) NOT NULL,
    profile_version_before bigint NOT NULL CHECK (profile_version_before > 0 AND profile_version_before < 9223372036854775807),
    profile_version_after bigint NOT NULL CHECK (profile_version_after = profile_version_before + 1),
    preference_id uuid NULL,
    preference_version bigint NULL,
    preference_sha256 char(64) NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (environment,user_id,command_key),
    UNIQUE (environment,user_id,profile_version_after),
    FOREIGN KEY (environment,user_id) REFERENCES identity.users(environment,id),
    FOREIGN KEY (environment,device_session_id) REFERENCES identity.device_sessions(environment,id),
    CHECK ((prompt='preferences' AND next_step='equipment') OR (prompt='equipment' AND next_step='ready')),
    CHECK ((disposition='skipped' AND preference_id IS NULL AND preference_version IS NULL AND preference_sha256 IS NULL) OR
           (disposition='answered' AND preference_id IS NOT NULL AND preference_version IS NOT NULL AND preference_version > 0 AND
            preference_sha256 IS NOT NULL AND preference_sha256 ~ '^[0-9a-f]{64}$'))
);

CREATE FUNCTION profile.keep_onboarding_decision_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Onboarding decision is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER onboarding_decision_immutable BEFORE UPDATE OR DELETE ON profile.onboarding_decisions
    FOR EACH ROW EXECUTE FUNCTION profile.keep_onboarding_decision_immutable();
