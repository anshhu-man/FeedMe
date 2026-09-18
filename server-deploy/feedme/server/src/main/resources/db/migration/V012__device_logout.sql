-- Expand-only binding for canonical logoutSession's empty success receipt.
-- Keep this on the exact registered device: provider session IDs are not FeedMe devices.
-- Old revoked rows remain NULL and cannot impersonate an explicitly acknowledged logout.
ALTER TABLE identity.device_sessions ADD COLUMN logout_command_key uuid NULL;
ALTER TABLE identity.device_sessions ADD CONSTRAINT device_logout_requires_revocation
    CHECK (logout_command_key IS NULL OR revoked_at IS NOT NULL);
CREATE UNIQUE INDEX device_logout_command_owner
    ON identity.device_sessions(environment, user_id, logout_command_key)
    WHERE logout_command_key IS NOT NULL;
