package com.feedme.server.staff

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only compatibility/privilege probe, never an installer. New activation rights:
 * USAGE on staff and SELECT on the listed staff fields. Current session-to-TOTP
 * binding is read only through the reviewed provider projection; the runtime has no
 * direct Auth schema/table privilege. Existing provider projection rights still apply.
 * Forced RLS must not turn a missing enrollment into an apparently authoritative read.
 * This observes required capabilities, not a least-privilege certification of the role. */
internal object SupabaseStaffServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff admission interrupted")
            c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=40").use { s ->
                s.executeQuery().use { r -> check(r.next() && r.getString(1) == checksum && !r.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT rolsuper OR rolbypassrls, current_setting('session_replication_role')='origin' " +
                    "FROM pg_catalog.pg_roles WHERE rolname=current_user").use { r ->
                    check(r.next() && r.getBoolean(1) && r.getBoolean(2) && !r.next())
                }
                // Exact SELECTs acquire ordinary schema-stability locks, require no staff
                // UPDATE permission, read no secret columns, and cannot seed any authority.
                s.executeQuery("SELECT environment,version,issuer,client_id,audience,enabled,not_before,valid_until " +
                    "FROM ONLY staff.publication_policies WHERE false").close()
                s.executeQuery("SELECT environment,actor_id,issuer,subject,can_publish,can_review,enabled,token_valid_after,not_before,valid_until " +
                    "FROM ONLY staff.actors WHERE false").close()
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT p.prosecdef,p.prokind,p.proretset,p.pronargs," +
                    "p.prorettype='pg_catalog.record'::regtype,l.lanname,p.provolatile,p.proisstrict," +
                    "p.proparallel,p.proleakproof,p.proconfig," +
                    "encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')," +
                    "(o.rolsuper OR o.rolbypassrls)," +
                    "has_function_privilege(current_user,p.oid,'EXECUTE')," +
                    "has_function_privilege(current_user,p.oid,'EXECUTE WITH GRANT OPTION')," +
                    "(SELECT count(*) FROM pg_proc q WHERE q.pronamespace=p.pronamespace AND q.proname=p.proname) " +
                    "FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang JOIN pg_roles o ON o.oid=p.proowner " +
                    "WHERE p.oid='feedme_auth_access.staff_totp_facts(uuid,uuid)'::regprocedure").use { r ->
                    check(r.next() && r.getBoolean(1) && r.getString(2) == "f" && r.getBoolean(3) && r.getInt(4) == 2 &&
                        r.getBoolean(5) && r.getString(6) == "plpgsql" && r.getString(7) == "v" && r.getBoolean(8) &&
                        r.getString(9) == "u" && !r.getBoolean(10) &&
                        r.getArray(11).array.let { it is Array<*> && it.toList() == listOf("search_path=pg_catalog, pg_temp", "row_security=off") } &&
                        r.getString(12) == staffTotpSourceSha256 && r.getBoolean(13) && r.getBoolean(14) &&
                        !r.getBoolean(15) && r.getLong(16) == 1L && !r.next())
                }
            }
            for ((table, columns) in staffColumns) {
                c.prepareStatement("SELECT c.relkind,c.relrowsecurity,c.relforcerowsecurity," +
                    "EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) " +
                    "FROM pg_catalog.pg_class c WHERE c.oid=pg_catalog.to_regclass(?)").use { s ->
                    s.setString(1, "staff.$table"); s.executeQuery().use { r ->
                        check(r.next() && r.getString(1) == "r" && r.getBoolean(2) && r.getBoolean(3) && !r.getBoolean(4) && !r.next())
                    }
                }
                c.prepareStatement("SELECT a.attname,n.nspname||'.'||t.typname,a.attnotnull FROM pg_catalog.pg_attribute a " +
                    "JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace " +
                    "WHERE a.attrelid=pg_catalog.to_regclass(?) AND a.attnum>0 AND NOT a.attisdropped").use { s ->
                    s.setString(1, "staff.$table"); s.executeQuery().use { r ->
                        val actual = mutableMapOf<String, String>()
                        while (r.next()) { check(r.getBoolean(3)); actual[r.getString(1)] = r.getString(2) }
                        // New policy/enrollment fields require review, not silent omission.
                        check(actual == columns)
                    }
                }
            }
            for ((table, column, type) in providerColumns) c.prepareStatement(
                "SELECT n.nspname||'.'||t.typname FROM pg_catalog.pg_attribute a " +
                    "JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace " +
                    "WHERE a.attrelid=pg_catalog.to_regclass(?) AND a.attname=? AND a.attnum>0 AND NOT a.attisdropped").use { s ->
                s.setString(1, table); s.setString(2, column); s.executeQuery().use { r ->
                    check(r.next() && r.getString(1) == type && !r.next())
                }
            }
        } catch (e: CancellationException) { throw e }
          catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
          catch (_: Exception) { throw SupabaseStaffFailure(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED) }
    }

    private val staffColumns = mapOf(
        "publication_policies" to mapOf("environment" to "pg_catalog.varchar", "version" to "pg_catalog.varchar",
            "issuer" to "pg_catalog.varchar", "client_id" to "pg_catalog.varchar", "audience" to "pg_catalog.varchar",
            "enabled" to "pg_catalog.bool", "not_before" to "pg_catalog.timestamptz", "valid_until" to "pg_catalog.timestamptz"),
        "actors" to mapOf("environment" to "pg_catalog.varchar", "actor_id" to "pg_catalog.uuid", "issuer" to "pg_catalog.varchar",
            "subject" to "pg_catalog.varchar", "can_publish" to "pg_catalog.bool", "can_review" to "pg_catalog.bool",
            "enabled" to "pg_catalog.bool", "token_valid_after" to "pg_catalog.timestamptz",
            "not_before" to "pg_catalog.timestamptz", "valid_until" to "pg_catalog.timestamptz"),
    )
    private val providerColumns = listOf(
        Triple("auth.sessions", "factor_id", "pg_catalog.uuid"),
        Triple("auth.mfa_factors", "factor_type", "auth.factor_type"),
    )
    private const val staffTotpSourceSha256 = "5bee9c962edcd245c3e4131b9bbad3036bece2cf7519919011e7bcaf8c4d448f"
    private val checksum by lazy {
        checkNotNull(javaClass.getResourceAsStream("/db/migration/V040__staff_publication_approvals.sql")).use {
            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) }
        }
    }
}
