-- Local additive safety foundation. No runtime grant, policy decision or social rollout.
-- Allocation order (not wall-clock/commit order) permanently fences old generic invitations
-- for a blocked pair. Gaps on rollback are intentional; the sequence is never reset/recycled.
CREATE SEQUENCE social.relationship_order AS bigint MINVALUE 1 NO CYCLE;
ALTER TABLE social.circle_invitations ADD COLUMN issued_order bigint NOT NULL
    DEFAULT nextval('social.relationship_order') CHECK (issued_order > 0);
CREATE FUNCTION social.protect_invitation_order() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.issued_order IS DISTINCT FROM OLD.issued_order THEN
  RAISE EXCEPTION 'Invitation allocation identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER invitation_order_identity BEFORE UPDATE ON social.circle_invitations
 FOR EACH ROW EXECUTE FUNCTION social.protect_invitation_order();

CREATE TABLE social.block_pairs (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 user_low uuid NOT NULL, user_high uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0),
 deny_invites_through bigint NOT NULL CHECK(deny_invites_through>0),
 PRIMARY KEY(environment,user_low,user_high), CHECK(user_low<user_high)
);
CREATE TABLE social.blocks (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 owner_user_id uuid NOT NULL, target_user_id uuid NOT NULL, id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), active boolean NOT NULL,
 last_command_key uuid NOT NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,owner_user_id,target_user_id), UNIQUE(environment,id),
 CHECK(owner_user_id<>target_user_id), CHECK(updated_at>=created_at),
 FOREIGN KEY(environment,owner_user_id) REFERENCES identity.users(environment,id)
);
-- No foreign-target FK: it would silently take a foreign account lock after the pair
-- lock. The writer checks target existence without locking; retained blocks survive
-- target suspension/deletion and are never authority to read a target profile.
CREATE INDEX active_owned_blocks ON social.blocks(environment,owner_user_id,id) WHERE active;
ALTER TABLE social.blocks ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.blocks FORCE ROW LEVEL SECURITY;
ALTER TABLE social.block_pairs ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.block_pairs FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE social.blocks,social.block_pairs FROM PUBLIC;
REVOKE ALL ON SEQUENCE social.relationship_order FROM PUBLIC;
CREATE FUNCTION social.protect_block_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Block history cannot be removed' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='blocks' THEN
  IF ROW(NEW.environment,NEW.owner_user_id,NEW.target_user_id,NEW.id,NEW.created_at)
     IS DISTINCT FROM ROW(OLD.environment,OLD.owner_user_id,OLD.target_user_id,OLD.id,OLD.created_at)
     OR NEW.version<>OLD.version+1 OR NEW.active=OLD.active OR NEW.updated_at<OLD.updated_at THEN
   RAISE EXCEPTION 'Block transition requires original identity and next version' USING ERRCODE='23514';
  END IF;
 ELSE
  IF ROW(NEW.environment,NEW.user_low,NEW.user_high) IS DISTINCT FROM ROW(OLD.environment,OLD.user_low,OLD.user_high)
     OR NEW.version<>OLD.version+1 OR NEW.deny_invites_through<=OLD.deny_invites_through THEN
   RAISE EXCEPTION 'Relationship cutoff cannot be restored or recycled' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER block_history BEFORE UPDATE OR DELETE ON social.blocks
 FOR EACH ROW EXECUTE FUNCTION social.protect_block_history();
CREATE TRIGGER block_pair_history BEFORE UPDATE OR DELETE ON social.block_pairs
 FOR EACH ROW EXECUTE FUNCTION social.protect_block_history();
CREATE TRIGGER block_history_truncate BEFORE TRUNCATE ON social.blocks
 FOR EACH STATEMENT EXECUTE FUNCTION social.protect_block_history();
CREATE TRIGGER block_pair_history_truncate BEFORE TRUNCATE ON social.block_pairs
 FOR EACH STATEMENT EXECUTE FUNCTION social.protect_block_history();
