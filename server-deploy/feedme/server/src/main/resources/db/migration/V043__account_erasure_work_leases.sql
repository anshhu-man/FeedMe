-- Worker-only durable inventory scheduling. This does not erase data, authorize a
-- provider call, mark a job complete, create a runtime grant or start a scheduler.
-- Original V041 acknowledgement/device lineage remains immutable and separate.
CREATE TABLE identity.account_erasure_work (
 environment varchar(40) NOT NULL,
 job_id uuid NOT NULL,
 stage varchar(16) NOT NULL DEFAULT 'inventory' CHECK(stage='inventory'),
 generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),
 lease_token uuid NULL,
 lease_expires_at timestamptz NULL,
 available_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(available_at)),
 last_reason varchar(32) NOT NULL DEFAULT 'none'
   CHECK(last_reason IN ('none','retry','unattributed_events','unknown_receipts','related_data','identity_conflict')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(created_at)),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(updated_at)),
 PRIMARY KEY(environment,job_id),
 FOREIGN KEY(environment,job_id) REFERENCES identity.account_deletion_jobs(environment,id),
 CHECK((lease_token IS NULL)=(lease_expires_at IS NULL)),
 CHECK(lease_expires_at IS NULL OR (generation>0 AND isfinite(lease_expires_at)))
);
ALTER TABLE identity.account_erasure_work ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.account_erasure_work FORCE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE identity.account_erasure_work FROM PUBLIC;
CREATE INDEX account_erasure_work_available ON identity.account_erasure_work(environment,available_at,job_id);

CREATE FUNCTION identity.keep_account_erasure_work_binding() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $feedme_work_binding$
BEGIN
 IF ROW(NEW.environment,NEW.job_id,NEW.created_at) IS DISTINCT FROM ROW(OLD.environment,OLD.job_id,OLD.created_at) THEN
  RAISE EXCEPTION 'Account erasure work binding is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$feedme_work_binding$;
REVOKE ALL ON FUNCTION identity.keep_account_erasure_work_binding() FROM PUBLIC;
CREATE TRIGGER account_erasure_work_binding BEFORE UPDATE ON identity.account_erasure_work
 FOR EACH ROW EXECUTE FUNCTION identity.keep_account_erasure_work_binding();

CREATE FUNCTION identity.claim_account_erasure_work(p_environment text,p_token uuid,p_lease_seconds integer)
RETURNS TABLE(job_id uuid,user_id uuid,principal_id uuid,provider_issuer text,provider_subject uuid,
 generation bigint,lease_expires_at timestamptz,last_reason text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_work_claim$
DECLARE accepted identity.account_deletion_jobs%ROWTYPE; current_work identity.account_erasure_work%ROWTYPE;
        at_time timestamptz;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$' OR p_token IS NULL
   OR p_lease_seconds IS NULL OR p_lease_seconds NOT BETWEEN 1 AND 300 THEN
  RAISE EXCEPTION 'Invalid account erasure lease input' USING ERRCODE='23514';
 END IF;
 -- Lock the immutable accepted job before materializing its first work row. This
 -- avoids an uncommitted-insert race and gives all worker calls one lock order.
 SELECT j.* INTO accepted FROM identity.account_deletion_jobs j
 LEFT JOIN identity.account_erasure_work w ON w.environment=j.environment AND w.job_id=j.id
 WHERE j.environment=p_environment AND (w.job_id IS NULL OR
   (w.stage='inventory' AND w.generation<9223372036854775807 AND w.available_at<=clock_timestamp()
    AND (w.lease_expires_at IS NULL OR w.lease_expires_at<=clock_timestamp())))
 ORDER BY COALESCE(w.available_at,j.accepted_at),j.accepted_at,j.id
 LIMIT 1 FOR UPDATE OF j SKIP LOCKED;
 IF NOT FOUND THEN RETURN; END IF;
 INSERT INTO identity.account_erasure_work(environment,job_id) VALUES(accepted.environment,accepted.id)
 ON CONFLICT ON CONSTRAINT account_erasure_work_pkey DO NOTHING;
 SELECT w.* INTO STRICT current_work FROM identity.account_erasure_work w
 WHERE w.environment=accepted.environment AND w.job_id=accepted.id FOR UPDATE;
 at_time:=clock_timestamp();
 IF current_work.stage<>'inventory' OR current_work.generation=9223372036854775807
   OR current_work.available_at>at_time OR current_work.lease_expires_at>at_time THEN RETURN; END IF;
 UPDATE identity.account_erasure_work w
 SET generation=w.generation+1,lease_token=p_token,
     lease_expires_at=at_time+p_lease_seconds*interval '1 second',updated_at=at_time
 WHERE w.environment=accepted.environment AND w.job_id=accepted.id
 RETURNING w.* INTO STRICT current_work;
 RETURN QUERY SELECT accepted.id,accepted.user_id,accepted.principal_id,accepted.provider_issuer::text,
   accepted.provider_subject,current_work.generation,current_work.lease_expires_at,current_work.last_reason::text;
END;
$feedme_work_claim$;
REVOKE ALL ON FUNCTION identity.claim_account_erasure_work(text,uuid,integer) FROM PUBLIC;

CREATE FUNCTION identity.defer_account_erasure_work(p_environment text,p_job_id uuid,p_token uuid,
 p_generation bigint,p_reason text,p_delay_seconds integer) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp SET row_security=off AS $feedme_work_defer$
DECLARE current_work identity.account_erasure_work%ROWTYPE; at_time timestamptz;
BEGIN
 IF p_environment IS NULL OR p_environment !~ '^[a-z][a-z0-9-]{0,39}$' OR p_job_id IS NULL OR p_token IS NULL
   OR p_generation IS NULL OR p_generation<1 OR p_reason IS NULL
   OR p_reason NOT IN ('retry','unattributed_events','unknown_receipts','related_data','identity_conflict')
   OR p_delay_seconds IS NULL OR p_delay_seconds NOT BETWEEN 1 AND 86400 THEN
  RAISE EXCEPTION 'Invalid account erasure deferral input' USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM identity.account_deletion_jobs j WHERE j.environment=p_environment AND j.id=p_job_id FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT w.* INTO current_work FROM identity.account_erasure_work w
 WHERE w.environment=p_environment AND w.job_id=p_job_id FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 -- Check actual database time AFTER both locks; waiting never extends a lease.
 at_time:=clock_timestamp();
 IF current_work.stage<>'inventory' OR current_work.lease_token IS DISTINCT FROM p_token
   OR current_work.generation<>p_generation OR current_work.lease_expires_at IS NULL
   OR current_work.lease_expires_at<=at_time THEN RETURN false; END IF;
 UPDATE identity.account_erasure_work w SET lease_token=NULL,lease_expires_at=NULL,
   available_at=at_time+p_delay_seconds*interval '1 second',last_reason=p_reason,updated_at=at_time
 WHERE w.environment=p_environment AND w.job_id=p_job_id;
 RETURN true;
END;
$feedme_work_defer$;
REVOKE ALL ON FUNCTION identity.defer_account_erasure_work(text,uuid,uuid,bigint,text,integer) FROM PUBLIC;
