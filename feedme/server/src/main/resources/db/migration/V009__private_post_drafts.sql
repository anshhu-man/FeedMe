-- Unpublished owner-only PostDrafts. Upload roots exist separately and never imply a draft/post.
CREATE TABLE platform.post_draft_heads (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>0),
 PRIMARY KEY(environment,owner_user_id)
);
CREATE TABLE platform.post_drafts (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, id uuid NOT NULL,
 client_draft_id uuid NOT NULL, draft_generation bigint NOT NULL CHECK(draft_generation>0),
 version bigint NOT NULL CHECK(version>0), status varchar(16) NOT NULL CHECK(status IN ('draft','published','discarded','expired')),
 content jsonb NOT NULL CHECK(jsonb_typeof(content)='object' AND octet_length(content::text)<=262144),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 published_post_id uuid NULL, deletion_key uuid NULL,
 PRIMARY KEY(environment,owner_user_id,id), UNIQUE(environment,owner_user_id,client_draft_id),
 FOREIGN KEY(environment,owner_user_id,client_draft_id) REFERENCES platform.media_draft_lifecycles(environment,owner_user_id,client_draft_id),
 CHECK(updated_at>=created_at AND expires_at>created_at),
 CHECK((status='published')=(published_post_id IS NOT NULL)), CHECK((status='discarded')=(deletion_key IS NOT NULL)),
 CHECK((status='discarded')=(content='{}'::jsonb))
);
CREATE INDEX editable_post_drafts ON platform.post_drafts(environment,owner_user_id,id) WHERE status='draft';
CREATE INDEX expiring_post_drafts ON platform.post_drafts(expires_at) WHERE status='draft';
-- Exact cleanup correlation for the original discard, including media already individually deleted.
CREATE TABLE platform.post_draft_discard_media (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, draft_id uuid NOT NULL,
 media_id uuid NOT NULL, media_version bigint NOT NULL CHECK(media_version>0),
 cleanup_evidence jsonb NOT NULL CHECK(jsonb_typeof(cleanup_evidence)='object' AND octet_length(cleanup_evidence::text)<=65536),
 PRIMARY KEY(environment,owner_user_id,draft_id,media_id),
 FOREIGN KEY(environment,owner_user_id,draft_id) REFERENCES platform.post_drafts(environment,owner_user_id,id),
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id)
);
CREATE FUNCTION platform.protect_post_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.environment,NEW.owner_user_id,NEW.id,NEW.client_draft_id,NEW.draft_generation,NEW.created_at)
     IS DISTINCT FROM ROW(OLD.environment,OLD.owner_user_id,OLD.id,OLD.client_draft_id,OLD.draft_generation,OLD.created_at)
    OR NOT (OLD.status='draft' OR (OLD.status='expired' AND NEW.status='discarded')) OR NEW.version<>OLD.version+1
    OR NEW.updated_at<OLD.updated_at
    OR (NEW.status<>'draft' AND NEW.expires_at<>OLD.expires_at)
    OR (NEW.status NOT IN ('draft','discarded') AND NEW.content<>OLD.content) THEN
   RAISE EXCEPTION 'Post draft transition requires exact original owner and version' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER post_draft_immutable BEFORE UPDATE ON platform.post_drafts FOR EACH ROW EXECUTE FUNCTION platform.protect_post_draft();
