-- Structural guest persistence only: no seed, issuer, route, capability grant or replay authority.
-- Token-bearing responses belong only in dedicated encrypted receipts, never platform.idempotency.
-- Time checks describe stored intervals, not current eligibility; expired/revoked/merged rows remain.

CREATE FUNCTION identity.valid_guest_capabilities(value jsonb) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
BEGIN
    IF jsonb_typeof(value) <> 'array' THEN
        RETURN false;
    END IF;
    IF jsonb_array_length(value) > 32 THEN
        RETURN false;
    END IF;
    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(value) AS item
        WHERE jsonb_typeof(item) <> 'string' OR char_length(item #>> '{}') NOT BETWEEN 1 AND 128
    ) THEN
        RETURN false;
    END IF;
    RETURN (SELECT count(*) = count(DISTINCT item) FROM jsonb_array_elements(value) AS item);
END;
$$;

CREATE TABLE identity.guest_sessions (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    id uuid NOT NULL,
    installation_sha256 char(64) NOT NULL CHECK (installation_sha256 ~ '^[0-9a-f]{64}$'),
    token_sha256 char(64) NOT NULL CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL CHECK (isfinite(created_at)),
    last_seen_at timestamptz NOT NULL CHECK (isfinite(last_seen_at)),
    inactivity_expires_at timestamptz NOT NULL CHECK (isfinite(inactivity_expires_at)),
    absolute_expires_at timestamptz NOT NULL CHECK (isfinite(absolute_expires_at)),
    revoked_at timestamptz NULL CHECK (revoked_at IS NULL OR isfinite(revoked_at)),
    merged_to_user_id uuid NULL,
    policy_revision text NOT NULL CHECK (char_length(policy_revision) BETWEEN 1 AND 256 AND btrim(policy_revision) <> ''),
    capabilities jsonb NOT NULL CHECK (identity.valid_guest_capabilities(capabilities)),
    daily_plan_limit integer NOT NULL CHECK (daily_plan_limit BETWEEN 1 AND 10000),
    PRIMARY KEY(environment,id),
    UNIQUE(environment,token_sha256),
    FOREIGN KEY(environment,merged_to_user_id) REFERENCES identity.users(environment,id),
    CHECK (created_at <= last_seen_at AND last_seen_at < absolute_expires_at),
    CHECK (created_at < inactivity_expires_at AND last_seen_at <= inactivity_expires_at AND inactivity_expires_at <= absolute_expires_at)
);

-- Existing user uniqueness/FK remain intact. NULL user_id never satisfies an account join.
ALTER TABLE identity.principals ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE identity.principals DROP CONSTRAINT principals_kind_check;
ALTER TABLE identity.principals ADD COLUMN guest_session_id uuid NULL;
ALTER TABLE identity.principals ADD CONSTRAINT principals_kind_check CHECK (kind IN ('user','guest'));
ALTER TABLE identity.principals ADD CONSTRAINT principals_subtype_check CHECK (
    (kind='user' AND user_id IS NOT NULL AND guest_session_id IS NULL) OR
    (kind='guest' AND user_id IS NULL AND guest_session_id IS NOT NULL)
);
ALTER TABLE identity.principals ADD CONSTRAINT principals_guest_session_key UNIQUE(environment,guest_session_id);
ALTER TABLE identity.principals ADD CONSTRAINT principals_guest_session_fkey
    FOREIGN KEY(environment,guest_session_id) REFERENCES identity.guest_sessions(environment,id);

CREATE TABLE identity.guest_bootstrap_receipts (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    command_key uuid NOT NULL,
    installation_sha256 char(64) NOT NULL CHECK (installation_sha256 ~ '^[0-9a-f]{64}$'),
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    guest_session_id uuid NOT NULL,
    key_id varchar(32) NOT NULL CHECK (key_id ~ '^[a-z0-9_-]{1,32}$'),
    nonce bytea NOT NULL CHECK (octet_length(nonce)=12),
    ciphertext bytea NOT NULL CHECK (octet_length(ciphertext) BETWEEN 17 AND 8208),
    created_at timestamptz NOT NULL CHECK (isfinite(created_at)),
    expires_at timestamptz NOT NULL CHECK (isfinite(expires_at) AND created_at < expires_at),
    PRIMARY KEY(environment,command_key),
    UNIQUE(environment,guest_session_id),
    FOREIGN KEY(environment,guest_session_id) REFERENCES identity.guest_sessions(environment,id)
);

-- Admission policy, locking, window selection and atomic increments belong to the issuer.
CREATE TABLE identity.guest_issuance_windows (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    installation_sha256 char(64) NOT NULL CHECK (installation_sha256 ~ '^[0-9a-f]{64}$'),
    window_date date NOT NULL CHECK (isfinite(window_date)),
    issued_count integer NOT NULL CHECK (issued_count BETWEEN 0 AND 10000),
    PRIMARY KEY(environment,installation_sha256,window_date)
);
