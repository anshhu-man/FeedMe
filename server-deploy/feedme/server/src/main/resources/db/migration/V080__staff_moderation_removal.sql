-- Local removal is a current delivery denial plus durable exact cleanup intent.
-- No physical erasure, restore, worker/provider activation or grant is implied.
ALTER TABLE safety.moderation_actions ADD COLUMN target_version bigint NULL;
DO $moderation_constraints$
DECLARE selected record; removed integer:=0;
BEGIN
 FOR selected IN SELECT conname FROM pg_catalog.pg_constraint
  WHERE conrelid='safety.moderation_actions'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%action%' LOOP
  EXECUTE format('ALTER TABLE safety.moderation_actions DROP CONSTRAINT %I',selected.conname);
  removed:=removed+1;
 END LOOP;
 IF removed<>2 THEN RAISE EXCEPTION 'Unexpected previous moderation action constraints'; END IF;
END;
$moderation_constraints$;
ALTER TABLE safety.moderation_actions ADD CONSTRAINT moderation_action_kind CHECK(action IN ('claim','dismiss','remove'));
ALTER TABLE safety.moderation_actions ADD CONSTRAINT moderation_action_original CHECK(
 (action='claim' AND operation_id='adminClaimReport' AND case_version=2 AND if_match IS NULL AND target_version IS NULL)
 OR (action='dismiss' AND operation_id='adminActOnReport' AND case_version=3 AND if_match='"2"' AND target_version IS NULL)
 OR (action='remove' AND operation_id='adminActOnReport' AND case_version=3 AND if_match='"2"' AND target_version>0));
ALTER TABLE safety.moderation_actions ADD CONSTRAINT moderation_action_target CHECK((action='remove')=(target_version IS NOT NULL));
ALTER TABLE safety.moderation_access_audit DROP CONSTRAINT moderation_access_audit_purpose_check;
ALTER TABLE safety.moderation_access_audit ADD CONSTRAINT moderation_access_audit_purpose_check
 CHECK(purpose IN ('queue-list','case-review','claim-receipt','dismiss-receipt','remove-receipt'));

CREATE TABLE safety.moderation_removals (
 environment varchar(40) NOT NULL, id uuid NOT NULL, case_id uuid NOT NULL, report_id uuid NOT NULL, action_id uuid NOT NULL,
 target_type varchar(12) NOT NULL CHECK(target_type IN ('post','message')), target_id uuid NOT NULL, target_owner_id uuid NOT NULL,
 target_version bigint NOT NULL CHECK(target_version>0 AND (target_type<>'message' OR target_version=1)),
 target_sha256 char(64) NOT NULL CHECK(target_sha256 ~ '^[0-9a-f]{64}$'),
 state varchar(12) NOT NULL DEFAULT 'pending' CHECK(state='pending'),
 created_at timestamptz NOT NULL CHECK(isfinite(created_at)),
 PRIMARY KEY(environment,id), UNIQUE(environment,target_type,target_id), UNIQUE(environment,action_id),
 FOREIGN KEY(environment,case_id) REFERENCES safety.moderation_cases(environment,id),
 FOREIGN KEY(environment,report_id) REFERENCES safety.reports(environment,id),
 FOREIGN KEY(environment,action_id) REFERENCES safety.moderation_actions(environment,id),
 FOREIGN KEY(environment,target_owner_id) REFERENCES identity.users(environment,id)
);
ALTER TABLE safety.moderation_removals ENABLE ROW LEVEL SECURITY;
ALTER TABLE safety.moderation_removals FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE safety.moderation_removals FROM PUBLIC;
CREATE TRIGGER moderation_removal_immutable BEFORE UPDATE OR DELETE ON safety.moderation_removals
 FOR EACH ROW EXECUTE FUNCTION safety.protect_moderation_audit();
CREATE TRIGGER moderation_removal_no_truncate BEFORE TRUNCATE ON safety.moderation_removals
 FOR EACH STATEMENT EXECUTE FUNCTION safety.protect_moderation_audit();

