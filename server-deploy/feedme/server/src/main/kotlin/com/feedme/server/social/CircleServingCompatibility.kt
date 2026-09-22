package com.feedme.server.social

import java.sql.Connection
import kotlinx.coroutines.CancellationException

/** Read-only permission admission for the existing circle store, not a grant installer.
 * Exact migration history is checked by the owning runtime. These probes inspect privileges
 * and compile empty reads: no domain writes, invitation issue, sequence allocation or repair.
 * The canonical restricted account role currently lacks this social authority and must fail
 * optional feature startup rather than return empty lists or silently use an owner connection.
 */
internal object CircleServingCompatibility {
    fun check(connection: Connection, circleCreationEnabled: Boolean, invitationCreationEnabled: Boolean) {
        try {
            require(!connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_catalog.pg_roles WHERE rolname=current_user").use {
                    check(it.next() && it.getBoolean(1) && !it.next())
                }
                for ((table, lock) in reads) {
                    statement.executeQuery("SELECT * FROM $table WHERE false" + if (lock) " FOR SHARE NOWAIT" else "").use {
                        check(!it.next())
                    }
                }
            }
            if (circleCreationEnabled) rights(connection, "social.circles", "INSERT",
                "environment,id,owner_id,name,description,status,member_limit,version")
            rights(connection, "social.circles", "UPDATE", "owner_id,name,description,status,version,updated_at")
            // Accepting an already-issued invitation still needs membership insertion when
            // new circle/invitation creation is disabled; it does not allocate a new token.
            rights(connection, "social.circle_members", "INSERT", "environment,circle_id,user_id,id,role,status,generation,version")
            rights(connection, "social.circle_members", "UPDATE",
                "role,status,generation,version,removal_key,removal_operation,removed_by,removed_generation,updated_at,joined_at")
            rights(connection, "social.circle_invitations", "UPDATE", "status,consumed_by,consumed_generation,version,updated_at")
            if (invitationCreationEnabled) {
                rights(connection, "social.circle_invitations", "INSERT",
                    "environment,id,circle_id,created_by,token_hash,token_key_id,issuer_generation,issuer_version,status,version,created_at,updated_at,expires_at")
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_catalog.has_sequence_privilege(current_user,'social.relationship_order','USAGE')").use {
                        check(it.next() && it.getBoolean(1) && !it.next())
                    }
                }
            }
            rights(connection, "platform.idempotency", "INSERT", "principal_scope,operation_id,key,request_hash,state,expires_at")
            rights(connection, "platform.idempotency", "UPDATE",
                "state,response_code,response_json,response_etag,updated_at,expires_at,tombstoned_at")
            rights(connection, "platform.outbox", "INSERT",
                "event_id,event_type,schema_version,aggregate_type,aggregate_id,aggregate_version,producer,correlation_id,causation_id,payload,owner_environment,owner_kind,owner_id")
        } catch (failure: SocialFailure) { throw failure }
          catch (failure: CancellationException) { throw failure }
          catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
          catch (_: Exception) { throw SocialFailure(SocialFailureCode.NOT_CONFIGURED) }
    }

    private fun rights(connection: Connection, table: String, privilege: String, columns: String) {
        // All identifiers and privilege names above are fixed source constants. Bound
        // column names support exact column grants; whole-table mutation is not required.
        connection.prepareStatement("SELECT pg_catalog.bool_and(pg_catalog.has_column_privilege(current_user,?::text,column_name,?::text)) " +
            "FROM pg_catalog.unnest(pg_catalog.string_to_array(?::text,',')) AS names(column_name)").use { statement ->
            statement.setString(1, table); statement.setString(2, privilege); statement.setString(3, columns)
            statement.executeQuery().use { check(it.next() && it.getBoolean(1) && !it.next()) }
        }
    }

    private val reads = listOf(
        "identity.users" to true, "identity.principals" to true, "profile.profiles" to true,
        "social.circles" to true, "social.circle_members" to false, "social.circle_invitations" to true,
        "social.blocks" to false, "social.block_pairs" to false, "platform.idempotency" to true,
    )
}
