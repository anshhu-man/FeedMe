-- Permit the role-scoped ADMIN_AUDIT reader to record its own access in the
-- existing immutable moderation access journal. This does not create an export,
-- grant another staff role, backfill catalog events or widen evidence access.
ALTER TABLE safety.moderation_access_audit DROP CONSTRAINT moderation_access_audit_purpose_check;
ALTER TABLE safety.moderation_access_audit ADD CONSTRAINT moderation_access_audit_purpose_check
 CHECK(purpose IN ('queue-list','case-review','claim-receipt','dismiss-receipt','remove-receipt','audit-list'));
