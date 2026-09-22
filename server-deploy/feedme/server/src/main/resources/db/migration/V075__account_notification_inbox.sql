-- New direct-message receipts only. No backfill, push delivery, grant or activation.
CREATE TABLE platform.account_notifications (
 environment varchar(40) NOT NULL, recipient_user_id uuid NOT NULL, id uuid NOT NULL,
 event_id uuid NOT NULL, thread_id uuid NOT NULL, message_id uuid NOT NULL, sender_user_id uuid NOT NULL,
 created_at timestamptz NOT NULL, version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 read_at timestamptz, updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,recipient_user_id,id), UNIQUE(environment,id),
 UNIQUE(environment,recipient_user_id,event_id), UNIQUE(environment,recipient_user_id,message_id),
 FOREIGN KEY(environment,recipient_user_id) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,sender_user_id) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,thread_id) REFERENCES social.direct_threads(environment,id),
 FOREIGN KEY(environment,message_id) REFERENCES social.thread_messages(environment,id),
 FOREIGN KEY(event_id) REFERENCES platform.outbox(event_id),
 CHECK(recipient_user_id<>sender_user_id),
 CHECK(isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at),
 CHECK((read_at IS NULL)=(version=1)),
 CHECK(read_at IS NULL OR (isfinite(read_at) AND read_at>=created_at AND read_at<=updated_at))
);
CREATE INDEX account_notifications_page ON platform.account_notifications(environment,recipient_user_id,created_at DESC,id DESC);
CREATE TABLE platform.notification_read_watermarks (
 environment varchar(40) NOT NULL, user_id uuid NOT NULL,
 through_created_at timestamptz, read_at timestamptz,
 version bigint NOT NULL DEFAULT 0 CHECK(version BETWEEN 0 AND 9223372036854775805), updated_at timestamptz NOT NULL,
 PRIMARY KEY(environment,user_id),
 FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id),
 CHECK((through_created_at IS NULL)=(read_at IS NULL)),
 CHECK(isfinite(updated_at)),
 CHECK(through_created_at IS NULL OR (isfinite(through_created_at) AND isfinite(read_at)
   AND through_created_at<=read_at AND read_at<=updated_at))
);
ALTER TABLE platform.account_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.account_notifications FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.notification_read_watermarks ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.notification_read_watermarks FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform.account_notifications,platform.notification_read_watermarks FROM PUBLIC;

CREATE FUNCTION platform.guard_notification_inbox() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_notification_inbox$
DECLARE owner_id uuid; source_message social.thread_messages%ROWTYPE;
 source_thread social.direct_threads%ROWTYPE; watermark platform.notification_read_watermarks%ROWTYPE;
