package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import kotlinx.serialization.json.*
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal data class PostLocal(val id: String, val revision: Long, val caption: String, val alt: String?,
    val server: WireDocument? = null, val etag: String? = null) {
    override fun toString() = "PostLocal(<redacted>)"
}
internal class PostOriginal(val id: String, val operation: String, val clientId: String, val localRevision: Long,
    val body: WireDocument?, val baseline: WireDocument?, val etag: String?, val created: Long) {
    val serverId get() = baseline?.let { postString(it, "id") }
    fun call() = ApiCall(operation, pathParameters = serverId?.let { mapOf("draftId" to it) } ?: emptyMap(),
        body = body?.let { PrivateBytes(it.encodeUtf8()) }, idempotencyKey = SecretText(id), ifMatch = etag)
    override fun toString() = "PostOriginal(operation=$operation, details=<redacted>)"
}
internal data class PostTerminal(val clientId: String, val serverId: String?, val commandId: String?)
internal data class PostLocalPending(val clientId: String, val revision: Long)
internal data class PostCompletion(val commandId: String, val operation: String, val clientId: String, val unsent: Boolean)
internal data class PostRecord(val clock: Long, val locals: List<PostLocal> = emptyList(), val issued: List<String> = emptyList(),
    val tombstones: List<PostTerminal> = emptyList(), val command: PostOriginal? = null,
    val completion: PostCompletion? = null, val localPending: PostLocalPending? = null) {
    override fun toString() = "PostRecord(<redacted>)"
}
internal class PostEntry(val record: PrivateRecord?, val value: PostRecord)

/** Strict, bounded domain journal. Stored bodies remain original UTF-8, not recomputed requests. */
internal class PostDraftCodec(private val origin: String, private val policy: PostDraftClientPolicy) {
    private val validator = CanonicalBodyValidator.bundled()
    fun schema(name: String, document: WireDocument) {
        if (document.encodeUtf8().size > policy.maxResponseBytes ||
            validator.validateSchema(name, document.encodeUtf8()) != ContractValidationResult.Valid) mealFail(FailureReason.INVALID_DATA)
    }
    fun encode(value: PostRecord): PrivateBytes = PrivateBytes(buildJsonObject {
        put("schema", 1); put("origin", origin); put("clock", value.clock)
        put("locals", JsonArray(value.locals.map { local -> buildJsonObject {
            put("id", local.id); put("revision", local.revision); put("caption", local.caption)
            put("alt", local.alt?.let(::JsonPrimitive) ?: JsonNull)
            put("server", text(local.server)); put("etag", local.etag?.let(::JsonPrimitive) ?: JsonNull)
        } }))
        put("issued", JsonArray(value.issued.map(::JsonPrimitive)))
        put("tombstones", JsonArray(value.tombstones.map { buildJsonObject {
            put("client", it.clientId); put("server", it.serverId?.let(::JsonPrimitive) ?: JsonNull)
            put("command", it.commandId?.let(::JsonPrimitive) ?: JsonNull)
        } }))
        put("command", value.command?.let { c -> buildJsonObject {
            put("id", c.id); put("operation", c.operation); put("client", c.clientId); put("revision", c.localRevision)
            put("body", text(c.body)); put("baseline", text(c.baseline)); put("etag", c.etag?.let(::JsonPrimitive) ?: JsonNull); put("created", c.created)
        } } ?: JsonNull)
        put("completion", value.completion?.let { buildJsonObject {
            put("command", it.commandId); put("operation", it.operation); put("client", it.clientId); put("unsent", it.unsent)
        } } ?: JsonNull)
        put("localPending", value.localPending?.let { buildJsonObject { put("client", it.clientId); put("revision", it.revision) } } ?: JsonNull)
    }.toString().encodeToByteArray()).also {
        if (it.copyForCodec().size > policy.maxRecordBytes) mealFail(FailureReason.UNAVAILABLE)
        decode(it)
    }

