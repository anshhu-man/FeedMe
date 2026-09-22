-- Extend V030 without rewriting its immutable originals. Existing rows/inserts remain
-- explicitly password evidence. OAuth is a distinct provider observation, never a
-- password timestamp or a client-supplied claim. No runtime grant or policy is enabled.
ALTER TABLE identity.device_reconnections
    ADD COLUMN authentication_method varchar(8) NOT NULL DEFAULT 'password',
    ADD COLUMN oauth_authenticated_at timestamptz,
    ALTER COLUMN password_authenticated_at DROP NOT NULL;

-- PostgreSQL CHECK accepts NULL, so each branch must require its own timestamp and
-- forbid the other one. The older password checks alone cannot authorize OAuth rows.
ALTER TABLE identity.device_reconnections
    ADD CONSTRAINT device_reconnection_typed_authentication CHECK (
        (authentication_method = 'password'
            AND password_authenticated_at IS NOT NULL AND oauth_authenticated_at IS NULL
            AND isfinite(password_authenticated_at)
            AND provider_session_created_at <= password_authenticated_at
            AND password_authenticated_at <= authorized_at AND authorized_at < valid_until
            AND valid_until <= provider_session_created_at + maximum_authentication_age_seconds * interval '1 second'
            AND valid_until <= password_authenticated_at + maximum_authentication_age_seconds * interval '1 second')
        OR
        (authentication_method = 'oauth'
            AND oauth_authenticated_at IS NOT NULL AND password_authenticated_at IS NULL
            AND isfinite(oauth_authenticated_at)
            AND provider_session_created_at <= oauth_authenticated_at
            AND oauth_authenticated_at <= authorized_at AND authorized_at < valid_until
            AND valid_until <= provider_session_created_at + maximum_authentication_age_seconds * interval '1 second'
            AND valid_until <= oauth_authenticated_at + maximum_authentication_age_seconds * interval '1 second')
    );

-- Existing immutable UPDATE/DELETE/TRUNCATE triggers, forced RLS, owner/command keys,
-- unique predecessor/successor and deferred successor FK remain unchanged.
