package com.feedme.server.media.processing

import java.sql.Connection

/** Nonmutating admission for this worker's actual SQL, not a grant installer or complete
 * deployment/safety attestation. Explicit package dependencies and the digest checkpoint's
 * actual guards are checked. The operator still owns migration/schema review and exact grants.
 * Ordinary serving API and owner/superuser fallback are deliberately not worker identities. */
internal object SupabasePhotoWorkerCompatibility {
    fun check(c: Connection) {
        check(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        pq(c, "SELECT rolname,rolsuper,rolbypassrls,rolcreatedb,rolcreaterole,rolreplication,rolinherit " +
            "FROM pg_catalog.pg_roles WHERE rolname=current_user") { r ->
            check(r.next() && r.getString(1) != "feedme_api" && !r.getBoolean(2) && r.getBoolean(3) &&
                !r.getBoolean(4) && !r.getBoolean(5) && !r.getBoolean(6) && !r.getBoolean(7) && !r.next())
        }
        for ((version, resource) in dependencies) {
            val expected = checkNotNull(javaClass.getResourceAsStream("/db/migration/$resource")).use { processingHash(it.readBytes()) }
            pq(c, "SELECT checksum FROM platform.schema_migrations WHERE version=?", { setInt(1, version) }) {
                check(it.next() && it.getString(1) == expected && !it.next())
            }
        }
        for ((table, lock) in reads) {
            pq(c, "SELECT * FROM $table WHERE false" + if (lock == null) "" else " FOR $lock NOWAIT") { check(!it.next()) }
            pq(c, "SELECT c.relkind='r' AND c.relowner<>(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user) " +
                "AND NOT pg_catalog.pg_has_role(current_user,c.relowner,'MEMBER') " +
                "AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) " +
                "FROM pg_catalog.pg_class c WHERE c.oid=pg_catalog.to_regclass(?)", { setString(1, table) }) {
                check(it.next() && it.getBoolean(1) && !it.next())
            }
        }
        // Authority uses a NOWAIT SHARE relation fence. It neither reads private cases here
        // nor writes them; a role that cannot acquire the actual lock must fail before polling.
        c.createStatement().use { it.execute("LOCK TABLE ONLY safety.moderation_cases IN SHARE MODE NOWAIT") }
        for ((table, privilege, columns) in writes) pq(c,
            "SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) " +
                "FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) AS names(column_name)", {
                setString(1, table); setString(2, privilege); setString(3, columns)
            }) { check(it.next() && it.getBoolean(1) && !it.next()) }
        SupabaseMediaReadiness.checkCompatibility(c)
    }

    private val dependencies = listOf(
        7 to "V007__owned_photo_media.sql", 8 to "V008__media_processing_jobs.sql",
        27 to "V027__supabase_media_sources.sql", 28 to "V028__supabase_private_derivatives.sql",
        37 to "V037__media_safety_records.sql", 56 to "V056__supabase_digest_readiness.sql",
    )
    private val reads = listOf(
        "identity.users" to "UPDATE", "identity.principals" to "UPDATE",
        "profile.profiles" to "SHARE", "safety.moderation_cases" to null,
        "platform.post_draft_heads" to "UPDATE", "platform.media_draft_lifecycles" to "UPDATE",
        "platform.post_drafts" to "SHARE", "social.post_publications" to "SHARE",
        "platform.media_assets" to "UPDATE", "platform.media_processing_jobs" to "UPDATE",
        "platform.media_processing_inbox" to "UPDATE", "platform.media_derivative_intents" to "UPDATE",
        "platform.media_processing_cleanup" to "UPDATE", "platform.media_private_materializations" to null,
        "platform.media_safety_records" to "SHARE", "platform.media_safety_revocations" to "SHARE",
        "platform.media_digest_readiness" to "SHARE", "platform.outbox" to null,
    )
    private val writes = listOf(
        Triple("platform.media_processing_jobs", "INSERT", "id,environment,owner_user_id,media_id,source,policy_revision,codec_revision,state,available_at,created_at,terminal_media_version,terminal_at"),
        Triple("platform.media_processing_jobs", "UPDATE", "state,lease_token,lease_generation,lease_expires_at,attempts,last_failure_code,available_at,terminal_token,terminal_generation,terminal_media_version,terminal_event_id,terminal_at"),
        Triple("platform.media_processing_inbox", "INSERT", "event_id,event_sha256,job_id,accepted_at"),
        Triple("platform.media_derivative_intents", "INSERT", "id,job_id,variant,object_key,sha256,bytes,content_type,width,height,acceptance_deadline,created_at,storage_protocol,storage_bucket"),
        Triple("platform.media_derivative_intents", "UPDATE", "write_attempted_at,acknowledged_at"),
        Triple("platform.media_private_materializations", "INSERT", "job_id,lease_token,lease_generation,attempt,acknowledged_at"),
        Triple("platform.media_safety_records", "INSERT", "job_id,environment,owner_user_id,media_id,source_text,source_sha256,policy_revision,codec_revision,evidence_text,evidence_sha256,outputs_text,outputs_sha256,stage,media_version,record_text,record_sha256,recorded_at"),
        Triple("platform.media_digest_readiness", "INSERT", "job_id,environment,owner_user_id,media_id,media_version,lease_token,lease_generation,safety_record_sha256,manifest_text,manifest_sha256,inspection_started_at,event_id,ready_at"),
        Triple("platform.media_assets", "UPDATE", "state,version,updated_at,derivative_set"),
        Triple("platform.media_processing_cleanup", "INSERT", "id,environment,owner_user_id,media_id,object_key,derivative_intent_id,not_before,available_at,state,storage_protocol,storage_bucket"),
        Triple("platform.outbox", "INSERT", "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id"),
    )
}
