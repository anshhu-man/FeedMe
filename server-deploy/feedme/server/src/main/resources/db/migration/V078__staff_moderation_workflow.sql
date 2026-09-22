-- Local opt-in moderation workflow. No moderator enrollment, runtime grant, content
-- removal, media capability, retention purge, backfill or publication activation.
CREATE TABLE safety.moderation_access_audit (
 environment varchar(40) NOT NULL,
 id uuid NOT NULL, actor_id uuid NOT NULL, provider_session_id uuid NOT NULL,
 authority_revision char(64) NOT NULL CHECK(authority_revision ~ '^[0-9a-f]{64}$'),
 purpose varchar(24) NOT NULL CHECK(purpose IN ('queue-list','case-review','claim-receipt','dismiss-receipt')),
 observed_cases jsonb NOT NULL CHECK(jsonb_typeof(observed_cases)='array' AND jsonb_array_length(observed_cases)<=50 AND octet_length(observed_cases::text)<=16384),
 trace_id varchar(128) NOT NULL CHECK(length(trace_id)>0 AND trace_id !~ '[[:cntrl:]]'),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 PRIMARY KEY(environment,id),
 FOREIGN KEY(environment,actor_id) REFERENCES staff.moderator_enrollments(environment,actor_id)
);
CREATE TABLE safety.moderation_actions (
 environment varchar(40) NOT NULL, id uuid NOT NULL, case_id uuid NOT NULL, report_id uuid NOT NULL,
 case_version bigint NOT NULL CHECK(case_version IN (2,3)), report_version bigint NOT NULL CHECK(report_version=case_version),
 actor_id uuid NOT NULL, provider_session_id uuid NOT NULL,
 authority_revision char(64) NOT NULL CHECK(authority_revision ~ '^[0-9a-f]{64}$'),
 action varchar(8) NOT NULL CHECK(action IN ('claim','dismiss')),
 reason_code varchar(100) NOT NULL CHECK(length(reason_code)>0 AND reason_code !~ '[[:cntrl:]]'),
 notes text NOT NULL CHECK(length(notes) BETWEEN 1 AND 2000),
 operation_id varchar(32) NOT NULL CHECK(operation_id IN ('adminClaimReport','adminActOnReport')),
 command_key uuid NOT NULL, request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 request_text text NOT NULL CHECK(octet_length(request_text)<=16384 AND jsonb_typeof(request_text::jsonb)='object'),
 if_match varchar(24) NULL,
 response_text text NOT NULL CHECK(octet_length(response_text)<=16384 AND jsonb_typeof(response_text::jsonb)='object'),
 response_sha256 char(64) NOT NULL CHECK(response_sha256=encode(sha256(convert_to(response_text,'UTF8')),'hex')),
 event_id uuid NOT NULL UNIQUE,
 trace_id varchar(128) NOT NULL CHECK(length(trace_id)>0 AND trace_id !~ '[[:cntrl:]]'),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 PRIMARY KEY(environment,id), UNIQUE(environment,case_id,case_version),
 UNIQUE(environment,actor_id,operation_id,command_key),
 FOREIGN KEY(environment,case_id) REFERENCES safety.moderation_cases(environment,id),
 FOREIGN KEY(environment,report_id) REFERENCES safety.reports(environment,id),
 FOREIGN KEY(environment,actor_id) REFERENCES staff.moderator_enrollments(environment,actor_id),
 CHECK((action='claim' AND operation_id='adminClaimReport' AND case_version=2 AND if_match IS NULL)
    OR (action='dismiss' AND operation_id='adminActOnReport' AND case_version=3 AND if_match='"2"'))
);
ALTER TABLE safety.moderation_access_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.moderation_access_audit FORCE ROW LEVEL SECURITY;
ALTER TABLE safety.moderation_actions ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.moderation_actions FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE safety.moderation_access_audit,safety.moderation_actions FROM PUBLIC;

CREATE FUNCTION safety.protect_moderation_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'Moderation audit is immutable and requires reviewed retention' USING ERRCODE='23514';
END; $$;
CREATE TRIGGER moderation_access_immutable BEFORE UPDATE OR DELETE ON safety.moderation_access_audit
 FOR EACH ROW EXECUTE FUNCTION safety.protect_moderation_audit();
CREATE TRIGGER moderation_access_no_truncate BEFORE TRUNCATE ON safety.moderation_access_audit
 FOR EACH STATEMENT EXECUTE FUNCTION safety.protect_moderation_audit();
CREATE TRIGGER moderation_action_immutable BEFORE UPDATE OR DELETE ON safety.moderation_actions
 FOR EACH ROW EXECUTE FUNCTION safety.protect_moderation_audit();
CREATE TRIGGER moderation_action_no_truncate BEFORE TRUNCATE ON safety.moderation_actions
 FOR EACH STATEMENT EXECUTE FUNCTION safety.protect_moderation_audit();

-- All three changed records and the durable receipt/event must agree at commit.
-- This intentionally supports only the initial claim -> no-action dismissal lane.
CREATE FUNCTION safety.require_moderation_action() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item safety.moderation_cases%ROWTYPE; report safety.reports%ROWTYPE;
 receipt platform.idempotency%ROWTYPE; event platform.outbox%ROWTYPE; expected jsonb; request jsonb;
