package com.feedme.server.guest

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.memory.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** One actual guest-owned compound action, not another transaction or a copy/feedback
 * authority. The parent supplies the original canonical input and actual Save hook facts.
 * No child write is performed during replay. The owner MUST seal after its kernels finish,
 * revalidate after final authority, then checkAt using its final accepted database time.
 * Only opaque linkage and fingerprints persist; no recipe, signal or note body is copied. */
internal class GuestMakeAgainActionStore(private val environment: String,
    private val connection: Connection, private val access: GuestFeedbackAccess) {
    private val actor = access.principal
    private val guard = Guard(connection)
    private val refused = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private var attempted = false
    private var entry: Entry? = null
    private var expected: Map<String, String>? = null
    private var firstAt: Instant? = null
    private var lastAt: Instant? = null
    private var deadlines: List<Instant> = emptyList()
    private var sealed = false
    private var revalidated = false
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        if (actor.environment != environment || actor.kind != CommandActor.GUEST || actor.deviceSessionId != null || actor.guestSessionId == null)
            fail(FeedbackFailureCode.UNAUTHENTICATED)
        bound()
        GuestFeedbackAccess.compatibility(connection, 23, "make_again_actions")
    }

    fun record(parent: CommandIdentity, saveKey: UUID, saveInput: JsonObject, saved: JsonObject,
        generation: Long, created: Boolean, feedbackKey: UUID, feedback: JsonObject,
        feedbackInput: JsonObject, cookId: UUID?): Unit = checked {
        begin(parent, saveInput, feedbackInput, cookId)
        if (generation <= 0 || (parent.operationId == "saveRecipe" && saveKey != parent.key)) unavailable()
        val context = context(feedbackInput)
        validateBody("SavedRecipe", saved); validateBody("Feedback", feedback)
        val savedId = id(saved, "id"); val feedbackId = id(feedback, "id")
        if (number(feedback, "version") != 1L || !explicitOnly(feedback) ||
            FeedbackTargetContext.fromInput(feedback).exactDocument != context.exactDocument) unavailable()
        val feedbackRow = owned("memory.feedback", "id", feedbackId)
        val provenanceHash = feedbackSha(feedbackRow.text("provenance_text"))
        if (feedbackRow["provenance_sha256"] != JsonPrimitive(provenanceHash)) unavailable()
        val value = Entry(parent.operationId, parent.key, parent.requestHash, savedId, generation,
            number(saved, "version"), fingerprint(saved), created, saveKey, saveIdentity(saveKey, saveInput).requestHash,
            feedbackId, feedbackKey, context.sha256, feedbackIdentity(feedbackKey, feedbackInput).requestHash,
            fingerprint(feedback), provenanceHash, cookId)
        connection.prepareStatement("INSERT INTO memory.make_again_actions(environment,actor_kind,principal_id,principal_scope," +
            "parent_operation,parent_key,parent_request_hash,saved_recipe_id,saved_generation,saved_version,saved_snapshot_sha256," +
            "created_save,save_key,save_request_hash,feedback_id,feedback_key,feedback_context_sha256,feedback_request_hash," +
            "feedback_snapshot_sha256,feedback_provenance_sha256,cook_session_id) VALUES(?,'guest',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
            it.setString(1, environment); it.setObject(2, actor.principalId); it.setString(3, scope())
            it.setString(4, value.operation); it.setObject(5, value.parentKey); it.setString(6, value.parentHash)
            it.setObject(7, value.savedId); it.setLong(8, value.generation); it.setLong(9, value.savedVersion)
            it.setString(10, value.savedHash); it.setBoolean(11, value.created); it.setObject(12, value.saveKey)
            it.setString(13, value.saveHash); it.setObject(14, value.feedbackId); it.setObject(15, value.feedbackKey)
            it.setString(16, value.contextHash); it.setString(17, value.feedbackHash); it.setString(18, value.feedbackResponseHash)
            it.setString(19, value.provenanceHash); it.setObject(20, value.cookId); if (it.executeUpdate() != 1) unavailable()
        }
        entry = value
        if (decode(action(parent.operationId, parent.key)).identity() != value.identity()) unavailable()
        // Save's marker/parent receipt may still be in progress inside its genuine hook.
        // Their full completed state is mandatory at seal, never fabricated here.
        firstAt = now()
        bound()
    }

    fun replay(parent: CommandIdentity, saveInput: JsonObject, feedbackInput: JsonObject, cookId: UUID?): Record = checked {
        begin(parent, saveInput, feedbackInput, cookId)
        val value = decode(action(parent.operationId, parent.key))
        if (value.operation != parent.operationId || value.parentKey != parent.key ||
            value.parentHash != parent.requestHash || value.cookId != cookId ||
            value.saveHash != saveIdentity(value.saveKey, saveInput).requestHash ||
            value.feedbackHash != feedbackIdentity(value.feedbackKey, feedbackInput).requestHash ||
            value.contextHash != context(feedbackInput).sha256 ||
            (parent.operationId == "saveRecipe" && value.saveKey != parent.key)) unavailable()
        entry = value
        firstAt = now()
        expected = inspect(value).mapValues { fingerprint(it.value) }
        checkTime(now())
        bound()
        Record(value.savedId, value.generation, value.saveKey, value.feedbackId, value.feedbackKey, value.created)
    }

    fun seal(): Unit = checked {
        if (sealed) unavailable()
        val value = entry ?: unavailable()
        bound()
        val images = inspect(value)
        val hashes = images.mapValues { fingerprint(it.value) }
        expected?.let { if (it != hashes) unavailable() }
        // The actual Save kernel determines created. An action cannot claim creation if
        // another durable Save already selected the same identity/generation.
        if (expected == null && value.created) {
            connection.prepareStatement("SELECT 1 FROM memory.save_commands WHERE environment=? AND actor_kind='guest' " +
                "AND principal_id=? AND saved_recipe_id=? AND generation=? AND command_id<>? LIMIT 1").use {
                it.owner(); it.setObject(3, value.savedId); it.setLong(4, value.generation); it.setObject(5, value.saveKey)
                it.executeQuery().use { rows -> if (rows.next()) unavailable() }
            }
        }
        expected = hashes; sealed = true
        bound(); checkTime(now())
    }

    fun revalidate(): Unit = checked {
        if (!sealed) unavailable()
        bound()
        if (inspect(entry ?: unavailable()).mapValues { fingerprint(it.value) } != expected) unavailable()
        bound(); checkTime(now())
        revalidated = true
    }

    /** SQL-free rejection only, after all actual authority/domain reads have finished. */
    fun checkAt(connection: Connection, at: Instant): Unit = checked {
        if (!sealed || !revalidated || expected == null) unavailable()
        guard.local(connection)
        checkTime(at)
    }

    private fun begin(parent: CommandIdentity, saveInput: JsonObject, feedbackInput: JsonObject, cookId: UUID?) {
        if (attempted) unavailable()
        attempted = true; bound()
        if (parent.scope.storageKey != scope() || parent.operationId !in setOf("saveRecipe", "completeCookSession"))
            fail(FeedbackFailureCode.UNAUTHENTICATED)
        if ((parent.operationId == "completeCookSession") != (cookId != null)) unavailable()
        if (validator.validateRequest("saveRecipe", saveInput.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid ||
            saveInput["markMakeAgain"] != JsonPrimitive(true) ||
            validator.validateRequest("createFeedback", feedbackInput.toString().encodeToByteArray(), "application/json") != BodyValidationResult.Valid ||
            !explicitOnly(feedbackInput)) fail(FeedbackFailureCode.INPUT_INVALID)
        val context = context(feedbackInput)
        if (cookId != null) {
            if (context.cookSessionId != cookId || context.kind != "cookSession" || context.resourceId != cookId ||
                saveInput.keys != setOf("planId", "markMakeAgain")) unavailable()
        } else {
            val plan = saveInput["planId"]?.jsonPrimitive?.content?.let(UUID::fromString)
            val recipe = saveInput["recipeVersionId"]?.jsonPrimitive?.content?.let(UUID::fromString)
            if (context.cookSessionId != null || context.kind != (if (plan != null) "plan" else "recipeVersion") ||
                context.resourceId != (plan ?: recipe)) unavailable()
        }
    }

    /** Constant number of exact rows; full original response bodies exist only transiently. */
    private fun inspect(value: Entry): Map<String, JsonObject> {
        val action = action(value.operation, value.parentKey)
        if (decode(action).identity() != value.identity()) unavailable()
        val saved = owned("memory.saved_recipes", "id", value.savedId)
        if (saved["deleted"] != JsonPrimitive(false) || number(saved, "generation") != value.generation ||
            number(saved, "version") != value.savedVersion || fingerprint(saved.getValue("snapshot")) != value.savedHash)
            conflict()
        validateBody("SavedRecipe", saved.getValue("snapshot").jsonObject)
        val marker = owned("memory.save_commands", "command_id", value.saveKey)
        if (marker["saved_recipe_id"] != JsonPrimitive(value.savedId.toString()) || number(marker, "generation") != value.generation) unavailable()
        val collectionId = id(marker, "collection_id")
        val collection = owned("memory.collections", "id", collectionId)
        if (collection["is_default"] != JsonPrimitive(true)) unavailable()
        val membership = image("memory.collection_items",
            "environment=? AND actor_kind='guest' AND principal_id=? AND collection_id=? AND saved_recipe_id=?") {
            owner(); setObject(3, collectionId); setObject(4, value.savedId)
        }
        requireOwner(membership)
        if (membership["collection_id"] != JsonPrimitive(collectionId.toString()) ||
            membership["saved_recipe_id"] != JsonPrimitive(value.savedId.toString()) || number(membership, "position") <= 0) unavailable()
        val feedback = owned("memory.feedback", "id", value.feedbackId)
        if (feedback["deleted"] != JsonPrimitive(false) || number(feedback, "version") != 1L) conflict()
        val body = feedback.getValue("snapshot").jsonObject
        if (fingerprint(body) != value.feedbackResponseHash || !explicitOnly(body)) conflict()
        validateBody("Feedback", body)
        if (id(body, "id") != value.feedbackId || number(body, "version") != 1L) unavailable()
        val exactContext = feedback.getValue("context_text").jsonPrimitive.content
        if (feedbackSha(exactContext) != value.contextHash || feedback["context_sha256"] != JsonPrimitive(value.contextHash) ||
            FeedbackTargetContext.decode(exactContext).exactDocument != FeedbackTargetContext.fromInput(body).exactDocument ||
            feedback["cook_session_id"] != (value.cookId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)) unavailable()
        val provenance = feedback.getValue("provenance_text").jsonPrimitive.content
        if (feedbackSha(provenance) != value.provenanceHash || feedback["provenance_sha256"] != JsonPrimitive(value.provenanceHash) ||
            feedbackCanonical(Json.parseToJsonElement(provenance)) != provenance) unavailable()
        val feedbackCommand = image("memory.feedback_commands", "principal_scope=? AND operation_id='createFeedback' AND command_key=?") {
            setString(1, scope()); setObject(2, value.feedbackKey)
        }
        if (feedbackCommand["principal_scope"] != JsonPrimitive(scope()) || feedbackCommand["operation_id"] != JsonPrimitive("createFeedback") ||
            feedbackCommand["command_key"] != JsonPrimitive(value.feedbackKey.toString()) ||
            feedbackCommand["environment"] != JsonPrimitive(environment) || feedbackCommand["actor_kind"] != JsonPrimitive("guest") ||
            feedbackCommand["principal_id"] != JsonPrimitive(actor.principalId.toString()) ||
            feedbackCommand["feedback_id"] != JsonPrimitive(value.feedbackId.toString()) || number(feedbackCommand, "feedback_version") != 1L ||
            feedbackCommand["request_hash"] != JsonPrimitive(value.feedbackHash) ||
            feedbackCommand["response_sha256"] != JsonPrimitive(value.feedbackResponseHash)) unavailable()
        val saveReceipt = receipt("saveRecipe", value.saveKey, value.saveHash, 201, value.savedVersion, saved.getValue("snapshot"))
        val feedbackReceipt = receipt("createFeedback", value.feedbackKey, value.feedbackHash, 201, 1, body)
        val result = linkedMapOf("action" to action, "saved" to saved, "saveCommand" to marker,
            "collection" to collection, "membership" to membership, "feedback" to feedback,
            "feedbackCommand" to feedbackCommand, "saveReceipt" to saveReceipt, "feedbackReceipt" to feedbackReceipt)
        val parentReceipt = if (value.cookId == null) {
            if (value.operation != "saveRecipe" || value.parentKey != value.saveKey || value.parentHash != value.saveHash) unavailable()
            saveReceipt
        } else {
            val cook = owned("cooking.cook_sessions", "id", value.cookId)
            val cookBody = cook.getValue("snapshot").jsonObject
            if (cook["status"] != JsonPrimitive("completed") || cookBody["status"] != JsonPrimitive("completed") ||
                id(cookBody, "id") != value.cookId || number(cookBody, "version") != number(cook, "version")) conflict()
            validateBody("CookSession", cookBody)
            val saveInput = buildJsonObject { put("planId", cook.getValue("plan_id")); put("markMakeAgain", true) }
            if (saveIdentity(value.saveKey, saveInput).requestHash != value.saveHash) unavailable()
            result["cook"] = cook
            receipt(value.operation, value.parentKey, value.parentHash, 200, number(cook, "version"), cookBody)
        }
        result["parentReceipt"] = parentReceipt
        deadlines = listOf(saveReceipt, feedbackReceipt, parentReceipt).map { instant(it, "expires_at") }
        // A completion is still bounded by the original cooking pin lifetime.
        value.cookId?.let { deadlines = deadlines + instant(result.getValue("cook"), "expires_at") }
        return result
    }

    private fun receipt(operation: String, key: UUID, requestHash: String, status: Int, version: Long, body: JsonElement): JsonObject {
        val value = image("platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
            setString(1, scope()); setString(2, operation); setObject(3, key)
        }
        if (value["principal_scope"] != JsonPrimitive(scope()) || value["operation_id"] != JsonPrimitive(operation) ||
            value["key"] != JsonPrimitive(key.toString()) || value["request_hash"] != JsonPrimitive(requestHash)) unavailable()
        if (value["state"] != JsonPrimitive("completed") || value["response_code"] != JsonPrimitive(status) ||
            value["response_etag"] != JsonPrimitive("\"$version\"") ||
            fingerprint(value.getValue("response_json")) != fingerprint(body)) conflict()
        return value
    }
    private fun action(operation: String, key: UUID) = image("memory.make_again_actions",
        "principal_scope=? AND parent_operation=? AND parent_key=?") { setString(1, scope()); setString(2, operation); setObject(3, key) }
    private fun owned(table: String, column: String, id: UUID) = image(table,
        "environment=? AND actor_kind='guest' AND principal_id=? AND $column=?") { owner(); setObject(3, id) }.also {
            requireOwner(it)
            if (it[column] != JsonPrimitive(id.toString())) unavailable()
        }
    private fun requireOwner(row: JsonObject) {
        if (row["environment"] != JsonPrimitive(environment) || row["actor_kind"] != JsonPrimitive("guest") ||
            row["principal_id"] != JsonPrimitive(actor.principalId.toString())) unavailable()
    }
    private fun image(table: String, where: String, bind: PreparedStatement.() -> Unit): JsonObject =
        connection.prepareStatement("SELECT to_jsonb(r)::text FROM $table r WHERE $where FOR SHARE").use {
            it.bind(); it.executeQuery().use { rows ->
                if (!rows.next()) conflict()
                Json.parseToJsonElement(rows.getString(1)).jsonObject.also { if (rows.next()) unavailable() }
            }
        }
    private fun decode(row: JsonObject): Entry {
        if (row["environment"] != JsonPrimitive(environment) || row["actor_kind"] != JsonPrimitive("guest") ||
            row["principal_id"] != JsonPrimitive(actor.principalId.toString()) || row["principal_scope"] != JsonPrimitive(scope()) ||
            row["save_operation"] != JsonPrimitive("saveRecipe") || row["feedback_operation"] != JsonPrimitive("createFeedback") ||
            number(row, "feedback_version") != 1L) unavailable()
        return Entry(row.text("parent_operation"), id(row, "parent_key"), row.text("parent_request_hash"),
            id(row, "saved_recipe_id"), number(row, "saved_generation"), number(row, "saved_version"), row.text("saved_snapshot_sha256"),
            row.getValue("created_save").jsonPrimitive.boolean, id(row, "save_key"), row.text("save_request_hash"),
            id(row, "feedback_id"), id(row, "feedback_key"), row.text("feedback_context_sha256"), row.text("feedback_request_hash"),
            row.text("feedback_snapshot_sha256"), row.text("feedback_provenance_sha256"),
            row["cook_session_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(UUID::fromString))
    }
    private fun saveIdentity(key: UUID, input: JsonObject) = CommandIdentity(PrincipalScope(environment, CommandActor.GUEST, actor.principalId), "saveRecipe", key, body = input)
    private fun feedbackIdentity(key: UUID, input: JsonObject) = CommandIdentity(PrincipalScope(environment, CommandActor.GUEST, actor.principalId), "createFeedback", key, body = input)
    private fun context(input: JsonObject) = FeedbackTargetContext.fromInput(input)
    private fun scope() = PrincipalScope(environment, CommandActor.GUEST, actor.principalId).storageKey
    private fun PreparedStatement.owner() { setString(1, environment); setObject(2, actor.principalId) }
    private fun bound() { guard.check(); access.lockPrincipal(connection, actor); guard.check() }
    private fun now(): Instant = connection.createStatement().use { statement -> statement.executeQuery("SELECT clock_timestamp()").use {
        if (!it.next()) unavailable(); it.getObject(1, OffsetDateTime::class.java).toInstant().also { _ -> if (it.next()) unavailable() }
    } }
    private fun checkTime(at: Instant) {
        guard.local(connection)
        val first = firstAt ?: unavailable()
        if (at < first || lastAt?.let { at < it } == true || deadlines.any { !it.isAfter(at) }) unavailable()
        lastAt = at
    }
    private fun <T> checked(action: () -> T): T = safe {
        if (refused.get() || !active.compareAndSet(false, true)) unavailable()
        try { action() } catch (failure: Throwable) { refused.set(true); throw failure } finally { active.set(false) }
    }
    internal class Record internal constructor(val savedId: UUID, val generation: Long, val saveKey: UUID,
        val feedbackId: UUID, val feedbackKey: UUID, val created: Boolean) { override fun toString() = "MakeAgainRecord(<redacted>)" }
    private class Entry(val operation: String, val parentKey: UUID, val parentHash: String, val savedId: UUID,
        val generation: Long, val savedVersion: Long, val savedHash: String, val created: Boolean, val saveKey: UUID,
        val saveHash: String, val feedbackId: UUID, val feedbackKey: UUID, val contextHash: String,
        val feedbackHash: String, val feedbackResponseHash: String, val provenanceHash: String, val cookId: UUID?) {
        fun identity() = listOf(operation, parentKey, parentHash, savedId, generation, savedVersion, savedHash,
            created, saveKey, saveHash, feedbackId, feedbackKey, contextHash, feedbackHash, feedbackResponseHash, provenanceHash, cookId)
    }
    internal class Guard(private val connection: Connection) {
        private val thread = Thread.currentThread()
        private val transaction: Long
        init { local(connection); transaction = transactionId() }
        fun check() { local(connection); if (transactionId() != transaction) unavailable() }
        fun local(actual: Connection) {
            current()
            if (actual !== connection || Thread.currentThread() !== thread || actual.isClosed || actual.autoCommit)
                fail(FeedbackFailureCode.UNAUTHENTICATED)
        }
        private fun transactionId(): Long = connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use {
            if (!it.next()) unavailable(); it.getLong(1).also { _ -> if (it.next()) unavailable() }
        } }
    }
    override fun toString() = "GuestMakeAgainActionStore(<redacted>)"
    private companion object {
        val validator by lazy { ContractBodyValidator.bundled() }
        fun fingerprint(value: JsonElement) = feedbackSha(feedbackCanonical(value))
        fun explicitOnly(body: JsonObject) = body["makeAgain"] == JsonPrimitive(true) && listOf("taste", "effort", "note").none(body::containsKey)
        fun id(body: JsonObject, field: String) = UUID.fromString(body.text(field))
        fun number(body: JsonObject, field: String) = body.getValue(field).jsonPrimitive.content.toBigDecimal().longValueExact()
        fun instant(body: JsonObject, field: String) = OffsetDateTime.parse(body.text(field)).toInstant()
        fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content
        fun validateBody(schema: String, body: JsonObject) {
            if (validator.validateSchema(schema, body.toString().encodeToByteArray()) != BodyValidationResult.Valid) unavailable()
        }
        fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Make Again interrupted") }
        fun fail(code: FeedbackFailureCode): Nothing = throw FeedbackFailure(code)
        fun unavailable(): Nothing = fail(FeedbackFailureCode.STORAGE_UNAVAILABLE)
        fun conflict(): Nothing = fail(FeedbackFailureCode.VERSION_CONFLICT)
        fun <T> safe(action: () -> T): T = try { action() }
            catch (failure: FeedbackFailure) { throw failure }
            catch (failure: GuestSessionFailure) { throw failure }
            catch (failure: CommitOutcomeUnknown) { throw failure }
            catch (failure: CancellationException) { throw failure }
            catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
            catch (failure: SQLException) { current(); if (failure.sqlState in setOf("40001", "40P01")) throw failure else unavailable() }
            catch (_: Exception) { current(); unavailable() }
    }
}
