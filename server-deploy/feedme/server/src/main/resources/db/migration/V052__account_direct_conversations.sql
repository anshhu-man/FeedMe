-- Private direct conversations only. No route, grant, notification delivery or
-- recipe-card capability is enabled by this expansion.
CREATE TABLE social.account_privacy (
 environment varchar(40) NOT NULL, user_id uuid NOT NULL, id uuid NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 default_audience jsonb NOT NULL DEFAULT '{"kind":"self","circleIds":[]}'::jsonb CHECK(jsonb_typeof(default_audience)='object'),
 allow_circle_member_messages boolean NOT NULL DEFAULT false,
 allow_recipe_requests boolean NOT NULL DEFAULT false,
 analytics_consent boolean NOT NULL DEFAULT false,
 allow_coordination_invites boolean NOT NULL DEFAULT false,
 social_discovery_visible boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(environment,user_id), UNIQUE(environment,id),
 FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id) ON DELETE CASCADE
);
CREATE TABLE social.direct_threads (
 environment varchar(40) NOT NULL, id uuid NOT NULL,
 user_low uuid NOT NULL, user_high uuid NOT NULL CHECK(user_low<user_high),
 version bigint NOT NULL CHECK(version>0), last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence>=0),
 last_message_at timestamptz NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(environment,id), UNIQUE(environment,user_low,user_high),
 FOREIGN KEY(environment,user_low) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,user_high) REFERENCES identity.users(environment,id)
);
CREATE INDEX direct_threads_low ON social.direct_threads(environment,user_low,id);
CREATE INDEX direct_threads_high ON social.direct_threads(environment,user_high,id);
CREATE TABLE social.thread_messages (
 environment varchar(40) NOT NULL, id uuid NOT NULL, thread_id uuid NOT NULL,
 sender_user_id uuid NOT NULL, client_message_id uuid NOT NULL,
 sequence bigint NOT NULL CHECK(sequence>0), text varchar(2000) NOT NULL CHECK(char_length(text)>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(environment,id), UNIQUE(environment,thread_id,sequence), UNIQUE(environment,sender_user_id,client_message_id),
 FOREIGN KEY(environment,thread_id) REFERENCES social.direct_threads(environment,id),
 FOREIGN KEY(environment,sender_user_id) REFERENCES identity.users(environment,id)
);
CREATE TABLE social.thread_read_watermarks (
 environment varchar(40) NOT NULL, thread_id uuid NOT NULL, user_id uuid NOT NULL,
 last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence>=0),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(environment,thread_id,user_id),
 FOREIGN KEY(environment,thread_id) REFERENCES social.direct_threads(environment,id),
 FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id)
);
CREATE INDEX thread_messages_sender_time ON social.thread_messages(environment,sender_user_id,created_at);

CREATE FUNCTION social.initialize_account_privacy() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_privacy_initial$
BEGIN
 INSERT INTO social.account_privacy(environment,user_id,id) VALUES(NEW.environment,NEW.id,gen_random_uuid());
 RETURN NEW;
END;
$feedme_privacy_initial$;
REVOKE ALL ON FUNCTION social.initialize_account_privacy() FROM PUBLIC;
INSERT INTO social.account_privacy(environment,user_id,id) SELECT environment,id,gen_random_uuid() FROM identity.users;
CREATE TRIGGER account_privacy_initial AFTER INSERT ON identity.users FOR EACH ROW EXECUTE FUNCTION social.initialize_account_privacy();

