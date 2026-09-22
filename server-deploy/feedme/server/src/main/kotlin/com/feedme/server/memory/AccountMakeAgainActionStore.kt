package com.feedme.server.memory

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Exact account-owned linkage of an original Save command and one random feedback
 * child. The ledger contains identifiers/hashes only. It never writes either child
 * on replay, and it refuses removed copies or superseded/retracted feedback. The
 * caller must seal after both kernels and revalidate/checkAt before its commit. */
internal class AccountMakeAgainActionStore(private val environment: String,
    private val connection: Connection, private val access: AccountFeedbackAccess) {
    private val actor = access.principal
    private val thread = Thread.currentThread()
    private val transaction: Long
    private val active = AtomicBoolean(false)
    private var refused = false
    private var attempted = false
    private var expected: Map<String, String>? = null
    private var entry: JsonObject? = null
    private var firstAt: Instant? = null
    private var lastAt: Instant? = null
    private var deadlines = emptyList<Instant>()
    private var sealed = false
    private var revalidated = false
    init {
        if (actor.environment != environment || actor.kind != CommandActor.ACCOUNT ||
            actor.deviceSessionId == null || actor.guestSessionId != null) unauthenticated()
        local(connection); transaction = transactionId(); bound()
    }

    fun record(parent: CommandIdentity, saveInput: JsonObject, saved: JsonObject, generation: Long,
        created: Boolean, feedbackKey: UUID, feedback: JsonObject, feedbackInput: JsonObject): Unit = checked {
        begin(parent, saveInput, saved, generation, feedbackInput)
        validate("Feedback", feedback)
        val context = FeedbackTargetContext.fromInput(feedbackInput)
        if (number(feedback, "version") != 1L || !explicitOnly(feedback) ||
            FeedbackTargetContext.fromInput(feedback).exactDocument != context.exactDocument) unavailable()
        val feedbackId = id(feedback, "id")
        val row = owned("memory.feedback", "id", feedbackId)
        val provenanceHash = feedbackSha(row.text("provenance_text"))
        if (row["provenance_sha256"] != JsonPrimitive(provenanceHash)) unavailable()
        val value = buildJsonObject {
            put("environment", environment); put("actor_kind", "account"); put("principal_id", actor.principalId.toString())
            put("principal_scope", scope()); put("parent_operation", "saveRecipe"); put("parent_key", parent.key.toString())
            put("parent_request_hash", parent.requestHash); put("saved_recipe_id", saved.getValue("id"))
            put("saved_generation", generation); put("saved_version", number(saved, "version")); put("saved_snapshot_sha256", fingerprint(saved))
            put("created_save", created); put("save_operation", "saveRecipe"); put("save_key", parent.key.toString())
            put("save_request_hash", parent.requestHash); put("feedback_id", feedbackId.toString()); put("feedback_version", 1)
            put("feedback_operation", "createFeedback"); put("feedback_key", feedbackKey.toString())
            put("feedback_context_sha256", context.sha256)
            put("feedback_request_hash", feedbackIdentity(feedbackKey, feedbackInput).requestHash)
            put("feedback_snapshot_sha256", fingerprint(feedback)); put("feedback_provenance_sha256", provenanceHash)
        }
        connection.prepareStatement("INSERT INTO memory.account_make_again_actions(environment,actor_kind,principal_id,principal_scope," +
            "parent_operation,parent_key,parent_request_hash,saved_recipe_id,saved_generation,saved_version,saved_snapshot_sha256," +
            "created_save,save_key,save_request_hash,feedback_id,feedback_key,feedback_context_sha256,feedback_request_hash," +
            "feedback_snapshot_sha256,feedback_provenance_sha256) VALUES(?,'account',?,?,'saveRecipe',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
            it.setString(1, environment); it.setObject(2, actor.principalId); it.setString(3, scope())
            it.setObject(4, parent.key); it.setString(5, parent.requestHash); it.setObject(6, id(saved, "id"))
            it.setLong(7, generation); it.setLong(8, number(saved, "version")); it.setString(9, fingerprint(saved))
            it.setBoolean(10, created); it.setObject(11, parent.key); it.setString(12, parent.requestHash)
            it.setObject(13, feedbackId); it.setObject(14, feedbackKey); it.setString(15, context.sha256)
            it.setString(16, feedbackIdentity(feedbackKey, feedbackInput).requestHash)
            it.setString(17, fingerprint(feedback)); it.setString(18, provenanceHash)
            if (it.executeUpdate() != 1) unavailable()
        }
        val actual = action(parent.key)
        if (JsonObject(actual - "created_at") != value) unavailable()
        entry = actual; firstAt = now(); bound()
    }

    fun replay(parent: CommandIdentity, saveInput: JsonObject, saved: JsonObject,
        generation: Long, feedbackInput: JsonObject): Unit = checked {
        begin(parent, saveInput, saved, generation, feedbackInput)
        val value = action(parent.key)
        if (value["parent_request_hash"] != JsonPrimitive(parent.requestHash) ||
            value["saved_recipe_id"] != saved["id"] || number(value, "saved_generation") != generation ||
            number(value, "saved_version") != number(saved, "version") || value["saved_snapshot_sha256"] != JsonPrimitive(fingerprint(saved)) ||
            value["feedback_context_sha256"] != JsonPrimitive(FeedbackTargetContext.fromInput(feedbackInput).sha256) ||
            value["feedback_request_hash"] != JsonPrimitive(feedbackIdentity(id(value, "feedback_key"), feedbackInput).requestHash)) unavailable()
        entry = value; firstAt = now(); expected = inspect(value).mapValues { fingerprint(it.value) }
        bound(); checkTime(now())
    }

    fun seal(): Unit = checked {
        if (sealed) unavailable()
        val value = entry ?: unavailable()
        bound(); val current = inspect(value).mapValues { fingerprint(it.value) }
        expected?.let { if (it != current) unavailable() }
        if (expected == null && value["created_save"] == JsonPrimitive(true)) {
            connection.prepareStatement("SELECT 1 FROM memory.save_commands WHERE environment=? AND actor_kind='account' " +
                "AND principal_id=? AND saved_recipe_id=? AND generation=? AND command_id<>? LIMIT 1").use {
                it.owner(); it.setObject(3, id(value, "saved_recipe_id")); it.setLong(4, number(value, "saved_generation"))
                it.setObject(5, id(value, "save_key")); it.executeQuery().use { rows -> if (rows.next()) unavailable() }
            }
        }
        expected = current; sealed = true; bound(); checkTime(now())
    }
    fun revalidate(): Unit = checked {
        if (!sealed) unavailable()
        bound()
        if (inspect(entry ?: unavailable()).mapValues { fingerprint(it.value) } != expected) unavailable()
        bound(); checkTime(now()); revalidated = true
    }
    /** SQL-free final clock rejection, after the owner's final accepted DB time. */
    fun checkAt(c: Connection, at: Instant): Unit = checked {
        if (!sealed || !revalidated || expected == null) unavailable()
        local(c); checkTime(at)
    }

    private fun begin(parent: CommandIdentity, saveInput: JsonObject, saved: JsonObject,
        generation: Long, feedbackInput: JsonObject) {
        if (attempted) unavailable()
        attempted = true; bound()
        if (parent.scope.storageKey != scope() || parent.operationId != "saveRecipe" ||
            parent.requestHash != CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, actor.principalId),
                "saveRecipe", parent.key, body = saveInput).requestHash) unauthenticated()
        if (validator.validateRequest("saveRecipe", saveInput.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid ||
            saveInput["markMakeAgain"] != JsonPrimitive(true) || !saveInput.containsKey("planId") ||
            validator.validateRequest("createFeedback", feedbackInput.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid ||
            feedbackInput.keys != setOf("target", "makeAgain") || !explicitOnly(feedbackInput))
            throw FeedbackFailure(FeedbackFailureCode.INPUT_INVALID)
        val context = FeedbackTargetContext.fromInput(feedbackInput)
        if (context.cookSessionId != null || context.kind != "plan" || context.resourceId != id(saveInput, "planId") || generation <= 0) unavailable()
        validate("SavedRecipe", saved)
    }

    private fun inspect(value: JsonObject): Map<String, JsonObject> {
        val action = action(id(value, "parent_key"))
        if (action != value) unavailable()
        val savedId = id(value, "saved_recipe_id"); val feedbackId = id(value, "feedback_id")
        val saved = owned("memory.saved_recipes", "id", savedId)
        if (saved["deleted"] != JsonPrimitive(false) || number(saved, "generation") != number(value, "saved_generation") ||
            number(saved, "version") != number(value, "saved_version") ||
            fingerprint(saved.getValue("snapshot")) != value.text("saved_snapshot_sha256")) conflict()
        validate("SavedRecipe", saved.getValue("snapshot").jsonObject)
        val marker = owned("memory.save_commands", "command_id", id(value, "save_key"))
        if (marker["saved_recipe_id"] != JsonPrimitive(savedId.toString()) || number(marker, "generation") != number(value, "saved_generation")) unavailable()
        val collectionId = id(marker, "collection_id")
        val collection = owned("memory.collections", "id", collectionId)
        if (collection["is_default"] != JsonPrimitive(true)) unavailable()
        val membership = image("memory.collection_items", "environment=? AND actor_kind='account' AND principal_id=? AND collection_id=? AND saved_recipe_id=?") {
            owner(); setObject(3, collectionId); setObject(4, savedId)
        }
        requireOwner(membership)
        if (membership["collection_id"] != JsonPrimitive(collectionId.toString()) || membership["saved_recipe_id"] != JsonPrimitive(savedId.toString()) ||
            number(membership, "position") <= 0) unavailable()
        val feedback = owned("memory.feedback", "id", feedbackId)
        if (feedback["deleted"] != JsonPrimitive(false) || number(feedback, "version") != 1L) conflict()
        val body = feedback.getValue("snapshot").jsonObject
        if (fingerprint(body) != value.text("feedback_snapshot_sha256") || !explicitOnly(body)) conflict()
        validate("Feedback", body)
        if (id(body, "id") != feedbackId || number(body, "version") != 1L || feedback["cook_session_id"] != JsonNull) unavailable()
        val context = feedback.text("context_text")
        if (feedbackSha(context) != value.text("feedback_context_sha256") || feedback["context_sha256"] != value["feedback_context_sha256"] ||
            FeedbackTargetContext.decode(context).exactDocument != FeedbackTargetContext.fromInput(body).exactDocument) unavailable()
        val provenance = feedback.text("provenance_text")
        if (feedbackSha(provenance) != value.text("feedback_provenance_sha256") || feedback["provenance_sha256"] != value["feedback_provenance_sha256"] ||
            feedbackCanonical(Json.parseToJsonElement(provenance)) != provenance) unavailable()
        val childKey = id(value, "feedback_key")
        val child = image("memory.feedback_commands", "principal_scope=? AND operation_id='createFeedback' AND command_key=?") {
            setString(1, scope()); setObject(2, childKey)
        }
        requireOwner(child)
        if (child["principal_scope"] != JsonPrimitive(scope()) || child["operation_id"] != JsonPrimitive("createFeedback") ||
            child["command_key"] != JsonPrimitive(childKey.toString()) || child["feedback_id"] != JsonPrimitive(feedbackId.toString()) ||
            number(child, "feedback_version") != 1L || child["request_hash"] != value["feedback_request_hash"] ||
            child["response_sha256"] != value["feedback_snapshot_sha256"]) unavailable()
        val saveReceipt = receipt("saveRecipe", id(value, "save_key"), value.text("save_request_hash"), number(value, "saved_version"), saved.getValue("snapshot"))
        val childReceipt = receipt("createFeedback", childKey, value.text("feedback_request_hash"), 1, body)
        deadlines = listOf(saveReceipt, childReceipt).map { OffsetDateTime.parse(it.text("expires_at")).toInstant() }
        return linkedMapOf("action" to action, "saved" to saved, "marker" to marker, "collection" to collection,
            "membership" to membership, "feedback" to feedback, "child" to child, "saveReceipt" to saveReceipt, "childReceipt" to childReceipt)
    }

    private fun receipt(operation: String, key: UUID, hash: String, version: Long, body: JsonElement): JsonObject {
        val row = image("platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
            setString(1, scope()); setString(2, operation); setObject(3, key)
        }
        if (row["principal_scope"] != JsonPrimitive(scope()) || row["operation_id"] != JsonPrimitive(operation) ||
            row["key"] != JsonPrimitive(key.toString()) || row["request_hash"] != JsonPrimitive(hash)) unavailable()
        if (row["state"] != JsonPrimitive("completed") || row["response_code"] != JsonPrimitive(201) ||
            row["response_etag"] != JsonPrimitive("\"$version\"") || fingerprint(row.getValue("response_json")) != fingerprint(body)) conflict()
        return row
    }
    private fun action(key: UUID): JsonObject = image("memory.account_make_again_actions", "principal_scope=? AND parent_operation='saveRecipe' AND parent_key=?") {
        setString(1, scope()); setObject(2, key)
    }.also {
        requireOwner(it)
        if (it["principal_scope"] != JsonPrimitive(scope()) || it["parent_operation"] != JsonPrimitive("saveRecipe") ||
            it["parent_key"] != JsonPrimitive(key.toString()) || it["save_key"] != it["parent_key"] ||
            it["save_request_hash"] != it["parent_request_hash"] || it["save_operation"] != JsonPrimitive("saveRecipe") ||
            it["feedback_operation"] != JsonPrimitive("createFeedback") || number(it, "feedback_version") != 1L) unavailable()
    }
    private fun owned(table: String, column: String, id: UUID) = image(table,
        "environment=? AND actor_kind='account' AND principal_id=? AND $column=?") { owner(); setObject(3, id) }.also {
        requireOwner(it); if (it[column] != JsonPrimitive(id.toString())) unavailable()
    }
    private fun image(table: String, where: String, bind: PreparedStatement.() -> Unit): JsonObject =
        connection.prepareStatement("SELECT to_jsonb(r)::text FROM $table r WHERE $where FOR SHARE").use {
            it.bind(); it.executeQuery().use { rows -> if (!rows.next()) conflict()
                Json.parseToJsonElement(rows.getString(1)).jsonObject.also { if (rows.next()) unavailable() } }
        }
    private fun requireOwner(row: JsonObject) {
        if (row["environment"] != JsonPrimitive(environment) || row["actor_kind"] != JsonPrimitive("account") ||
            row["principal_id"] != JsonPrimitive(actor.principalId.toString())) unavailable()
    }
    private fun feedbackIdentity(key: UUID, input: JsonObject) = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, actor.principalId), "createFeedback", key, body = input)
    private fun scope() = PrincipalScope(environment, CommandActor.ACCOUNT, actor.principalId).storageKey
    private fun PreparedStatement.owner() { setString(1, environment); setObject(2, actor.principalId) }
    private fun bound() { local(connection); if (transactionId() != transaction) unauthenticated(); access.lockPrincipal(connection, actor); local(connection) }
    private fun transactionId(): Long = connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
        if (!r.next()) unavailable(); r.getLong(1).also { if (r.next()) unavailable() }
    } }
    private fun now(): Instant = connection.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
        if (!r.next()) unavailable(); r.getObject(1, OffsetDateTime::class.java).toInstant().also { if (r.next()) unavailable() }
    } }
    private fun checkTime(at: Instant) {
        local(connection)
        if (at < (firstAt ?: unavailable()) || lastAt?.let { at < it } == true || deadlines.any { !it.isAfter(at) }) unavailable()
        lastAt = at
    }
    private fun local(c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Account Make Again interrupted")
        if (c !== connection || Thread.currentThread() !== thread || c.isClosed || c.autoCommit) unauthenticated()
    }
    private fun <T> checked(action: () -> T): T {
        if (refused || !active.compareAndSet(false, true)) unavailable()
        try { return action() } catch (failure: Throwable) { refused = true; throw failure } finally { active.set(false) }
    }
    override fun toString() = "AccountMakeAgainActionStore(<redacted>)"
    private companion object {
        val validator by lazy { ContractBodyValidator.bundled() }
        fun fingerprint(value: JsonElement) = feedbackSha(feedbackCanonical(value))
        fun explicitOnly(body: JsonObject) = body["makeAgain"] == JsonPrimitive(true) && listOf("taste", "effort", "note").none(body::containsKey)
        fun id(body: JsonObject, field: String) = UUID.fromString(body.text(field))
        fun number(body: JsonObject, field: String) = body.getValue(field).jsonPrimitive.content.toBigDecimal().longValueExact()
        fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content
        fun validate(schema: String, body: JsonObject) {
            if (validator.validateSchema(schema, body.toString().encodeToByteArray()) != BodyValidationResult.Valid) unavailable()
        }
        fun unavailable(): Nothing = throw FeedbackFailure(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        fun conflict(): Nothing = throw FeedbackFailure(FeedbackFailureCode.VERSION_CONFLICT)
        fun unauthenticated(): Nothing = throw FeedbackFailure(FeedbackFailureCode.UNAUTHENTICATED)
    }
}