    fun decode(bytes: PrivateBytes): PostRecord {
        val root = WireDocument.decode(bytes.copyForCodec(), WireLimits(policy.maxRecordBytes, 32)).json().jsonObject
        keys(root, "schema", "origin", "clock", "locals", "issued", "tombstones", "command", "completion", "localPending")
        if (long(root.getValue("schema")) != 1L || string(root.getValue("origin")) != origin) mealFail(FailureReason.INVALID_DATA)
        val locals = root.getValue("locals").jsonArray.map { item -> item.jsonObject.let {
            keys(it, "id", "revision", "caption", "alt", "server", "etag")
            val id = strictId(string(it.getValue("id"))); val revision = positive(it.getValue("revision"))
            val caption = string(it.getValue("caption")); val alt = nullableString(it.getValue("alt")); postText(caption, alt)
            val server = document(it.getValue("server"), "PostDraft"); val etag = nullableString(it.getValue("etag"))
            if ((server == null) != (etag == null)) mealFail(FailureReason.INVALID_DATA)
            if (server != null) { postEtag(server, etag!!); if (postString(server, "clientDraftId") != id) mealFail(FailureReason.INVALID_DATA) }
            PostLocal(id, revision, caption, alt, server, etag)
        } }
        val issued = root.getValue("issued").jsonArray.map { strictId(string(it)) }
        val tombstones = root.getValue("tombstones").jsonArray.map { item -> item.jsonObject.let {
            keys(it, "client", "server", "command")
            PostTerminal(strictId(string(it.getValue("client"))), nullableString(it.getValue("server"))?.let(::strictId),
                nullableString(it.getValue("command"))?.let(::strictId))
        } }
        val command = root.getValue("command").takeUnless { it == JsonNull }?.jsonObject?.let { c ->
            keys(c, "id", "operation", "client", "revision", "body", "baseline", "etag", "created")
            val operation = string(c.getValue("operation"))
            if (operation !in POST_MUTATIONS) mealFail(FailureReason.INVALID_DATA)
            val body = document(c.getValue("body"), if (operation == "createPostDraft") "PostDraftWrite" else "PostDraftPatch")
            val baseline = document(c.getValue("baseline"), "PostDraft"); val etag = nullableString(c.getValue("etag"))
            val client = strictId(string(c.getValue("client")))
            if (operation == "createPostDraft") {
                if (baseline != null || etag != null || body == null || postString(body, "clientDraftId") != client) mealFail(FailureReason.INVALID_DATA)
                val allowed = setOf("clientDraftId", "caption", "altText", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves")
                val actual = body.json().jsonObject
                if (actual.keys.any { it !in allowed } || actual["mediaIds"] != JsonArray(emptyList()) ||
                    actual["audience"] != postSelfAudience() || actual["keepOnPlate"] != JsonPrimitive(false) ||
                    actual["allowRecipeSaves"] != JsonPrimitive(false)) mealFail(FailureReason.INVALID_DATA)
            } else {
                if (baseline == null || etag == null || postString(baseline, "clientDraftId") != client) mealFail(FailureReason.INVALID_DATA)
                postEtag(baseline, etag)
                if (operation == "deletePostDraft") { if (body != null) mealFail(FailureReason.INVALID_DATA) }
                else if (body == null || body.json().jsonObject.keys !in listOf(setOf("caption"), setOf("caption", "altText"))) mealFail(FailureReason.INVALID_DATA)
            }
            PostOriginal(strictId(string(c.getValue("id"))), operation, client, positive(c.getValue("revision")), body,
                baseline, etag, long(c.getValue("created")))
        }
        val completion = root.getValue("completion").takeUnless { it == JsonNull }?.jsonObject?.let {
            keys(it, "command", "operation", "client", "unsent")
            val operation = string(it.getValue("operation")); if (operation !in POST_MUTATIONS) mealFail(FailureReason.INVALID_DATA)
            PostCompletion(strictId(string(it.getValue("command"))), operation, strictId(string(it.getValue("client"))), boolean(it.getValue("unsent")))
        }
        val localPending = root.getValue("localPending").takeUnless { it == JsonNull }?.jsonObject?.let {
            keys(it, "client", "revision"); PostLocalPending(strictId(string(it.getValue("client"))), positive(it.getValue("revision")))
        }
        val value = PostRecord(long(root.getValue("clock")), locals, issued, tombstones, command, completion, localPending)
        if (locals.size > policy.maxLocalDrafts || issued.size > policy.maxIssuedIds || tombstones.size > policy.maxTombstones ||
            locals.map { it.id }.distinct().size != locals.size || issued.distinct().size != issued.size ||
            tombstones.map { it.clientId }.distinct().size != tombstones.size ||
            locals.any { it.id !in issued || tombstones.any { tomb -> tomb.clientId == it.id } } ||
            tombstones.any { it.clientId !in issued || (it.commandId != null && it.commandId !in issued) } ||
            (command != null && (completion != null || command.id !in issued || command.clientId !in issued ||
                locals.none { it.id == command.clientId && it.revision >= command.localRevision } || command.created > value.clock)) ||
            (completion != null && (completion.commandId !in issued || completion.clientId !in issued ||
                (locals.none { it.id == completion.clientId } && tombstones.none { it.clientId == completion.clientId }))) ||
            (localPending != null && locals.none { it.id == localPending.clientId && it.revision == localPending.revision })) mealFail(FailureReason.INVALID_DATA)
        return value
    }
    private fun document(value: JsonElement, name: String): WireDocument? = nullableString(value)?.let {
        WireDocument.parse(it, WireLimits(policy.maxResponseBytes, 24)).also { doc -> schema(name, doc) }
    }
    private fun text(document: WireDocument?): JsonElement = document?.let { JsonPrimitive(it.encodeUtf8().decodeToString()) } ?: JsonNull
    private fun positive(value: JsonElement) = long(value).also { if (it == 0L) mealFail(FailureReason.INVALID_DATA) }
}

internal val POST_MUTATIONS = setOf("createPostDraft", "updatePostDraft", "deletePostDraft")
internal fun strictId(value: String) = uuid(value).also { if (it != value) mealFail(FailureReason.INVALID_DATA) }
internal fun postString(document: WireDocument, field: String) = (document.field(field) as? WireField.Value)?.value?.stringOrNull()
    ?: mealFail(FailureReason.INVALID_DATA)
internal fun postAlt(document: WireDocument) = (document.field("altText") as? WireField.Value)?.value?.stringOrNull()
internal fun postVersion(document: WireDocument): String = kiNumber(document.json().jsonObject.getValue("version")).also {
    if (!it.matches(Regex("[1-9][0-9]*"))) mealFail(FailureReason.INVALID_DATA)
}
internal fun postEtag(document: WireDocument, etag: String) { if (etag != "\"${postVersion(document)}\"") mealFail(FailureReason.INVALID_DATA) }
internal fun postCompare(left: String, right: String) = if (left.length == right.length) left.compareTo(right) else left.length.compareTo(right.length)
internal fun postSuccessor(version: String): String {
    val digits = version.toCharArray(); var index = digits.lastIndex
    while (index >= 0 && digits[index] == '9') { digits[index] = '0'; index-- }
    if (index < 0) return "1" + digits.concatToString()
    digits[index] = (digits[index].code + 1).toChar(); return digits.concatToString()
}
internal fun postHead(tag: String?): String {
    val value = tag ?: mealFail(FailureReason.INVALID_DATA)
    if (value.length !in 3..2004 || value.first() != '"' || value.last() != '"' ||
        !value.substring(1, value.lastIndex).matches(Regex("0|[1-9][0-9]*"))) mealFail(FailureReason.INVALID_DATA)
    return value
}
// Compiled canonical rules are immutable; every validation keeps its own document/budget.
private val postTextContract by lazy { CanonicalBodyValidator.bundled() }
internal fun postText(caption: String, alt: String?) {
    // Contract validator counts Unicode scalar values; this also rejects unpaired surrogates.
    val body = WireDocument.parse(buildJsonObject { put("caption", caption); alt?.let { put("altText", it) } }.toString(), WireLimits(8192, 4))
    if (postTextContract.validateSchema("PostDraftPatch", body.encodeUtf8()) != ContractValidationResult.Valid) mealFail(FailureReason.INVALID_DATA)
}
internal fun postSelfAudience() = buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) }
internal fun postSame(a: PrivateRecord?, b: PrivateRecord?) = if (a == null || b == null) a == b else
    a.revision == b.revision && a.schemaVersion == b.schemaVersion && a.payload.copyForCodec().contentEquals(b.payload.copyForCodec())
