-- Fresh installation only, inside one installer-owned transaction. The installer must
-- verify the trusted existing owner and its provider privileges before executing this
-- resource, and must commit only after checking ownership and the complete ACLs.
-- No runtime role, membership, Auth grant, or provider mutation is created here.
-- The managed deployment uses its existing trusted postgres owner; that owner's broad
-- privileges are NOT a claim of a least-privileged helper owner. Runtime receives only
-- separately reviewed schema USAGE and EXECUTE on these six fixed projections.
--
-- Callers must use their existing READ COMMITTED transaction and schema_lock before
-- checking the schema and locking user -> session -> ordered AMR -> FeedMe roots.
-- Fact-table ROW EXCLUSIVE locks coexist with normal Auth DML and concurrent callers,
-- but exclude incompatible schema/FK-trigger changes for that transaction. The history
-- vector is observed, not frozen: ACCESS SHARE does not prevent history DML. These
-- functions do not lock provider binaries/settings or independently grant app access.

CREATE SCHEMA feedme_auth_access;
REVOKE ALL ON SCHEMA feedme_auth_access FROM PUBLIC;

CREATE FUNCTION feedme_auth_access.schema_lock()
RETURNS pg_catalog.void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme$
BEGIN
    LOCK TABLE ONLY auth.schema_migrations IN ACCESS SHARE MODE;
    LOCK TABLE ONLY auth.users, ONLY auth.sessions, ONLY auth.mfa_amr_claims
        IN ROW EXCLUSIVE MODE;
END;
$feedme$;
REVOKE ALL ON FUNCTION feedme_auth_access.schema_lock() FROM PUBLIC;

CREATE FUNCTION feedme_auth_access.migration_versions()
RETURNS TABLE(version pg_catalog.text)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme$
BEGIN
    RETURN QUERY
        SELECT m.version::pg_catalog.text
        FROM ONLY auth.schema_migrations AS m
        ORDER BY m.version
        LIMIT 513;
END;
$feedme$;
REVOKE ALL ON FUNCTION feedme_auth_access.migration_versions() FROM PUBLIC;

CREATE FUNCTION feedme_auth_access.user_facts(p_user_id pg_catalog.uuid)
RETURNS TABLE(
    aud pg_catalog.text,
    role pg_catalog.text,
    email_confirmed_at pg_catalog.timestamptz,
    deleted_at pg_catalog.timestamptz,
    banned_until pg_catalog.timestamptz,
    is_anonymous pg_catalog.bool
)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER STRICT
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme$
BEGIN
    -- Projection and lock are the same statement: an absent row cannot be followed by
    -- an unlocked read that accidentally observes a subsequently inserted identity.
    RETURN QUERY
        SELECT u.aud::pg_catalog.text, u.role::pg_catalog.text,
            u.email_confirmed_at, u.deleted_at, u.banned_until, u.is_anonymous
        FROM ONLY auth.users AS u
        WHERE u.id = p_user_id
        FOR UPDATE OF u;
END;
$feedme$;
REVOKE ALL ON FUNCTION feedme_auth_access.user_facts(pg_catalog.uuid) FROM PUBLIC;

CREATE FUNCTION feedme_auth_access.session_facts(p_session_id pg_catalog.uuid)
RETURNS TABLE(
    user_id pg_catalog.uuid,
    created_at pg_catalog.timestamptz,
    not_after pg_catalog.timestamptz,
    refreshed_at pg_catalog.timestamp,
    aal pg_catalog.text,
    oauth_client_id pg_catalog.uuid
)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER STRICT
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme$
BEGIN
    RETURN QUERY
        SELECT s.user_id, s.created_at, s.not_after, s.refreshed_at,
            s.aal::pg_catalog.text, s.oauth_client_id
        FROM ONLY auth.sessions AS s
        WHERE s.id = p_session_id
        FOR UPDATE OF s;
END;
$feedme$;
REVOKE ALL ON FUNCTION feedme_auth_access.session_facts(pg_catalog.uuid) FROM PUBLIC;

CREATE FUNCTION feedme_auth_access.amr_facts(p_session_id pg_catalog.uuid)
RETURNS TABLE(authentication_method pg_catalog.text, updated_at pg_catalog.timestamptz)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER STRICT
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme$
BEGIN
    -- The caller already holds this session FOR UPDATE. Reviewed non-deferrable FKs
    -- make new AMR inserts conflict with that parent lock. Row 33 signals over-capacity.
    RETURN QUERY
        SELECT a.authentication_method::pg_catalog.text, a.updated_at
        FROM ONLY auth.mfa_amr_claims AS a
        WHERE a.session_id = p_session_id
        ORDER BY a.id
        LIMIT 33
        FOR UPDATE OF a;
END;
$feedme$;
REVOKE ALL ON FUNCTION feedme_auth_access.amr_facts(pg_catalog.uuid) FROM PUBLIC;

CREATE FUNCTION feedme_auth_access.password_facts(
    p_session_id pg_catalog.uuid,
    p_user_id pg_catalog.uuid
)
RETURNS TABLE(created_at pg_catalog.timestamptz, updated_at pg_catalog.timestamptz)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER STRICT
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme$
BEGIN
    -- The caller has already locked this exact user/session/AMR set and checked its
    -- current facts. Two rows preserve fail-closed duplicate-password-AMR detection.
    RETURN QUERY
        SELECT s.created_at, a.updated_at
        FROM ONLY auth.sessions AS s
        JOIN ONLY auth.mfa_amr_claims AS a ON a.session_id = s.id
        WHERE s.id = p_session_id AND s.user_id = p_user_id
            AND a.authentication_method = 'password'
        ORDER BY a.id
        LIMIT 2
        FOR UPDATE OF s, a;
END;
$feedme$;
REVOKE ALL ON FUNCTION feedme_auth_access.password_facts(pg_catalog.uuid, pg_catalog.uuid) FROM PUBLIC;