-- FKs deliberately retain shared conversation history and prevent core-erased
-- checkpoint/provider erasure until its retention policy and cleanup are reviewed.
-- Only exact-owned privacy cascades. No shared rows are silently discarded.
CREATE FUNCTION social.guard_direct_conversation_write() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_conversation_write$
DECLARE low_user uuid; high_user uuid; parent social.direct_threads%ROWTYPE;
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Conversation retention requires reviewed cleanup' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='account_privacy' THEN
  low_user:=NEW.user_id; high_user:=NEW.user_id;
  IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.user_id,NEW.id,NEW.created_at) IS DISTINCT FROM ROW(OLD.environment,OLD.user_id,OLD.id,OLD.created_at)
    OR NEW.version<>OLD.version+1) THEN RAISE EXCEPTION 'Privacy identity is immutable' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='direct_threads' THEN
  low_user:=NEW.user_low; high_user:=NEW.user_high;
  IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.id,NEW.user_low,NEW.user_high,NEW.created_at) IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.user_low,OLD.user_high,OLD.created_at)
    OR NEW.version<>OLD.version+1 OR NEW.last_sequence<OLD.last_sequence OR NEW.last_sequence>OLD.last_sequence+1) THEN
   RAISE EXCEPTION 'Thread identity is immutable' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO parent FROM social.direct_threads WHERE environment=NEW.environment AND id=NEW.thread_id FOR SHARE NOWAIT;
  IF NOT FOUND THEN RAISE EXCEPTION 'Thread unavailable' USING ERRCODE='23514'; END IF;
  low_user:=parent.user_low; high_user:=parent.user_high;
  IF TG_TABLE_NAME='thread_messages' THEN
   IF TG_OP<>'INSERT' OR NEW.sender_user_id NOT IN (low_user,high_user) OR NEW.sequence<>parent.last_sequence THEN
    RAISE EXCEPTION 'Message identity is immutable' USING ERRCODE='23514'; END IF;
  ELSIF TG_TABLE_NAME='thread_read_watermarks' THEN
   IF NEW.user_id NOT IN (low_user,high_user) OR NEW.last_sequence>parent.last_sequence OR (TG_OP='UPDATE' AND
     (ROW(NEW.environment,NEW.thread_id,NEW.user_id) IS DISTINCT FROM ROW(OLD.environment,OLD.thread_id,OLD.user_id) OR NEW.last_sequence<OLD.last_sequence)) THEN
    RAISE EXCEPTION 'Read watermark is invalid' USING ERRCODE='23514'; END IF;
  ELSE RAISE EXCEPTION 'Unsupported conversation relation' USING ERRCODE='23514'; END IF;
 END IF;
 PERFORM 1 FROM identity.users WHERE environment=NEW.environment AND id=low_user AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Conversation owner unavailable' USING ERRCODE='23514'; END IF;
 IF high_user<>low_user THEN
  PERFORM 1 FROM identity.users WHERE environment=NEW.environment AND id=high_user AND status='active' FOR SHARE NOWAIT;
  IF NOT FOUND THEN RAISE EXCEPTION 'Conversation peer unavailable' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$feedme_conversation_write$;
REVOKE ALL ON FUNCTION social.guard_direct_conversation_write() FROM PUBLIC;
CREATE TRIGGER privacy_owner_write BEFORE INSERT OR UPDATE ON social.account_privacy FOR EACH ROW EXECUTE FUNCTION social.guard_direct_conversation_write();
CREATE TRIGGER direct_thread_write BEFORE INSERT OR UPDATE OR DELETE ON social.direct_threads FOR EACH ROW EXECUTE FUNCTION social.guard_direct_conversation_write();
CREATE TRIGGER thread_message_write BEFORE INSERT OR UPDATE OR DELETE ON social.thread_messages FOR EACH ROW EXECUTE FUNCTION social.guard_direct_conversation_write();
CREATE TRIGGER thread_watermark_write BEFORE INSERT OR UPDATE OR DELETE ON social.thread_read_watermarks FOR EACH ROW EXECUTE FUNCTION social.guard_direct_conversation_write();
CREATE TRIGGER direct_thread_retained BEFORE TRUNCATE ON social.direct_threads FOR EACH STATEMENT EXECUTE FUNCTION social.guard_direct_conversation_write();
CREATE TRIGGER thread_message_retained BEFORE TRUNCATE ON social.thread_messages FOR EACH STATEMENT EXECUTE FUNCTION social.guard_direct_conversation_write();
CREATE TRIGGER thread_watermark_retained BEFORE TRUNCATE ON social.thread_read_watermarks FOR EACH STATEMENT EXECUTE FUNCTION social.guard_direct_conversation_write();
ALTER TABLE social.account_privacy ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.account_privacy FORCE ROW LEVEL SECURITY;
ALTER TABLE social.direct_threads ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.direct_threads FORCE ROW LEVEL SECURITY;
ALTER TABLE social.thread_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.thread_messages FORCE ROW LEVEL SECURITY;
ALTER TABLE social.thread_read_watermarks ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.thread_read_watermarks FORCE ROW LEVEL SECURITY;
REVOKE ALL ON social.account_privacy,social.direct_threads,social.thread_messages,social.thread_read_watermarks FROM PUBLIC;
