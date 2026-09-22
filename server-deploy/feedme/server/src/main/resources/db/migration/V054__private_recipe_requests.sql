-- Local expansion only. No role grant, notification delivery, post attachment or copy grant.
CREATE TABLE social.recipe_requests (
 environment varchar(40) NOT NULL, id uuid NOT NULL, requester_user_id uuid NOT NULL,
 author_user_id uuid NOT NULL CHECK(author_user_id<>requester_user_id), post_id uuid NOT NULL, thread_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), status varchar(16) NOT NULL CHECK(status IN('pending','fulfilled','declined','unavailable')),
 expires_at timestamptz NOT NULL, recipe_version_id uuid NULL, close_reason varchar(16) NULL,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,id), UNIQUE(environment,id,thread_id),
 CHECK(expires_at>created_at AND updated_at>=created_at),
 CHECK((status='fulfilled')=(recipe_version_id IS NOT NULL)),
 CHECK((status='pending')=(close_reason IS NULL)),
 CHECK(close_reason IS NULL OR close_reason IN('fulfilled','declined','cancelled','expired')),
 FOREIGN KEY(environment,requester_user_id) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,author_user_id) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,author_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id),
 FOREIGN KEY(environment,thread_id) REFERENCES social.direct_threads(environment,id)
);
CREATE UNIQUE INDEX pending_recipe_request ON social.recipe_requests(environment,requester_user_id,post_id) WHERE status='pending';
CREATE INDEX recipe_request_sender_time ON social.recipe_requests(environment,requester_user_id,created_at);
ALTER TABLE social.recipe_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE social.recipe_requests FORCE ROW LEVEL SECURITY;
REVOKE ALL ON social.recipe_requests FROM PUBLIC;

ALTER TABLE social.thread_messages ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'text' CHECK(kind IN('text','recipeRequest','recipeCard','system'));
ALTER TABLE social.thread_messages ADD COLUMN recipe_request_id uuid NULL;
ALTER TABLE social.thread_messages ADD COLUMN recipe_version_id uuid NULL;
ALTER TABLE social.thread_messages DROP CONSTRAINT thread_messages_text_check;
ALTER TABLE social.thread_messages ADD CONSTRAINT thread_message_content CHECK(
 (kind='text' AND char_length(text)>0 AND recipe_request_id IS NULL AND recipe_version_id IS NULL)
 OR (kind IN('recipeRequest','system') AND recipe_request_id IS NOT NULL AND recipe_version_id IS NULL)
 OR (kind='recipeCard' AND recipe_request_id IS NOT NULL AND recipe_version_id IS NOT NULL));
ALTER TABLE social.thread_messages ADD CONSTRAINT thread_message_request FOREIGN KEY(environment,recipe_request_id,thread_id)
 REFERENCES social.recipe_requests(environment,id,thread_id);
CREATE UNIQUE INDEX thread_request_message_kind ON social.thread_messages(environment,recipe_request_id,kind) WHERE recipe_request_id IS NOT NULL;

CREATE FUNCTION social.guard_recipe_request() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_recipe_request_guard$
DECLARE thread social.direct_threads%ROWTYPE;
BEGIN
 IF TG_OP IN('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Shared request history requires reviewed cleanup' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.id,NEW.requester_user_id,NEW.author_user_id,NEW.post_id,NEW.thread_id,NEW.created_at,NEW.expires_at)
   IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.requester_user_id,OLD.author_user_id,OLD.post_id,OLD.thread_id,OLD.created_at,OLD.expires_at)
   OR OLD.status<>'pending' OR NEW.status='pending' OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at) THEN
  RAISE EXCEPTION 'Request requires original pending version' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' AND (NEW.version<>1 OR NEW.status<>'pending') THEN RAISE EXCEPTION 'Request must begin pending' USING ERRCODE='23514'; END IF;
 SELECT * INTO thread FROM social.direct_threads WHERE environment=NEW.environment AND id=NEW.thread_id FOR SHARE NOWAIT;
 IF NOT FOUND OR NEW.requester_user_id NOT IN(thread.user_low,thread.user_high) OR NEW.author_user_id NOT IN(thread.user_low,thread.user_high) THEN
  RAISE EXCEPTION 'Request participants differ from thread' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM identity.users WHERE environment=NEW.environment AND id=NEW.requester_user_id AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Request owner unavailable' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM identity.users WHERE environment=NEW.environment AND id=NEW.author_user_id AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Request author unavailable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$feedme_recipe_request_guard$;
REVOKE ALL ON FUNCTION social.guard_recipe_request() FROM PUBLIC;
CREATE TRIGGER recipe_request_write BEFORE INSERT OR UPDATE OR DELETE ON social.recipe_requests FOR EACH ROW EXECUTE FUNCTION social.guard_recipe_request();
CREATE TRIGGER recipe_request_retained BEFORE TRUNCATE ON social.recipe_requests FOR EACH STATEMENT EXECUTE FUNCTION social.guard_recipe_request();

CREATE FUNCTION social.guard_recipe_request_message() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_recipe_message_guard$
DECLARE request social.recipe_requests%ROWTYPE;
BEGIN
 IF NEW.kind='text' THEN RETURN NEW; END IF;
 SELECT * INTO request FROM social.recipe_requests WHERE environment=NEW.environment AND id=NEW.recipe_request_id AND thread_id=NEW.thread_id FOR SHARE NOWAIT;
 IF NOT FOUND OR (NEW.kind='recipeRequest' AND (NEW.sender_user_id<>request.requester_user_id OR request.status<>'pending'))
   OR (NEW.kind='recipeCard' AND (NEW.sender_user_id<>request.author_user_id OR request.status<>'fulfilled' OR NEW.recipe_version_id IS DISTINCT FROM request.recipe_version_id))
   OR (NEW.kind='system' AND (NEW.sender_user_id<>request.author_user_id OR request.status<>'declined')) THEN
  RAISE EXCEPTION 'Message differs from real request transition' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$feedme_recipe_message_guard$;
REVOKE ALL ON FUNCTION social.guard_recipe_request_message() FROM PUBLIC;
CREATE TRIGGER recipe_request_message BEFORE INSERT ON social.thread_messages FOR EACH ROW EXECUTE FUNCTION social.guard_recipe_request_message();
-- The new shared relation is intentionally NOT whitelisted into the V053 core purge.
-- Reviewed request/history cleanup is a release hold; no false full-erasure completion.
