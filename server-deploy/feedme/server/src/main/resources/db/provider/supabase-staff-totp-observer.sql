-- Packaged provider-owner extension for the staff admission boundary. This is
-- installed only by the fixed-project rollout after the reviewed base Auth
-- projections. It exposes one current session-to-TOTP-factor fact set; no
-- factor secret, challenge, phone, credential, email or provider token leaves
-- the managed Auth schema. This resource deliberately grants no capability.

LOCK TABLE ONLY auth.sessions, ONLY auth.mfa_factors IN ACCESS SHARE MODE;

DO $feedme_staff_totp_schema$
DECLARE
    sessions_oid oid := 'auth.sessions'::pg_catalog.regclass;
    factors_oid oid := 'auth.mfa_factors'::pg_catalog.regclass;
    session_id smallint;
    factor_id smallint;
BEGIN
    IF pg_catalog.current_setting('session_replication_role') <> 'origin'
        OR pg_catalog.current_setting('transaction_isolation') <> 'read committed'
        OR pg_catalog.current_setting('transaction_read_only') <> 'off'
        OR EXISTS (
            SELECT 1 FROM pg_catalog.pg_class c
            WHERE c.oid IN (sessions_oid, factors_oid)
              AND (c.relkind <> 'r' OR EXISTS (
                  SELECT 1 FROM pg_catalog.pg_inherits i
                  WHERE i.inhrelid = c.oid OR i.inhparent = c.oid
              ))
        ) THEN
        RAISE EXCEPTION 'Unsupported provider staff-TOTP installer context';
    END IF;

    IF (SELECT pg_catalog.count(*) FROM pg_catalog.pg_attribute a
        JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
        JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
        WHERE a.attrelid = sessions_oid AND a.attnum > 0 AND NOT a.attisdropped
          AND ((a.attname IN ('id','user_id') AND a.attnotnull
                AND n.nspname = 'pg_catalog' AND t.typname = 'uuid')
            OR (a.attname = 'factor_id' AND n.nspname = 'pg_catalog'
                AND t.typname = 'uuid'))) <> 3
        OR (SELECT pg_catalog.count(*) FROM pg_catalog.pg_attribute a
        JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
        JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
        WHERE a.attrelid = factors_oid AND a.attnum > 0 AND NOT a.attisdropped
          AND a.attnotnull
          AND ((a.attname IN ('id','user_id') AND n.nspname = 'pg_catalog'
                AND t.typname = 'uuid')
            OR (a.attname = 'status' AND n.nspname = 'auth'
                AND t.typname = 'factor_status' AND t.typtype = 'e')
            OR (a.attname = 'factor_type' AND n.nspname = 'auth'
                AND t.typname = 'factor_type' AND t.typtype = 'e'))) <> 4
        OR NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_enum e
            WHERE e.enumtypid = 'auth.factor_status'::pg_catalog.regtype
              AND e.enumlabel = 'verified'
        )
        OR NOT EXISTS (
            SELECT 1 FROM pg_catalog.pg_enum e
            WHERE e.enumtypid = 'auth.factor_type'::pg_catalog.regtype
              AND e.enumlabel = 'totp'
        ) THEN
        RAISE EXCEPTION 'Unsupported provider staff-TOTP schema';
    END IF;

    SELECT a.attnum INTO STRICT session_id FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = sessions_oid AND a.attname = 'id'
      AND a.attnum > 0 AND NOT a.attisdropped;
    SELECT a.attnum INTO STRICT factor_id FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = factors_oid AND a.attname = 'id'
      AND a.attnum > 0 AND NOT a.attisdropped;
    IF (SELECT pg_catalog.count(*) FROM pg_catalog.pg_constraint c
        WHERE c.conrelid = sessions_oid AND c.contype = 'p' AND c.convalidated
          AND NOT c.condeferrable AND NOT c.condeferred
          AND c.conkey = ARRAY[session_id]::smallint[]) <> 1
        OR (SELECT pg_catalog.count(*) FROM pg_catalog.pg_constraint c
        WHERE c.conrelid = factors_oid AND c.contype = 'p' AND c.convalidated
          AND NOT c.condeferrable AND NOT c.condeferred
          AND c.conkey = ARRAY[factor_id]::smallint[]) <> 1 THEN
        RAISE EXCEPTION 'Unsupported provider staff-TOTP primary key';
    END IF;
END;
$feedme_staff_totp_schema$;

CREATE FUNCTION feedme_auth_access.staff_totp_facts(
    p_session_id pg_catalog.uuid,
    p_user_id pg_catalog.uuid
)
RETURNS TABLE(
    factor_id pg_catalog.uuid,
    user_id pg_catalog.uuid,
    status pg_catalog.text,
    factor_type pg_catalog.text
)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER STRICT
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme_staff_totp$
BEGIN
    RETURN QUERY
        SELECT s.factor_id, f.user_id, f.status::pg_catalog.text,
            f.factor_type::pg_catalog.text
        FROM ONLY auth.sessions AS s
        JOIN ONLY auth.mfa_factors AS f
          ON f.id = s.factor_id AND f.user_id = s.user_id
        WHERE s.id = p_session_id AND s.user_id = p_user_id
        ORDER BY s.id
        LIMIT 2
        FOR UPDATE OF s, f;
END;
$feedme_staff_totp$;
REVOKE ALL ON FUNCTION feedme_auth_access.staff_totp_facts(
    pg_catalog.uuid, pg_catalog.uuid
) FROM PUBLIC;

-- A separate reviewed serving delta grants only EXECUTE to feedme_api after
-- exact owner, source, shape and ACL verification.
