-- Expand-only private planning persistence. No identity/catalog/provider fixture or HTTP gate.
CREATE SCHEMA planning;
CREATE TABLE planning.plan_requests (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    actor_kind varchar(10) NOT NULL CHECK (actor_kind IN ('account','guest')),
    principal_id uuid NOT NULL, id uuid NOT NULL,
    request_text text NOT NULL CHECK (octet_length(request_text) BETWEEN 2 AND 65536 AND jsonb_typeof(request_text::jsonb)='object'),
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    evidence_text text NOT NULL CHECK (octet_length(evidence_text) BETWEEN 2 AND 2097152 AND jsonb_typeof(evidence_text::jsonb)='object'),
    evidence_hash char(64) NOT NULL CHECK (evidence_hash ~ '^[0-9a-f]{64}$'),
    ordered_ids jsonb NOT NULL CHECK (jsonb_typeof(ordered_ids)='array' AND jsonb_array_length(ordered_ids)<=128),
    policy_text text NOT NULL CHECK (octet_length(policy_text) BETWEEN 2 AND 1024 AND jsonb_typeof(policy_text::jsonb)='object'),
    current_plan_id uuid NULL, version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, cursor_expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at AND cursor_expires_at > created_at AND cursor_expires_at <= expires_at),
    PRIMARY KEY(environment,actor_kind,principal_id,id)
);
CREATE INDEX planning_request_owner ON planning.plan_requests(environment,actor_kind,principal_id,created_at DESC);
CREATE INDEX planning_request_expiry ON planning.plan_requests(expires_at);

CREATE TABLE planning.plans (
    environment varchar(40) NOT NULL, actor_kind varchar(10) NOT NULL, principal_id uuid NOT NULL,
    id uuid NOT NULL, request_id uuid NOT NULL, parent_plan_id uuid NULL,
    version bigint NOT NULL CHECK (version=1), position integer NOT NULL CHECK (position BETWEEN -1 AND 128),
    recipe_version_id uuid NULL, status varchar(24) NOT NULL CHECK (status IN ('ready','needsConfirmation','noMatch')),
    snapshot_text text NOT NULL CHECK (octet_length(snapshot_text) BETWEEN 2 AND 262144 AND jsonb_typeof(snapshot_text::jsonb)='object'),
    snapshot_hash char(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    proof_text text NOT NULL CHECK (octet_length(proof_text) BETWEEN 2 AND 32768 AND jsonb_typeof(proof_text::jsonb)='object'),
    proof_hash char(64) NOT NULL CHECK (proof_hash ~ '^[0-9a-f]{64}$'),
    next_cursor_hash char(64) NULL CHECK (next_cursor_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    PRIMARY KEY(environment,actor_kind,principal_id,id),
    UNIQUE(environment,actor_kind,principal_id,request_id,id),
    FOREIGN KEY(environment,actor_kind,principal_id,request_id) REFERENCES planning.plan_requests(environment,actor_kind,principal_id,id),
    FOREIGN KEY(environment,actor_kind,principal_id,request_id,parent_plan_id) REFERENCES planning.plans(environment,actor_kind,principal_id,request_id,id),
    CHECK ((status='ready') = (recipe_version_id IS NOT NULL)),
    CHECK (next_cursor_hash IS NULL OR status='ready')
);
ALTER TABLE planning.plan_requests ADD CONSTRAINT planning_current_owned_plan
    FOREIGN KEY(environment,actor_kind,principal_id,id,current_plan_id)
    REFERENCES planning.plans(environment,actor_kind,principal_id,request_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX planning_plan_owner ON planning.plans(environment,actor_kind,principal_id,created_at DESC);

-- Immutable snapshots/proofs are never silently corrected or used as a new catalog version.
-- Expiry/erasure workers may delete owned rows in dependency order; no retention worker is enabled here.
CREATE FUNCTION planning.reject_plan_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Plan snapshots are immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER immutable_plan_snapshot BEFORE UPDATE ON planning.plans
    FOR EACH ROW EXECUTE FUNCTION planning.reject_plan_update();
