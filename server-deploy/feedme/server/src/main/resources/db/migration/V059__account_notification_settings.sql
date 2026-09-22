-- Private preferences only. No push token, scheduler, delivery grant, or implicit opt-in.
CREATE TABLE profile.notification_settings (
 environment varchar(40) NOT NULL, user_id uuid NOT NULL, id uuid NOT NULL CHECK(id=user_id),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 replies boolean NOT NULL DEFAULT false, reactions boolean NOT NULL DEFAULT false,
 invitations boolean NOT NULL DEFAULT false, remixes boolean NOT NULL DEFAULT false,
 cooking_reminders boolean NOT NULL DEFAULT false,
 quiet_start_local varchar(5), quiet_end_local varchar(5), time_zone varchar(100) NOT NULL DEFAULT 'UTC',
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(environment,user_id), UNIQUE(environment,id),
 FOREIGN KEY(environment,user_id) REFERENCES identity.users(environment,id),
 CHECK((quiet_start_local IS NULL)=(quiet_end_local IS NULL)),
 CHECK(quiet_start_local IS NULL OR (quiet_start_local ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
   AND quiet_end_local ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' AND quiet_start_local<>quiet_end_local)),
 CHECK(isfinite(created_at) AND isfinite(updated_at) AND updated_at>=created_at)
);
ALTER TABLE profile.notification_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE profile.notification_settings FORCE ROW LEVEL SECURITY;
REVOKE ALL ON profile.notification_settings FROM PUBLIC;

CREATE FUNCTION profile.guard_notification_settings() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off
AS $feedme_notification_settings$
BEGIN
 IF TG_OP IN('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Notification preferences require account erasure' USING ERRCODE='23514';
 END IF;
 IF TG_OP='UPDATE' AND (ROW(NEW.environment,NEW.user_id,NEW.id,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.environment,OLD.user_id,OLD.id,OLD.created_at)
   OR OLD.version=9223372036854775807 OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at) THEN
  RAISE EXCEPTION 'Notification preference identity is immutable' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' AND (NEW.version<>1 OR NEW.replies OR NEW.reactions OR NEW.invitations OR NEW.remixes
   OR NEW.cooking_reminders OR NEW.quiet_start_local IS NOT NULL OR NEW.quiet_end_local IS NOT NULL OR NEW.time_zone<>'UTC') THEN
  RAISE EXCEPTION 'Notification preferences begin opted out' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM identity.users WHERE environment=NEW.environment AND id=NEW.user_id AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND THEN RAISE EXCEPTION 'Notification owner unavailable' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM identity.principals WHERE environment=NEW.environment AND user_id=NEW.user_id AND kind='user' AND status='active' FOR SHARE NOWAIT;
 IF NOT FOUND OR EXISTS(SELECT 1 FROM identity.account_deletion_jobs WHERE environment=NEW.environment AND user_id=NEW.user_id) THEN
  RAISE EXCEPTION 'Notification owner unavailable' USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_catalog.pg_timezone_names WHERE name=NEW.time_zone) THEN
  RAISE EXCEPTION 'Notification timezone unavailable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_notification_settings$;
REVOKE ALL ON FUNCTION profile.guard_notification_settings() FROM PUBLIC;
CREATE TRIGGER notification_settings_write BEFORE INSERT OR UPDATE OR DELETE ON profile.notification_settings
 FOR EACH ROW EXECUTE FUNCTION profile.guard_notification_settings();
CREATE TRIGGER notification_settings_retained BEFORE TRUNCATE ON profile.notification_settings
 FOR EACH STATEMENT EXECUTE FUNCTION profile.guard_notification_settings();
