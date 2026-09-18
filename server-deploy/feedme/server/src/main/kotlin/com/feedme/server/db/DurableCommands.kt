package com.feedme.server.db

import com.feedme.server.contract.ContractCatalog
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlinx.serialization.json.*

enum class CommandActor { ACCOUNT, GUEST, STAFF }

/** Caller must construct this from verified server identity, never from a request owner ID. */
class PrincipalScope(environment: String, actor: CommandActor, id: UUID) {
    val storageKey: String
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        storageKey = "$environment:${actor.name.lowercase()}:$id"
    }
    override fun toString() = "PrincipalScope([redacted])"
}

/** Canonical, versioned fingerprint; does not retain credentials or raw request bodies in the database. */
class CommandIdentity(
    val scope: PrincipalScope,
    val operationId: String,
    val key: UUID,
    pathParameters: Map<String, String> = emptyMap(),
    queryParameters: Map<String, List<String>> = emptyMap(),
    body: JsonElement? = null,
    ifMatch: String? = null,
    catalog: ContractCatalog = ContractCatalog.bundled(),
) {
    val requestHash: String
    init {
        val operation = catalog.operations.singleOrNull { it.id == operationId }
            ?: throw IllegalArgumentException("Unknown canonical operation")
        val schema = catalog.document.getValue("paths").jsonObject.getValue(operation.path).jsonObject
            .getValue(operation.method.lowercase()).jsonObject
        require(schema.getValue("x-idempotency-required").jsonPrimitive.boolean) { "Operation does not use durable command keys" }
        // Bootstrap has no verified principal yet; webhooks use a provider-specific dedupe design.
        require(operation.principal in setOf("user", "both", "admin")) { "Anonymous/bootstrap commands require their own identity design" }
        val actor = scope.storageKey.split(':')[1]
        require(when (operation.principal) { "admin" -> actor == "staff"; "user" -> actor == "account"; else -> actor in setOf("account", "guest") })
        val pathKeys = Regex("\\{([^}]+)}").findAll(operation.path).map { it.groupValues[1] }.toSet()
        require(pathParameters.keys == pathKeys) { "Path parameters do not match the canonical route" }
        fun checkedText(value: String) { require(value.length <= 1024 && value.none { it.code < 32 || it.code == 127 }) }
        pathParameters.values.forEach { checkedText(it); require(it.isNotEmpty()) }
        queryParameters.forEach { (name, values) -> checkedText(name); values.forEach(::checkedText) }
        ifMatch?.let { checkedText(it); require(it.isNotEmpty()) }
        val material = buildJsonObject {
            put("fingerprintVersion", 1)
            put("operationId", operationId)
            put("method", operation.method)
            put("route", operation.path)
            put("path", JsonObject(pathParameters.mapValues { JsonPrimitive(it.value) }))
            put("query", JsonObject(queryParameters.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
            put("bodyPresent", body != null)
            put("body", body ?: JsonNull)
            put("ifMatch", ifMatch?.let(::JsonPrimitive) ?: JsonNull)
        }
        require(material.toString().toByteArray(Charsets.UTF_8).size <= 262144) { "Command fingerprint input exceeds bound" }
        requestHash = MessageDigest.getInstance("SHA-256").digest(canonicalJson(material).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    override fun toString() = "CommandIdentity(operationId=$operationId, identity=[redacted])"
}

private fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, v) -> "${JsonPrimitive(key)}:${canonicalJson(v)}" }
    is JsonArray -> value.joinToString(",", "[", "]") { canonicalJson(it) }
    is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
        else BigDecimal(value.content).stripTrailingZeros().toString()
}

/** Only durable success responses. Authorization, transient failures and one-time secrets are not cached. */
class StoredReply(val status: Int, body: JsonElement? = null, val etag: String? = null) {
    val body: JsonElement? = body?.let { Json.parseToJsonElement(it.toString()) }
    init {
        require(status in 200..299)
        require((status in setOf(204, 205)) == (body == null)) { "No-content responses and JSON bodies must remain distinct" }
        require(body?.toString()?.toByteArray(Charsets.UTF_8)?.size?.let { it <= 262144 } ?: true)
        require(etag == null || (etag.isNotEmpty() && etag.length <= 256 && etag.none { it.code < 32 || it.code == 127 }))
    }
    override fun toString() = "StoredReply(status=$status, body=[redacted], etag=[redacted])"
}

sealed class CommandResult {
    class Applied(val reply: StoredReply) : CommandResult()
    class Replayed(val reply: StoredReply) : CommandResult()
    data object Mismatch : CommandResult()
    data object ReceiptExpired : CommandResult()
    data object IncompleteReceipt : CommandResult()
}

/**
 * Same-key transactions serialize on the canonical primary key. All callbacks run inside the DB transaction.
 * Identity/session check always precedes receipt access. New execution and replay need distinct authorization:
 * replay of a successful delete must be able to authorize its receipt even though the target no longer exists.
 * The hooks must lock the same policy roots as revocation, in the documented global lock order.
 */
class DurableCommands(private val transactions: PgTransactions) {
    fun execute(
        command: CommandIdentity,
        validatePrincipal: (Connection) -> Unit,
        authorizeNew: (Connection) -> Unit,
        authorizeReplay: (Connection, StoredReply) -> Unit,
        mutate: (Connection) -> StoredReply,
    ): CommandResult = transactions.run { connection ->
        executeInTransaction(connection, command, validatePrincipal, authorizeNew, authorizeReplay, mutate)
    }

    /** Caller owns the SAME transaction as first-use identity mapping. No nested commit or
     * alternate replay implementation. All original principal, expiry and authorization checks apply. */
    internal fun executeInTransaction(
        connection: Connection,
        command: CommandIdentity,
        validatePrincipal: (Connection) -> Unit,
        authorizeNew: (Connection) -> Unit,
        authorizeReplay: (Connection, StoredReply) -> Unit,
        mutate: (Connection) -> StoredReply,
    ): CommandResult {
        require(!connection.autoCommit) { "A command requires an owned database transaction" }
        validatePrincipal(connection)
        val inserted = connection.prepareStatement("""
            INSERT INTO platform.idempotency(principal_scope,operation_id,key,request_hash,state,expires_at)
            VALUES (?,?,?,?,'pending',clock_timestamp()+interval '7 days') ON CONFLICT DO NOTHING
        """.trimIndent()).use { statement ->
            bindKey(statement, command)
            statement.setString(4, command.requestHash)
            statement.executeUpdate() == 1
        }
        connection.prepareStatement("""
            SELECT * FROM platform.idempotency
            WHERE principal_scope=? AND operation_id=? AND key=? FOR UPDATE
        """.trimIndent()).use { statement ->
            bindKey(statement, command)
            statement.executeQuery().use { row ->
                check(row.next()) { "Command receipt disappeared" }
                if (row.getString("request_hash") != command.requestHash) return CommandResult.Mismatch
                if (!inserted) {
                    if (row.getString("state") == "tombstone") return CommandResult.ReceiptExpired
                    if (row.getString("state") != "completed") return CommandResult.IncompleteReceipt
                    if (expireLockedReceipt(connection, command)) {
                        return CommandResult.ReceiptExpired
                    }
                    val reply = readReply(row)
                    authorizeReplay(connection, reply)
                    // Authorization can wait on a policy lock. Re-evaluate DB time after that wait.
                    if (expireLockedReceipt(connection, command)) return CommandResult.ReceiptExpired
                    return CommandResult.Replayed(reply)
                }
            }
        }
        authorizeNew(connection)
        val reply = mutate(connection)
        connection.prepareStatement("""
            UPDATE platform.idempotency SET state='completed', response_code=?, response_json=?::jsonb,
            response_etag=?,updated_at=clock_timestamp(),expires_at=clock_timestamp()+interval '7 days'
            WHERE principal_scope=? AND operation_id=? AND key=? AND state='pending'
        """.trimIndent()).use { statement ->
            statement.setInt(1, reply.status)
            statement.setString(2, reply.body?.toString())
            statement.setString(3, reply.etag)
            statement.setString(4, command.scope.storageKey)
            statement.setString(5, command.operationId)
            statement.setObject(6, command.key)
            check(statement.executeUpdate() == 1)
        }
        return CommandResult.Applied(reply)
    }

    /** Bounded retention sweep; scheduler wiring and approved tombstone purge remain separate work. */
    fun compactExpired(batchSize: Int = 100): Int {
        require(batchSize in 1..100)
        return transactions.run { connection ->
            connection.prepareStatement("""
                WITH expired AS (
                    SELECT principal_scope,operation_id,key FROM platform.idempotency
                    WHERE state='completed' AND expires_at<=clock_timestamp()
                    ORDER BY expires_at,principal_scope,operation_id,key LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE platform.idempotency i SET state='tombstone',response_code=NULL,response_json=NULL,response_etag=NULL,
                    tombstoned_at=clock_timestamp(),updated_at=clock_timestamp()
                FROM expired e WHERE i.principal_scope=e.principal_scope AND i.operation_id=e.operation_id AND i.key=e.key
            """.trimIndent()).use { it.setInt(1,batchSize);it.executeUpdate() }
        }
    }

    private fun expireLockedReceipt(connection: Connection, command: CommandIdentity): Boolean =
        connection.prepareStatement("""
            UPDATE platform.idempotency SET state='tombstone',response_code=NULL,response_json=NULL,response_etag=NULL,
                tombstoned_at=clock_timestamp(),updated_at=clock_timestamp()
            WHERE principal_scope=? AND operation_id=? AND key=? AND state='completed' AND expires_at<=clock_timestamp()
        """.trimIndent()).use { bindKey(it,command);it.executeUpdate()==1 }

    private fun readReply(row: ResultSet) = StoredReply(row.getInt("response_code"),
        row.getString("response_json")?.let(Json::parseToJsonElement), row.getString("response_etag"))

    private fun bindKey(statement: java.sql.PreparedStatement, command: CommandIdentity) {
        statement.setString(1, command.scope.storageKey)
        statement.setString(2, command.operationId)
        statement.setObject(3, command.key)
    }
}
