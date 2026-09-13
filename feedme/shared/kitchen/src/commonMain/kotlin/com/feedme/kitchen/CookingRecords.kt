package com.feedme.kitchen

import com.feedme.contracts.*
import com.feedme.core.ports.PrivateBytes
import kotlinx.serialization.json.*

enum class CookingStatus { ACTIVE, PAUSED, COMPLETED, ABANDONED }
enum class CookingAvailability { AVAILABLE, RECALLED, INCOMPLETE, PERSONAL_UNREVIEWED, CONFLICT }

/** Local progress has no invented server version, completion timestamp or safety certification. */
class CookingProgress internal constructor(
    val status: CookingStatus,
    val currentStepId: String,
    completedStepIds: List<String>,
    timers: List<TimerStateWire>,
    personalNotes: List<WireDocument>?,
    val deviceSequence: String,
) {
    private val completed = completedStepIds.toList()
    private val timerValues = timers.toList()
    private val notes = personalNotes?.toList()
    val completedStepIds: List<String> get() = completed.toList()
    val timers: List<TimerStateWire> get() = timerValues.toList()
    val personalNotes: List<WireDocument>? get() = notes?.toList()
    override fun toString() = "CookingProgress(status=$status, private=<redacted>)"
}

class CookingSnapshot internal constructor(
    val id: String,
    val plan: PlanWire,
    val remote: CookSessionWire,
    val progress: CookingProgress,
    val availability: CookingAvailability,
    val lastCheckedMillis: Long,
    val localRevision: Long,
    val etag: String?,
    pendingCommandIds: List<String>,
    val conflictingRemote: CookSessionWire?,
) {
    private val pending = pendingCommandIds.toList()
    val pendingCommandIds: List<String> get() = pending.toList()
    override fun toString() = "CookingSnapshot(availability=$availability, private=<redacted>)"
}

sealed interface CookingEdit {
    class MoveTo(val stepId: String) : CookingEdit { override fun toString() = "MoveTo(<redacted>)" }
    class MarkStepComplete(val stepId: String) : CookingEdit { override fun toString() = "MarkStepComplete(<redacted>)" }
    class SetStatus(val status: CookingStatus) : CookingEdit
    class ReplaceTimers(timers: List<WireDocument>) : CookingEdit {
        private val values = timers.toList()
        val timers: List<WireDocument> get() = values.toList()
        override fun toString() = "ReplaceTimers(<redacted>)"
    }
    class ReplaceNotes(notes: List<WireDocument>) : CookingEdit {
        private val values = notes.toList()
        val notes: List<WireDocument> get() = values.toList()
        override fun toString() = "ReplaceNotes(<redacted>)"
    }
    class Complete(val makeAgain: Boolean, val finishedAtClient: String? = null) : CookingEdit {
        override fun toString() = "Complete(<redacted>)"
    }
}

internal data class CookHeader(
    val id: String, val planId: String, val originBinding: String,
    val planHash: String, val serverHash: String, val progressHash: String,
    val etag: String?, val checkedAt: Long, val pending: List<String>,
    val conflictHash: String? = null, val conflictEtag: String? = null,
)
internal data class CookActionHeader(
    val id: String, val sessionId: String, val operationId: String, val bodyHash: String,
    val createdAt: Long, val materialized: Boolean, val ifMatch: String?,
)

internal object CookingRecords {
    private val headerKeys = setOf("version", "id", "planId", "originBinding", "planHash", "serverHash", "progressHash", "etag", "checkedAt", "pending", "conflictHash", "conflictEtag")
    private val actionKeys = setOf("version", "id", "sessionId", "operationId", "bodyHash", "createdAt", "materialized", "ifMatch")
    fun encodeHeader(value: CookHeader): PrivateBytes = PrivateJson.encode(buildJsonObject {
        put("version", 1); put("id", value.id); put("planId", value.planId); put("originBinding", value.originBinding)
        put("planHash", value.planHash); put("serverHash", value.serverHash); put("progressHash", value.progressHash)
        put("etag", value.etag?.let(::JsonPrimitive) ?: JsonNull); put("checkedAt", value.checkedAt)
        put("pending", JsonArray(value.pending.map(::JsonPrimitive)))
        put("conflictHash", value.conflictHash?.let(::JsonPrimitive) ?: JsonNull)
        put("conflictEtag", value.conflictEtag?.let(::JsonPrimitive) ?: JsonNull)
    }).also(::decodeHeader)
    fun decodeHeader(bytes: PrivateBytes): CookHeader {
        val root = PrivateJson.decode(bytes, headerKeys)
        if (PrivateJson.long(root.getValue("version")) != 1L) PrivateJson.invalid()
        val pending = PrivateJson.strings(root.getValue("pending"))
        if (pending.size > 64 || pending.distinct().size != pending.size || pending.any { normalizedId(it) != it }) PrivateJson.invalid()
        val conflictHash = root.getValue("conflictHash").let { if (it == JsonNull) null else PrivateJson.hash(it) }
        val conflictEtag = PrivateJson.nullableString(root.getValue("conflictEtag"))
        if (conflictHash == null && conflictEtag != null) PrivateJson.invalid()
        return CookHeader(PrivateJson.uuid(root.getValue("id")), PrivateJson.uuid(root.getValue("planId")),
            PrivateJson.uuid(root.getValue("originBinding")), PrivateJson.hash(root.getValue("planHash")),
            PrivateJson.hash(root.getValue("serverHash")), PrivateJson.hash(root.getValue("progressHash")),
            PrivateJson.nullableString(root.getValue("etag")), PrivateJson.long(root.getValue("checkedAt")), pending, conflictHash, conflictEtag)
    }
    fun encodeAction(value: CookActionHeader): PrivateBytes = PrivateJson.encode(buildJsonObject {
        put("version", 1); put("id", value.id); put("sessionId", value.sessionId); put("operationId", value.operationId)
        put("bodyHash", value.bodyHash); put("createdAt", value.createdAt); put("materialized", value.materialized)
        put("ifMatch", value.ifMatch?.let(::JsonPrimitive) ?: JsonNull)
    }).also(::decodeAction)
    fun decodeAction(bytes: PrivateBytes): CookActionHeader {
        val root = PrivateJson.decode(bytes, actionKeys)
        if (PrivateJson.long(root.getValue("version")) != 1L) PrivateJson.invalid()
        val operation = PrivateJson.string(root.getValue("operationId"))
        if (operation !in setOf("updateCookSession", "completeCookSession")) PrivateJson.invalid()
        val materialized = PrivateJson.boolean(root.getValue("materialized"))
        val etag = PrivateJson.nullableString(root.getValue("ifMatch"))
        if ((!materialized || operation == "completeCookSession") && etag != null) PrivateJson.invalid()
        if (materialized && operation == "updateCookSession" && (etag == null || !etag.matches(Regex("\"[0-9]+\"")))) PrivateJson.invalid()
        return CookActionHeader(PrivateJson.uuid(root.getValue("id")), PrivateJson.uuid(root.getValue("sessionId")), operation,
            PrivateJson.hash(root.getValue("bodyHash")), PrivateJson.long(root.getValue("createdAt")), materialized, etag)
    }

