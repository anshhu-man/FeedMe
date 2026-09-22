-- Explicit reversible F27 reactions. No serving grants, notifications, ranking input or
-- physical erasure worker is installed by this migration.
CREATE TABLE social.post_reactions (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 actor_user_id uuid NOT NULL, post_owner_user_id uuid NOT NULL, post_id uuid NOT NULL, id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), active boolean NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN('heart','looksDoable','makingThis','yum')),
 last_command_key uuid NOT NULL, last_operation_id varchar(20) NOT NULL CHECK(last_operation_id IN('setReaction','removeReaction')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,actor_user_id,post_id), UNIQUE(environment,id),
 FOREIGN KEY(environment,actor_user_id) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,post_owner_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id),
 CHECK(isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at),
 CHECK(active=(last_operation_id='setReaction'))
);
CREATE INDEX post_reactions_summary ON social.post_reactions(environment,post_owner_user_id,post_id,actor_user_id) WHERE active;
CREATE TABLE social.post_reaction_cleanup_jobs (
 environment varchar(40) NOT NULL, post_owner_user_id uuid NOT NULL, post_id uuid NOT NULL,
 post_version bigint NOT NULL CHECK(post_version>0), requested_at timestamptz NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'pending' CHECK(state='pending'),
 reason varchar(32) NOT NULL CHECK(reason='post_deleted'),
 PRIMARY KEY(environment,post_owner_user_id,post_id),
 FOREIGN KEY(environment,post_owner_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id),
 CHECK(isfinite(requested_at))
);
ALTER TABLE social.post_reactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.post_reactions FORCE ROW LEVEL SECURITY;
ALTER TABLE social.post_reaction_cleanup_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.post_reaction_cleanup_jobs FORCE ROW LEVEL SECURITY;
REVOKE ALL ON social.post_reactions,social.post_reaction_cleanup_jobs FROM PUBLIC;

CREATE FUNCTION social.protect_post_reaction_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP IN('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Reaction history requires reviewed cleanup' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.version<>1 OR NOT NEW.active OR NEW.updated_at<>NEW.created_at THEN
   RAISE EXCEPTION 'Reaction begins active at version one' USING ERRCODE='23514'; END IF;
 ELSE
  IF ROW(NEW.environment,NEW.actor_user_id,NEW.post_owner_user_id,NEW.post_id,NEW.id,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.environment,OLD.actor_user_id,OLD.post_owner_user_id,OLD.post_id,OLD.id,OLD.created_at)
    OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at
    OR ROW(NEW.last_command_key,NEW.last_operation_id)=ROW(OLD.last_command_key,OLD.last_operation_id)
    OR ROW(NEW.active,NEW.kind) IS NOT DISTINCT FROM ROW(OLD.active,OLD.kind)
    OR (NOT NEW.active AND (NOT OLD.active OR NEW.kind<>OLD.kind)) THEN
   RAISE EXCEPTION 'Reaction transition requires exact next original' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.updated_at>clock_timestamp() THEN RAISE EXCEPTION 'Reaction clock unavailable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER post_reaction_history BEFORE INSERT OR UPDATE OR DELETE ON social.post_reactions
 FOR EACH ROW EXECUTE FUNCTION social.protect_post_reaction_history();
CREATE TRIGGER post_reaction_history_truncate BEFORE TRUNCATE ON social.post_reactions
 FOR EACH STATEMENT EXECUTE FUNCTION social.protect_post_reaction_history();

CREATE FUNCTION social.protect_post_reaction_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' AND pg_trigger_depth()>=2 AND NEW.state='pending' AND NEW.reason='post_deleted'
   AND NEW.requested_at<=clock_timestamp() THEN
  PERFORM 1 FROM ONLY social.posts WHERE environment=NEW.environment AND owner_user_id=NEW.post_owner_user_id
   AND id=NEW.post_id AND version=NEW.post_version AND status='deleted' AND xmin::text::bigint=mod(txid_current(),4294967296);
  IF FOUND THEN RETURN NEW; END IF;
 END IF;
 RAISE EXCEPTION 'Reaction cleanup remains pending until reviewed worker retention' USING ERRCODE='23514';
END; $$;
CREATE TRIGGER post_reaction_cleanup_immutable BEFORE INSERT OR UPDATE OR DELETE ON social.post_reaction_cleanup_jobs
 FOR EACH ROW EXECUTE FUNCTION social.protect_post_reaction_cleanup();
CREATE TRIGGER post_reaction_cleanup_truncate BEFORE TRUNCATE ON social.post_reaction_cleanup_jobs
 FOR EACH STATEMENT EXECUTE FUNCTION social.protect_post_reaction_cleanup();

-- The real owner deletion transaction creates the exact pending cleanup intent even
-- if no reaction rows remain active. This does not claim data or remote bytes erased.
CREATE FUNCTION social.queue_deleted_post_reactions() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $$
BEGIN
 IF NEW.status='deleted' AND OLD.status<>'deleted' THEN
  INSERT INTO social.post_reaction_cleanup_jobs(environment,post_owner_user_id,post_id,post_version,requested_at,state,reason)
   VALUES(NEW.environment,NEW.owner_user_id,NEW.id,NEW.version,clock_timestamp(),'pending','post_deleted');
 END IF;
 RETURN NEW;
END; $$;
REVOKE ALL ON FUNCTION social.queue_deleted_post_reactions() FROM PUBLIC;
CREATE TRIGGER deleted_post_reaction_cleanup AFTER UPDATE OF status ON social.posts
 FOR EACH ROW EXECUTE FUNCTION social.queue_deleted_post_reactions();
