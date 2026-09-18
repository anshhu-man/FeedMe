-- Explicit account-planning operational allowance. No policy, account, eligibility,
-- deployment setting or permissive RLS grant is seeded by this additive migration.
CREATE TABLE planning.account_plan_windows (
    environment varchar(40) NOT NULL,
    actor_kind varchar(10) NOT NULL CHECK (actor_kind='account'),
    principal_id uuid NOT NULL,
    window_date date NOT NULL CHECK (isfinite(window_date)),
    policy_revision varchar(128) NOT NULL CHECK (char_length(btrim(policy_revision)) BETWEEN 1 AND 128),
    max_plans integer NOT NULL CHECK (max_plans BETWEEN 1 AND 10000),
    used_count integer NOT NULL CHECK (used_count BETWEEN 1 AND max_plans),
    PRIMARY KEY(environment,principal_id,window_date),
    FOREIGN KEY(environment,principal_id) REFERENCES identity.principals(environment,id)
);
CREATE FUNCTION planning.guard_account_plan_window() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.used_count <> 1 OR NOT EXISTS (SELECT 1 FROM identity.principals
            WHERE environment=NEW.environment AND id=NEW.principal_id AND kind='user') THEN
            RAISE EXCEPTION 'Account planning allowance unavailable' USING ERRCODE='23514';
        END IF;
    ELSE
        IF ROW(NEW.environment,NEW.actor_kind,NEW.principal_id,NEW.window_date,NEW.policy_revision,NEW.max_plans)
            IS DISTINCT FROM ROW(OLD.environment,OLD.actor_kind,OLD.principal_id,OLD.window_date,OLD.policy_revision,OLD.max_plans)
            OR NEW.used_count <> OLD.used_count + 1 THEN
            RAISE EXCEPTION 'Account planning allowance unavailable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_plan_window_monotonic BEFORE INSERT OR UPDATE ON planning.account_plan_windows
    FOR EACH ROW EXECUTE FUNCTION planning.guard_account_plan_window();
CREATE TRIGGER account_plan_window_retained BEFORE DELETE OR TRUNCATE ON planning.account_plan_windows
    FOR EACH STATEMENT EXECUTE FUNCTION planning.reject_manifest_mutation();
ALTER TABLE planning.account_plan_windows ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.account_plan_windows FORCE ROW LEVEL SECURITY;
REVOKE ALL ON planning.account_plan_windows FROM PUBLIC;
