package com.feedme.server.staff

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Observes V077 and required read/lock rights only. Never creates an enrollment or
 * grants access. Serving may SELECT and execute the exact locking helper, not write
 * moderator authority, policies or staff identity. Catalog compatibility is separate. */
internal object SupabaseStaffModerationServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.isClosed && !c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff moderation compatibility interrupted")
            c.prepareStatement("SELECT description,checksum FROM platform.schema_migrations WHERE version=77").use { s ->
                s.executeQuery().use { r -> check(r.next() && r.getString(1) == "staff_moderator_enrollments" &&
                    r.getString(2) == sha(source) && !r.next()) }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT r.rolsuper,r.rolbypassrls,current_setting('session_replication_role')='origin'," +
                    "m.relkind,m.relrowsecurity,m.relforcerowsecurity,m.relowner<>r.oid," +
                    "NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=m.oid OR i.inhparent=m.oid)," +
                    "has_table_privilege(current_user,m.oid,'SELECT')," +
                    "has_table_privilege(current_user,m.oid,'SELECT WITH GRANT OPTION')," +
                    "has_table_privilege(current_user,m.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')," +
                    "NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(m.relacl,acldefault('r',m.relowner))) a " +
                    "WHERE a.grantee=0 OR a.grantee IN (SELECT oid FROM pg_catalog.pg_roles WHERE rolname IN ('anon','authenticated','service_role')))," +
                    "m.relowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='staff.actors'::regclass)," +
                    "NOT EXISTS(SELECT 1 FROM pg_catalog.pg_policy p WHERE p.polrelid=m.oid) " +
                    "FROM pg_catalog.pg_roles r CROSS JOIN pg_catalog.pg_class m " +
                    "WHERE r.rolname=current_user AND m.oid=to_regclass('staff.moderator_enrollments')").use { r ->
                    check(r.next() && !r.getBoolean(1) && r.getBoolean(2) && r.getBoolean(3) && r.getString(4) == "r" &&
                        r.getBoolean(5) && r.getBoolean(6) && r.getBoolean(7) && r.getBoolean(8) && r.getBoolean(9) &&
                        !r.getBoolean(10) && !r.getBoolean(11) && r.getBoolean(12) && r.getBoolean(13) && r.getBoolean(14) && !r.next())
                }
                s.executeQuery("SELECT a.attname,n.nspname||'.'||t.typname,a.attnotnull,a.atttypmod," +
                    "has_column_privilege(current_user,a.attrelid,a.attnum,'INSERT,UPDATE,REFERENCES')," +
                    "has_column_privilege(current_user,a.attrelid,a.attnum,'SELECT WITH GRANT OPTION')," +
                    "NOT EXISTS(SELECT 1 FROM aclexplode(a.attacl) x WHERE x.grantee=0 OR x.grantee IN " +
                    "(SELECT oid FROM pg_catalog.pg_roles WHERE rolname IN ('anon','authenticated','service_role')))," +
                    "pg_get_expr(d.adbin,d.adrelid) FROM pg_catalog.pg_attribute a " +
                    "JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace " +
                    "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum " +
                    "WHERE a.attrelid='staff.moderator_enrollments'::regclass AND a.attnum>0 AND NOT a.attisdropped").use { r ->
                    val actual = mutableMapOf<String, Pair<String, Int>>()
                    while (r.next()) {
                        val name = r.getString(1)
                        check(r.getBoolean(3) && !r.getBoolean(5) && !r.getBoolean(6) && r.getBoolean(7) &&
                            r.getString(8) == (if (name == "enabled") "false" else null))
                        actual[name] = r.getString(2) to r.getInt(4)
                    }
                    check(actual == columns)
                }
                s.executeQuery("SELECT contype::text,convalidated,condeferrable,condeferred," +
                    "ARRAY(SELECT a.attname FROM unnest(conkey) WITH ORDINALITY k(n,ordinality) " +
                    "JOIN pg_catalog.pg_attribute a ON a.attrelid=conrelid AND a.attnum=k.n ORDER BY k.ordinality)," +
                    "confrelid='staff.actors'::regclass," +
                    "ARRAY(SELECT a.attname FROM unnest(confkey) WITH ORDINALITY k(n,ordinality) " +
                    "JOIN pg_catalog.pg_attribute a ON a.attrelid=confrelid AND a.attnum=k.n ORDER BY k.ordinality)," +
                    "confupdtype::text,confdeltype::text FROM pg_catalog.pg_constraint " +
                    "WHERE conrelid='staff.moderator_enrollments'::regclass AND contype IN ('p','f')").use { r ->
                    val found = mutableSetOf<String>()
                    while (r.next()) {
                        val type = r.getString(1)
                        check(found.add(type) && r.getBoolean(2) && !r.getBoolean(3) && !r.getBoolean(4) &&
                            (r.getArray(5).array as Array<*>).map { it.toString() } == listOf("environment", "actor_id"))
                        if (type == "f") check(r.getBoolean(6) &&
                            (r.getArray(7).array as Array<*>).map { it.toString() } == listOf("environment", "actor_id") &&
                            r.getString(8) == "a" && r.getString(9) == "a")
                    }
                    check(found == setOf("p", "f"))
                }
                s.executeQuery("SELECT t.tgname,t.tgenabled::text,t.tgfoid='staff.guard_moderator_enrollment()'::regprocedure," +
                    "t.tgtype::text,t.tgqual IS NULL AND t.tgnargs=0 AND t.tgattr=''::int2vector " +
                    "FROM pg_catalog.pg_trigger t WHERE t.tgrelid='staff.moderator_enrollments'::regclass AND NOT t.tgisinternal").use { r ->
                    val actual = mutableSetOf<Pair<String, String>>()
                    while (r.next()) {
                        check(r.getString(2) in setOf("O", "A") && r.getBoolean(3) && r.getBoolean(5))
                        actual += r.getString(1) to r.getString(4)
                    }
                    check(actual == setOf("staff_moderator_enrollment_guard" to "31", "staff_moderator_enrollment_retained" to "34"))
                }
                s.executeQuery("SELECT environment,actor_id,version,policy_version,enabled,not_before,valid_until " +
                    "FROM ONLY staff.moderator_enrollments WHERE false").close()
            }
            function(c, "staff.guard_moderator_enrollment()", false, "trigger", guardBody,
                setOf("search_path=pg_catalog, pg_temp"))
            function(c, "staff.lock_moderation_actor(text,text,text)", true, "boolean", lockBody,
                setOf("search_path=pg_catalog, pg_temp", "row_security=off"))
        } catch (e: CancellationException) { throw e }
          catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
          catch (_: Exception) { throw SupabaseStaffFailure(SupabaseStaffFailureCode.STAFF_NOT_CONFIGURED) }
    }

    private fun function(c: Connection, name: String, definer: Boolean, result: String, body: String, settings: Set<String>) {
        c.prepareStatement("SELECT p.prokind::text,p.prosecdef,p.proleakproof,p.provolatile::text,p.proparallel::text," +
            "p.prosrc,p.proconfig,p.prorettype=to_regtype(?),l.lanname,p.proisstrict,p.proretset,p.pronargdefaults," +
            "p.proowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='staff.moderator_enrollments'::regclass)," +
            "NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0 " +
            "OR a.grantee IN (SELECT oid FROM pg_catalog.pg_roles WHERE rolname IN ('anon','authenticated','service_role')))," +
            "has_function_privilege(current_user,p.oid,'EXECUTE'),has_function_privilege(current_user,p.oid,'EXECUTE WITH GRANT OPTION')," +
            "p.pronargs,p.proargnames,p.proargmodes IS NULL,p.proallargtypes IS NULL," +
            "p.prosupport=0 AND p.probin IS NULL AND p.prosqlbody IS NULL AND p.protrftypes IS NULL " +
            "FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang WHERE p.oid=to_regprocedure(?)").use { s ->
            s.setString(1, result); s.setString(2, name)
            s.executeQuery().use { r ->
                check(r.next() && r.getString(1) == "f" && r.getBoolean(2) == definer && !r.getBoolean(3) &&
                    r.getString(4) == "v" && r.getString(5) == "u" && r.getString(6) == body &&
                    (r.getArray(7).array as Array<*>).map { it.toString() }.toSet() == settings && r.getBoolean(8) &&
                    r.getString(9) == "plpgsql" && !r.getBoolean(10) && !r.getBoolean(11) && r.getInt(12) == 0 &&
                    r.getBoolean(13) && r.getBoolean(14) && (!definer || r.getBoolean(15)) && !r.getBoolean(16) &&
                    r.getInt(17) == (if (definer) 3 else 0) &&
                    ((r.getArray(18)?.array as? Array<*>)?.map { it.toString() } ?: emptyList()) ==
                        (if (definer) listOf("p_environment", "p_issuer", "p_subject") else emptyList()) &&
                    r.getBoolean(19) && r.getBoolean(20) && r.getBoolean(21) && !r.next())
            }
        }
    }
    private val columns = mapOf(
        "environment" to ("pg_catalog.varchar" to 44), "actor_id" to ("pg_catalog.uuid" to -1),
        "version" to ("pg_catalog.int8" to -1), "policy_version" to ("pg_catalog.varchar" to 132),
        "enabled" to ("pg_catalog.bool" to -1), "not_before" to ("pg_catalog.timestamptz" to -1),
        "valid_until" to ("pg_catalog.timestamptz" to -1),
    )
    private val source by lazy {
        checkNotNull(javaClass.getResourceAsStream("/db/migration/V077__staff_moderator_enrollments.sql"))
            .use { it.readBytes().decodeToString() }
    }
    private fun body(marker: String): String {
        check(source.split(marker).size == 3)
        return source.substringAfter("AS $marker").substringBefore(marker)
    }
    private val guardBody by lazy { body("\$feedme_moderator_guard\$") }
    private val lockBody by lazy { body("\$feedme_moderator_lock\$") }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
