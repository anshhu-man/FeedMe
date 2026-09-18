-- Separate, immutable consent/evidence for explicit same-account reconnection. This
-- migration supplies no policy, approval, eligibility, credentials or automatic replacement.
CREATE TABLE identity.device_reconnections (
    environment varchar(40) NOT NULL,
    user_id uuid NOT NULL,
    command_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    previous_device_id uuid NOT NULL,
    new_device_id uuid NOT NULL,
    previous_provider_session_id uuid NOT NULL,
    new_provider_session_id uuid NOT NULL,
    provider_issuer varchar(2048) NOT NULL,
    provider_subject uuid NOT NULL,
    installation_id_hash char(64) NOT NULL CHECK (installation_id_hash ~ '^[0-9a-f]{64}$'),
    platform varchar(8) NOT NULL CHECK (platform IN ('android','ios')),
    previous_device_version bigint NOT NULL CHECK (previous_device_version > 0 AND previous_device_version < 9223372036854775807),
    policy_revision varchar(128) NOT NULL CHECK (char_length(btrim(policy_revision)) BETWEEN 1 AND 128),
    consent_version varchar(256) NOT NULL CHECK (char_length(btrim(consent_version)) BETWEEN 1 AND 256),
    maximum_authentication_age_seconds integer NOT NULL CHECK (maximum_authentication_age_seconds BETWEEN 1 AND 900),
    provider_session_created_at timestamptz NOT NULL CHECK (isfinite(provider_session_created_at)),
    password_authenticated_at timestamptz NOT NULL CHECK (isfinite(password_authenticated_at)),
    authorized_at timestamptz NOT NULL CHECK (isfinite(authorized_at)),
    valid_until timestamptz NOT NULL CHECK (isfinite(valid_until)),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,user_id,command_key),
    UNIQUE(environment,previous_device_id),
    UNIQUE(environment,new_device_id),
    FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id),
    FOREIGN KEY(environment,previous_device_id) REFERENCES identity.device_sessions(environment,id),
    -- Evidence is inserted before revocation/registration, but all three effects must commit
    -- together. The successor must exist when this owned transaction commits.
    FOREIGN KEY(environment,new_device_id) REFERENCES identity.device_sessions(environment,id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (previous_device_id <> new_device_id AND previous_provider_session_id <> new_provider_session_id),
    CHECK (provider_session_created_at <= password_authenticated_at AND password_authenticated_at <= authorized_at AND authorized_at < valid_until),
    CHECK (valid_until <= provider_session_created_at + maximum_authentication_age_seconds * interval '1 second'
       AND valid_until <= password_authenticated_at + maximum_authentication_age_seconds * interval '1 second')
);
CREATE FUNCTION identity.keep_device_reconnection_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Device reconnection evidence is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER device_reconnection_immutable BEFORE UPDATE OR DELETE ON identity.device_reconnections
    FOR EACH ROW EXECUTE FUNCTION identity.keep_device_reconnection_immutable();
CREATE TRIGGER device_reconnection_retained BEFORE TRUNCATE ON identity.device_reconnections
    FOR EACH STATEMENT EXECUTE FUNCTION identity.keep_device_reconnection_immutable();
ALTER TABLE identity.device_reconnections ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.device_reconnections FORCE ROW LEVEL SECURITY;
REVOKE ALL ON identity.device_reconnections FROM PUBLIC;
