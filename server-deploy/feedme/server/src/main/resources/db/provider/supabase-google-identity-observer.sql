-- Packaged provider-owner upgrade; explicit fixed-project rollout only.
-- Adds one bounded current-Google-identity projection to the already reviewed
-- feedme_auth_access owner. It does not grant EXECUTE, expose email/identity_data,
-- issue a support challenge, consume a challenge or authorize account deletion.
-- Installation verifies the required auth.identities columns, primary keys and
-- validated non-deferrable user_id -> auth.users(id) foreign key. The caller first
-- locks the exact current provider user/session/AMR transaction through
-- SupabasePostgresAuthority, so a concurrent identity link cannot bypass that parent lock.

LOCK TABLE ONLY auth.users, ONLY auth.identities IN ACCESS SHARE MODE;

DO $feedme_google_identity_schema$
DECLARE
    identities_oid oid := 'auth.identities'::pg_catalog.regclass;
    users_oid oid := 'auth.users'::pg_catalog.regclass;
    identity_id smallint;
    identity_user smallint;
    identity_provider smallint;
    user_id smallint;
    identity_user_fk oid;
    identity_user_fks oid[];
BEGIN
    IF pg_catalog.current_setting('session_replication_role') <> 'origin'
        OR pg_catalog.current_setting('transaction_isolation') <> 'read committed'
        OR pg_catalog.current_setting('transaction_read_only') <> 'off'
        OR EXISTS (
            SELECT 1 FROM pg_catalog.pg_class c
            WHERE c.oid IN (identities_oid, users_oid)
              AND (c.relkind <> 'r' OR EXISTS (
                  SELECT 1 FROM pg_catalog.pg_inherits i
                  WHERE i.inhrelid = c.oid OR i.inhparent = c.oid
              ))
        ) THEN
        RAISE EXCEPTION 'Unsupported provider identity installer context';
    END IF;

    SELECT a.attnum INTO STRICT identity_id
    FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = identities_oid AND a.attname = 'id'
      AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull
      AND a.atttypid = 'pg_catalog.uuid'::pg_catalog.regtype;
    SELECT a.attnum INTO STRICT identity_user
    FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = identities_oid AND a.attname = 'user_id'
      AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull
      AND a.atttypid = 'pg_catalog.uuid'::pg_catalog.regtype;
    SELECT a.attnum INTO STRICT identity_provider
    FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = identities_oid AND a.attname = 'provider'
      AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull
      AND a.atttypid = 'pg_catalog.text'::pg_catalog.regtype;
    SELECT a.attnum INTO STRICT user_id
    FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = users_oid AND a.attname = 'id'
      AND a.attnum > 0 AND NOT a.attisdropped AND a.attnotnull
      AND a.atttypid = 'pg_catalog.uuid'::pg_catalog.regtype;

    IF identity_id = identity_user OR identity_id = identity_provider
        OR identity_user = identity_provider
        OR (SELECT pg_catalog.count(*) FROM pg_catalog.pg_constraint c
            WHERE c.conrelid = identities_oid AND c.contype = 'p'
              AND c.convalidated AND NOT c.condeferrable AND NOT c.condeferred
              AND c.conkey = ARRAY[identity_id]::smallint[]) <> 1
        OR (SELECT pg_catalog.count(*) FROM pg_catalog.pg_constraint c
            WHERE c.conrelid = users_oid AND c.contype = 'p'
              AND c.convalidated AND NOT c.condeferrable AND NOT c.condeferred
              AND c.conkey = ARRAY[user_id]::smallint[]) <> 1 THEN
        RAISE EXCEPTION 'Unsupported provider identity primary key';
    END IF;

    SELECT pg_catalog.array_agg(c.oid ORDER BY c.oid) INTO identity_user_fks
    FROM pg_catalog.pg_constraint c
    WHERE c.conrelid = identities_oid AND c.confrelid = users_oid
      AND c.contype = 'f' AND c.convalidated
      AND NOT c.condeferrable AND NOT c.condeferred
      AND c.conkey = ARRAY[identity_user]::smallint[]
      AND c.confkey = ARRAY[user_id]::smallint[]
      AND c.confdeltype = 'c' AND c.confupdtype = 'a' AND c.confmatchtype = 's';

    IF pg_catalog.cardinality(identity_user_fks) IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION 'Unsupported provider identity foreign key';
    END IF;
    identity_user_fk := identity_user_fks[1];

    IF (SELECT pg_catalog.count(*) FROM pg_catalog.pg_trigger t
        WHERE t.tgconstraint = identity_user_fk) <> 4
        OR EXISTS (
            SELECT 1 FROM pg_catalog.pg_trigger t
            WHERE t.tgconstraint = identity_user_fk
              AND (NOT t.tgisinternal OR t.tgenabled NOT IN ('O','A')
                OR t.tgdeferrable OR t.tginitdeferred OR t.tgqual IS NOT NULL)
        )
        OR (SELECT pg_catalog.count(*)
            FROM pg_catalog.pg_trigger t
            JOIN pg_catalog.pg_proc p ON p.oid = t.tgfoid
            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
            WHERE t.tgconstraint = identity_user_fk AND n.nspname = 'pg_catalog'
              AND (
                (t.tgrelid = identities_oid AND t.tgconstrrelid = users_oid AND
                  ((p.proname = 'RI_FKey_check_ins' AND t.tgtype = 5) OR
                   (p.proname = 'RI_FKey_check_upd' AND t.tgtype = 17)))
                OR
                (t.tgrelid = users_oid AND t.tgconstrrelid = identities_oid AND
                  ((p.proname = 'RI_FKey_cascade_del' AND t.tgtype = 9) OR
                   (p.proname = 'RI_FKey_noaction_upd' AND t.tgtype = 17)))
              )) <> 4 THEN
        RAISE EXCEPTION 'Unsupported provider identity foreign key enforcement';
    END IF;
END;
$feedme_google_identity_schema$;

CREATE FUNCTION feedme_auth_access.google_identity_facts(p_user_id pg_catalog.uuid)
RETURNS TABLE(
    user_id pg_catalog.uuid,
    provider pg_catalog.text,
    observed_at pg_catalog.timestamptz
)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER STRICT
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme_google_identity$
BEGIN
    RETURN QUERY
        SELECT i.user_id, i.provider::pg_catalog.text, pg_catalog.clock_timestamp()
        FROM ONLY auth.identities AS i
        WHERE i.user_id = p_user_id AND i.provider = 'google'
        ORDER BY i.id
        LIMIT 2
        FOR SHARE OF i;
END;
$feedme_google_identity$;
REVOKE ALL ON FUNCTION feedme_auth_access.google_identity_facts(pg_catalog.uuid) FROM PUBLIC;

-- Deliberately no GRANT. A separate reviewed serving delta must grant only EXECUTE
-- to feedme_api after exact schema/FK/owner/function/ACL verification.