    fun index(bytes: PrivateBytes): List<String> {
        val root = PrivateJson.decode(bytes, setOf("version", "ids"))
        if (PrivateJson.long(root.getValue("version")) != 1L) PrivateJson.invalid()
        return PrivateJson.strings(root.getValue("ids")).also {
            if (it.size > 64 || it.distinct().size != it.size || it.any { id -> normalizedId(id) != id }) PrivateJson.invalid()
        }
    }
    fun index(ids: List<String>): PrivateBytes = PrivateJson.encode(buildJsonObject {
        put("version", 1); put("ids", JsonArray(ids.map(::JsonPrimitive)))
    }).also(::index)

    fun fromServer(session: CookSessionWire): CookingProgress {
        val sequence = integerValue(session.deviceSequence.jsonToken)
        return CookingProgress(CookingStatus.valueOf(session.status.uppercase()), session.currentStepId.value,
            session.completedStepIds.map { it.value }, session.timers,
            (session.personalNotes as? WireField.Value)?.value, sequence)
    }
    fun encodeProgress(progress: CookingProgress): PrivateBytes = PrivateBytes(buildJsonObject {
        put("version", 1); put("status", progress.status.name); put("currentStepId", progress.currentStepId)
        put("completedStepIds", JsonArray(progress.completedStepIds.map(::JsonPrimitive)))
        put("deviceSequence", progress.deviceSequence)
        put("timers", JsonArray(progress.timers.map { it.document.json() }))
        put("personalNotes", progress.personalNotes?.let { JsonArray(it.map(WireDocument::json)) } ?: JsonNull)
    }.toString().encodeToByteArray())
    fun decodeProgress(bytes: PrivateBytes, context: KitchenContext): CookingProgress {
        val root = WireDocument.decode(bytes.copyForCodec()).json().jsonObject
        if (root.keys != setOf("version", "status", "currentStepId", "completedStepIds", "deviceSequence", "timers", "personalNotes") ||
            PrivateJson.long(root.getValue("version")) != 1L) PrivateJson.invalid()
        val status = CookingStatus.entries.firstOrNull { it.name == PrivateJson.string(root.getValue("status")) } ?: PrivateJson.invalid()
        val sequence = PrivateJson.string(root.getValue("deviceSequence"))
        if (!sequence.matches(Regex("0|[1-9][0-9]*")) || sequence.length > 4096) PrivateJson.invalid()
        val timers = (root.getValue("timers") as? JsonArray ?: PrivateJson.invalid()).map {
            TimerStateWire.from(context.document("TimerState", PrivateBytes(it.toString().encodeToByteArray())))
        }
        val notes = root.getValue("personalNotes").let { if (it == JsonNull) null else (it as? JsonArray ?: PrivateJson.invalid()).map { note -> WireDocument.parse(note.toString()) } }
        val progress = CookingProgress(status, PrivateJson.string(root.getValue("currentStepId")),
            PrivateJson.strings(root.getValue("completedStepIds")), timers, notes, sequence)
        // Validate timer/note/progress fields without expanding a legitimate exponent-spelled
        // remote counter past the wire parser's token limit. The local exact counter is checked
        // above; edit separately requires a non-overflowing Long before creating any HTTP body.
        context.document("CookPatch", patch(progress, "0"))
        return progress
    }
    /** Complete is a different canonical command; it never sends status=completed in CookPatch. */
    fun patch(progress: CookingProgress, sequence: String = progress.deviceSequence): PrivateBytes = PrivateBytes(buildJsonObject {
        if (progress.status != CookingStatus.COMPLETED) put("status", progress.status.name.lowercase())
        put("currentStepId", progress.currentStepId); put("completedStepIds", JsonArray(progress.completedStepIds.map(::JsonPrimitive)))
        put("deviceSequence", Json.parseToJsonElement(sequence))
        put("timers", JsonArray(progress.timers.map { it.document.json() }))
        progress.personalNotes?.let { put("personalNotes", JsonArray(it.map(WireDocument::json))) }
    }.toString().encodeToByteArray())
}
