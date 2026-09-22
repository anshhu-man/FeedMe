package com.feedme.server.staff

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only qualification admission. Serving can observe and hold row locks, not
 * enroll, renew, revoke or rewrite professional qualifications. No grant installer. */
internal object SupabaseStaffRecipeQualificationServingCompatibility {
    fun check(c: Connection) {
        try {
            require(!c.isClosed && !c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff qualification compatibility interrupted")
            c.prepareStatement("SELECT description,checksum FROM platform.schema_migrations WHERE version=72").use { s ->
                s.executeQuery().use { r ->
                    check(r.next() && r.getString(1) == "staff_recipe_reviewer_qualifications" && r.getString(2) == sha(source) && !r.next())
                }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT r.rolsuper,r.rolbypassrls,current_setting('session_replication_role')='origin'," +
                    "q.relkind,q.relrowsecurity,q.relforcerowsecurity,q.relowner<>r.oid," +
                    "NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=q.oid OR i.inhparent=q.oid)," +
                    "has_table_privilege(current_user,q.oid,'SELECT')," +
                    "has_table_privilege(current_user,q.oid,'SELECT WITH GRANT OPTION')," +
                    "has_table_privilege(current_user,q.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')," +
                    "NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(q.relacl,acldefault('r',q.relowner))) a WHERE a.grantee=0)," +
                    "q.relowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='staff.actors'::regclass) " +
                    "FROM pg_catalog.pg_roles r CROSS JOIN pg_catalog.pg_class q " +
                    "WHERE r.rolname=current_user AND q.oid=to_regclass('staff.reviewer_qualifications')").use { r ->
                    check(r.next() && !r.getBoolean(1) && r.getBoolean(2) && r.getBoolean(3) && r.getString(4) == "r" &&
                        r.getBoolean(5) && r.getBoolean(6) && r.getBoolean(7) && r.getBoolean(8) && r.getBoolean(9) &&
                        !r.getBoolean(10) && !r.getBoolean(11) && r.getBoolean(12) && r.getBoolean(13) && !r.next())
                }
                s.executeQuery("SELECT a.attname,n.nspname||'.'||t.typname,a.attnotnull,a.atttypmod," +
                    "has_column_privilege(current_user,a.attrelid,a.attnum,'UPDATE')," +
                    "has_column_privilege(current_user,a.attrelid,a.attnum,'UPDATE WITH GRANT OPTION')," +
                    "has_column_privilege(current_user,a.attrelid,a.attnum,'INSERT,REFERENCES')," +
                    "NOT EXISTS(SELECT 1 FROM aclexplode(a.attacl) x WHERE x.grantee=0) " +
                    "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid " +
                    "JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace " +
                    "WHERE a.attrelid='staff.reviewer_qualifications'::regclass AND a.attnum>0 AND NOT a.attisdropped").use { r ->
                    val actual = mutableMapOf<String, Pair<String, Int>>()
                    while (r.next()) {
                        val name = r.getString(1)
                        check(r.getBoolean(3) && r.getBoolean(5) == (name == "qualification_id") &&
                            !r.getBoolean(6) && !r.getBoolean(7) && r.getBoolean(8))
                        actual[name] = r.getString(2) to r.getInt(4)
                    }
                    check(actual == columns)
                }
                s.executeQuery("SELECT conname,contype::text,convalidated,condeferrable,condeferred," +
                    "ARRAY(SELECT a.attname FROM unnest(conkey) WITH ORDINALITY k(n,ordinality) " +
                    "JOIN pg_catalog.pg_attribute a ON a.attrelid=conrelid AND a.attnum=k.n ORDER BY k.ordinality)," +
                    "confrelid='staff.actors'::regclass," +
                    "ARRAY(SELECT a.attname FROM unnest(confkey) WITH ORDINALITY k(n,ordinality) " +
                    "JOIN pg_catalog.pg_attribute a ON a.attrelid=confrelid AND a.attnum=k.n ORDER BY k.ordinality)," +
                    "confupdtype::text,confdeltype::text " +
                    "FROM pg_catalog.pg_constraint WHERE conrelid='staff.reviewer_qualifications'::regclass AND contype IN ('p','f')").use { r ->
                    val found = mutableSetOf<String>()
                    while (r.next()) {
                        val type = r.getString(2)
                        check(found.add(type) && r.getBoolean(3) && !r.getBoolean(4) && !r.getBoolean(5))
                        val keys = (r.getArray(6).array as Array<*>).map { it.toString() }
                        if (type == "p") check(keys == listOf("environment", "qualification_id"))
                        else check(keys == listOf("environment", "actor_id") && r.getBoolean(7) &&
                            (r.getArray(8).array as Array<*>).map { it.toString() } == listOf("environment", "actor_id") &&
                            r.getString(9) == "a" && r.getString(10) == "a")
                    }
                    check(found == setOf("p", "f"))
                }
                s.executeQuery("SELECT t.tgname,t.tgenabled::text,t.tgfoid='staff.guard_recipe_reviewer_qualification()'::regprocedure," +
                    "t.tgtype::text,t.tgqual IS NULL AND t.tgnargs=0 AND t.tgattr=''::int2vector " +
                    "FROM pg_catalog.pg_trigger t WHERE t.tgrelid='staff.reviewer_qualifications'::regclass AND NOT t.tgisinternal").use { r ->
                    val actual = mutableSetOf<Pair<String, String>>()
                    while (r.next()) {
                        check(r.getString(2) in setOf("O", "A") && r.getBoolean(3) && r.getBoolean(5))
                        actual += r.getString(1) to r.getString(4)
                    }
                    check(actual == setOf("staff_reviewer_qualification_immutable" to "27", "staff_reviewer_qualification_retained" to "34"))
                }
                s.executeQuery("SELECT p.prokind::text,p.prosecdef,p.proleakproof,p.provolatile::text,p.proparallel::text," +
                    "p.prosrc,p.proconfig,p.prorettype='trigger'::regtype,l.lanname," +
                    "p.proowner=(SELECT relowner FROM pg_catalog.pg_class WHERE oid='staff.reviewer_qualifications'::regclass)," +
                    "NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) " +
                    "FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_language l ON l.oid=p.prolang " +
                    "WHERE p.oid=to_regprocedure('staff.guard_recipe_reviewer_qualification()')").use { r ->
                    check(r.next() && r.getString(1) == "f" && !r.getBoolean(2) && !r.getBoolean(3) &&
                        r.getString(4) == "v" && r.getString(5) == "u" && r.getString(6) == guardBody &&
                        (r.getArray(7).array as Array<*>).map { it.toString() }.toSet() == setOf("search_path=pg_catalog, pg_temp") &&
                        r.getBoolean(8) && r.getString(9) == "plpgsql" && r.getBoolean(10) && r.getBoolean(11) && !r.next())
                }
                s.executeQuery("SELECT qualification_id FROM ONLY staff.reviewer_qualifications WHERE false FOR SHARE").close()
            }
        } catch (f: CancellationException) { throw f }
          catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
          catch (_: Exception) { throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.NOT_CONFIGURED) }
    }
    private val source by lazy {
        checkNotNull(javaClass.getResourceAsStream("/db/migration/V072__staff_recipe_reviewer_qualifications.sql"))
            .use { it.readBytes().decodeToString() }
    }
    private val guardBody by lazy {
        val marker = "\$feedme_reviewer_qualification_guard\$"
        check(source.split(marker).size == 3)
        source.substringAfter("AS $marker").substringBefore(marker)
    }
    private val columns = mapOf(
        "environment" to ("pg_catalog.varchar" to 44), "qualification_id" to ("pg_catalog.uuid" to -1),
        "version" to ("pg_catalog.int8" to -1), "actor_id" to ("pg_catalog.uuid" to -1),
        "scope" to ("pg_catalog.varchar" to 68), "evidence_reference" to ("pg_catalog.varchar" to 260),
        "policy_version" to ("pg_catalog.varchar" to 132), "verified_at" to ("pg_catalog.timestamptz" to -1),
        "not_before" to ("pg_catalog.timestamptz" to -1), "valid_until" to ("pg_catalog.timestamptz" to -1),
        "enabled" to ("pg_catalog.bool" to -1),
    )
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
