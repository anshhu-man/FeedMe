-- A dedicated local consumer of real reaction events. No fake direct messages,
-- push delivery, global outbox acknowledgement or grants. Only bounded-age recent
-- originals can deliver; older originals are consumed without a notification.
CREATE TABLE platform.account_reaction_notifications (
 environment varchar(40) NOT NULL, recipient_user_id uuid NOT NULL, id uuid NOT NULL,
 event_id uuid NOT NULL, actor_user_id uuid NOT NULL, post_id uuid NOT NULL,
 reaction_id uuid NOT NULL, reaction_version bigint NOT NULL CHECK(reaction_version>0),
 created_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 read_at timestamptz, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,recipient_user_id,id), UNIQUE(environment,id),
 UNIQUE(environment,recipient_user_id,event_id), UNIQUE(environment,reaction_id,reaction_version),
 FOREIGN KEY(environment,recipient_user_id,post_id) REFERENCES social.posts(environment,owner_user_id,id),
 FOREIGN KEY(environment,actor_user_id) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,reaction_id) REFERENCES social.post_reactions(environment,id),
 FOREIGN KEY(event_id) REFERENCES platform.outbox(event_id),
 CHECK(recipient_user_id<>actor_user_id),
 CHECK(isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at),
 CHECK((read_at IS NULL)=(version=1)),
 CHECK(read_at IS NULL OR (isfinite(read_at) AND read_at>=created_at AND read_at<=updated_at))
);
CREATE INDEX account_reaction_notifications_page ON platform.account_reaction_notifications(environment,recipient_user_id,created_at DESC,id DESC);
CREATE INDEX outbox_reaction_consumer_candidates ON platform.outbox(owner_environment,occurred_at,event_id)
 WHERE schema_version=1 AND event_type IN('social.reaction.changed.v1','social.reaction.removed.v1');
ALTER TABLE platform.account_reaction_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.account_reaction_notifications FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.account_reaction_notifications FROM PUBLIC;

CREATE FUNCTION platform.guard_reaction_notification() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_reaction_notification$
DECLARE source_reaction social.post_reactions%ROWTYPE; watermark platform.notification_read_watermarks%ROWTYPE;
BEGIN
 IF current_setting('session_replication_role')<>'origin' OR current_setting('transaction_isolation')<>'read committed' THEN
  RAISE EXCEPTION 'Reaction notification transaction unavailable' USING ERRCODE='23514'; END IF;
 IF TG_OP IN('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Reaction notification retention requires reviewed cleanup' USING ERRCODE='23514'; END IF;
 IF NEW.updated_at>clock_timestamp() THEN RAISE EXCEPTION 'Reaction notification clock unavailable' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF (to_jsonb(NEW)-ARRAY['read_at','version','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['read_at','version','updated_at'])
    OR OLD.read_at IS NOT NULL OR NEW.read_at IS NULL OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
   RAISE EXCEPTION 'Reaction notification identity is immutable' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.version<>1 OR NEW.read_at IS NOT NULL OR NEW.updated_at<>NEW.created_at THEN
   RAISE EXCEPTION 'Reaction notification begins unread' USING ERRCODE='23514'; END IF;
  SELECT * INTO source_reaction FROM ONLY social.post_reactions WHERE environment=NEW.environment AND id=NEW.reaction_id FOR SHARE NOWAIT;
  IF NOT FOUND OR source_reaction.version<>NEW.reaction_version OR NOT source_reaction.active
    OR source_reaction.last_operation_id<>'setReaction' OR source_reaction.actor_user_id<>NEW.actor_user_id
    OR source_reaction.post_owner_user_id<>NEW.recipient_user_id OR source_reaction.post_id<>NEW.post_id
    OR source_reaction.updated_at>NEW.created_at THEN RAISE EXCEPTION 'Reaction notification source unavailable' USING ERRCODE='23514'; END IF;
  PERFORM 1 FROM ONLY platform.outbox e WHERE e.event_id=NEW.event_id
    AND e.event_type='social.reaction.changed.v1' AND e.schema_version=1 AND e.producer='social'
    AND e.aggregate_type='reaction' AND e.aggregate_id=NEW.reaction_id AND e.aggregate_version=NEW.reaction_version
    AND e.owner_environment=NEW.environment AND e.owner_kind='account' AND e.owner_id=NEW.actor_user_id
    AND e.causation_id=source_reaction.last_command_key AND e.correlation_id=source_reaction.last_command_key::text
    AND e.payload=jsonb_build_object('reactionId',NEW.reaction_id::text,'postId',NEW.post_id::text,
      'postOwnerUserId',NEW.recipient_user_id::text,'actorUserId',NEW.actor_user_id::text,'kind',source_reaction.kind,'active',true)
    AND e.occurred_at>=source_reaction.updated_at AND e.occurred_at<=NEW.created_at;
  IF NOT FOUND THEN RAISE EXCEPTION 'Reaction notification event unavailable' USING ERRCODE='23514'; END IF;
  PERFORM 1 FROM ONLY platform.consumer_inbox WHERE consumer_name='feedme.reaction-inbox.v1.'||NEW.environment
    AND event_id=NEW.event_id AND xmin::text::bigint=mod(txid_current(),4294967296);
  IF NOT FOUND THEN RAISE EXCEPTION 'Reaction notification consumer unavailable' USING ERRCODE='23514'; END IF;
  PERFORM 1 FROM ONLY profile.notification_settings WHERE environment=NEW.environment AND user_id=NEW.recipient_user_id AND reactions FOR SHARE NOWAIT;
  IF NOT FOUND THEN RAISE EXCEPTION 'Reaction notification opt-in unavailable' USING ERRCODE='23514'; END IF;
  SELECT * INTO watermark FROM ONLY platform.notification_read_watermarks WHERE environment=NEW.environment AND user_id=NEW.recipient_user_id FOR SHARE NOWAIT;
  IF NOT FOUND OR (watermark.through_created_at IS NOT NULL AND NEW.created_at<=watermark.through_created_at) THEN
   RAISE EXCEPTION 'Reaction notification chronology unavailable' USING ERRCODE='23514'; END IF;
 END IF;
 PERFORM 1 FROM ONLY identity.users WHERE environment=NEW.environment AND id=NEW.recipient_user_id AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Reaction notification owner unavailable' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM ONLY identity.principals WHERE environment=NEW.environment AND user_id=NEW.recipient_user_id AND kind='user' AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND OR EXISTS(SELECT 1 FROM identity.account_deletion_jobs WHERE environment=NEW.environment AND user_id=NEW.recipient_user_id) THEN
  RAISE EXCEPTION 'Reaction notification owner unavailable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$feedme_reaction_notification$;
REVOKE ALL ON FUNCTION platform.guard_reaction_notification() FROM PUBLIC;
CREATE TRIGGER account_reaction_notification_write BEFORE INSERT OR UPDATE OR DELETE ON platform.account_reaction_notifications
 FOR EACH ROW EXECUTE FUNCTION platform.guard_reaction_notification();
CREATE TRIGGER account_reaction_notification_retained BEFORE TRUNCATE ON platform.account_reaction_notifications
 FOR EACH STATEMENT EXECUTE FUNCTION platform.guard_reaction_notification();
