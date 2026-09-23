-- Persistent role-scoped feature-flag inventory and an audited emergency kill.
-- The serving role cannot create flags, enable a flag, choose a variant, raise or
-- partially change rollout, erase history, or alter staff authority. Broader
-- changes remain assigned to the separately reviewed proposal/approval workflow.
CREATE TABLE platform.feature_flags (
 environment varchar(40) NOT NULL,
 flag_key varchar(101) NOT NULL CHECK(flag_key ~ '^[a-z][a-z0-9_.-]{1,100}$'),
 enabled boolean NOT NULL,
 rollout_percent numeric(7,4) NOT NULL CHECK(rollout_percent BETWEEN 0 AND 100),
 variant varchar(256) NULL CHECK(variant IS NULL OR variant !~ '[[:cntrl:]]'),
 revision bigint NOT NULL CHECK(revision>0),
 last_action_id uuid NULL,
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 updated_at timestamptz NOT NULL CHECK(isfinite(updated_at) AND updated_at>=created_at),
 PRIMARY KEY(environment,flag_key),
 CHECK(enabled OR rollout_percent=0),
 CHECK((revision=1 AND last_action_id IS NULL) OR (revision>1 AND last_action_id IS NOT NULL))
);

CREATE TABLE platform.feature_flag_actions (
 environment varchar(40) NOT NULL,
 id uuid NOT NULL,
 flag_key varchar(101) NOT NULL,
 flag_revision bigint NOT NULL CHECK(flag_revision>1),
 actor_id uuid NOT NULL,
 provider_session_id uuid NOT NULL,
 authority_revision char(64) NOT NULL CHECK(authority_revision ~ '^[0-9a-f]{64}$'),
 operation_id varchar(32) NOT NULL CHECK(operation_id='adminUpdateFlag'),
 command_key uuid NOT NULL,
 request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 request_text text NOT NULL CHECK(octet_length(request_text)<=4096 AND jsonb_typeof(request_text::jsonb)='object'),
 if_match varchar(24) NOT NULL,
 reason text NOT NULL CHECK(length(reason) BETWEEN 10 AND 1000 AND reason !~ '[[:cntrl:]]'),
 response_text text NOT NULL CHECK(octet_length(response_text)<=4096 AND jsonb_typeof(response_text::jsonb)='object'),
 response_sha256 char(64) NOT NULL CHECK(response_sha256=encode(sha256(convert_to(response_text,'UTF8')),'hex')),
 event_id uuid NOT NULL UNIQUE,
 trace_id varchar(128) NOT NULL CHECK(length(trace_id)>0 AND trace_id !~ '[[:cntrl:]]'),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 PRIMARY KEY(environment,id),
 UNIQUE(environment,flag_key,flag_revision),
 UNIQUE(environment,actor_id,operation_id,command_key),
 FOREIGN KEY(environment,flag_key) REFERENCES platform.feature_flags(environment,flag_key),
 FOREIGN KEY(environment,actor_id) REFERENCES staff.moderator_enrollments(environment,actor_id),
 CHECK(if_match='"'||(flag_revision-1)::text||'"')
);

ALTER TABLE platform.feature_flags ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.feature_flags FORCE ROW LEVEL SECURITY;
ALTER TABLE platform.feature_flag_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.feature_flag_actions FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE platform.feature_flags,platform.feature_flag_actions FROM PUBLIC;

CREATE FUNCTION platform.guard_feature_flag() RETURNS trigger LANGUAGE plpgsql AS $feedme_feature_flag_guard$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Feature flags require reviewed retained history' USING ERRCODE='23514';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.revision<>1 OR NEW.last_action_id IS NOT NULL OR NEW.created_at<>NEW.updated_at THEN
   RAISE EXCEPTION 'Feature flag must begin at revision one' USING ERRCODE='23514'; END IF;
 ELSE
  IF ROW(NEW.environment,NEW.flag_key,NEW.variant,NEW.created_at)
      IS DISTINCT FROM ROW(OLD.environment,OLD.flag_key,OLD.variant,OLD.created_at)
    OR NOT OLD.enabled OR NEW.enabled OR NEW.rollout_percent<>0
    OR OLD.revision=9223372036854775807 OR NEW.revision<>OLD.revision+1
    OR NEW.updated_at<OLD.updated_at OR NEW.updated_at>clock_timestamp()
    OR NEW.last_action_id IS NULL OR NEW.last_action_id IS NOT DISTINCT FROM OLD.last_action_id THEN
   RAISE EXCEPTION 'Direct feature-flag change must be an exact complete kill' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$feedme_feature_flag_guard$;

CREATE FUNCTION platform.protect_feature_flag_action() RETURNS trigger LANGUAGE plpgsql AS $feedme_feature_flag_action_guard$
BEGIN
 RAISE EXCEPTION 'Feature-flag action history is immutable' USING ERRCODE='23514';
