-- Principal identity/catalog lifecycle are supplied by mandatory trusted adapters, not fabricated here.
-- JSONB retains exact decimal values (no numeric(12,4) rounding of the wider canonical transport).
CREATE SCHEMA profile;
CREATE SCHEMA pantry;

CREATE TABLE profile.preferences (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    actor_kind varchar(7) NOT NULL CHECK (actor_kind IN ('account','guest')),
    principal_id uuid NOT NULL,
    id uuid NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    fields jsonb NOT NULL CHECK (jsonb_typeof(fields)='object' AND octet_length(fields::text)<=262144),
    PRIMARY KEY (environment,actor_kind,principal_id),
    UNIQUE (environment,actor_kind,principal_id,id)
);

CREATE TABLE pantry.pantry_items (
    environment varchar(40) NOT NULL CHECK (environment ~ '^[a-z][a-z0-9-]{0,39}$'),
    actor_kind varchar(7) NOT NULL CHECK (actor_kind IN ('account','guest')),
    principal_id uuid NOT NULL,
    ingredient_id uuid NOT NULL,
    id uuid NOT NULL,
    -- Never reset on delete/recreate: an old If-Match cannot act on a replacement incarnation.
    version bigint NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    fields jsonb NULL CHECK (fields IS NULL OR (jsonb_typeof(fields)='object' AND octet_length(fields::text)<=262144)),
    deleted boolean NOT NULL DEFAULT false,
    deletion_key uuid NULL,
    PRIMARY KEY (environment,actor_kind,principal_id,ingredient_id),
    UNIQUE (environment,actor_kind,principal_id,id),
    CHECK ((deleted AND fields IS NULL AND deletion_key IS NOT NULL) OR
           (NOT deleted AND fields IS NOT NULL AND deletion_key IS NULL))
);
CREATE INDEX pantry_items_owner_live ON pantry.pantry_items(environment,actor_kind,principal_id,ingredient_id) WHERE NOT deleted;