CREATE FUNCTION safety.require_moderation_removal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE decision safety.moderation_actions%ROWTYPE; item safety.moderation_cases%ROWTYPE; evidence safety.report_evidence%ROWTYPE;
 post social.posts%ROWTYPE; message social.thread_messages%ROWTYPE; digest text;
BEGIN
 SELECT * INTO STRICT decision FROM safety.moderation_actions WHERE environment=NEW.environment AND id=NEW.action_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT item FROM safety.moderation_cases WHERE environment=NEW.environment AND id=NEW.case_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT evidence FROM safety.report_evidence WHERE environment=NEW.environment AND report_id=NEW.report_id FOR SHARE NOWAIT;
 IF decision.action<>'remove' OR decision.case_version<>3 OR decision.target_version<>NEW.target_version
  OR decision.case_id<>NEW.case_id OR decision.report_id<>NEW.report_id OR decision.created_at<>NEW.created_at
  OR item.report_id<>NEW.report_id OR item.action<>'remove' OR item.status<>'resolved' OR item.version<>3
  OR item.target_type<>NEW.target_type OR item.target_id<>NEW.target_id OR item.assignee_staff_id IS DISTINCT FROM decision.actor_id
  OR evidence.target_type<>NEW.target_type OR evidence.target_id<>NEW.target_id OR evidence.target_owner_id<>NEW.target_owner_id
  OR evidence.target_version<>NEW.target_version THEN
  RAISE EXCEPTION 'Removal requires exact resolved case and original evidence' USING ERRCODE='23514';
 END IF;
 IF NEW.target_type='post' THEN
  SELECT * INTO STRICT post FROM social.posts WHERE environment=NEW.environment AND id=NEW.target_id FOR SHARE NOWAIT;
  IF post.owner_user_id<>NEW.target_owner_id OR post.version<>NEW.target_version OR post.status<>'published' OR post.content IS NULL THEN
   RAISE EXCEPTION 'Removal requires current exact post' USING ERRCODE='23514';
  END IF;
  digest:=encode(sha256(convert_to(post.content::text,'UTF8')),'hex');
 ELSE
  SELECT * INTO STRICT message FROM social.thread_messages WHERE environment=NEW.environment AND id=NEW.target_id FOR SHARE NOWAIT;
  IF message.sender_user_id<>NEW.target_owner_id OR NEW.target_version<>1 THEN
   RAISE EXCEPTION 'Removal requires current exact message' USING ERRCODE='23514';
  END IF;
  digest:=encode(sha256(convert_to(jsonb_build_object('id',message.id,'threadId',message.thread_id,'ownerId',message.sender_user_id,'version',1,
    'kind',message.kind,'text',message.text,'createdAt',to_char(message.created_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
    'recipeRequestId',message.recipe_request_id,'recipeVersionId',message.recipe_version_id)::text,'UTF8')),'hex');
 END IF;
 IF digest IS DISTINCT FROM NEW.target_sha256 THEN
  RAISE EXCEPTION 'Removal target changed' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER moderation_removal_checkpoint AFTER INSERT ON safety.moderation_removals
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION safety.require_moderation_removal();
REVOKE ALL ON FUNCTION safety.require_moderation_removal() FROM PUBLIC;

CREATE OR REPLACE FUNCTION safety.require_moderation_action() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item safety.moderation_cases%ROWTYPE; report safety.reports%ROWTYPE;
 receipt platform.idempotency%ROWTYPE; event platform.outbox%ROWTYPE; expected jsonb; request jsonb;
BEGIN
 SELECT * INTO STRICT item FROM safety.moderation_cases WHERE environment=NEW.environment AND id=NEW.case_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT report FROM safety.reports WHERE environment=NEW.environment AND id=NEW.report_id FOR SHARE NOWAIT;
 SELECT * INTO STRICT receipt FROM platform.idempotency
  WHERE principal_scope=NEW.environment||':staff:'||NEW.actor_id::text AND operation_id=NEW.operation_id AND key=NEW.command_key FOR SHARE NOWAIT;
 SELECT * INTO STRICT event FROM platform.outbox WHERE event_id=NEW.event_id FOR SHARE NOWAIT;
 request := jsonb_build_object('reasonCode',NEW.reason_code,'notes',NEW.notes);
 IF NEW.action IN ('dismiss','remove') THEN request := request||jsonb_build_object('action',NEW.action); END IF;
 IF NEW.action='remove' THEN request := request||jsonb_build_object('targetVersion',NEW.target_version); END IF;
 expected := jsonb_build_object('id',item.id,'version',item.version,'createdAt',item.created_at,'updatedAt',item.updated_at,
  'reportIds',jsonb_build_array(item.report_id),'targetType',item.target_type,'targetId',item.target_id,
  'priority',item.priority,'status',item.status,'assigneeId',item.assignee_staff_id,'action',item.action,'reasonCode',NEW.reason_code);
 IF item.report_id<>report.id OR report.case_id<>item.id OR item.target_type<>report.target_type OR item.target_id<>report.target_id
  OR item.version<>NEW.case_version OR report.version<>NEW.report_version OR item.assignee_staff_id IS DISTINCT FROM NEW.actor_id
  OR item.action<>(CASE WHEN NEW.action='remove' THEN 'remove' ELSE 'none' END) OR item.reason_code IS DISTINCT FROM NEW.reason_code
  OR item.updated_at<>NEW.created_at OR report.updated_at<>NEW.created_at
  OR (NEW.action='claim' AND (item.status<>'assigned' OR report.status<>'triaged'))
  OR (NEW.action='dismiss' AND (item.status<>'resolved' OR report.status<>'dismissed'))
  OR (NEW.action='remove' AND (item.status<>'resolved' OR report.status<>'resolved' OR item.target_type NOT IN ('post','message')
     OR NOT EXISTS(SELECT 1 FROM safety.moderation_removals x WHERE x.environment=NEW.environment AND x.action_id=NEW.id
        AND x.case_id=item.id AND x.report_id=report.id AND x.target_type=item.target_type AND x.target_id=item.target_id
        AND x.target_version=NEW.target_version AND x.created_at=NEW.created_at AND x.state='pending')))
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

CREATE OR REPLACE FUNCTION safety.require_moderation_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item safety.moderation_actions%ROWTYPE; selected_case uuid;
BEGIN
 IF TG_TABLE_NAME='reports' THEN selected_case := NEW.case_id;
 ELSE selected_case := NEW.id; END IF;
 SELECT * INTO STRICT item FROM safety.moderation_actions
  WHERE environment=NEW.environment AND case_id=selected_case AND case_version=NEW.version FOR SHARE NOWAIT;
 IF TG_TABLE_NAME='reports' THEN
  IF NEW.id<>item.report_id OR NEW.version<>OLD.version+1 OR NEW.updated_at<>item.created_at
   OR NOT ((OLD.status='received' AND NEW.status='triaged' AND item.action='claim')
        OR (OLD.status='triaged' AND NEW.status='dismissed' AND item.action='dismiss')
        OR (OLD.status='triaged' AND NEW.status='resolved' AND item.action='remove')) THEN
   RAISE EXCEPTION 'Report transition requires exact moderation action' USING ERRCODE='23514';
  END IF;
 ELSE
  IF NEW.report_id<>item.report_id OR NEW.version<>OLD.version+1 OR NEW.updated_at<>item.created_at
   OR NEW.priority<>OLD.priority OR NEW.action<>(CASE WHEN item.action='remove' THEN 'remove' ELSE 'none' END) OR OLD.action<>'none'
   OR NEW.assignee_staff_id IS DISTINCT FROM item.actor_id OR NEW.reason_code IS DISTINCT FROM item.reason_code
   OR NOT ((OLD.status='open' AND OLD.assignee_staff_id IS NULL AND NEW.status='assigned' AND item.action='claim')
        OR (OLD.status='assigned' AND OLD.assignee_staff_id=item.actor_id AND NEW.status='resolved' AND item.action IN ('dismiss','remove'))) THEN
   RAISE EXCEPTION 'Case transition requires exact moderation action' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NULL;
END; $$;

