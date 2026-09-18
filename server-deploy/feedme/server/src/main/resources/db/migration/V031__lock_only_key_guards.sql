-- PostgreSQL row-locking SELECTs require UPDATE on at least one column. These six
-- immutable identity columns can therefore receive narrowly scoped UPDATE(column)
-- grants without authorizing an actual key write. No grants are made by this migration.
--
-- UPDATE OF deliberately rejects even SET key=key: it is not a changed-value test.
-- SELECT FOR UPDATE/SHARE does not fire these triggers. Existing non-key publication,
-- principal lifecycle and outbox delivery updates, plus membership INSERT/DELETE,
-- remain unchanged. No stored rows or V001--V030 bytes are rewritten.

CREATE FUNCTION platform.reject_lock_only_key_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'Lock-only identity columns cannot be updated' USING ERRCODE = '23514';
END;
$$;
REVOKE ALL ON FUNCTION platform.reject_lock_only_key_update() FROM PUBLIC;

CREATE TRIGGER ingredient_heads_lock_key_immutable
    BEFORE UPDATE OF environment ON catalog.ingredient_heads
    FOR EACH ROW EXECUTE FUNCTION platform.reject_lock_only_key_update();

CREATE TRIGGER recipe_heads_lock_key_immutable
    BEFORE UPDATE OF environment ON catalog.recipe_heads
    FOR EACH ROW EXECUTE FUNCTION platform.reject_lock_only_key_update();

CREATE TRIGGER principals_lock_key_immutable
    BEFORE UPDATE OF id ON identity.principals
    FOR EACH ROW EXECUTE FUNCTION platform.reject_lock_only_key_update();

CREATE TRIGGER outbox_lock_key_immutable
    BEFORE UPDATE OF event_id ON platform.outbox
    FOR EACH ROW EXECUTE FUNCTION platform.reject_lock_only_key_update();

CREATE TRIGGER collection_items_lock_key_immutable
    BEFORE UPDATE OF saved_recipe_id ON memory.collection_items
    FOR EACH ROW EXECUTE FUNCTION platform.reject_lock_only_key_update();

CREATE TRIGGER save_commands_lock_key_immutable
    BEFORE UPDATE OF command_id ON memory.save_commands
    FOR EACH ROW EXECUTE FUNCTION platform.reject_lock_only_key_update();
