-- A row-locking SELECT needs UPDATE on one column. The runtime may lock the
-- existing substitution head, but must never publish, advance or rekey it.
-- Reuse V031's unconditional guard; UPDATE OF also rejects SET environment=environment.
-- No grant, function, policy, content or stored-row change is made here.

CREATE TRIGGER substitution_heads_lock_key_immutable
    BEFORE UPDATE OF environment ON catalog.substitution_heads
    FOR EACH ROW EXECUTE FUNCTION platform.reject_lock_only_key_update();
