-- Explicit original Publish only. No feed, accepting identity/provider or implicit saved draft.
CREATE TABLE social.posts (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), status varchar(12) NOT NULL CHECK(status IN ('published','hidden','deleted')),
 content jsonb NULL CHECK(jsonb_typeof(content)='object' AND octet_length(content::text)<=262144),
 published_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,owner_user_id,id),
 CHECK(expires_at=published_at+interval '24 hours' AND updated_at>=published_at),
 CHECK((status='deleted')=(content IS NULL)),
 CHECK(content IS NULL OR (content->>'id'=id::text AND (content->>'version')::bigint=version AND content->>'status'=status))
);
CREATE INDEX published_posts ON social.posts(published_at DESC,id DESC) WHERE status='published';
CREATE TABLE social.post_publications (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, client_draft_id uuid NOT NULL,
 draft_generation bigint NOT NULL CHECK(draft_generation>0), post_id uuid NOT NULL,
 command_key uuid NOT NULL, request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 response_sha256 char(64) NOT NULL CHECK(response_sha256 ~ '^[0-9a-f]{64}$'),
 recipe_sha256 char(64) NULL CHECK(recipe_sha256 ~ '^[0-9a-f]{64}$'),
 draft_id uuid NULL, reviewed_draft_version bigint NULL CHECK(reviewed_draft_version>0),
 published_at timestamptz NOT NULL,
 PRIMARY KEY(environment,owner_user_id,client_draft_id), UNIQUE(environment,owner_user_id,post_id),
 UNIQUE(environment,owner_user_id,draft_id), UNIQUE(environment,owner_user_id,command_key),
 CHECK((draft_id IS NULL)=(reviewed_draft_version IS NULL)),
 FOREIGN KEY(environment,owner_user_id,client_draft_id) REFERENCES platform.media_draft_lifecycles(environment,owner_user_id,client_draft_id),
 FOREIGN KEY(environment,owner_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id),
 FOREIGN KEY(environment,owner_user_id,draft_id) REFERENCES platform.post_drafts(environment,owner_user_id,id)
);
CREATE TABLE social.post_media (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, post_id uuid NOT NULL,
 media_id uuid NOT NULL, position integer NOT NULL CHECK(position>=0), media_version bigint NOT NULL CHECK(media_version>0),
 derivative_set jsonb NOT NULL CHECK(jsonb_typeof(derivative_set)='object' AND octet_length(derivative_set::text)<=65536),
 PRIMARY KEY(environment,owner_user_id,post_id,media_id), UNIQUE(environment,owner_user_id,media_id),
 UNIQUE(environment,owner_user_id,post_id,position),
 FOREIGN KEY(environment,owner_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id),
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id)
);
CREATE TABLE social.post_audiences (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, post_id uuid NOT NULL,
 circle_id uuid NOT NULL, author_membership_generation bigint NOT NULL CHECK(author_membership_generation>0),
 PRIMARY KEY(environment,owner_user_id,post_id,circle_id),
 FOREIGN KEY(environment,owner_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id)
);
CREATE TABLE social.post_attachments (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, post_id uuid NOT NULL,
 attachment jsonb NOT NULL CHECK(jsonb_typeof(attachment)='object' AND octet_length(attachment::text)<=65536),
 recipe_snapshot jsonb NOT NULL CHECK(jsonb_typeof(recipe_snapshot)='object' AND octet_length(recipe_snapshot::text)<=262144),
 recipe_sha256 char(64) NOT NULL CHECK(recipe_sha256 ~ '^[0-9a-f]{64}$'), accepted_at timestamptz NOT NULL,
 PRIMARY KEY(environment,owner_user_id,post_id),
 FOREIGN KEY(environment,owner_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id)
);
CREATE TABLE social.recipe_save_policies (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, post_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), allow_future_saves boolean NOT NULL,
 recipe_sha256 char(64) NULL CHECK(recipe_sha256 ~ '^[0-9a-f]{64}$'),
 disclosure_version text NOT NULL CHECK(octet_length(disclosure_version)<=65536), accepted_at timestamptz NOT NULL,
 PRIMARY KEY(environment,owner_user_id,post_id),
 CHECK(NOT allow_future_saves OR recipe_sha256 IS NOT NULL),
 FOREIGN KEY(environment,owner_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id)
);
-- Exact unused-upload deletion lineage, never a claim that external cleanup has completed.
CREATE TABLE social.post_publication_discard_media (
 environment varchar(40) NOT NULL, owner_user_id uuid NOT NULL, post_id uuid NOT NULL,
 media_id uuid NOT NULL, media_version bigint NOT NULL CHECK(media_version>0),
 cleanup_evidence jsonb NOT NULL CHECK(jsonb_typeof(cleanup_evidence)='object' AND octet_length(cleanup_evidence::text)<=65536),
 PRIMARY KEY(environment,owner_user_id,post_id,media_id),
 FOREIGN KEY(environment,owner_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id),
 FOREIGN KEY(environment,owner_user_id,media_id) REFERENCES platform.media_assets(environment,owner_user_id,id)
);
CREATE FUNCTION social.protect_publication_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Original publication identity cannot be changed or recycled' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER original_publication_identity BEFORE UPDATE OR DELETE ON social.post_publications FOR EACH ROW EXECUTE FUNCTION social.protect_publication_identity();
CREATE TRIGGER original_publication_media BEFORE UPDATE OR DELETE ON social.post_media FOR EACH ROW EXECUTE FUNCTION social.protect_publication_identity();
CREATE TRIGGER original_publication_cleanup BEFORE UPDATE OR DELETE ON social.post_publication_discard_media FOR EACH ROW EXECUTE FUNCTION social.protect_publication_identity();
CREATE TRIGGER original_publication_attachment BEFORE UPDATE OR DELETE ON social.post_attachments FOR EACH ROW EXECUTE FUNCTION social.protect_publication_identity();
CREATE FUNCTION social.protect_post_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.environment,NEW.owner_user_id,NEW.id,NEW.published_at,NEW.expires_at)
    IS DISTINCT FROM ROW(OLD.environment,OLD.owner_user_id,OLD.id,OLD.published_at,OLD.expires_at)
    OR OLD.status='deleted' OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
   RAISE EXCEPTION 'Post transition requires original identity and next version' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER post_identity BEFORE UPDATE ON social.posts FOR EACH ROW EXECUTE FUNCTION social.protect_post_identity();