internal fun postSameCall(a: ApiCall, b: ApiCall): Boolean {
    val ak = a.idempotencyKey; val bk = b.idempotencyKey
    return a.operationId == b.operationId && a.pathParameters == b.pathParameters && a.queryParameters == b.queryParameters &&
        a.ifMatch == b.ifMatch && ak != null && bk != null && ak.use { first -> bk.use { first == it } } &&
        (if (a.body == null || b.body == null) a.body == null && b.body == null else a.body!!.copyForCodec().contentEquals(b.body!!.copyForCodec()))
}

internal class PostEdit(val proposed: PostLocal, predecessors: List<PostLocal?>) {
    // At most the actually observed predecessor and a possibly committed proposal. Collapse to
    // the exact read immediately before each write, never grow an edit-history list.
    var predecessors: List<PostLocal?> = predecessors.toList()
    var mutation: StoreMutation.Put? = null
    var delivery: PostDelivery? = null
    override fun toString() = "PostEdit(<redacted>)"
}
internal class PostApply(val original: PostOriginal, val mutation: StoreMutation.Put, val receiptRevision: Long, val unsent: Boolean) {
    var archive: StoreMutation.Put? = null
    var delivery: PostDelivery? = null
    override fun toString() = "PostApply(<redacted>)"
}
/** No-suspension, exact publication ticket used only after every cancellable dispatcher return. */
@OptIn(ExperimentalAtomicApi::class)
internal class PostDelivery {
    private sealed interface State
    private data object Pending : State
    private class Armed(val edit: PostEdit?, val apply: PostApply?, val publication: Any) : State
    private class Delivered(val edit: PostEdit?, val apply: PostApply?) : State
    private data object Revoked : State
    private val state = AtomicReference<State>(Pending)
    fun arm(edit: PostEdit?, apply: PostApply?, publication: Any) = state.compareAndSet(Pending, Armed(edit, apply, publication))
    fun deliver(edit: PostEdit?, apply: PostApply?, publication: Any): Boolean {
        val current = state.load() as? Armed ?: return false
        return current.edit === edit && current.apply === apply && current.publication === publication && state.compareAndSet(current, Delivered(edit, apply))
    }
    fun delivered(edit: PostEdit) = (state.load() as? Delivered)?.edit === edit
    fun delivered(apply: PostApply) = (state.load() as? Delivered)?.apply === apply
    fun revoke() { while (true) { val old = state.load(); if (old is Delivered || old === Revoked || state.compareAndSet(old, Revoked)) return } }
}
/** Immutable atomic registry membership; entries belong to the exact lease/store/boundary/origin.
 * Editor close does not dispose uncertain text or proof. Only actual lease invalidation does. */
