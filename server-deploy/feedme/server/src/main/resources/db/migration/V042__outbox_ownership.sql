-- Explicit attribution for newly written, reviewed account/private event families only.
-- Legacy and unknown-family rows remain unattributed; no inferred ownership or backfill.
-- These immutable coordinates are not an authorization grant or an erasure receipt.
ALTER TABLE platform.outbox
    ADD COLUMN owner_environment text NULL,
    ADD COLUMN owner_kind text NULL,
    ADD COLUMN owner_id uuid NULL,
    ADD CONSTRAINT outbox_owner_tuple CHECK (
        num_nonnulls(owner_environment,owner_kind,owner_id) IN (0,3)
    ),
    ADD CONSTRAINT outbox_owner_environment CHECK (
        owner_environment ~ '^[a-z][a-z0-9-]{0,39}$'
    ),
    ADD CONSTRAINT outbox_owner_kind CHECK (
        owner_kind IN ('account','private_principal','guest_principal')
    );

CREATE INDEX outbox_owned_events
    ON platform.outbox(owner_environment,owner_kind,owner_id,event_id)
    WHERE owner_id IS NOT NULL;

CREATE FUNCTION platform.protect_outbox_ownership() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp
AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF ROW(NEW.owner_environment,NEW.owner_kind,NEW.owner_id)
            IS DISTINCT FROM ROW(OLD.owner_environment,OLD.owner_kind,OLD.owner_id) THEN
            RAISE EXCEPTION 'Outbox ownership is immutable' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.event_type IN (
        'identity.account.bootstrapped.v1','identity.session.revoked.v1',
        'profile.profile.changed.v1','identity.account.deletion_requested.v1'
    ) THEN
        IF NEW.owner_environment IS NULL OR NEW.owner_kind IS DISTINCT FROM 'account'
            OR NEW.owner_id IS NULL
            OR NEW.payload->>'userId' IS DISTINCT FROM NEW.owner_id::text THEN
            RAISE EXCEPTION 'Account event requires explicit matching ownership' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.event_type IN (
        'profile.preferences.changed.v1','pantry.item.changed.v1','planning.plan.created.v1',
        'cooking.session.started.v1','cooking.session.progressed.v1','cooking.session.completed.v1',
        'memory.recipe.saved.v1','memory.recipe.deleted.v1','memory.collection.changed.v1',
        'memory.feedback.changed.v1','memory.preference.changed.v1'
    ) THEN
        IF NEW.owner_environment IS NULL OR NEW.owner_kind IS NULL
            OR NEW.owner_kind NOT IN ('private_principal','guest_principal')
            OR NEW.owner_id IS NULL
            OR NEW.payload->>'principalId' IS DISTINCT FROM NEW.owner_id::text THEN
            RAISE EXCEPTION 'Private event requires explicit matching ownership' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION platform.protect_outbox_ownership() FROM PUBLIC;

CREATE TRIGGER outbox_ownership_required BEFORE INSERT ON platform.outbox
    FOR EACH ROW EXECUTE FUNCTION platform.protect_outbox_ownership();
CREATE TRIGGER outbox_ownership_immutable BEFORE UPDATE ON platform.outbox
    FOR EACH ROW EXECUTE FUNCTION platform.protect_outbox_ownership();
