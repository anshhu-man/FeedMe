package com.feedme.sync

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.PrivateBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Corrupt private records must never attach their contents or a parser exception as a cause. */
internal class JournalDecodingException : IllegalArgumentException("Invalid command journal")

/** Versioned metadata only. Request and receipt bodies live in separate private byte records. */
internal object JournalCodec {
    fun encodeIndex(value: QueueIndex): PrivateBytes = guarded {
        validateIndex(value)
        val encoded = bytes(JsonObject(mapOf(
            "version" to JsonPrimitive(JOURNAL_SCHEMA),
            "ids" to strings(value.ids.toList()),
            "lastObservedMillis" to JsonPrimitive(value.lastObservedMillis),
        )))
        decodeIndex(encoded)
        encoded
    }

    fun decodeIndex(value: PrivateBytes): QueueIndex = guarded {
        val root = document(value).objectWith(indexKeys)
        checkJournal(root.getValue("version").long() == JOURNAL_SCHEMA.toLong())
        QueueIndex(
            ids = root.getValue("ids").stringList(),
            lastObservedMillis = root.getValue("lastObservedMillis").long(),
        ).also(::validateIndex)
    }

    fun encodeCommand(value: JournalCommand): PrivateBytes = guarded {
        validateCommand(value)
        val encoded = bytes(JsonObject(mapOf(
            "version" to JsonPrimitive(JOURNAL_SCHEMA),
            "id" to JsonPrimitive(value.id),
            "originBinding" to JsonPrimitive(value.originBinding),
            "scopeEnvironment" to JsonPrimitive(value.scopeEnvironment),
            "scopeActorKind" to JsonPrimitive(value.scopeActorKind),
            "scopeActorId" to JsonPrimitive(value.scopeActorId),
            "request" to encodeRequest(value.request),
            "dependencies" to strings(value.dependencies.toList()),
            "createdAt" to JsonPrimitive(value.createdAt),
            "firstAttemptAt" to nullableNumber(value.firstAttemptAt),
            "retryAt" to JsonPrimitive(value.retryAt),
            "phase" to JsonPrimitive(value.phase.name),
            "attempts" to JsonPrimitive(value.attempts),
            "issue" to JsonPrimitive(value.issue.name),
            "reply" to (value.reply?.let(::encodeReply) ?: JsonNull),
        )))
        // Keep encoder and decoder invariants together, including the final escaped UTF-8 size.
        decodeCommand(encoded)
        encoded
    }

    fun decodeCommand(value: PrivateBytes): JournalCommand = guarded {
        val root = document(value).objectWith(commandKeys)
        checkJournal(root.getValue("version").long() == JOURNAL_SCHEMA.toLong())
        JournalCommand(
            id = root.getValue("id").string(),
            originBinding = root.getValue("originBinding").string(),
            scopeEnvironment = root.getValue("scopeEnvironment").string(),
            scopeActorKind = root.getValue("scopeActorKind").string(),
            scopeActorId = root.getValue("scopeActorId").string(),
            request = decodeRequest(root.getValue("request")),
            dependencies = root.getValue("dependencies").stringList(),
            createdAt = root.getValue("createdAt").long(),
            firstAttemptAt = root.getValue("firstAttemptAt").nullableLong(),
            retryAt = root.getValue("retryAt").long(),
            phase = CommandPhase.entries.firstOrNull { it.name == root.getValue("phase").string() } ?: reject(),
            attempts = root.getValue("attempts").int(),
            issue = CommandIssue.entries.firstOrNull { it.name == root.getValue("issue").string() } ?: reject(),
            reply = root.getValue("reply").let { if (it == JsonNull) null else decodeReply(it) },
        ).also(::validateCommand)
    }
}

private const val MAX_METADATA_BYTES = 128 * 1024
private val journalLimits = WireLimits(maxBytes = MAX_METADATA_BYTES, maxDepth = 12, maxNumberLength = 19)
private val integerToken = Regex("0|[1-9][0-9]*")
private val operationToken = Regex("[A-Za-z][A-Za-z0-9]*")
private val indexKeys = setOf("version", "ids", "lastObservedMillis")
private val commandKeys = setOf(
    "version", "id", "originBinding", "scopeEnvironment", "scopeActorKind", "scopeActorId",
    "request", "dependencies", "createdAt", "firstAttemptAt", "retryAt", "phase", "attempts", "issue", "reply",
)
private val requestKeys = setOf("operationId", "path", "query", "ifMatch", "hasBody")
private val replyKeys = setOf("status", "etag", "traceId", "retryAfterSeconds", "contentType", "hasBody")

