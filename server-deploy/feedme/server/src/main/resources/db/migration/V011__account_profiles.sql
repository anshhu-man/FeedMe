-- Expand-only account/bootstrap persistence. No auth/provider route is enabled by migration.
-- Prior migration bytes/rows remain unchanged. Rollback retains this schema and its records.
CREATE SCHEMA identity;
-- profile schema already exists from V004; add only this new aggregate table.

CREATE TABLE identity.users (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    id uuid NOT NULL,
    provider_issuer varchar(2048) NOT NULL CHECK (provider_issuer <> ''),
    provider_subject uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('active','suspended','deleting','deleted')),
    eligibility_state varchar(12) NOT NULL CHECK (eligibility_state IN ('pending','eligible','ineligible')),
    eligibility_policy_version varchar(256) NOT NULL CHECK (eligibility_policy_version <> ''),
    terms_version varchar(256) NULL, terms_accepted_at timestamptz NULL,
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,id), UNIQUE(environment,provider_issuer,provider_subject),
    CHECK ((terms_version IS NULL) = (terms_accepted_at IS NULL))
);
CREATE TABLE identity.principals (
    environment varchar(40) NOT NULL, id uuid NOT NULL, user_id uuid NOT NULL,
    kind varchar(8) NOT NULL CHECK (kind='user'), status varchar(16) NOT NULL CHECK (status IN ('active','suspended','deleting','deleted')),
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,id), UNIQUE(environment,user_id),
    FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id)
);
CREATE TABLE profile.profiles (
    environment varchar(40) NOT NULL, user_id uuid NOT NULL,
    display_name varchar(50) NULL CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 50),
    normalized_handle varchar(24) NULL CHECK (normalized_handle IS NULL OR normalized_handle ~ '^[a-z0-9_]{3,24}$'),
    bio varchar(160) NULL, avatar_media_id uuid NULL,
    onboarding_step varchar(16) NOT NULL CHECK (onboarding_step IN ('profile','preferences','equipment','ready')),
    live boolean NOT NULL, version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,user_id), FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id),
    CHECK ((display_name IS NULL) = (normalized_handle IS NULL)),
    CHECK (onboarding_step='profile' OR (display_name IS NOT NULL AND normalized_handle IS NOT NULL))
);
CREATE UNIQUE INDEX profile_live_handle ON profile.profiles(environment,normalized_handle) WHERE live AND normalized_handle IS NOT NULL;

-- Eligibility is represented in canonical Profile but owned by authoritative account policy.
-- The account UPDATE already owns its row lock; take the profile lock only afterwards. A
-- changed representation must never keep the old ETag, even for direct policy SQL writers.
CREATE FUNCTION identity.advance_profile_for_eligibility() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE profile.profiles SET version=version+1,updated_at=clock_timestamp()
    WHERE environment=NEW.environment AND user_id=NEW.id AND live AND version<9223372036854775807;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account profile version cannot advance' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_eligibility_profile_version AFTER UPDATE OF eligibility_state ON identity.users
    FOR EACH ROW WHEN (OLD.eligibility_state IS DISTINCT FROM NEW.eligibility_state)
    EXECUTE FUNCTION identity.advance_profile_for_eligibility();

CREATE TABLE identity.device_sessions (
    environment varchar(40) NOT NULL, id uuid NOT NULL, user_id uuid NOT NULL,
    installation_id_hash char(64) NOT NULL CHECK (installation_id_hash ~ '^[0-9a-f]{64}$'),
    provider_session_id uuid NOT NULL,
    platform varchar(8) NOT NULL CHECK (platform IN ('android','ios')),
    device_label varchar(20) NOT NULL CHECK ((platform='android' AND device_label='Android device') OR (platform='ios' AND device_label='iOS device')),
    app_version text NOT NULL CHECK (octet_length(app_version) <= 16384),
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(), revoked_at timestamptz NULL,
    PRIMARY KEY(environment,id), FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id)
);
CREATE UNIQUE INDEX device_active_installation ON identity.device_sessions(environment,user_id,installation_id_hash) WHERE revoked_at IS NULL;
CREATE INDEX device_provider_session ON identity.device_sessions(environment,user_id,provider_session_id) WHERE revoked_at IS NULL;

CREATE TABLE identity.bootstrap_consents (
    environment varchar(40) NOT NULL, user_id uuid NOT NULL, command_key uuid NOT NULL,
    submitted_terms_version text NULL CHECK (submitted_terms_version IS NULL OR octet_length(submitted_terms_version)<=16384),
    accepted_terms_version varchar(256) NULL,
    eligibility_declaration varchar(16) NULL CHECK (eligibility_declaration IS NULL OR eligibility_declaration IN ('adultPilot','notProvided')),
    eligibility_state varchar(12) NOT NULL CHECK (eligibility_state IN ('pending','eligible','ineligible')),
    eligibility_policy_version varchar(256) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,user_id,command_key), FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id),
    CHECK (accepted_terms_version IS NULL OR accepted_terms_version=submitted_terms_version)
);