BEGIN
 SELECT * INTO STRICT item FROM safety.moderation_cases WHERE environment=NEW.environment AND id=NEW.case_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT report FROM safety.reports WHERE environment=NEW.environment AND id=NEW.report_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT receipt FROM platform.idempotency
  WHERE principal_scope=NEW.environment||':staff:'||NEW.actor_id::text AND operation_id=NEW.operation_id AND key=NEW.command_key FOR SHARE NOWAIT;
 SELECT * INTO STRICT event FROM platform.outbox WHERE event_id=NEW.event_id FOR SHARE NOWAIT;
 request := jsonb_build_object('reasonCode',NEW.reason_code,'notes',NEW.notes);
 IF NEW.action='dismiss' THEN request := request||jsonb_build_object('action','dismiss'); END IF;
 expected := jsonb_build_object('id',item.id,'version',item.version,'createdAt',item.created_at,'updatedAt',item.updated_at,
  'reportIds',jsonb_build_array(item.report_id),'targetType',item.target_type,'targetId',item.target_id,
  'priority',item.priority,'status',item.status,'assigneeId',item.assignee_staff_id,'action','none','reasonCode',NEW.reason_code);
 IF item.report_id<>report.id OR report.case_id<>item.id OR item.target_type<>report.target_type OR item.target_id<>report.target_id
  OR item.version<>NEW.case_version OR report.version<>NEW.report_version OR item.assignee_staff_id IS DISTINCT FROM NEW.actor_id
  OR item.action<>'none' OR item.reason_code IS DISTINCT FROM NEW.reason_code
  OR item.updated_at<>NEW.created_at OR report.updated_at<>NEW.created_at
  OR (NEW.action='claim' AND (item.status<>'assigned' OR report.status<>'triaged'))
  OR (NEW.action='dismiss' AND (item.status<>'resolved' OR report.status<>'dismissed'))
  OR NEW.request_text::jsonb IS DISTINCT FROM request
  OR (NEW.response_text::jsonb-ARRAY['createdAt','updatedAt']) IS DISTINCT FROM (expected-ARRAY['createdAt','updatedAt'])
  OR (NEW.response_text::jsonb->>'createdAt')::timestamptz IS DISTINCT FROM item.created_at
  OR (NEW.response_text::jsonb->>'updatedAt')::timestamptz IS DISTINCT FROM item.updated_at
  OR receipt.request_hash IS DISTINCT FROM NEW.request_sha256 OR receipt.state<>'completed'
  OR receipt.response_code IS DISTINCT FROM 200 OR receipt.response_etag IS DISTINCT FROM '"'||NEW.case_version::text||'"'
  OR receipt.response_json IS DISTINCT FROM NEW.response_text::jsonb
  OR ROW(event.event_type,event.schema_version,event.aggregate_type,event.aggregate_id,event.aggregate_version,event.producer,event.causation_id)
     IS DISTINCT FROM ROW('safety.moderation.'||NEW.action||'.v1',1,'moderationCase'::varchar,item.id,item.version,'safety'::varchar,NEW.command_key)
  OR event.payload IS DISTINCT FROM jsonb_build_object('caseId',item.id,'reportId',report.id,'caseVersion',item.version,'action',NEW.action) THEN
  RAISE EXCEPTION 'Moderation requires exact atomic case, report, audit, command and event' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER moderation_action_checkpoint AFTER INSERT ON safety.moderation_actions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION safety.require_moderation_action();

CREATE FUNCTION safety.require_moderation_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item safety.moderation_actions%ROWTYPE; selected_case uuid;
BEGIN
 IF TG_TABLE_NAME='reports' THEN selected_case := NEW.case_id;
 ELSE selected_case := NEW.id; END IF;
 SELECT * INTO STRICT item FROM safety.moderation_actions
  WHERE environment=NEW.environment AND case_id=selected_case AND case_version=NEW.version FOR SHARE NOWAIT;
 IF TG_TABLE_NAME='reports' THEN
  IF NEW.id<>item.report_id OR NEW.version<>OLD.version+1 OR NEW.updated_at<>item.created_at
   OR NOT ((OLD.status='received' AND NEW.status='triaged' AND item.action='claim')
        OR (OLD.status='triaged' AND NEW.status='dismissed' AND item.action='dismiss')) THEN
   RAISE EXCEPTION 'Report transition requires exact moderation action' USING ERRCODE='23514';
  END IF;
 ELSE
  IF NEW.report_id<>item.report_id OR NEW.version<>OLD.version+1 OR NEW.updated_at<>item.created_at
   OR NEW.priority<>OLD.priority OR NEW.action<>'none' OR OLD.action<>'none'
   OR NEW.assignee_staff_id IS DISTINCT FROM item.actor_id OR NEW.reason_code IS DISTINCT FROM item.reason_code
   OR NOT ((OLD.status='open' AND OLD.assignee_staff_id IS NULL AND NEW.status='assigned' AND item.action='claim')
        OR (OLD.status='assigned' AND OLD.assignee_staff_id=item.actor_id AND NEW.status='resolved' AND item.action='dismiss')) THEN
   RAISE EXCEPTION 'Case transition requires exact moderation action' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER report_moderation_checkpoint AFTER UPDATE ON safety.reports
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION safety.require_moderation_transition();
CREATE CONSTRAINT TRIGGER case_moderation_checkpoint AFTER UPDATE ON safety.moderation_cases
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION safety.require_moderation_transition();
REVOKE ALL ON FUNCTION safety.protect_moderation_audit(),safety.require_moderation_action(),safety.require_moderation_transition() FROM PUBLIC;
