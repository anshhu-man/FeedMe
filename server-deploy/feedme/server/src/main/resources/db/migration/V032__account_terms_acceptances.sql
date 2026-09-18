-- Exact account/device Terms acceptance evidence, separate from bootstrap/reconnection.
-- No notice is published, no consent is inferred and no eligibility/device/profile changes
-- are authorized by this migration. Prior schema and audit rows remain unchanged.
CREATE TABLE identity.account_terms_acceptances (
    environment varchar(40) NOT NULL,
    user_id uuid NOT NULL,
    command_key uuid NOT NULL,
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    device_session_id uuid NOT NULL,
    provider_issuer varchar(2048) NOT NULL CHECK (provider_issuer <> ''),
    provider_subject uuid NOT NULL,
    provider_session_id uuid NOT NULL,
    terms_version varchar(256) NOT NULL CHECK (char_length(btrim(terms_version)) BETWEEN 1 AND 256),
    notice_sha256 char(64) NOT NULL CHECK (notice_sha256 ~ '^[0-9a-f]{64}$'),
    terms_url varchar(2048) NOT NULL CHECK (terms_url LIKE 'https://%'),
    privacy_url varchar(2048) NOT NULL CHECK (privacy_url LIKE 'https://%'),
    accepted_at timestamptz NOT NULL CHECK (isfinite(accepted_at)),
    PRIMARY KEY(environment,user_id,command_key),
    FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id),
    FOREIGN KEY(environment,device_session_id) REFERENCES identity.device_sessions(environment,id),
    CHECK (user_id <> device_session_id AND device_session_id <> provider_session_id)
);
CREATE FUNCTION identity.keep_account_terms_acceptance_immutable() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    RAISE EXCEPTION 'Account Terms acceptance evidence is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER account_terms_acceptance_immutable BEFORE UPDATE OR DELETE ON identity.account_terms_acceptances
    FOR EACH ROW EXECUTE FUNCTION identity.keep_account_terms_acceptance_immutable();
CREATE TRIGGER account_terms_acceptance_retained BEFORE TRUNCATE ON identity.account_terms_acceptances
    FOR EACH STATEMENT EXECUTE FUNCTION identity.keep_account_terms_acceptance_immutable();
ALTER TABLE identity.account_terms_acceptances ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.account_terms_acceptances FORCE ROW LEVEL SECURITY;
REVOKE ALL ON identity.account_terms_acceptances FROM PUBLIC;
REVOKE ALL ON FUNCTION identity.keep_account_terms_acceptance_immutable() FROM PUBLIC;
