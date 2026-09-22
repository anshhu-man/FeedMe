package com.feedme.server.staff

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only admission, never a schema/grant installer. Checks the exact lock helper
 * before any call can exercise its definer privileges. Ordinary serving needs SELECT
 * and INSERT on the draft/submission/review tables, UPDATE(current_version,updated_at) on heads,
 * UPDATE(version) on immutable revisions and UPDATE(submitted_version/result_version)
 * on submission/review evidence solely for FOR SHARE, and EXECUTE on the
 * narrow registry-lock helper. No UPDATE on staff actors/policies, no DELETE/TRUNCATE,
 * and no permission to publish public catalog content is requested here. */
internal object SupabaseStaffRecipeServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff draft compatibility interrupted")
            for ((version, name) in migrations) c.prepareStatement("SELECT description,checksum FROM platform.schema_migrations WHERE version=?").use { s ->
                s.setInt(1, version); s.executeQuery().use { r ->
                    check(r.next() && r.getString(1) == name && r.getString(2) == sha(resources.getValue(version)) && !r.next())
                }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls,current_setting('session_replication_role')='origin' " +
                    "FROM pg_catalog.pg_roles WHERE rolname=current_user").use { r ->
                    check(r.next() && r.getBoolean(1) && r.getBoolean(2) && !r.next())
                }
                for (table in columns.keys) s.executeQuery("SELECT * FROM ONLY staff.$table WHERE false FOR SHARE").close()
            }
            for ((table, expected) in columns) {
                c.prepareStatement("SELECT c.relkind,c.relrowsecurity,c.relforcerowsecurity," +
                    "EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)," +
                    "has_table_privilege(current_user,c.oid,'INSERT') FROM pg_catalog.pg_class c WHERE c.oid=to_regclass(?)").use { s ->
                    s.setString(1, "staff.$table"); s.executeQuery().use { r ->
                        check(r.next() && r.getString(1) == "r" && r.getBoolean(2) && r.getBoolean(3) && !r.getBoolean(4) && r.getBoolean(5) && !r.next())
                    }
                }
                c.prepareStatement("SELECT a.attname,n.nspname||'.'||t.typname,a.attnotnull FROM pg_catalog.pg_attribute a " +
                    "JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace " +
                    "WHERE a.attrelid=to_regclass(?) AND a.attnum>0 AND NOT a.attisdropped").use { s ->
                    s.setString(1, "staff.$table"); s.executeQuery().use { r ->
                        val actual = mutableMapOf<String, String>()
                        while (r.next()) { check(r.getBoolean(3)); actual[r.getString(1)] = r.getString(2) }
                        check(actual == expected)
                    }
                }
            }
            for (column in listOf("current_version", "updated_at")) c.prepareStatement(
                "SELECT has_column_privilege(current_user,'staff.recipe_drafts',?,'UPDATE')").use { s ->
                s.setString(1, column); s.executeQuery().use { r -> check(r.next() && r.getBoolean(1) && !r.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT p.prosecdef,p.proleakproof,p.provolatile::text,p.proparallel::text,p.prosrc," +
                    "p.proconfig,p.prorettype='boolean'::regtype,l.lanname,o.rolsuper OR o.rolbypassrls," +
                    "has_function_privilege(current_user,p.oid,'EXECUTE')," +
                    "p.proowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='staff.actors'::regclass)," +
                    "p.proowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='staff.publication_policies'::regclass)," +
                    "NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0 AND a.privilege_type='EXECUTE') " +
                    "FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang " +
                    "JOIN pg_catalog.pg_roles o ON o.oid=p.proowner " +
                    "WHERE p.oid=to_regprocedure('staff.lock_recipe_draft_actor(text,text,text)')").use { r ->
                    check(r.next() && r.getBoolean(1) && !r.getBoolean(2) && r.getString(3) == "v" && r.getString(4) == "u" &&
                        r.getString(5) == helperBody && (r.getArray(6).array as Array<*>).map { it.toString() }.toSet() ==
                        setOf("search_path=pg_catalog, pg_temp", "row_security=off") && r.getBoolean(7) && r.getString(8) == "plpgsql" &&
                        r.getBoolean(9) && r.getBoolean(10) && r.getBoolean(11) && r.getBoolean(12) && r.getBoolean(13) && !r.next())
                }
                s.executeQuery("SELECT c.relname,t.tgname,t.tgenabled::text,CASE " +
                    "WHEN t.tgfoid='staff.guard_recipe_draft_head()'::regprocedure THEN 'guard_recipe_draft_head' " +
                    "WHEN t.tgfoid='staff.guard_recipe_editorial_revision()'::regprocedure THEN 'guard_recipe_editorial_revision' " +
                    "WHEN t.tgfoid='staff.reject_evidence_mutation()'::regprocedure THEN 'reject_evidence_mutation' ELSE 'unknown' END," +
                    "t.tgtype::text,t.tgqual IS NULL AND t.tgnargs=0 AND t.tgattr=''::int2vector " +
                    "FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid " +
                    "WHERE t.tgrelid IN ('staff.recipe_drafts'::regclass,'staff.recipe_draft_revisions'::regclass," +
                    "'staff.recipe_submissions'::regclass,'staff.recipe_reviews'::regclass,'staff.recipe_publication_commands'::regclass) AND NOT t.tgisinternal").use { r ->
                    val actual = mutableSetOf<List<String>>()
                    while (r.next()) {
                        check(r.getString(3) in setOf("O", "A") && r.getBoolean(6))
                        actual += listOf(r.getString(1), r.getString(2), r.getString(4), r.getString(5))
                    }
                    check(actual == expectedTriggers)
                }
            }
            for ((name, expected) in guardFunctions) c.prepareStatement("SELECT p.prosecdef,p.proleakproof," +
                "p.provolatile::text,p.proparallel::text,p.prosrc,p.proconfig,p.prorettype='trigger'::regtype,l.lanname " +
                "FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang WHERE p.oid=to_regprocedure(?)").use { s ->
                s.setString(1, "staff.$name()"); s.executeQuery().use { r ->
                    check(r.next() && !r.getBoolean(1) && !r.getBoolean(2) && r.getString(3) == "v" && r.getString(4) == "u" &&
                        r.getString(5) == expected.first &&
                        ((r.getArray(6)?.array as? Array<*>)?.map { it.toString() }?.toSet() ?: emptySet()) == expected.second &&
                        r.getBoolean(7) && r.getString(8) == "plpgsql" && !r.next())
                }
            }
        } catch (f: CancellationException) { throw f }
          catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
          catch (_: Exception) { throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.NOT_CONFIGURED) }
    }
    private val migrations = mapOf(68 to "staff_recipe_drafts", 69 to "staff_recipe_draft_erasure_inventory",
        70 to "staff_recipe_editorial_review", 71 to "staff_recipe_review_erasure_inventory",
        73 to "staff_recipe_catalog_lifecycle", 74 to "staff_recipe_publication_erasure_inventory")
    private val resources by lazy { migrations.mapValues { (version, name) ->
        checkNotNull(javaClass.getResourceAsStream("/db/migration/V${version.toString().padStart(3, '0')}__$name.sql")).use { it.readBytes().decodeToString() }
    } }
    private val helperBody by lazy {
        val source = resources.getValue(68); val marker = "\$feedme_staff_draft_lock\$"
        check(source.split(marker).size == 3)
        source.substringAfter("AS $marker").substringBefore(marker)
    }
    private val guardFunctions by lazy {
        val older = checkNotNull(javaClass.getResourceAsStream("/db/migration/V040__staff_publication_approvals.sql"))
            .use { it.readBytes().decodeToString() }
        mapOf(
            "guard_recipe_draft_head" to (resources.getValue(68).substringAfter("AS \$feedme_draft_head\$")
                .substringBefore("\$feedme_draft_head\$") to setOf("search_path=pg_catalog, pg_temp")),
            "guard_recipe_editorial_revision" to (resources.getValue(73).substringAfter("AS \$feedme_publication_revision\$")
                .substringBefore("\$feedme_publication_revision\$") to setOf("search_path=pg_catalog, pg_temp")),
            "reject_evidence_mutation" to (older.substringAfter("CREATE FUNCTION staff.reject_evidence_mutation()")
                .substringAfter("AS \$\$").substringBefore("\$\$") to emptySet()),
        )
    }
    private val columns = mapOf(
        "recipe_drafts" to mapOf("environment" to "pg_catalog.varchar", "recipe_id" to "pg_catalog.uuid", "id" to "pg_catalog.uuid",
            "author_id" to "pg_catalog.uuid", "current_version" to "pg_catalog.int8", "created_at" to "pg_catalog.timestamptz", "updated_at" to "pg_catalog.timestamptz"),
        "recipe_draft_revisions" to mapOf("environment" to "pg_catalog.varchar", "recipe_id" to "pg_catalog.uuid", "draft_id" to "pg_catalog.uuid",
            "version" to "pg_catalog.int8", "author_id" to "pg_catalog.uuid", "editor_id" to "pg_catalog.uuid", "principal_scope" to "pg_catalog.varchar",
            "operation_id" to "pg_catalog.varchar", "command_key" to "pg_catalog.uuid", "request_hash" to "pg_catalog.bpchar", "request_text" to "pg_catalog.text",
            "snapshot_text" to "pg_catalog.text", "snapshot_sha256" to "pg_catalog.bpchar", "recorded_at" to "pg_catalog.timestamptz"),
        "recipe_submissions" to mapOf("environment" to "pg_catalog.varchar", "draft_id" to "pg_catalog.uuid", "submission_id" to "pg_catalog.uuid",
            "source_version" to "pg_catalog.int8", "submitted_version" to "pg_catalog.int8", "author_id" to "pg_catalog.uuid",
            "material_sha256" to "pg_catalog.bpchar", "submission_text" to "pg_catalog.text", "submission_sha256" to "pg_catalog.bpchar", "submitted_at" to "pg_catalog.timestamptz"),
        "recipe_reviews" to mapOf("environment" to "pg_catalog.varchar", "draft_id" to "pg_catalog.uuid", "review_id" to "pg_catalog.uuid",
            "submission_id" to "pg_catalog.uuid", "submitted_version" to "pg_catalog.int8", "result_version" to "pg_catalog.int8",
            "author_id" to "pg_catalog.uuid", "reviewer_id" to "pg_catalog.uuid", "decision" to "pg_catalog.varchar",
            "material_sha256" to "pg_catalog.bpchar", "review_text" to "pg_catalog.text", "review_sha256" to "pg_catalog.bpchar", "reviewed_at" to "pg_catalog.timestamptz"),
        "recipe_publication_commands" to mapOf("environment" to "pg_catalog.varchar", "draft_id" to "pg_catalog.uuid",
            "source_version" to "pg_catalog.int8", "result_version" to "pg_catalog.int8", "actor_id" to "pg_catalog.uuid",
            "publisher_id" to "pg_catalog.uuid", "reviewer_id" to "pg_catalog.uuid", "operation_id" to "pg_catalog.varchar",
            "release_id" to "pg_catalog.uuid", "catalog_revision" to "pg_catalog.int8", "catalog_request_hash" to "pg_catalog.bpchar",
            "public_recipe_text" to "pg_catalog.text", "public_recipe_sha256" to "pg_catalog.bpchar", "recorded_at" to "pg_catalog.timestamptz"),
    )
    private val expectedTriggers = setOf(
        listOf("recipe_drafts", "staff_recipe_draft_head_guard", "guard_recipe_draft_head", "31"),
        listOf("recipe_drafts", "staff_recipe_draft_head_retained", "guard_recipe_draft_head", "34"),
        listOf("recipe_draft_revisions", "staff_recipe_draft_revision_immutable", "reject_evidence_mutation", "27"),
        listOf("recipe_draft_revisions", "staff_recipe_draft_revision_retained", "reject_evidence_mutation", "34"),
        listOf("recipe_draft_revisions", "staff_recipe_editorial_revision", "guard_recipe_editorial_revision", "7"),
        listOf("recipe_submissions", "staff_recipe_submissions_immutable", "reject_evidence_mutation", "27"),
        listOf("recipe_submissions", "staff_recipe_submissions_retained", "reject_evidence_mutation", "34"),
        listOf("recipe_reviews", "staff_recipe_reviews_immutable", "reject_evidence_mutation", "27"),
        listOf("recipe_reviews", "staff_recipe_reviews_retained", "reject_evidence_mutation", "34"),
        listOf("recipe_publication_commands", "staff_recipe_publications_immutable", "reject_evidence_mutation", "27"),
        listOf("recipe_publication_commands", "staff_recipe_publications_retained", "reject_evidence_mutation", "34"),
    )
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
