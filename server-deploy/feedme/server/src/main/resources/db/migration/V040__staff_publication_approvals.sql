-- Workforce publication evidence only. No staff, provider, policy, approval, content,
-- runtime grant or accepting default is seeded. Trusted owner enrollment is separate.
CREATE SCHEMA staff;
REVOKE ALL ON SCHEMA staff FROM PUBLIC;

CREATE TABLE staff.publication_policies (
    environment varchar(40) PRIMARY KEY CHECK(environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    version varchar(128) NOT NULL CHECK(length(btrim(version)) > 0),
    issuer varchar(2048) NOT NULL CHECK(length(btrim(issuer)) > 0),
    client_id varchar(256) NOT NULL CHECK(length(btrim(client_id)) > 0),
    audience varchar(256) NOT NULL CHECK(length(btrim(audience)) > 0),
    enabled boolean NOT NULL,
    not_before timestamptz NOT NULL, valid_until timestamptz NOT NULL,
    CHECK(isfinite(not_before) AND isfinite(valid_until) AND not_before < valid_until)
);
CREATE TABLE staff.actors (
    environment varchar(40) NOT NULL REFERENCES staff.publication_policies(environment),
    actor_id uuid NOT NULL, issuer varchar(2048) NOT NULL CHECK(length(btrim(issuer)) > 0),
    subject varchar(256) NOT NULL CHECK(length(btrim(subject)) > 0),
    can_publish boolean NOT NULL, can_review boolean NOT NULL, enabled boolean NOT NULL,
    token_valid_after timestamptz NOT NULL, not_before timestamptz NOT NULL, valid_until timestamptz NOT NULL,
    PRIMARY KEY(environment,actor_id), UNIQUE(environment,issuer,subject),
    CHECK(isfinite(token_valid_after) AND isfinite(not_before) AND isfinite(valid_until) AND not_before < valid_until)
);
CREATE TABLE staff.publication_approvals (
    environment varchar(40) NOT NULL REFERENCES staff.publication_policies(environment),
    approval_id uuid NOT NULL,
    kind varchar(20) NOT NULL CHECK(kind IN ('ingredients','recipes','copy-grant','copy-revocation')),
    exact_document text NOT NULL CHECK(octet_length(exact_document) BETWEEN 1 AND 1048576),
    request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
    publisher_id uuid NOT NULL, reviewer_id uuid NOT NULL CHECK(publisher_id <> reviewer_id),
    policy_version varchar(128) NOT NULL,
    issuer varchar(2048) NOT NULL, client_id varchar(256) NOT NULL, audience varchar(256) NOT NULL,
    publisher_subject varchar(256) NOT NULL, reviewer_subject varchar(256) NOT NULL,
    reviewer_issued_at timestamptz NOT NULL, reviewer_authenticated_at timestamptz NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT clock_timestamp(), expires_at timestamptz NOT NULL,
    reason varchar(4000) NOT NULL CHECK(length(btrim(reason)) > 0),
    rights_attestation_reference varchar(256) NOT NULL CHECK(length(btrim(rights_attestation_reference)) > 0),
    PRIMARY KEY(environment,approval_id),
    FOREIGN KEY(environment,publisher_id) REFERENCES staff.actors(environment,actor_id),
    FOREIGN KEY(environment,reviewer_id) REFERENCES staff.actors(environment,actor_id),
    CHECK(isfinite(reviewer_issued_at) AND isfinite(reviewer_authenticated_at) AND isfinite(reviewed_at) AND isfinite(expires_at)
        AND reviewer_authenticated_at <= reviewer_issued_at AND reviewer_issued_at <= reviewed_at AND reviewed_at < expires_at)
);
CREATE TABLE staff.approval_revocations (
    environment varchar(40) NOT NULL, approval_id uuid NOT NULL, revocation_id uuid NOT NULL,
    actor_id uuid NOT NULL, reason varchar(4000) NOT NULL CHECK(length(btrim(reason)) > 0),
    revoked_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(revoked_at)),
    PRIMARY KEY(environment,approval_id), UNIQUE(environment,revocation_id),
    FOREIGN KEY(environment,approval_id) REFERENCES staff.publication_approvals(environment,approval_id),
    FOREIGN KEY(environment,actor_id) REFERENCES staff.actors(environment,actor_id)
);

CREATE FUNCTION staff.protect_enrollment_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.environment IS DISTINCT FROM NEW.environment
        OR OLD.actor_id IS DISTINCT FROM NEW.actor_id OR OLD.issuer IS DISTINCT FROM NEW.issuer
        OR OLD.subject IS DISTINCT FROM NEW.subject THEN
        RAISE EXCEPTION 'Staff enrollment identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER staff_actor_identity BEFORE UPDATE OR DELETE ON staff.actors
    FOR EACH ROW EXECUTE FUNCTION staff.protect_enrollment_identity();
CREATE FUNCTION staff.reject_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Staff publication evidence is immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER staff_approvals_immutable BEFORE UPDATE OR DELETE ON staff.publication_approvals
    FOR EACH ROW EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_approvals_no_truncate BEFORE TRUNCATE ON staff.publication_approvals
    FOR EACH STATEMENT EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_revocations_immutable BEFORE UPDATE OR DELETE ON staff.approval_revocations
    FOR EACH ROW EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_revocations_no_truncate BEFORE TRUNCATE ON staff.approval_revocations
    FOR EACH STATEMENT EXECUTE FUNCTION staff.reject_evidence_mutation();
CREATE TRIGGER staff_actors_no_truncate BEFORE TRUNCATE ON staff.actors
    FOR EACH STATEMENT EXECUTE FUNCTION staff.reject_evidence_mutation();

ALTER TABLE staff.publication_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.publication_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE staff.actors ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.actors FORCE ROW LEVEL SECURITY;
ALTER TABLE staff.publication_approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.publication_approvals FORCE ROW LEVEL SECURITY;
ALTER TABLE staff.approval_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff.approval_revocations FORCE ROW LEVEL SECURITY;
REVOKE ALL ON ALL TABLES IN SCHEMA staff FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA staff FROM PUBLIC;
DO $$ DECLARE target_role text; BEGIN
    FOREACH target_role IN ARRAY ARRAY['anon','authenticated','feedme_api'] LOOP
        IF EXISTS(SELECT 1 FROM pg_catalog.pg_roles WHERE rolname=target_role) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA staff FROM %I',target_role);
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA staff FROM %I',target_role);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA staff FROM %I',target_role);
        END IF;
    END LOOP;
END; $$;