@OptIn(ExperimentalAtomicApi::class)
internal object PostDraftHeld {
    private class Held(val access: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary) {
        val edit = AtomicReference<PostEdit?>(null); val apply = AtomicReference<PostApply?>(null)
        val redacted = AtomicReference<Set<String>>(emptySet())
        val observations = AtomicReference<List<WireDocument>>(emptyList())
        var subscription: SessionInvalidationSubscription? = null
        fun matches(a: AuthenticatedMealPlanningAccess, b: SessionBoundary) = access.lease === a.lease && access.store === a.store && access.origin == a.origin && boundary === b
    }
    private val held = AtomicReference<List<Held>>(emptyList())
    private fun find(a: AuthenticatedMealPlanningAccess, b: SessionBoundary) = held.load().singleOrNull { it.matches(a, b) }
    private fun holder(a: AuthenticatedMealPlanningAccess, b: SessionBoundary): Held {
        if (!b.isCurrent(a.lease)) mealFail(FailureReason.STALE_SESSION)
        while (true) {
            val old = held.load(); old.singleOrNull { it.matches(a, b) }?.let { return it }
            val next = Held(a, b); if (!held.compareAndSet(old, old + next)) continue
            val subscription = b.onInvalidated(a.lease) { clear(a, b) }
            if (b.isCurrent(a.lease) && next in held.load()) next.subscription = subscription
            else { clear(a, b); subscription.close(); mealFail(FailureReason.STALE_SESSION) }
            return next
        }
    }
    fun edit(a: AuthenticatedMealPlanningAccess, b: SessionBoundary) = find(a, b)?.edit?.load()
    fun apply(a: AuthenticatedMealPlanningAccess, b: SessionBoundary) = find(a, b)?.apply?.load()
    fun retainEdit(a: AuthenticatedMealPlanningAccess, b: SessionBoundary, value: PostEdit) { holder(a, b).edit.store(value) }
    fun retainApply(a: AuthenticatedMealPlanningAccess, b: SessionBoundary, value: PostApply) { holder(a, b).apply.store(value) }
    fun redacted(a: AuthenticatedMealPlanningAccess, b: SessionBoundary) = find(a, b)?.redacted?.load() ?: emptySet()
    fun redact(a: AuthenticatedMealPlanningAccess, b: SessionBoundary, id: String) {
        val values = holder(a, b).redacted
        while (true) { val old = values.load(); if (id in old || values.compareAndSet(old, old + id)) return }
    }
    fun reveal(a: AuthenticatedMealPlanningAccess, b: SessionBoundary, id: String) {
        val values = find(a, b)?.redacted ?: return
        while (true) { val old = values.load(); if (id !in old || values.compareAndSet(old, old - id)) return }
    }
    fun observations(a: AuthenticatedMealPlanningAccess, b: SessionBoundary) = find(a, b)?.observations?.load() ?: emptyList()
    fun remember(a: AuthenticatedMealPlanningAccess, b: SessionBoundary, documents: List<WireDocument>, policy: PostDraftClientPolicy) {
        val values = holder(a, b).observations
        while (true) {
            val old = values.load(); val adapter = PostDraftAdapter(policy)
            for (document in documents) {
                val id = postString(document, "id"); val client = postString(document, "clientDraftId")
                if (old.any { postString(it, "clientDraftId") == client && postString(it, "id") != id }) mealFail(FailureReason.CONFLICT)
                old.singleOrNull { postString(it, "id") == id }?.let { adapter.monotone(it, document) }
            }
            val ids = documents.map { postString(it, "id") }.toSet()
            val next = old.filterNot { postString(it, "id") in ids } + documents
            if (next.size > policy.maxTraversalItems || next.sumOf { it.encodeUtf8().size.toLong() } > policy.maxRecordBytes) mealFail(FailureReason.UNAVAILABLE)
            if (values.compareAndSet(old, next)) return
        }
    }
    fun clearEdit(a: AuthenticatedMealPlanningAccess, b: SessionBoundary, value: PostEdit) { find(a, b)?.edit?.compareAndSet(value, null) }
    fun clearApply(a: AuthenticatedMealPlanningAccess, b: SessionBoundary, value: PostApply) { find(a, b)?.apply?.compareAndSet(value, null) }
    fun clear(a: AuthenticatedMealPlanningAccess, b: SessionBoundary) { while (true) {
        val old = held.load(); val removed = old.filter { it.matches(a, b) }; if (removed.isEmpty()) return
        if (held.compareAndSet(old, old - removed.toSet())) { removed.forEach { it.subscription?.close() }; return }
    } }
}
