package com.feedme.server.identity

import com.feedme.server.db.PgTransactions
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** A durable completion is valid only for the reviewed media-free V094 protocol. */
internal enum class AccountDeletionCompletionOutcome { COMPLETED, NOT_READY }

/**
 * Future V094 worker capability. Construction starts no worker and grants no authority.
 * The coordinator can compose it only behind an explicit default-false construction flag;
 * the production runtime does not supply that flag while V094 remains unregistered.
 * The completion UUID is deterministically derived from the fixed protocol, environment and
 * deletion job. A retry after an unknown commit therefore reproduces the exact identity rather
 * than relying on volatile process memory or inventing a replacement.
 */
internal class AccountDeletionCompletionStore(
    internal val environment: String,
    internal val transactions: PgTransactions,
) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun complete(jobId: UUID): AccountDeletionCompletionOutcome {
        require(jobId != ZERO_UUID)
        val completionId = completionId(environment, jobId)
        return transactions.run { connection ->
            checkCompatibility(connection)
            connection.prepareStatement("SELECT erasure.complete_account_deletion(?,?,?)").use { statement ->
                statement.setString(1, environment)
                statement.setObject(2, jobId)
                statement.setObject(3, completionId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) incompatible()
                    val completed = rows.getBoolean(1)
                    if (rows.wasNull() || rows.next()) incompatible()
                    if (completed) AccountDeletionCompletionOutcome.COMPLETED
                    else AccountDeletionCompletionOutcome.NOT_READY
                }
            }
        }
    }

    /** Runtime checks cannot authorize or install V094. They only refuse drift after a
     * separately reviewed registration, migration and least-privilege EXECUTE grant. */
    internal fun checkCompatibility(connection: Connection) {
        connection.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=94").use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) != V094_CANDIDATE_SHA256 || rows.next()) incompatible()
            }
        }

        connection.createStatement().use { statement -> statement.executeQuery("""
            SELECT c.relrowsecurity,c.relforcerowsecurity,c.relkind='r',(o.rolsuper OR o.rolbypassrls),
                NOT EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid),
                NOT EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid),
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a
                    WHERE a.grantee<>c.relowner),
                NOT EXISTS(SELECT 1 FROM pg_attribute a CROSS JOIN LATERAL aclexplode(a.attacl) x
                    WHERE a.attrelid=c.oid AND x.grantee<>c.relowner),
                NOT EXISTS(SELECT 1 FROM pg_attrdef d WHERE d.adrelid=c.oid),
                (SELECT array_agg(a.attname::text ORDER BY a.attnum) FROM pg_attribute a
                    WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped),
                (SELECT bool_and(a.attnotnull AND a.attgenerated='' AND a.attidentity='') FROM pg_attribute a
                    WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped),
                (SELECT count(*) FROM pg_constraint k WHERE k.conrelid=c.oid AND k.contype='c'),
                (SELECT count(*) FROM pg_constraint k WHERE k.conrelid=c.oid AND k.contype='f'),
                (SELECT count(*) FROM pg_constraint k WHERE k.conrelid=c.oid AND k.contype='p'),
                (SELECT count(*) FROM pg_constraint k WHERE k.conrelid=c.oid AND k.contype='u')
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_roles o ON o.oid=c.relowner
            WHERE n.nspname='erasure' AND c.relname='account_deletion_completions'
        """.trimIndent()).use { rows ->
            if (!rows.next()) incompatible()
            if (!(1..9).all(rows::getBoolean)) incompatible()
            if ((rows.getArray(10)?.array as? Array<*>)?.map { it.toString() } != COLUMNS) incompatible()
            if (!rows.getBoolean(11)) incompatible()
            if (rows.getLong(12) != 10L) incompatible()
            if (rows.getLong(13) != 3L) incompatible()
            if (rows.getLong(14) != 1L) incompatible()
            if (rows.getLong(15) != 4L) incompatible()
            if (rows.next()) incompatible()
        } }

        checkFunction(connection, COMPLETE_SIGNATURE, COMPLETE_BODY_SHA256, allowCurrentRole = true)
        checkFunction(connection, GUARD_SIGNATURE, GUARD_BODY_SHA256, allowCurrentRole = false)

        connection.createStatement().use { statement -> statement.executeQuery("""
            SELECT t.tgname,t.tgtype,t.tgenabled::text,t.tgisinternal,t.tgqual,t.tgattr::text,
                t.tgnargs,t.tgconstraint,p.oid::regprocedure::text,p.proowner=c.relowner
            FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_class c ON c.oid=t.tgrelid
            WHERE t.tgrelid='erasure.account_deletion_completions'::regclass AND NOT t.tgisinternal
            ORDER BY t.tgname
        """.trimIndent()).use { rows ->
            for ((name, type) in listOf(
                "account_deletion_completion_immutable" to 31,
                "account_deletion_completion_retained" to 34,
            )) {
                if (!rows.next() || rows.getString(1) != name || rows.getInt(2) != type ||
                    rows.getString(3) !in setOf("O", "A") || rows.getBoolean(4) || rows.getObject(5) != null ||
                    rows.getString(6) != "" || rows.getInt(7) != 0 || rows.getLong(8) != 0L ||
                    rows.getString(9) != GUARD_SIGNATURE || !rows.getBoolean(10)) incompatible()
            }
            if (rows.next()) incompatible()
        } }
    }

    private fun checkFunction(connection: Connection, signature: String, expectedHash: String, allowCurrentRole: Boolean) {
        connection.prepareStatement("""
            SELECT encode(sha256(convert_to(p.prosrc,'UTF8')),'hex'),p.prosecdef,
                p.proconfig,p.prokind::text,p.provolatile::text,p.proisstrict,p.proretset,
                p.prorettype::regtype::text,l.lanname,p.proowner=c.relowner,(o.rolsuper OR o.rolbypassrls),
                has_function_privilege(current_user,p.oid,'EXECUTE'),
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                    WHERE a.grantee=0),
                NOT EXISTS(SELECT 1 FROM aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
                    WHERE a.grantee<>p.proowner AND
                      (NOT ? OR a.grantee<>(SELECT oid FROM pg_roles WHERE rolname=current_user)
                       OR a.privilege_type<>'EXECUTE' OR a.is_grantable))
            FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang
            JOIN pg_roles o ON o.oid=p.proowner
            JOIN pg_class c ON c.oid='erasure.account_deletion_completions'::regclass
            WHERE p.oid=?::regprocedure
        """.trimIndent()).use { statement ->
            statement.setBoolean(1, allowCurrentRole)
            statement.setString(2, signature)
            statement.executeQuery().use { rows ->
                val returnType = if (signature == COMPLETE_SIGNATURE) "boolean" else "trigger"
                if (!rows.next() || rows.getString(1) != expectedHash || !rows.getBoolean(2) ||
                    (rows.getArray(3)?.array as? Array<*>)?.toSet() !=
                        setOf("search_path=pg_catalog, pg_temp", "row_security=off") ||
                    rows.getString(4) != "f" || rows.getString(5) != "v" || rows.getBoolean(6) ||
                    rows.getBoolean(7) || rows.getString(8) != returnType || rows.getString(9) != "plpgsql" ||
                    !rows.getBoolean(10) || !rows.getBoolean(11) ||
                    (allowCurrentRole && !rows.getBoolean(12)) || !rows.getBoolean(13) || !rows.getBoolean(14) ||
                    rows.next()) incompatible()
            }
        }
    }

    private fun incompatible(): Nothing = throw SQLException("Account deletion completion authority differs from reviewed source")
    override fun toString() = "AccountDeletionCompletionStore([redacted])"

    companion object {
        private val ZERO_UUID = UUID(0, 0)
        private val COMPLETION_DOMAIN = "feedme-account-deletion-completion-v1".toByteArray(StandardCharsets.UTF_8)
        internal const val V094_CANDIDATE_SHA256 = "cc07fc6809f1736f1c83e9e03ecc3e70f84ff898cae50544d6a442c8d480067c"
        internal const val V094_PURGE_BODY_SHA256 = "074c6653749cc3fe4d640db7d9c654e120b32497b031e199ef078aa84abfe096"
        internal const val GUARD_BODY_SHA256 = "172bbd7451e3f13f19a5953ef215912fd983274c8d8b8965360f9a5e699db631"
        internal const val COMPLETE_BODY_SHA256 = "76a733023e5c842a9e37566fd27dfb509d12c9b312b1235b6768b9a9f468677c"
        private const val COMPLETE_SIGNATURE = "erasure.complete_account_deletion(text,uuid,uuid)"
        private const val GUARD_SIGNATURE = "erasure.guard_account_deletion_completion()"
        private val COLUMNS = listOf(
            "environment", "job_id", "completion_id", "user_id", "principal_id", "provider_issuer",
            "provider_subject", "core_erased_at", "core_erased_token", "core_erased_generation",
            "provider_absent_at", "provider_absent_token", "provider_absent_generation",
            "protocol_version", "completed_at",
        )

        /** Version-8 UUID derived from fixed, unambiguous bytes; not a credential or authority. */
        internal fun completionId(environment: String, jobId: UUID): UUID {
            require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && jobId != ZERO_UUID)
            val job = ByteBuffer.allocate(16).putLong(jobId.mostSignificantBits)
                .putLong(jobId.leastSignificantBits).array()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(COMPLETION_DOMAIN)
            digest.update(0.toByte())
            digest.update(environment.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            val bytes = digest.digest(job)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val value = ByteBuffer.wrap(bytes, 0, 16)
            return UUID(value.long, value.long)
        }
    }
}