private fun document(value: PrivateBytes): JsonElement {
    // WireDocument supplies strict UTF-8, duplicate-key, Unicode and number-token safeguards.
    val document = WireDocument.decode(value.copyForCodec(), journalLimits)
    return Json.parseToJsonElement(document.encodeUtf8().decodeToString())
}

private fun bytes(value: JsonElement): PrivateBytes =
    PrivateBytes(value.toString().encodeToByteArray(throwOnInvalidSequence = true))

private fun encodeRequest(value: RequestMetadata): JsonObject = JsonObject(mapOf(
    "operationId" to JsonPrimitive(value.operationId),
    "path" to JsonObject(value.path.toMap().mapValues { JsonPrimitive(it.value) }),
    "query" to JsonObject(value.query.mapValues { strings(it.value.toList()) }),
    "ifMatch" to nullableString(value.ifMatch),
    "hasBody" to JsonPrimitive(value.hasBody),
))

private fun decodeRequest(value: JsonElement): RequestMetadata {
    val root = value.objectWith(requestKeys)
    return RequestMetadata(
        operationId = root.getValue("operationId").string(),
        path = root.getValue("path").objectValue().mapValues { it.value.string() }.toMap(),
        query = root.getValue("query").objectValue().mapValues { it.value.stringList() }.toMap(),
        ifMatch = root.getValue("ifMatch").nullableString(),
        hasBody = root.getValue("hasBody").boolean(),
    )
}

private fun encodeReply(value: ReplyMetadata): JsonObject = JsonObject(mapOf(
    "status" to JsonPrimitive(value.status),
    "etag" to nullableString(value.etag),
    "traceId" to nullableString(value.traceId),
    "retryAfterSeconds" to nullableNumber(value.retryAfterSeconds),
    "contentType" to nullableString(value.contentType),
    "hasBody" to JsonPrimitive(value.hasBody),
))

private fun decodeReply(value: JsonElement): ReplyMetadata {
    val root = value.objectWith(replyKeys)
    return ReplyMetadata(
        status = root.getValue("status").int(),
        etag = root.getValue("etag").nullableString(),
        traceId = root.getValue("traceId").nullableString(),
        retryAfterSeconds = root.getValue("retryAfterSeconds").nullableLong(),
        contentType = root.getValue("contentType").nullableString(),
        hasBody = root.getValue("hasBody").boolean(),
    )
}

private fun validateIndex(value: QueueIndex) {
    checkJournal(value.lastObservedMillis >= 0 && value.ids.size <= MAX_PENDING)
    checkJournal(value.ids.all(commandUuid::matches) && value.ids.toSet().size == value.ids.size)
}

private fun validateCommand(value: JournalCommand) {
    checkJournal(commandUuid.matches(value.id) && commandUuid.matches(value.originBinding))
    scopeText(value.scopeEnvironment)
    scopeText(value.scopeActorId)
    checkJournal(value.scopeActorKind == ActorKind.ACCOUNT.name || value.scopeActorKind == ActorKind.GUEST.name)
    checkJournal(value.dependencies.size <= MAX_DEPENDENCIES)
    checkJournal(value.dependencies.all { commandUuid.matches(it) && it != value.id })
    checkJournal(value.dependencies.toSet().size == value.dependencies.size)
    checkJournal(value.createdAt >= 0 && value.retryAt >= 0 && value.attempts in 0..8)
    checkJournal((value.firstAttemptAt == null) == (value.attempts == 0))
    checkJournal(value.firstAttemptAt == null || value.firstAttemptAt >= value.createdAt)
    if (value.phase in setOf(CommandPhase.IN_FLIGHT, CommandPhase.RETRY_WAIT, CommandPhase.RECEIPT_READY, CommandPhase.APPLIED)) {
        checkJournal(value.attempts > 0)
    }
    if (value.phase == CommandPhase.DISCARDED) checkJournal(value.attempts == 0)
    checkJournal((value.phase == CommandPhase.RECEIPT_READY) == (value.reply != null))
    value.reply?.let(::validateReply)
    validateRequest(value.request, value.id)
    if (value.phase == CommandPhase.APPLIED || value.phase == CommandPhase.DISCARDED) {
        checkJournal(value.request.path.isEmpty() && value.request.query.isEmpty())
        checkJournal(value.request.ifMatch == null && !value.request.hasBody)
    }
}

