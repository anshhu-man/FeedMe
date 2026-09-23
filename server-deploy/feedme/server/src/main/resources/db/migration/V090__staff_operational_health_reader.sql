-- Permit the current moderator-only operational health reader to record its own
-- access in the immutable moderation journal. This adds no on-call command,
-- incident mutation, entitlement source, queue worker or staff enrollment.
ALTER TABLE safety.moderation_access_audit DROP CONSTRAINT moderation_access_audit_purpose_check;
ALTER TABLE safety.moderation_access_audit ADD CONSTRAINT moderation_access_audit_purpose_check
 CHECK(purpose IN ('queue-list','case-review','claim-receipt','dismiss-receipt','remove-receipt','audit-list','health-read'));
