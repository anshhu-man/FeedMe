-- Expand-only circles foundation. Verified accounts/device sessions/profile/block policy are
-- supplied by a separate trusted adapter; no identity or HTTP acceptance is introduced here.
CREATE SCHEMA social;

CREATE TABLE social.circles (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    id uuid NOT NULL, owner_id uuid NOT NULL,
    name varchar(60) NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    description varchar(300) NULL,
    status varchar(16) NOT NULL CHECK (status IN ('active','archived')),
    member_limit integer NOT NULL CHECK (member_limit BETWEEN 2 AND 50),
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,id)
);
CREATE TABLE social.circle_members (
    environment varchar(40) NOT NULL, circle_id uuid NOT NULL, user_id uuid NOT NULL,
    id uuid NOT NULL, role varchar(10) NOT NULL CHECK (role IN ('owner','admin','member')),
    status varchar(10) NOT NULL CHECK (status IN ('active','removed')),
    generation bigint NOT NULL CHECK (generation > 0),
    version bigint NOT NULL CHECK (version > 0),
    removal_key uuid NULL, removal_operation varchar(24) NULL, removed_by uuid NULL, removed_generation bigint NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    joined_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(environment,circle_id,user_id), UNIQUE(environment,id),
    FOREIGN KEY(environment,circle_id) REFERENCES social.circles(environment,id),
    CHECK ((removal_key IS NULL) = (removed_by IS NULL)),
    CHECK ((removal_key IS NULL) = (removal_operation IS NULL)),
    CHECK ((removal_key IS NULL) = (removed_generation IS NULL)),
    CHECK (removal_key IS NULL OR (status='removed' AND removed_generation=generation AND removal_operation IN ('leaveCircle','removeCircleMember')))
);
CREATE UNIQUE INDEX circle_one_active_owner ON social.circle_members(environment,circle_id)
    WHERE role='owner' AND status='active';
CREATE INDEX circle_member_listing ON social.circle_members(environment,user_id,circle_id) WHERE status='active';
ALTER TABLE social.circles ADD CONSTRAINT circle_owner_membership
    FOREIGN KEY(environment,id,owner_id) REFERENCES social.circle_members(environment,circle_id,user_id)
    DEFERRABLE INITIALLY DEFERRED;

-- Checked at transaction end: temporary demotion/promotion during an ownership transfer is legal.
CREATE FUNCTION social.check_circle_membership_invariants() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE env text; cid uuid; parent social.circles%ROWTYPE; owners integer; members integer;
BEGIN
    IF TG_TABLE_NAME='circles' THEN
        env := COALESCE(NEW.environment,OLD.environment); cid := COALESCE(NEW.id,OLD.id);
    ELSE
        env := COALESCE(NEW.environment,OLD.environment); cid := COALESCE(NEW.circle_id,OLD.circle_id);
    END IF;
    -- Serialize even direct SQL writers at the deferred check. NO KEY UPDATE remains
    -- compatible with the foreign-key KEY SHARE locks held by concurrent inserts.
    SELECT * INTO parent FROM social.circles WHERE environment=env AND id=cid FOR NO KEY UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT count(*),count(*) FILTER (WHERE role='owner' AND user_id=parent.owner_id)
        INTO members,owners FROM social.circle_members WHERE environment=env AND circle_id=cid AND status='active';
    IF (parent.status='active' AND (owners<>1 OR members>parent.member_limit)) OR
       (parent.status='archived' AND members<>0) THEN
        RAISE EXCEPTION 'Circle membership invariant violated' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER circle_membership_invariant AFTER INSERT OR UPDATE ON social.circles
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION social.check_circle_membership_invariants();
CREATE CONSTRAINT TRIGGER circle_member_invariant AFTER INSERT OR UPDATE OR DELETE ON social.circle_members
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION social.check_circle_membership_invariants();

CREATE TABLE social.circle_invitations (
    environment varchar(40) NOT NULL, id uuid NOT NULL, circle_id uuid NOT NULL, created_by uuid NOT NULL,
    issuer_generation bigint NOT NULL CHECK (issuer_generation > 0),
    issuer_version bigint NOT NULL CHECK (issuer_version > 0),
    token_hash char(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    token_key_id varchar(32) NOT NULL CHECK (token_key_id ~ '^[a-z0-9_-]{1,32}$'),
    status varchar(12) NOT NULL CHECK (status IN ('active','revoked','exhausted')),
    consumed_by uuid NULL, consumed_generation bigint NULL CHECK (consumed_generation > 0),
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), expires_at timestamptz NOT NULL,
    PRIMARY KEY(environment,id), UNIQUE(environment,token_hash),
    FOREIGN KEY(environment,circle_id) REFERENCES social.circles(environment,id),
    CHECK (expires_at > created_at),
    CHECK ((status='exhausted') = (consumed_by IS NOT NULL)),
    CHECK ((consumed_by IS NULL) = (consumed_generation IS NULL))
);
CREATE INDEX circle_invitation_target ON social.circle_invitations(environment,circle_id);
