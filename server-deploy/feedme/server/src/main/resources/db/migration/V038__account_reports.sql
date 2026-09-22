-- Local reporting foundation only. No staff authority, encryption service, retention purge,
-- target removal, runtime grants, moderation provider or historical complaint backfill.
CREATE SCHEMA safety;
REVOKE ALL ON SCHEMA safety FROM PUBLIC;

CREATE TABLE safety.moderation_cases (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 id uuid NOT NULL, report_id uuid NOT NULL,
 target_type varchar(16) NOT NULL CHECK(target_type IN ('post','message','user','shortcut')), target_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), priority varchar(12) NOT NULL CHECK(priority IN ('urgent','high','normal')),
 status varchar(12) NOT NULL CHECK(status IN ('open','assigned','resolved','appealed')),
 assignee_staff_id uuid NULL, action varchar(12) NOT NULL CHECK(action IN ('none','hide','remove','warn','suspend','restore')),
 reason_code text NULL CHECK(octet_length(reason_code)<=1024),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)), updated_at timestamptz NOT NULL CHECK(isfinite(updated_at)),
 PRIMARY KEY(environment,id), UNIQUE(environment,report_id), CHECK(updated_at>=created_at)
);
CREATE TABLE safety.reports (
 environment varchar(40) NOT NULL CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
 reporter_user_id uuid NOT NULL, id uuid NOT NULL, case_id uuid NOT NULL,
 command_key uuid NOT NULL, request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 target_type varchar(16) NOT NULL CHECK(target_type IN ('post','message','user','shortcut')), target_id uuid NOT NULL,
 reason varchar(16) NOT NULL CHECK(reason IN ('harassment','unsafeFood','privacy','spam','other')),
 description text NULL CHECK(length(description)<=1000),
 status varchar(12) NOT NULL CHECK(status IN ('received','triaged','resolved','dismissed')),
 version bigint NOT NULL CHECK(version>0), creation_event_id uuid NOT NULL UNIQUE,
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)), updated_at timestamptz NOT NULL CHECK(isfinite(updated_at)),
 PRIMARY KEY(environment,reporter_user_id,id), UNIQUE(environment,id), UNIQUE(environment,reporter_user_id,command_key),
 UNIQUE(environment,case_id), CHECK(updated_at>=created_at),
 FOREIGN KEY(environment,reporter_user_id) REFERENCES identity.users(environment,id),
 FOREIGN KEY(environment,case_id) REFERENCES safety.moderation_cases(environment,id)
);
ALTER TABLE safety.moderation_cases ADD CONSTRAINT moderation_case_original_report
 FOREIGN KEY(environment,report_id) REFERENCES safety.reports(environment,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE safety.report_evidence (
 environment varchar(40) NOT NULL, reporter_user_id uuid NOT NULL, report_id uuid NOT NULL,
 target_type varchar(16) NOT NULL CHECK(target_type IN ('post','message','user','shortcut')), target_id uuid NOT NULL,
 target_owner_id uuid NOT NULL, target_version bigint NOT NULL CHECK(target_version>0),
 evidence_text text NOT NULL CHECK(octet_length(evidence_text)<=524288 AND jsonb_typeof(evidence_text::jsonb)='object'),
 evidence_sha256 char(64) NOT NULL CHECK(evidence_sha256=encode(sha256(convert_to(evidence_text,'UTF8')),'hex')),
 valid_until timestamptz NULL CHECK(valid_until IS NULL OR isfinite(valid_until)),
 captured_at timestamptz NOT NULL CHECK(isfinite(captured_at)),
 PRIMARY KEY(environment,reporter_user_id,report_id),
 FOREIGN KEY(environment,reporter_user_id,report_id) REFERENCES safety.reports(environment,reporter_user_id,id),
 CHECK(valid_until IS NULL OR valid_until>captured_at)
);
-- Target IDs/owners intentionally have no foreign key: authorized evidence survives target
-- removal and never causes a foreign-account lock while taking the reporter's command.
CREATE INDEX owned_reports ON safety.reports(environment,reporter_user_id,created_at DESC,id);
CREATE INDEX moderation_case_queue ON safety.moderation_cases(environment,status,priority,created_at,id);
ALTER TABLE safety.reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.reports FORCE ROW LEVEL SECURITY;
ALTER TABLE safety.report_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.report_evidence FORCE ROW LEVEL SECURITY;
ALTER TABLE safety.moderation_cases ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.moderation_cases FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE safety.reports,safety.report_evidence,safety.moderation_cases FROM PUBLIC;

CREATE FUNCTION safety.protect_report_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN
  RAISE EXCEPTION 'Report history requires separate reviewed retention work' USING ERRCODE='23514';
 END IF;
 IF TG_TABLE_NAME='reports' THEN
  IF ROW(NEW.environment,NEW.reporter_user_id,NEW.id,NEW.case_id,NEW.command_key,NEW.request_sha256,
       NEW.target_type,NEW.target_id,NEW.reason,NEW.description,NEW.creation_event_id,NEW.created_at)
     IS DISTINCT FROM ROW(OLD.environment,OLD.reporter_user_id,OLD.id,OLD.case_id,OLD.command_key,OLD.request_sha256,
       OLD.target_type,OLD.target_id,OLD.reason,OLD.description,OLD.creation_event_id,OLD.created_at)
     OR NEW.version<>OLD.version+1 OR NEW.status=OLD.status OR NEW.updated_at<OLD.updated_at THEN
   RAISE EXCEPTION 'Report status requires original identity and next version' USING ERRCODE='23514';
  END IF;
 ELSIF TG_TABLE_NAME='moderation_cases' THEN
  IF ROW(NEW.environment,NEW.id,NEW.report_id,NEW.target_type,NEW.target_id,NEW.created_at)
     IS DISTINCT FROM ROW(OLD.environment,OLD.id,OLD.report_id,OLD.target_type,OLD.target_id,OLD.created_at)
     OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
   RAISE EXCEPTION 'Moderation case requires original identity and next version' USING ERRCODE='23514';
  END IF;
 ELSE
  RAISE EXCEPTION 'Original report evidence is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER report_identity BEFORE UPDATE OR DELETE ON safety.reports
 FOR EACH ROW EXECUTE FUNCTION safety.protect_report_history();
CREATE TRIGGER report_no_truncate BEFORE TRUNCATE ON safety.reports
 FOR EACH STATEMENT EXECUTE FUNCTION safety.protect_report_history();
CREATE TRIGGER report_evidence_identity BEFORE UPDATE OR DELETE ON safety.report_evidence
 FOR EACH ROW EXECUTE FUNCTION safety.protect_report_history();
CREATE TRIGGER report_evidence_no_truncate BEFORE TRUNCATE ON safety.report_evidence
 FOR EACH STATEMENT EXECUTE FUNCTION safety.protect_report_history();
CREATE TRIGGER moderation_case_identity BEFORE UPDATE OR DELETE ON safety.moderation_cases
 FOR EACH ROW EXECUTE FUNCTION safety.protect_report_history();
CREATE TRIGGER moderation_case_no_truncate BEFORE TRUNCATE ON safety.moderation_cases
 FOR EACH STATEMENT EXECUTE FUNCTION safety.protect_report_history();

CREATE FUNCTION safety.bind_report_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original safety.reports%ROWTYPE; evidence jsonb;
BEGIN
 SELECT * INTO STRICT original FROM safety.reports
  WHERE environment=NEW.environment AND reporter_user_id=NEW.reporter_user_id AND id=NEW.report_id FOR SHARE NOWAIT;
 evidence := NEW.evidence_text::jsonb;
 IF ROW(original.target_type,original.target_id,original.created_at,original.status,original.version)
      IS DISTINCT FROM ROW(NEW.target_type,NEW.target_id,NEW.captured_at,'received'::varchar,1::bigint)
    OR (evidence-ARRAY['formatVersion','targetType','targetId','targetOwnerId','version','material','validUntil'])<>'{}'::jsonb
    OR NOT evidence ?& ARRAY['formatVersion','targetType','targetId','targetOwnerId','version','material','validUntil']
    OR evidence->'formatVersion' IS DISTINCT FROM '1'::jsonb
    OR evidence->'targetType' IS DISTINCT FROM to_jsonb(NEW.target_type)
    OR evidence->'targetId' IS DISTINCT FROM to_jsonb(NEW.target_id::text)
    OR evidence->'targetOwnerId' IS DISTINCT FROM to_jsonb(NEW.target_owner_id::text)
    OR evidence->'version' IS DISTINCT FROM to_jsonb(NEW.target_version)
    OR jsonb_typeof(evidence->'material') IS DISTINCT FROM 'object'
    OR octet_length((evidence->'material')::text)>524288
    OR (evidence->'validUntil'<>'null'::jsonb AND jsonb_typeof(evidence->'validUntil') IS DISTINCT FROM 'string')
    OR (evidence->>'validUntil')::timestamptz IS DISTINCT FROM NEW.valid_until THEN
  RAISE EXCEPTION 'Report evidence requires exact original capture' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER report_evidence_binding BEFORE INSERT ON safety.report_evidence
 FOR EACH ROW EXECUTE FUNCTION safety.bind_report_evidence();

CREATE FUNCTION safety.require_report_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE evidence safety.report_evidence%ROWTYPE; item safety.moderation_cases%ROWTYPE;
 receipt platform.idempotency%ROWTYPE; event platform.outbox%ROWTYPE; expected jsonb; at timestamptz;
BEGIN
 SELECT * INTO STRICT evidence FROM safety.report_evidence
  WHERE environment=NEW.environment AND reporter_user_id=NEW.reporter_user_id AND report_id=NEW.id FOR SHARE NOWAIT;
 SELECT * INTO STRICT item FROM safety.moderation_cases WHERE environment=NEW.environment AND id=NEW.case_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT receipt FROM platform.idempotency
  WHERE principal_scope=NEW.environment||':account:'||NEW.reporter_user_id::text
    AND operation_id='createReport' AND key=NEW.command_key FOR SHARE NOWAIT;
 SELECT * INTO STRICT event FROM platform.outbox WHERE event_id=NEW.creation_event_id FOR SHARE NOWAIT;
 expected := jsonb_build_object('id',NEW.id,'version',1,'targetType',NEW.target_type,'targetId',NEW.target_id,
   'reason',NEW.reason,'status','received');
 IF NEW.description IS NOT NULL THEN expected := expected||jsonb_build_object('description',NEW.description); END IF;
 IF NEW.status<>'received' OR NEW.version<>1 OR NEW.updated_at<>NEW.created_at
    OR ROW(item.report_id,item.target_type,item.target_id,item.version,item.priority,item.status,item.action,item.created_at,item.updated_at)
       IS DISTINCT FROM ROW(NEW.id,NEW.target_type,NEW.target_id,1::bigint,'normal'::varchar,'open'::varchar,'none'::varchar,NEW.created_at,NEW.created_at)
    OR item.assignee_staff_id IS NOT NULL OR item.reason_code IS NOT NULL
    OR receipt.request_hash IS DISTINCT FROM NEW.request_sha256 OR receipt.state<>'completed'
    OR receipt.response_code IS DISTINCT FROM 201 OR receipt.response_etag IS DISTINCT FROM '"1"'
    OR (receipt.response_json-ARRAY['createdAt','updatedAt']) IS DISTINCT FROM expected
    OR (receipt.response_json->>'createdAt')::timestamptz IS DISTINCT FROM NEW.created_at
    OR (receipt.response_json->>'updatedAt')::timestamptz IS DISTINCT FROM NEW.created_at
    OR ROW(event.event_type,event.schema_version,event.aggregate_type,event.aggregate_id,event.aggregate_version,event.producer,event.causation_id)
       IS DISTINCT FROM ROW('safety.report.created.v1'::varchar,1,'report'::varchar,NEW.id,1::bigint,'safety'::varchar,NEW.command_key)
    OR event.payload IS DISTINCT FROM jsonb_build_object('reportId',NEW.id,'caseId',NEW.case_id,'priority','normal') THEN
  RAISE EXCEPTION 'Report requires atomic original receipt, case, evidence and event' USING ERRCODE='23514';
 END IF;
 at := clock_timestamp();
 IF evidence.captured_at<>NEW.created_at OR evidence.captured_at>at
    OR (evidence.evidence_text::jsonb->>'validUntil')::timestamptz<=at THEN
  RAISE EXCEPTION 'Report target expired before capture commit' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER report_checkpoint AFTER INSERT ON safety.reports
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION safety.require_report_checkpoint();