END;
$feedme_feature_flag_action_guard$;

CREATE FUNCTION platform.require_feature_flag_action() RETURNS trigger LANGUAGE plpgsql AS $feedme_feature_flag_checkpoint$
DECLARE action platform.feature_flag_actions%ROWTYPE; flag platform.feature_flags%ROWTYPE;
 receipt platform.idempotency%ROWTYPE; event platform.outbox%ROWTYPE;
 expected_request jsonb; expected_response jsonb;
BEGIN
 IF TG_TABLE_NAME='feature_flags' THEN
  SELECT * INTO STRICT action FROM platform.feature_flag_actions
   WHERE environment=NEW.environment AND id=NEW.last_action_id FOR SHARE NOWAIT;
  flag := NEW;
 ELSE
  action := NEW;
  SELECT * INTO STRICT flag FROM platform.feature_flags
   WHERE environment=NEW.environment AND flag_key=NEW.flag_key FOR SHARE NOWAIT;
 END IF;
 SELECT * INTO STRICT receipt FROM platform.idempotency
  WHERE principal_scope=action.environment||':staff:'||action.actor_id::text
    AND operation_id=action.operation_id AND key=action.command_key FOR SHARE NOWAIT;
 SELECT * INTO STRICT event FROM platform.outbox WHERE event_id=action.event_id FOR SHARE NOWAIT;
 expected_request:=jsonb_build_object('enabled',false,'rolloutPercent',0,'reason',action.reason);
 expected_response:=jsonb_strip_nulls(jsonb_build_object('key',flag.flag_key,'enabled',false,
   'variant',flag.variant,'revision',flag.revision));
 IF flag.last_action_id<>action.id OR flag.flag_key<>action.flag_key
   OR flag.revision<>action.flag_revision OR flag.enabled OR flag.rollout_percent<>0
   OR flag.updated_at<>action.created_at
   OR action.request_text::jsonb IS DISTINCT FROM expected_request
   OR action.response_text::jsonb IS DISTINCT FROM expected_response
   OR receipt.request_hash IS DISTINCT FROM action.request_sha256 OR receipt.state<>'completed'
   OR receipt.response_code IS DISTINCT FROM 200 OR receipt.response_etag IS DISTINCT FROM NULL
   OR receipt.response_json IS DISTINCT FROM expected_response
   OR ROW(event.event_type,event.schema_version,event.aggregate_type,event.aggregate_id,
          event.aggregate_version,event.producer,event.causation_id)
      IS DISTINCT FROM ROW('platform.feature_flag.disabled.v1',1,'featureFlagAction'::varchar,
          action.id,flag.revision,'platform'::varchar,action.command_key)
   OR event.payload IS DISTINCT FROM jsonb_build_object('flagKey',flag.flag_key,
          'revision',flag.revision,'enabled',false) THEN
  RAISE EXCEPTION 'Feature-flag kill requires exact action, receipt and event' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END;
$feedme_feature_flag_checkpoint$;

CREATE TRIGGER feature_flag_guard BEFORE INSERT OR UPDATE OR DELETE ON platform.feature_flags
 FOR EACH ROW EXECUTE FUNCTION platform.guard_feature_flag();
CREATE TRIGGER feature_flag_no_truncate BEFORE TRUNCATE ON platform.feature_flags
 FOR EACH STATEMENT EXECUTE FUNCTION platform.guard_feature_flag();
CREATE TRIGGER feature_flag_action_immutable BEFORE UPDATE OR DELETE ON platform.feature_flag_actions
 FOR EACH ROW EXECUTE FUNCTION platform.protect_feature_flag_action();
CREATE TRIGGER feature_flag_action_no_truncate BEFORE TRUNCATE ON platform.feature_flag_actions
 FOR EACH STATEMENT EXECUTE FUNCTION platform.protect_feature_flag_action();
CREATE CONSTRAINT TRIGGER feature_flag_transition_checkpoint AFTER UPDATE ON platform.feature_flags
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.require_feature_flag_action();
CREATE CONSTRAINT TRIGGER feature_flag_action_checkpoint AFTER INSERT ON platform.feature_flag_actions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.require_feature_flag_action();

REVOKE ALL ON FUNCTION platform.guard_feature_flag(),platform.protect_feature_flag_action(),
 platform.require_feature_flag_action() FROM PUBLIC;

-- Existing moderator access auditing also records list and receipt reads. The
-- observed JSON remains bounded and contains only flag keys/revisions.
ALTER TABLE safety.moderation_access_audit DROP CONSTRAINT moderation_access_audit_purpose_check;
ALTER TABLE safety.moderation_access_audit ADD CONSTRAINT moderation_access_audit_purpose_check
 CHECK(purpose IN ('queue-list','case-review','claim-receipt','dismiss-receipt','remove-receipt',
   'audit-list','health-read','flags-list','flag-disable-receipt'));
