-- Optional, explicitly granted account security operation. No runtime/Auth grants.
-- Supabase Auth's reviewed LogoutSession deletes auth.sessions; the validated
-- refresh_tokens.session_id FK cascades the legacy refresh-token family. New
-- encoded refresh tokens also require the retained session row. Never a Google
-- account/global logout, and never a claim that already-issued JWTs disappear.
-- Sources: supabase/auth internal/models/sessions.go and
-- migrations/20220811173540_add_sessions_table.up.sql. Deployment still requires
-- the existing exact Auth revision/migration-vector review, not master inference.
-- Caller first authenticates/locks the original provider and FeedMe account via
-- AccountProfileStore, and commits this helper WITH the canonical durable receipt.
CREATE FUNCTION identity.revoke_account_device_session(
    p_environment text, p_issuer text, p_subject uuid, p_caller_device uuid,
    p_target_device uuid, p_expected_version bigint
) RETURNS bigint
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET row_security = off
AS $feedme_session_revoke$
DECLARE
    v_account uuid;
    v_principal uuid;
    v_target_provider uuid;
    v_caller_provider uuid;
    v_provider_owner uuid;
    v_version bigint;
    v_status text;
    v_revoked timestamptz;
    v_fk oid;
BEGIN
    IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$'
        OR p_issuer IS DISTINCT FROM 'https://bljskfhazmnzmhkbkcev.supabase.co/auth/v1'
        OR p_subject IS NULL OR p_caller_device IS NULL OR p_target_device IS NULL
        OR p_caller_device=p_target_device OR p_expected_version IS NULL
        OR p_expected_version<1 OR p_expected_version=9223372036854775807
        OR current_setting('session_replication_role')<>'origin'
        OR current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514';
    END IF;
    -- Schema locks exclude concurrent FK/trigger/schema changes, not normal Auth
    -- refresh. No credential-bearing refresh-token column is projected or read.
    LOCK TABLE ONLY auth.users, ONLY auth.sessions, ONLY auth.refresh_tokens IN ROW EXCLUSIVE MODE;
    IF EXISTS(SELECT 1 FROM pg_inherits WHERE inhrelid IN('auth.sessions'::regclass,'auth.refresh_tokens'::regclass)
        OR inhparent IN('auth.sessions'::regclass,'auth.refresh_tokens'::regclass)) THEN
        RAISE EXCEPTION 'Session revocation unavailable' USING ERRCODE='55000';
    END IF;
    SELECT k.oid INTO v_fk FROM pg_constraint k
    WHERE k.conrelid='auth.refresh_tokens'::regclass AND k.confrelid='auth.sessions'::regclass
      AND k.contype='f' AND k.confdeltype='c' AND k.convalidated AND NOT k.condeferrable
      AND k.conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=k.conrelid AND attname='session_id' AND NOT attisdropped)]::smallint[]
      AND k.confkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid=k.confrelid AND attname='id' AND NOT attisdropped)]::smallint[];
    IF v_fk IS NULL OR (SELECT count(*) FROM pg_trigger WHERE tgconstraint=v_fk AND tgisinternal AND tgenabled IN('O','A'))<>4 THEN
        RAISE EXCEPTION 'Session revocation unavailable' USING ERRCODE='55000';
    END IF;
    -- Original user lock precedes both sessions. The caller already holds it;
    -- NOWAIT also refuses incompatible direct callers without waiting cycles.
    PERFORM id FROM ONLY auth.users WHERE id=p_subject FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514'; END IF;
    SELECT u.id,u.status INTO v_account,v_status FROM identity.users u
      WHERE u.environment=p_environment AND u.provider_issuer=p_issuer AND u.provider_subject=p_subject FOR UPDATE NOWAIT;
    IF v_account IS NULL OR v_status<>'active' THEN RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514'; END IF;
    SELECT p.id,p.status INTO v_principal,v_status FROM identity.principals p
      WHERE p.environment=p_environment AND p.user_id=v_account AND p.kind='user' FOR UPDATE NOWAIT;
    IF v_principal IS NULL OR v_status<>'active' OR EXISTS(SELECT 1 FROM identity.account_deletion_jobs j
        WHERE j.environment=p_environment AND j.user_id=v_account) THEN
        RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514';
    END IF;
    PERFORM d.id FROM identity.device_sessions d WHERE d.environment=p_environment AND d.user_id=v_account
      AND d.id IN(p_caller_device,p_target_device) ORDER BY d.id FOR UPDATE NOWAIT;
    SELECT provider_session_id,revoked_at INTO v_caller_provider,v_revoked FROM identity.device_sessions
      WHERE environment=p_environment AND user_id=v_account AND id=p_caller_device;
    IF v_caller_provider IS NULL OR v_revoked IS NOT NULL THEN RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514'; END IF;
    SELECT provider_session_id,version,revoked_at INTO v_target_provider,v_version,v_revoked FROM identity.device_sessions
      WHERE environment=p_environment AND user_id=v_account AND id=p_target_device;
    IF v_target_provider IS NULL OR v_target_provider=v_caller_provider OR v_revoked IS NOT NULL
        OR v_version<>p_expected_version THEN RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514'; END IF;
    -- Do not silently revoke another registered device sharing this provider
    -- session, including aliases in another environment. Such cases need explicit
    -- wider-scope UI/retirement, not a misleading single-device response.
    IF EXISTS(SELECT 1 FROM identity.device_sessions d JOIN identity.users u ON u.environment=d.environment AND u.id=d.user_id
        WHERE u.provider_issuer=p_issuer AND u.provider_subject=p_subject AND d.provider_session_id=v_target_provider
          AND d.revoked_at IS NULL AND (d.environment<>p_environment OR d.id<>p_target_device)) THEN
        RAISE EXCEPTION 'Session scope unsupported' USING ERRCODE='23514';
    END IF;
    SELECT user_id INTO v_provider_owner FROM ONLY auth.sessions WHERE id=v_caller_provider FOR UPDATE NOWAIT;
    IF v_provider_owner IS DISTINCT FROM p_subject THEN RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514'; END IF;
    v_provider_owner:=NULL;
    SELECT user_id INTO v_provider_owner FROM ONLY auth.sessions WHERE id=v_target_provider FOR UPDATE NOWAIT;
    IF v_provider_owner IS NOT NULL AND v_provider_owner<>p_subject THEN
        RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514';
    END IF;
    -- Already absent is authoritative absence, not a successful HTTP guess.
    DELETE FROM ONLY auth.sessions WHERE id=v_target_provider AND user_id=p_subject;
    IF EXISTS(SELECT 1 FROM ONLY auth.sessions WHERE id=v_target_provider)
        OR EXISTS(SELECT 1 FROM ONLY auth.refresh_tokens WHERE session_id=v_target_provider) THEN
        RAISE EXCEPTION 'Session revocation incomplete' USING ERRCODE='55000';
    END IF;
    UPDATE identity.device_sessions SET revoked_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1
      WHERE environment=p_environment AND user_id=v_account AND id=p_target_device AND version=p_expected_version AND revoked_at IS NULL
      RETURNING version INTO v_version;
    IF NOT FOUND THEN RAISE EXCEPTION 'Session revocation refused' USING ERRCODE='23514'; END IF;
    RETURN v_version;
END;
$feedme_session_revoke$;
REVOKE ALL ON FUNCTION identity.revoke_account_device_session(text,text,uuid,uuid,uuid,bigint) FROM PUBLIC;