private fun validateRequest(value: RequestMetadata, commandId: String) {
    // Also bound encoding before constructing a JSON tree, including arbitrarily many empty values.
    val metadata = MetadataBudget()
    metadata.take(value.operationId)
    checkJournal(operationToken.matches(value.operationId))
    checkJournal(value.path.size <= MAX_METADATA_BYTES / 3 && value.query.size <= MAX_METADATA_BYTES / 3)
    val parameters = ParameterBudget()
    parameters.take(commandId)
    value.path.forEach { (key, text) ->
        parameterKey(key, metadata)
        checkJournal(text.isNotBlank())
        metadata.take(text)
        parameters.take(text)
    }
    value.query.forEach { (key, values) ->
        parameterKey(key, metadata)
        checkJournal(values.size <= MAX_METADATA_BYTES / 3)
        values.forEach { text -> metadata.take(text); parameters.take(text) }
    }
    value.ifMatch?.let {
        checkJournal(it.isNotBlank())
        metadata.take(it)
        parameters.take(it)
    }
}

private fun parameterKey(value: String, budget: MetadataBudget) {
    budget.take(value)
    checkJournal(value.isNotBlank() && value.none(Char::isISOControl))
    unicode(value)
}

private fun scopeText(value: String) {
    checkJournal(value.length <= 200 && value.isNotBlank() && value.none(Char::isISOControl))
    unicode(value)
}

private fun validateReply(value: ReplyMetadata) {
    checkJournal(value.status in 200..299)
    checkJournal(value.retryAfterSeconds == null || value.retryAfterSeconds >= 1)
    listOf(value.etag, value.traceId, value.contentType).forEach { text ->
        if (text != null) {
            checkJournal(text.length <= 256 && text.isNotBlank() && text.none(Char::isISOControl))
            unicode(text)
        }
    }
}

private class MetadataBudget {
    private var remaining = MAX_METADATA_BYTES
    fun take(value: String) {
        // Three units cover at least the quotes plus a separator; escaped bytes are checked later.
        checkJournal(value.length <= remaining - 3)
        remaining -= value.length + 3
    }
}

private class ParameterBudget {
    private var remaining = 16 * 1024
    fun take(value: String) {
        checkJournal(value.length <= 4096 && value.length <= remaining)
        remaining -= value.length
        checkJournal(value.none(Char::isISOControl))
        unicode(value)
    }
}

private fun unicode(value: String) {
    value.encodeToByteArray(throwOnInvalidSequence = true)
}

private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: reject()
private fun JsonElement.objectWith(keys: Set<String>): JsonObject = objectValue().also { checkJournal(it.keys == keys) }
private fun JsonElement.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: reject()
private fun JsonElement.nullableString(): String? = if (this == JsonNull) null else string()
private fun JsonElement.boolean(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: reject()
private fun JsonElement.long(): Long {
    val token = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content ?: reject()
    checkJournal(integerToken.matches(token))
    return token.toLongOrNull() ?: reject()
}
private fun JsonElement.nullableLong(): Long? = if (this == JsonNull) null else long()
private fun JsonElement.int(): Int = long().let { checkJournal(it <= Int.MAX_VALUE); it.toInt() }
private fun JsonElement.stringList(): List<String> = (this as? JsonArray)?.map { it.string() }?.toList() ?: reject()
private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
private fun nullableString(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
private fun nullableNumber(value: Long?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
private fun checkJournal(condition: Boolean) { if (!condition) reject() }
private fun reject(): Nothing = throw JournalDecodingException()
private inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (_: Exception) {
    // Deliberately drop causes: serialization diagnostics can include private values and field names.
    reject()
}
