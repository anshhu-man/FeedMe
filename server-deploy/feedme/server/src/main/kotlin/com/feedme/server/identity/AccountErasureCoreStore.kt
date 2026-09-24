package com.feedme.server.identity

import com.feedme.server.db.PgTransactions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException

/** A core-stage result is never whole-account/provider/storage erasure completion. */
internal enum class AccountErasureCoreOutcome {
    CORE_ERASED, ALREADY_CORE_ERASED, LEASE_LOST, IDENTITY_CONFLICT,
    UNATTRIBUTED_EVENTS, UNKNOWN_RECEIPTS, RELATED_DATA;
}

/** Worker-only, exact-job/lease-bound core cleanup. No route, scheduler, credential loader
 * or provider invocation is installed here. The SQL capability derives all targets from
 * retained acceptance, inventories dependencies and owns its transaction-local delete scope.
 * Caller-selected user IDs, row lists, table names or a session GUC cannot authorize erasure.
 * Preserve an existing lease when reconciling an unknown commit; never manufacture a new
 * deletion request or treat an exception as rollback/completion evidence.
 */
internal class AccountErasureCoreStore(internal val environment: String, internal val transactions: PgTransactions) {
    private val work = AccountErasureWorkStore(environment, transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun purge(lease: AccountErasureWorkLease): AccountErasureCoreOutcome {
        require(lease.environment == environment && lease.generation > 0)
        return transactions.run { c ->
            checkCompatibility(c)
            c.prepareStatement("SELECT identity.purge_account_core(?,?,?,?)").use { s ->
                s.setString(1, environment); s.setObject(2, lease.jobId)
                s.setObject(3, lease.token); s.setLong(4, lease.generation)
                s.executeQuery().use { r ->
                    if (!r.next()) incompatible()
                    val outcome = AccountErasureCoreOutcome.entries.singleOrNull { it.name.lowercase() == r.getString(1) }
                        ?: incompatible()
                    if (r.next()) incompatible()
                    outcome
                }
            }
        }
    }

    internal fun checkCompatibility(c: Connection) {
        work.checkCompatibility(c)
        val usesV094Purge = c.prepareStatement(
            "SELECT checksum FROM platform.schema_migrations WHERE version=94",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) false
                else {
                    if (rows.getString(1) != AccountDeletionCompletionStore.V094_CANDIDATE_SHA256 || rows.next())
                        incompatible()
                    true
                }
            }
        }
        for ((version, bytes) in resources) c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=?").use { s ->
            s.setInt(1, version)
            s.executeQuery().use { r -> if (!r.next() || r.getString(1) != hash(bytes) || r.next()) incompatible() }
        }
        // The private capability table has no worker/API/PUBLIC table or column grants.
        // Only the fixed definer code may install a transient, transaction-bound scope.
        c.createStatement().use { s -> s.executeQuery("""
            SELECT c.relrowsecurity AND c.relforcerowsecurity AND c.relkind='r'
                AND NOT EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid)
                AND NOT EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)
                AND NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a WHERE a.grantee<>c.relowner)
                AND NOT EXISTS(SELECT 1 FROM pg_attribute att CROSS JOIN LATERAL aclexplode(att.attacl) a
                    WHERE att.attrelid=c.oid AND a.grantee<>c.relowner)
            FROM pg_class c WHERE c.oid='identity.account_erasure_scope'::regclass
        """.trimIndent()).use { r -> if (!r.next() || !r.getBoolean(1) || r.next()) incompatible() } }

        for (f in functions) c.prepareStatement("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=c.relowner,l.lanname,
                p.prorettype::regtype::text,(o.rolsuper OR o.rolbypassrls),
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                    WHERE a.grantee=0 OR (a.grantee<>p.proowner AND p.oid<> 'identity.purge_account_core(text,uuid,uuid,bigint)'::regprocedure))
            FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
            JOIN pg_language l ON l.oid=p.prolang JOIN pg_roles o ON o.oid=p.proowner
            JOIN pg_class c ON c.oid='identity.account_erasure_scope'::regclass
            WHERE n.nspname||'.'||p.proname||'('||replace(oidvectortypes(p.proargtypes),' ','')||')'=?
        """.trimIndent()).use { s ->
            s.setString(1, f.signature)
            s.executeQuery().use { r ->
                if (!r.next()) incompatible()
                val expectedBody = if (usesV094Purge && f.signature == CORE_PURGE_SIGNATURE)
                    hash(r.getString(1).toByteArray(Charsets.UTF_8)) == AccountDeletionCompletionStore.V094_PURGE_BODY_SHA256
                else r.getString(1) == body(f.tag, f.version)
                if (!expectedBody || r.getBoolean(2) != f.securityDefiner ||
                    (r.getArray(3)?.array as? Array<*>)?.toSet() != f.configuration ||
                    !r.getBoolean(4) || r.getString(5) != "plpgsql" || r.getString(6) != f.returnType ||
                    !r.getBoolean(7) || !r.getBoolean(8) || r.next()) incompatible()
            }
        }
        // The separate draft-event admission guard deliberately remains INVOKER and
        // reads no table. Do not accidentally require/grant definer authority to it.
        c.createStatement().use { s -> s.executeQuery("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,p.proowner=t.relowner,l.lanname,
                p.prorettype::regtype::text,p.prokind::text,p.provolatile::text,p.proisstrict,p.proretset,
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                    WHERE a.grantee<>p.proowner)
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang
            JOIN pg_class t ON t.oid='platform.outbox'::regclass
            WHERE p.oid='platform.protect_post_draft_event_owner()'::regprocedure
        """.trimIndent()).use { r ->
            if (!r.next() || r.getString(1) != body("feedme_draft_event_owner", 48) || r.getBoolean(2) ||
                (r.getArray(3)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp") ||
                !r.getBoolean(4) || r.getString(5) != "plpgsql" || r.getString(6) != "trigger" ||
                r.getString(7) != "f" || r.getString(8) != "v" || r.getBoolean(9) || r.getBoolean(10) ||
                !r.getBoolean(11) || r.next()) incompatible()
        } }
        // Trigger names are not authority. Require each reviewed function to be attached
        // exactly once with the whole expected event set and no WHEN/column restriction.
        for ((table, function, type) in guards) c.prepareStatement("""
            SELECT t.tgtype,t.tgenabled::text,t.tgisinternal,t.tgqual,t.tgattr::text,t.tgnargs,t.tgconstraint,
                p.proowner=c.relowner
            FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_class c ON c.oid=t.tgrelid
            JOIN pg_namespace tn ON tn.oid=c.relnamespace JOIN pg_namespace pn ON pn.oid=p.pronamespace
            WHERE tn.nspname||'.'||c.relname=?
                AND pn.nspname||'.'||p.proname||'('||replace(oidvectortypes(p.proargtypes),' ','')||')'=? AND t.tgtype=?
        """.trimIndent()).use { s ->
            s.setString(1, table); s.setString(2, function); s.setInt(3, type)
            s.executeQuery().use { r ->
                if (!r.next() || r.getInt(1) != type || r.getString(2) !in setOf("O", "A") || r.getBoolean(3) ||
                    r.getObject(4) != null || r.getString(5) != "" || r.getInt(6) != 0 || r.getLong(7) != 0L ||
                    !r.getBoolean(8) || r.next()) incompatible()
            }
        }
        // Window and manifest TRUNCATE remain guarded by the original shared V017
        // function. Its guest-manifest/preparation callers have no erasure exception.
        c.createStatement().use { s -> s.executeQuery("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,l.lanname,p.prorettype::regtype::text
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang JOIN pg_namespace n ON n.oid=p.pronamespace
            WHERE n.nspname='planning' AND p.proname='reject_manifest_mutation' AND p.pronargs=0
        """.trimIndent()).use { r ->
            if (!r.next() || r.getString(1) != "\nBEGIN RAISE EXCEPTION 'Planning manifests are immutable' USING ERRCODE='23514'; END;\n" ||
                r.getBoolean(2) || r.getArray(3) != null || r.getString(4) != "plpgsql" || r.getString(5) != "trigger" || r.next()) incompatible()
        } }
        // V053 changes only private DELETE exceptions. The shared catalog/REUSE
        // TRUNCATE denial is still the exact original V051 invoker function.
        // Resolve fixed names through catalogs: a function-only erasure caller
        // deliberately has no USAGE on the private planning schema. regclass /
        // regprocedure name casts would wrongly require that additional grant.
        c.createStatement().use { s -> s.executeQuery("""
            SELECT p.prosrc,p.prosecdef,p.proconfig,l.lanname,p.prorettype::regtype::text,
                p.proowner=t.relowner,NOT EXISTS(
                  SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee<>p.proowner)
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang
            JOIN pg_namespace pn ON pn.oid=p.pronamespace AND pn.nspname='planning'
            JOIN pg_class t ON t.relname='reuse_requests'
            JOIN pg_namespace tn ON tn.oid=t.relnamespace AND tn.nspname='planning'
            WHERE p.proname='reject_reuse_evidence_mutation' AND p.pronargs=0
        """.trimIndent()).use { r ->
            if (!r.next() || r.getString(1) != body("feedme_reuse_immutable", 51) || r.getBoolean(2) ||
                (r.getArray(3)?.array as? Array<*>)?.toSet() != setOf("search_path=pg_catalog, pg_temp") ||
                r.getString(4) != "plpgsql" || r.getString(5) != "trigger" || !r.getBoolean(6) || !r.getBoolean(7) || r.next()) incompatible()
        } }
    }

    private fun incompatible(): Nothing = throw SQLException("Account core erasure authority differs from reviewed source")
    override fun toString() = "AccountErasureCoreStore([redacted])"

    companion object {
        private const val CORE_PURGE_SIGNATURE = "identity.purge_account_core(text,uuid,uuid,bigint)"
        private val resources = mapOf(
            44 to "/db/migration/V044__account_core_erasure.sql",
            46 to "/db/migration/V046__account_manifest_erasure.sql",
            48 to "/db/migration/V048__account_draft_erasure.sql",
            51 to "/db/migration/V051__account_reuse_proposals.sql",
            52 to "/db/migration/V052__account_direct_conversations.sql",
            53 to "/db/migration/V053__account_private_extension_erasure.sql",
            54 to "/db/migration/V054__private_recipe_requests.sql",
            55 to "/db/migration/V055__account_recipe_request_inventory.sql",
            56 to "/db/migration/V056__supabase_digest_readiness.sql",
            58 to "/db/migration/V058__session_and_media_erasure_inventory.sql",
            59 to "/db/migration/V059__account_notification_settings.sql",
            60 to "/db/migration/V060__notification_settings_erasure.sql",
            62 to "/db/migration/V062__post_recipe_saves.sql",
            63 to "/db/migration/V063__post_recipe_save_erasure_inventory.sql",
            64 to "/db/migration/V064__account_exports.sql",
            65 to "/db/migration/V065__account_export_erasure_inventory.sql",
            66 to "/db/migration/V066__account_make_again_actions.sql",
            67 to "/db/migration/V067__account_make_again_erasure.sql",
            68 to "/db/migration/V068__staff_recipe_drafts.sql",
            69 to "/db/migration/V069__staff_recipe_draft_erasure_inventory.sql",
            70 to "/db/migration/V070__staff_recipe_editorial_review.sql",
            71 to "/db/migration/V071__staff_recipe_review_erasure_inventory.sql",
            72 to "/db/migration/V072__staff_recipe_reviewer_qualifications.sql",
            73 to "/db/migration/V073__staff_recipe_catalog_lifecycle.sql",
            74 to "/db/migration/V074__staff_recipe_publication_erasure_inventory.sql",
            75 to "/db/migration/V075__account_notification_inbox.sql",
            76 to "/db/migration/V076__notification_inbox_erasure_inventory.sql",
            77 to "/db/migration/V077__staff_moderator_enrollments.sql",
            78 to "/db/migration/V078__staff_moderation_workflow.sql",
            79 to "/db/migration/V079__staff_moderation_erasure_inventory.sql",
            80 to "/db/migration/V080__staff_moderation_removal.sql",
            81 to "/db/migration/V081__staff_moderation_removal_erasure_inventory.sql",
            82 to "/db/migration/V082__post_placement_erasure_inventory.sql",
            83 to "/db/migration/V083__post_reactions.sql",
            84 to "/db/migration/V084__post_reaction_erasure_inventory.sql",
            85 to "/db/migration/V085__reaction_notifications.sql",
            86 to "/db/migration/V086__reaction_notification_erasure_inventory.sql",
            88 to "/db/migration/V088__reaction_actor_erasure.sql",
            89 to "/db/migration/V089__never_dispatched_export_erasure.sql",
        ).mapValues { (_, path) ->
            checkNotNull(AccountErasureCoreStore::class.java.getResourceAsStream(path)).use { it.readBytes() }
        }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private data class Function(val signature: String, val tag: String, val returnType: String, val version: Int = 44,
            val securityDefiner: Boolean = true,
            val configuration: Set<String>? = setOf("search_path=pg_catalog, pg_temp", "row_security=off"))
        private val functions = listOf(
            Function(CORE_PURGE_SIGNATURE, "feedme_core_purge", "text", 89),
            Function("identity.account_erasure_delete_allowed(oid,text,uuid,uuid)", "feedme_core_allowed", "boolean", 89),
            Function("platform.guard_account_export_jobs()", "feedme_export_jobs", "trigger", 89,
                securityDefiner = false, configuration = setOf("search_path=pg_catalog, pg_temp")),
            Function("platform.guard_account_export_artifacts()", "feedme_export_artifacts", "trigger", 89,
                securityDefiner = false, configuration = setOf("search_path=pg_catalog, pg_temp")),
            Function("social.protect_post_reaction_history()", "feedme_reaction_history", "trigger", 88),
            Function("platform.guard_reaction_notification()", "feedme_reaction_notification", "trigger", 88),
            Function("profile.guard_notification_settings()", "feedme_notification_settings", "trigger", 60),
            Function("platform.guard_notification_inbox()", "feedme_notification_inbox", "trigger", 75),
            Function("identity.guard_account_core_erasure_checkpoint()", "feedme_core_checkpoint", "trigger"),
            Function("profile.keep_onboarding_decision_immutable()", "feedme_core_onboarding", "trigger"),
            Function("identity.keep_device_reconnection_immutable()", "feedme_core_reconnection", "trigger"),
            Function("identity.keep_account_terms_acceptance_immutable()", "feedme_core_terms", "trigger"),
            Function("planning.guard_account_plan_window_delete()", "feedme_core_window", "trigger"),
            Function("planning.guard_account_manifest_erasure()", "feedme_manifest_erasure", "trigger", 46),
            Function("planning.guard_account_manifest_owner_insert()", "feedme_manifest_owner", "trigger", 46),
            Function("platform.guard_account_draft_owner_write()", "feedme_draft_owner", "trigger", 48),
            Function("planning.guard_reuse_owner_insert()", "feedme_reuse_owner", "trigger", 53),
            Function("planning.guard_reuse_window()", "feedme_reuse_window", "trigger", 53),
            Function("planning.guard_reuse_account_erasure()", "feedme_reuse_erasure", "trigger", 53),
            Function("social.guard_account_privacy_erasure()", "feedme_privacy_erasure", "trigger", 53),
            Function("social.guard_recipe_request()", "feedme_recipe_request_guard", "trigger", 54),
            Function("social.guard_recipe_request_message()", "feedme_recipe_message_guard", "trigger", 54),
        )
        private val guards = listOf(
            Triple("platform.account_export_jobs", "platform.guard_account_export_jobs()", 31),
            Triple("platform.account_export_jobs", "platform.guard_account_export_jobs()", 34),
            Triple("platform.account_export_artifacts", "platform.guard_account_export_artifacts()", 31),
            Triple("platform.account_export_artifacts", "platform.guard_account_export_artifacts()", 34),
            Triple("social.post_reactions", "social.protect_post_reaction_history()", 31),
            Triple("social.post_reactions", "social.protect_post_reaction_history()", 34),
            Triple("platform.account_reaction_notifications", "platform.guard_reaction_notification()", 31),
            Triple("platform.account_reaction_notifications", "platform.guard_reaction_notification()", 34),
            Triple("platform.account_notifications", "platform.guard_notification_inbox()", 31),
            Triple("platform.account_notifications", "platform.guard_notification_inbox()", 34),
            Triple("platform.notification_read_watermarks", "platform.guard_notification_inbox()", 31),
            Triple("platform.notification_read_watermarks", "platform.guard_notification_inbox()", 34),
            Triple("profile.notification_settings", "profile.guard_notification_settings()", 31),
            Triple("profile.notification_settings", "profile.guard_notification_settings()", 34),
            Triple("profile.onboarding_decisions", "profile.keep_onboarding_decision_immutable()", 27),
            Triple("identity.device_reconnections", "identity.keep_device_reconnection_immutable()", 27),
            Triple("identity.device_reconnections", "identity.keep_device_reconnection_immutable()", 34),
            Triple("identity.account_terms_acceptances", "identity.keep_account_terms_acceptance_immutable()", 27),
            Triple("identity.account_terms_acceptances", "identity.keep_account_terms_acceptance_immutable()", 34),
            Triple("planning.account_plan_windows", "planning.guard_account_plan_window_delete()", 11),
            Triple("planning.account_plan_windows", "planning.reject_manifest_mutation()", 34),
            Triple("identity.account_erasure_work", "identity.guard_account_core_erasure_checkpoint()", 19),
            Triple("identity.account_erasure_work", "identity.guard_account_core_erasure_checkpoint()", 11),
            Triple("identity.account_erasure_work", "identity.guard_account_core_erasure_checkpoint()", 34),
            Triple("planning.manifest_headers", "planning.guard_account_manifest_erasure()", 27),
            Triple("planning.manifest_ranks", "planning.guard_account_manifest_erasure()", 27),
            Triple("planning.manifest_seals", "planning.guard_account_manifest_erasure()", 27),
            Triple("planning.manifest_headers", "planning.reject_manifest_mutation()", 34),
            Triple("planning.manifest_ranks", "planning.reject_manifest_mutation()", 34),
            Triple("planning.manifest_seals", "planning.reject_manifest_mutation()", 34),
            Triple("planning.manifest_headers", "planning.guard_account_manifest_owner_insert()", 7),
            Triple("platform.post_drafts", "platform.guard_account_draft_owner_write()", 23),
            Triple("platform.post_draft_heads", "platform.guard_account_draft_owner_write()", 23),
            Triple("platform.media_draft_lifecycles", "platform.guard_account_draft_owner_write()", 23),
            Triple("platform.outbox", "platform.protect_post_draft_event_owner()", 7),
            Triple("planning.reuse_requests", "planning.guard_reuse_account_erasure()", 27),
            Triple("planning.reuse_pages", "planning.guard_reuse_account_erasure()", 27),
            Triple("planning.reuse_windows", "planning.guard_reuse_window()", 27),
            Triple("planning.reuse_requests", "planning.guard_reuse_owner_insert()", 7),
            Triple("planning.reuse_pages", "planning.guard_reuse_owner_insert()", 7),
            Triple("planning.reuse_windows", "planning.guard_reuse_owner_insert()", 7),
            Triple("planning.reuse_requests", "planning.reject_reuse_evidence_mutation()", 34),
            Triple("planning.reuse_pages", "planning.reject_reuse_evidence_mutation()", 34),
            Triple("planning.reuse_windows", "planning.reject_reuse_evidence_mutation()", 34),
            Triple("social.account_privacy", "social.guard_account_privacy_erasure()", 11),
            Triple("social.account_privacy", "social.guard_account_privacy_erasure()", 34),
            Triple("social.recipe_requests", "social.guard_recipe_request()", 31),
            Triple("social.recipe_requests", "social.guard_recipe_request()", 34),
            Triple("social.thread_messages", "social.guard_recipe_request_message()", 7),
        )
        private fun body(tag: String, version: Int): String {
            val marker = "$" + tag + "$"
            val resourceText = resources.getValue(version).toString(Charsets.UTF_8)
            return resourceText.substringAfter("AS $marker", "").substringBefore("$marker;", "")
                .also { check(it.isNotEmpty()) }
        }
    }
}

internal enum class AccountErasureCoreRun { IDLE, CORE_ERASED, HELD, LEASE_LOST }

/** Explicit single-step worker; no process/scheduler is started by construction. Provider,
 * media and final completion remain separate stages. All current operations are DB-only. */
internal class AccountErasureCoreWorker(private val work: AccountErasureWorkStore, private val core: AccountErasureCoreStore) {
    init { require(work.environment == core.environment && work.transactions === core.transactions) }
    fun runOne(): AccountErasureCoreRun {
        val lease = work.claim(60) ?: return AccountErasureCoreRun.IDLE
        val result = core.purge(lease)
        if (result == AccountErasureCoreOutcome.CORE_ERASED || result == AccountErasureCoreOutcome.ALREADY_CORE_ERASED)
            return AccountErasureCoreRun.CORE_ERASED
        if (result == AccountErasureCoreOutcome.LEASE_LOST) return AccountErasureCoreRun.LEASE_LOST
        val reason = when (result) {
            AccountErasureCoreOutcome.IDENTITY_CONFLICT -> AccountErasureHoldReason.IDENTITY_CONFLICT
            AccountErasureCoreOutcome.UNATTRIBUTED_EVENTS -> AccountErasureHoldReason.UNATTRIBUTED_EVENTS
            AccountErasureCoreOutcome.UNKNOWN_RECEIPTS -> AccountErasureHoldReason.UNKNOWN_RECEIPTS
            AccountErasureCoreOutcome.RELATED_DATA -> AccountErasureHoldReason.RELATED_DATA
        }
        return if (work.defer(lease, reason, 60)) AccountErasureCoreRun.HELD else AccountErasureCoreRun.LEASE_LOST
    }
}
