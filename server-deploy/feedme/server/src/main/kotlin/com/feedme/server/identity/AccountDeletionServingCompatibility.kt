package com.feedme.server.identity

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Optional serving admission, not a grant installer or worker readiness claim. The fixed
 * bundled resource's prefix is metadata/lock-only; its explicit opt-in grant suffix is
 * never executed here. Run inside the runtime's own read-committed transaction, at startup
 * and health observation. No accepted account or provider data is selected or changed.
 * Per-request proof, ownership, immutable-source and commit checks remain in the store. */
internal object AccountDeletionServingCompatibility {
    fun check(connection: Connection, store: AccountDeletionStore) {
        try {
            required(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_user='feedme_api'").use { rows ->
                    required(rows.next() && rows.getBoolean(1) && !rows.next())
                }
                // Check effective serving privileges first: absent optional grants must
                // fail closed, never execute the installer or repair a deployment.
                statement.executeQuery("SELECT " +
                    "pg_catalog.has_table_privilege(current_user,'identity.account_deletion_jobs','SELECT')," +
                    "pg_catalog.has_table_privilege(current_user,'identity.account_deletion_devices','SELECT')," +
                    "pg_catalog.has_function_privilege(current_user,'$ACCEPTANCE','EXECUTE')").use { rows ->
                    required(rows.next() && (1..3).all(rows::getBoolean) && !rows.next())
                }
                statement.execute(boundary)
            }
            // The store compares exact bundled V041 bytes and all eight trigger bodies
            // independently, in addition to the provisioning resource's SHA-256 pins.
            store.checkCompatibility(connection)
        } catch (failure: AccountFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { unavailable() }
    }

    internal const val ACCEPTANCE = "identity.accept_account_deletion(text,uuid,uuid,text,uuid,uuid,uuid,uuid,text,uuid,text,text,uuid,bigint,timestamptz,timestamptz,timestamptz,timestamptz)"
    private const val MARKER = "-- FEEDME_DELETION_SERVING_EXPLICIT_GRANTS_BEGIN"
    private val boundary by lazy {
        val source = checkNotNull(javaClass.getResourceAsStream("/db/provider/feedme-account-deletion-serving-grants.sql"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val parts = source.split(MARKER)
        check(parts.size == 2)
        // An accidental movement of installation statements across the marker must not
        // turn read-only runtime admission into provisioning authority.
        check(!Regex("(?im)^\\s*(GRANT|REVOKE|CREATE|ALTER|DROP)\\b").containsMatchIn(parts[0]))
        parts[0]
    }
    private fun required(value: Boolean) { if (!value) unavailable() }
    private fun unavailable(): Nothing = throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
}
