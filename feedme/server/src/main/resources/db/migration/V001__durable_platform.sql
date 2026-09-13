-- Expand-only foundation: existing application tables and HTTP behavior are untouched.
-- Future changes must preserve compatibility with running producers/consumers, then
-- backfill separately before tightening constraints. Rolling application code back
-- retains this schema and its durable records; never undo it by deleting user data.

CREATE TABLE platform.idempotency (
    principal_scope varchar(200) NOT NULL CHECK (btrim(principal_scope) <> ''),
    operation_id varchar(100) NOT NULL CHECK (btrim(operation_id) <> ''),
    key uuid NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-fA-F]{64}$'),
    state varchar(16) NOT NULL CHECK (state IN ('pending', 'completed', 'tombstone')),
    response_code integer NULL,
    response_json jsonb NULL,
    response_etag varchar(256) NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    tombstoned_at timestamptz NULL,
    PRIMARY KEY (principal_scope, operation_id, key),
    CONSTRAINT idempotency_response_size CHECK (
        response_json IS NULL OR octet_length(convert_to(response_json::text, 'UTF8')) <= 262144
    ),
    CONSTRAINT idempotency_response_etag CHECK (
        response_etag IS NULL OR (
            position(chr(13) IN response_etag) = 0 AND position(chr(10) IN response_etag) = 0
        )
    ),
    CONSTRAINT idempotency_state_response CHECK (
        (state = 'pending' AND response_code IS NULL AND response_json IS NULL
            AND response_etag IS NULL AND tombstoned_at IS NULL)
        OR (state = 'completed' AND response_code IS NOT NULL AND response_code BETWEEN 200 AND 299
            AND tombstoned_at IS NULL AND (
                (response_code IN (204, 205) AND response_json IS NULL)
                OR (response_code NOT IN (204, 205) AND response_json IS NOT NULL)
            ))
        OR (state = 'tombstone' AND response_code IS NULL AND response_json IS NULL
            AND response_etag IS NULL AND tombstoned_at IS NOT NULL)
    )
);

CREATE INDEX idempotency_completed_expiry_idx ON platform.idempotency (expires_at)
    WHERE state = 'completed';

CREATE TABLE platform.outbox (
    event_id uuid PRIMARY KEY,
    event_type varchar(120) NOT NULL CHECK (btrim(event_type) <> ''),
    schema_version integer NOT NULL CHECK (schema_version > 0),
    aggregate_type varchar(80) NOT NULL CHECK (btrim(aggregate_type) <> ''),
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version > 0),
    producer varchar(80) NOT NULL CHECK (btrim(producer) <> ''),
    correlation_id varchar(128) NOT NULL CHECK (btrim(correlation_id) <> ''),
    causation_id uuid NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid NULL,
    lease_expires_at timestamptz NULL,
    quarantined_at timestamptz NULL,
    last_failure_code varchar(80) NULL CHECK (last_failure_code IS NULL OR btrim(last_failure_code) <> ''),
    CONSTRAINT outbox_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT outbox_payload_size CHECK (octet_length(convert_to(payload::text, 'UTF8')) <= 65536),
    CONSTRAINT outbox_lease_pair CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT outbox_terminal_state CHECK (published_at IS NULL OR quarantined_at IS NULL)
);

-- Multiple event facts may legitimately share an aggregate version.
CREATE INDEX outbox_pending_idx ON platform.outbox (available_at, occurred_at, event_id)
    WHERE published_at IS NULL AND quarantined_at IS NULL;

-- No outbox foreign key: messages received through external queues are valid too.
CREATE TABLE platform.consumer_inbox (
    consumer_name varchar(100) NOT NULL CHECK (btrim(consumer_name) <> ''),
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_name, event_id)
);