BEGIN
 IF current_setting('session_replication_role')<>'origin' OR current_setting('transaction_isolation')<>'read committed' THEN
  RAISE EXCEPTION 'Notification transaction unavailable' USING ERRCODE='23514'; END IF;
 -- Only owner-private read metadata participates in the existing accepted-job purge.
 -- Actual message/event receipts retain the shared-history hold.
 IF TG_OP='DELETE' AND TG_TABLE_NAME='notification_read_watermarks' THEN
  IF identity.account_erasure_delete_allowed(TG_RELID,OLD.environment,OLD.user_id,NULL) THEN RETURN OLD; END IF;
 END IF;
 IF TG_OP IN('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Notification retention requires reviewed cleanup' USING ERRCODE='23514'; END IF;
 IF NEW.updated_at>clock_timestamp() THEN RAISE EXCEPTION 'Notification clock unavailable' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='account_notifications' THEN
  owner_id:=NEW.recipient_user_id;
  IF TG_OP='UPDATE' THEN
   IF (to_jsonb(NEW)-ARRAY['read_at','version','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['read_at','version','updated_at'])
     OR OLD.read_at IS NOT NULL OR NEW.read_at IS NULL OR OLD.version=9223372036854775807
     OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION 'Notification identity is immutable' USING ERRCODE='23514'; END IF;
  ELSE
   IF NEW.version<>1 OR NEW.read_at IS NOT NULL OR NEW.updated_at<>NEW.created_at THEN
    RAISE EXCEPTION 'Notification begins unread' USING ERRCODE='23514'; END IF;
   SELECT * INTO source_message FROM ONLY social.thread_messages WHERE environment=NEW.environment AND id=NEW.message_id
     AND xmin::text::bigint=mod(txid_current(),4294967296) FOR SHARE NOWAIT;
   IF NOT FOUND OR source_message.thread_id<>NEW.thread_id OR source_message.sender_user_id<>NEW.sender_user_id
     OR source_message.created_at>NEW.created_at THEN RAISE EXCEPTION 'New message source unavailable' USING ERRCODE='23514'; END IF;
   SELECT * INTO source_thread FROM ONLY social.direct_threads WHERE environment=NEW.environment AND id=NEW.thread_id FOR SHARE NOWAIT;
   IF NOT FOUND OR NEW.recipient_user_id NOT IN(source_thread.user_low,source_thread.user_high)
     OR NEW.sender_user_id NOT IN(source_thread.user_low,source_thread.user_high) THEN
    RAISE EXCEPTION 'Notification recipient unavailable' USING ERRCODE='23514'; END IF;
   PERFORM 1 FROM ONLY platform.outbox e WHERE e.event_id=NEW.event_id AND e.xmin::text::bigint=mod(txid_current(),4294967296)
     AND e.event_type='conversations.message.created.v1' AND e.schema_version=1 AND e.aggregate_type='message'
     AND e.aggregate_id=NEW.message_id AND e.aggregate_version=1 AND e.producer='conversations'
     AND e.owner_environment=NEW.environment AND e.owner_kind='account' AND e.owner_id=NEW.sender_user_id
     AND e.payload=jsonb_build_object('threadId',NEW.thread_id::text,'messageId',NEW.message_id::text,'senderUserId',NEW.sender_user_id::text)
     AND e.occurred_at<=NEW.created_at;
   IF NOT FOUND THEN RAISE EXCEPTION 'Notification event unavailable' USING ERRCODE='23514'; END IF;
   PERFORM 1 FROM ONLY profile.notification_settings WHERE environment=NEW.environment AND user_id=owner_id AND replies FOR SHARE NOWAIT;
   IF NOT FOUND THEN RAISE EXCEPTION 'Notification reply opt-in unavailable' USING ERRCODE='23514'; END IF;
   SELECT * INTO watermark FROM ONLY platform.notification_read_watermarks WHERE environment=NEW.environment AND user_id=owner_id FOR SHARE NOWAIT;
   IF NOT FOUND OR (watermark.through_created_at IS NOT NULL AND NEW.created_at<=watermark.through_created_at) THEN
    RAISE EXCEPTION 'Notification chronology unavailable' USING ERRCODE='23514'; END IF;
  END IF;
 ELSIF TG_TABLE_NAME='notification_read_watermarks' THEN
  owner_id:=NEW.user_id;
  IF TG_OP='INSERT' AND (NEW.version<>0 OR NEW.through_created_at IS NOT NULL OR NEW.read_at IS NOT NULL) THEN
   RAISE EXCEPTION 'Read watermark begins empty' USING ERRCODE='23514'; END IF;
  IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.user_id) IS DISTINCT FROM ROW(OLD.environment,OLD.user_id)
    OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.through_created_at IS NULL
    OR (OLD.through_created_at IS NOT NULL AND NEW.through_created_at<=OLD.through_created_at)
    OR NEW.updated_at<OLD.updated_at OR NEW.read_at IS NULL OR NEW.read_at<OLD.read_at
    OR NEW.through_created_at>clock_timestamp()) THEN
   RAISE EXCEPTION 'Read watermark must advance' USING ERRCODE='23514'; END IF;
 ELSE RAISE EXCEPTION 'Unsupported notification relation' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM ONLY identity.users WHERE environment=NEW.environment AND id=owner_id AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Notification owner unavailable' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM ONLY identity.principals WHERE environment=NEW.environment AND user_id=owner_id AND kind='user' AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND OR EXISTS(SELECT 1 FROM identity.account_deletion_jobs WHERE environment=NEW.environment AND user_id=owner_id) THEN
  RAISE EXCEPTION 'Notification owner unavailable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$feedme_notification_inbox$;
REVOKE ALL ON FUNCTION platform.guard_notification_inbox() FROM PUBLIC;
CREATE TRIGGER account_notification_write BEFORE INSERT OR UPDATE OR DELETE ON platform.account_notifications
 FOR EACH ROW EXECUTE FUNCTION platform.guard_notification_inbox();
CREATE TRIGGER account_notification_retained BEFORE TRUNCATE ON platform.account_notifications
 FOR EACH STATEMENT EXECUTE FUNCTION platform.guard_notification_inbox();
CREATE TRIGGER notification_watermark_write BEFORE INSERT OR UPDATE OR DELETE ON platform.notification_read_watermarks
 FOR EACH ROW EXECUTE FUNCTION platform.guard_notification_inbox();
CREATE TRIGGER notification_watermark_retained BEFORE TRUNCATE ON platform.notification_read_watermarks
 FOR EACH STATEMENT EXECUTE FUNCTION platform.guard_notification_inbox();
