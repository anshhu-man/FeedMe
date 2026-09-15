package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.transport.MobileRequestValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Authenticated GET-only picker. The sole write is an encrypted catalog-label cache: never
 * pantry stock, request selection or meal submission. All actions are explicit. Use the SAME
 * serialized dispatcher/boundary as the borrowed session and retain only its current StateFlow.
 */
class IngredientPickerController(
    private val access: AuthenticatedMealPlanningAccess,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
    private val connectivity: ConnectivityPort,
    private val policy: IngredientPickerPolicy,
) {
    private val mutex = Mutex()
    private val origin = uuid(access.origin)
    private val cacheKey = RecordKey("mealflow.ingredients.v1", origin)
    private val mealKey = RecordKey("mealflow.v1", origin)
    private val codec = IngredientPickerCache(origin, policy)
    private val validator = MobileRequestValidator()
    private val binder = CanonicalResponseBinder()
    private val bodyValidator = CanonicalBodyValidator.bundled()
    private val mutable = MutableStateFlow(IngredientPickerState.empty())
    val states: StateFlow<IngredientPickerState> = mutable.asStateFlow()
    private var lifecycle = Any(); private var searchGeneration = Any(); private var pantryGeneration = Any()
    private var closed = false; private var claimed = false; private var lastClock = 0L
    private var searchBusy: Any? = null; private var pantryBusy: Any? = null
    private var query = ""
    private var cache = IngredientCache(0, emptyList())
    private var matches = emptyList<CachedIngredient>(); private val liveLabels = mutableSetOf<String>()
    private var pantry = emptyList<PantryRow>()
    private var searchCursor: String? = null; private var pantryCursor: String? = null
    private var searchPages = 0; private var pantryPages = 0
    private val seenSearchCursors = mutableSetOf<String>(); private val seenPantryCursors = mutableSetOf<String>()
    private val subscription = boundary.onInvalidated(access.lease) { redact() }

    init { require(access.lease.scope.actorKind != ActorKind.DEMO) }

    /** Restore only already-downloaded metadata and the request controller's SINGLE pantry-page
     * cache. No second persisted pantry authority or implicit first-page network request. */
    suspend fun restore(): PortResult<IngredientPickerState> = run(Channel.ALL, replace = true) { token ->
        var stored = readCache(token)
        val valid = codec.prune(stored.data, now())
        val expired = valid.items.size != stored.data.items.size
        if (expired) stored = writeCache(stored, valid, token)
        cache = stored.data; matches = emptyList(); liveLabels.clear(); query = ""
        searchCursor = null; searchPages = 0; seenSearchCursors.clear()
        val result = access.store.read(access.lease.scope, mealKey); check(token)
        val record = mealValue(result)
        val page = record?.let {
            if (it.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
            MealFlowCodec(MealRequestBuilder(), origin).decode(it.payload).pantry
        }
        pantry = page?.let { pantryPage(it, null, true, 50).items }.orEmpty()
        pantryCursor = page?.let { cursor(it.json().jsonObject.getValue("nextCursor")) }
        pantryPages = if (page == null) 0 else 1; seenPantryCursors.clear()
        pantryCursor?.let(seenPantryCursors::add)
        publish(IngredientPickerPhase.IDLE, if (pantry.isEmpty()) IngredientPickerPhase.EMPTY else IngredientPickerPhase.READY,
            if (expired) IngredientPickerIssue.CACHE_EXPIRED else IngredientPickerIssue.NONE)
    }

    /** Empty q is schema-valid and is sent explicitly; backend refusal is surfaced, not replaced
     * with invented catalog rows. Offline search matches only downloaded names/aliases. */
    suspend fun search(query: String): PortResult<IngredientPickerState> = run(Channel.SEARCH, replace = true) { token ->
        checkedQuery(query)
        val stored = readCache(token)
        this.query = query; cache = codec.prune(stored.data, now())
        matches = emptyList(); searchCursor = null; searchPages = 0; seenSearchCursors.clear()
        publish(IngredientPickerPhase.LOADING)
        if (!online()) {
            liveLabels.clear()
            matches = cache.items.filter { ingredient ->
                val option = ingredient.option(true)
                option.name.contains(query, ignoreCase = true) || option.aliases.any { it.contains(query, ignoreCase = true) }
            }
            return@run publish(IngredientPickerPhase.OFFLINE, issue = IngredientPickerIssue.OFFLINE)
        }
        searchPage(stored, null, token)
    }

    suspend fun nextSearchPage(): PortResult<IngredientPickerState> = run(Channel.SEARCH) { token ->
        val next = searchCursor ?: return@run publish(issue = IngredientPickerIssue.PAGE_LIMIT)
        if (searchPages >= policy.maxPages) return@run publish(issue = IngredientPickerIssue.PAGE_LIMIT)
        if (!online()) return@run publish(IngredientPickerPhase.OFFLINE, issue = IngredientPickerIssue.OFFLINE)
        val stored = readCache(token)
        publish(IngredientPickerPhase.LOADING)
        searchPage(stored, next, token)
    }

    /** Fresh, ephemeral pantry view. This does not change the request controller's stored page,
     * pantry stock, or selected ingredients. Absence from fetched pages is not reported absence. */
    suspend fun refreshPantry(): PortResult<IngredientPickerState> = run(Channel.PANTRY, replace = true) { token ->
        if (!online()) return@run publish(pantryPhase = IngredientPickerPhase.OFFLINE, issue = IngredientPickerIssue.OFFLINE)
        publish(pantryPhase = IngredientPickerPhase.LOADING)
        val result = fetch("listPantry", null, null, token)
        val page = pantryPage(result, now(), false, policy.pageSize)
        pantry = page.items; pantryCursor = page.next; pantryPages = 1; seenPantryCursors.clear()
        page.next?.let(seenPantryCursors::add)
        publish(pantryPhase = if (pantry.isEmpty()) IngredientPickerPhase.EMPTY else IngredientPickerPhase.READY)
    }

    suspend fun nextPantryPage(): PortResult<IngredientPickerState> = run(Channel.PANTRY) { token ->
        val next = pantryCursor ?: return@run publish(issue = IngredientPickerIssue.PAGE_LIMIT)
        if (pantryPages >= policy.maxPages) return@run publish(issue = IngredientPickerIssue.PAGE_LIMIT)
        if (!online()) return@run publish(pantryPhase = IngredientPickerPhase.OFFLINE, issue = IngredientPickerIssue.OFFLINE)
        publish(pantryPhase = IngredientPickerPhase.LOADING)
        val page = pantryPage(fetch("listPantry", null, next, token), now(), false, policy.pageSize)
        if (page.next != null && page.next in seenPantryCursors) mealFail(FailureReason.CONFLICT)
        val ids = pantry.map { it.ingredientId }.toSet()
        val rowIds = pantry.map { it.id }.toSet()
        if (page.items.any { it.ingredientId in ids || it.id in rowIds }) mealFail(FailureReason.CONFLICT)
        pantry = pantry + page.items; pantryCursor = page.next; pantryPages++
        page.next?.let(seenPantryCursors::add)
        publish(pantryPhase = if (pantry.isEmpty()) IngredientPickerPhase.EMPTY else IngredientPickerPhase.READY)
    }

    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        redact(); closed = true; subscription.close(); PortResult.Value(Unit)
    }

    private suspend fun searchPage(before: IngredientCacheEntry, next: String?, token: Token): IngredientPickerState {
        val result = fetch("searchIngredients", query, next, token)
        val root = result.json().jsonObject; val items = root.getValue("items").jsonArray
        if (items.size > policy.pageSize) mealFail(FailureReason.INVALID_DATA)
        val received = now()
        val incoming = items.map { CachedIngredient(doc(it).also(codec::validate), received) }
        if (incoming.map { it.id }.distinct().size != incoming.size || incoming.any { fresh -> matches.any { it.id == fresh.id } })
            mealFail(FailureReason.CONFLICT)
        val following = cursor(root.getValue("nextCursor"))
        if (following != null && following in seenSearchCursors) mealFail(FailureReason.CONFLICT)
        val merged = codec.merge(before.data, incoming, received)
        val saved = writeCache(before, merged, token)
        cache = saved.data; matches = matches + incoming; liveLabels += incoming.map { it.id }
        searchCursor = following; searchPages++; following?.let(seenSearchCursors::add)
        return publish(if (matches.isEmpty()) IngredientPickerPhase.EMPTY else IngredientPickerPhase.READY)
    }

    private suspend fun fetch(operation: String, q: String?, cursor: String?, token: Token): WireDocument {
        check(token)
        if (!online()) mealFail(FailureReason.OFFLINE)
        val parameters = mutableMapOf("limit" to listOf(policy.pageSize.toString()))
        q?.let { parameters["q"] = listOf(it) }; cursor?.let { parameters["cursor"] = listOf(it) }
        val call = ApiCall(operation, queryParameters = parameters)
        if (!validator.accepts(call, principal())) mealFail(FailureReason.INVALID_DATA)
        val response = try { access.transport.execute(access.lease, call) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { check(token); throw PickerFailure(FailureReason.UNAVAILABLE, null) }
        check(token)
        val reply = when (response) { is PortResult.Value -> response.value
            is PortResult.Failure -> throw PickerFailure(response.reason, response.retryAfterSeconds) }
        if (binder.bind(operation, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted)
            mealFail(FailureReason.INVALID_DATA)
        if (reply.status != 200) throw PickerFailure(when (reply.status) {
            401 -> FailureReason.UNAUTHENTICATED; 403 -> FailureReason.FORBIDDEN; 404, 410 -> FailureReason.NOT_FOUND
            409, 412 -> FailureReason.CONFLICT; 429 -> FailureReason.RATE_LIMITED; 500, 503 -> FailureReason.UNAVAILABLE
            else -> FailureReason.INVALID_DATA
        }, reply.retryAfterSeconds)
        return WireDocument.decode(reply.body!!.copyForCodec(), WireLimits(262_144, 16))
    }

    private suspend fun readCache(token: Token): IngredientCacheEntry {
        check(token); claim()
        val result = access.store.read(access.lease.scope, cacheKey); check(token)
        val record = mealValue(result)
        if (record != null && record.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        val decoded = record?.let { codec.decode(it.payload) } ?: IngredientCache(now(), emptyList())
        if (decoded.clock > now()) mealFail(FailureReason.CONFLICT)
        return IngredientCacheEntry(record, decoded)
    }
    private suspend fun writeCache(before: IngredientCacheEntry, data: IngredientCache, token: Token): IngredientCacheEntry {
        check(token)
        val payload = codec.encode(data)
        val result = access.store.commit(access.lease.scope, listOf(StoreMutation.Put(cacheKey, before.record?.revision, 1, payload)))
        check(token)
        val receipt = mealValue(result); val revision = receipt[cacheKey]
        if (receipt.keys != setOf(cacheKey) || revision == null || revision <= (before.record?.revision ?: 0)) mealFail(FailureReason.STORAGE_FAILURE)
        val checked = access.store.read(access.lease.scope, cacheKey); check(token)
        val record = mealValue(checked) ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (record.revision != revision || record.schemaVersion != 1 || !record.payload.copyForCodec().contentEquals(payload.copyForCodec()))
            mealFail(FailureReason.CONFLICT)
        return IngredientCacheEntry(record, data)
    }

    private fun pantryPage(document: WireDocument, checked: Long?, historical: Boolean, maximum: Int): PantryPage {
        if (bodyValidator.validateSchema("PantryItemPage", document.encodeUtf8()) != ContractValidationResult.Valid)
            mealFail(FailureReason.INVALID_DATA)
        val root = document.json().jsonObject; val entries = root.getValue("items").jsonArray
        if (entries.size > maximum) mealFail(FailureReason.INVALID_DATA)
        val items = entries.map { item -> PantryRow(doc(item), checked, historical) }
        if (items.map { it.id }.distinct().size != items.size || items.map { it.ingredientId }.distinct().size != items.size)
            mealFail(FailureReason.CONFLICT)
        return PantryPage(items, cursor(root.getValue("nextCursor")))
    }
    private fun checkedQuery(value: String) {
        // Parsing first rejects malformed UTF-16 instead of lossy conversion into a new query.
        try { doc(JsonPrimitive(value)) } catch (_: Exception) { throw PickerFailure(FailureReason.INVALID_DATA, null, IngredientPickerIssue.INVALID_QUERY) }
        val points = value.indices.count { !value[it].isLowSurrogate() }
        if (points > 100 || value.any(Char::isISOControl)) throw PickerFailure(FailureReason.INVALID_DATA, null, IngredientPickerIssue.INVALID_QUERY)
    }
    private fun cursor(value: JsonElement): String? {
        if (value == JsonNull) return null
        val token = string(value)
        if (token.isBlank() || token.length > 2048 || token.any(Char::isISOControl)) mealFail(FailureReason.INVALID_DATA)
        return token
    }
    private fun online() = access.onlineAllowed && connectivity.current() == Connectivity.ONLINE
    private fun principal() = if (access.lease.scope.actorKind == ActorKind.GUEST) PrincipalClass.GUEST else PrincipalClass.ACCOUNT
    private fun now(): Long {
        val time = clock.nowMillis()
        if (time < 0 || time < lastClock) mealFail(FailureReason.CONFLICT)
        lastClock = time; return time
    }
    private suspend fun check(token: Token) {
        currentCoroutineContext().ensureActive()
        if (closed || !boundary.isCurrent(access.lease) || token.lifecycle !== lifecycle ||
            (token.channel == Channel.SEARCH && token.channelGeneration !== searchGeneration) ||
            (token.channel == Channel.PANTRY && token.channelGeneration !== pantryGeneration)) mealFail(FailureReason.STALE_SESSION)
    }
    private fun isCurrent(token: Token) = !closed && boundary.isCurrent(access.lease) && token.lifecycle === lifecycle &&
        (token.channel != Channel.SEARCH || token.channelGeneration === searchGeneration) &&
        (token.channel != Channel.PANTRY || token.channelGeneration === pantryGeneration)

    private suspend fun run(channel: Channel, replace: Boolean = false, action: suspend (Token) -> IngredientPickerState): PortResult<IngredientPickerState> {
        var owned: Token? = null; var busy: Any? = null
        try {
            return withContext(dispatcher) {
                if (closed || !boundary.isCurrent(access.lease)) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                if (!replace && (if (channel == Channel.SEARCH) searchBusy else pantryBusy) != null)
                    return@withContext PortResult.Failure(FailureReason.CONFLICT)
                if (replace) when (channel) { Channel.SEARCH -> searchGeneration = Any(); Channel.PANTRY -> pantryGeneration = Any()
                    Channel.ALL -> { lifecycle = Any(); searchGeneration = Any(); pantryGeneration = Any() } }
                val token = Token(lifecycle, channel, when (channel) { Channel.SEARCH -> searchGeneration; Channel.PANTRY -> pantryGeneration; Channel.ALL -> lifecycle })
                owned = token; busy = Any()
                if (channel == Channel.SEARCH) searchBusy = busy else if (channel == Channel.PANTRY) pantryBusy = busy
                try { mutex.withLock { check(token); claim(); val state = action(token); check(token); PortResult.Value(state) } }
                finally { if (searchBusy === busy) searchBusy = null; if (pantryBusy === busy) pantryBusy = null }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) { owned?.takeIf(::isCurrent)?.let { token ->
                when (token.channel) { Channel.SEARCH -> searchGeneration = Any(); Channel.PANTRY -> pantryGeneration = Any(); Channel.ALL -> lifecycle = Any() }
                publish(if (token.channel == Channel.PANTRY) mutable.value.searchPhase else IngredientPickerPhase.IDLE,
                    if (token.channel == Channel.SEARCH) mutable.value.pantryPhase else IngredientPickerPhase.IDLE)
            } }
            throw cancelled
        } catch (failure: Exception) {
            val reason = (failure as? PickerFailure)?.reason ?: (failure as? MealFailure)?.reason ?:
                if (failure is WireDecodingException || failure is SerializationException) FailureReason.INVALID_DATA else FailureReason.STORAGE_FAILURE
            val retry = (failure as? PickerFailure)?.retryAfter
            withContext(NonCancellable + dispatcher) { owned?.takeIf(::isCurrent)?.let {
                val unavailable = reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.FORBIDDEN, FailureReason.NOT_FOUND)
                val phase = if (unavailable) IngredientPickerPhase.UNAVAILABLE else if (reason == FailureReason.OFFLINE) IngredientPickerPhase.OFFLINE else IngredientPickerPhase.ERROR
                publish(if (it.channel == Channel.PANTRY) mutable.value.searchPhase else phase,
                    if (it.channel == Channel.SEARCH) mutable.value.pantryPhase else phase,
                    (failure as? PickerFailure)?.issue ?: when (reason) { FailureReason.OFFLINE -> IngredientPickerIssue.OFFLINE; FailureReason.RATE_LIMITED -> IngredientPickerIssue.RETRY_LATER
                        FailureReason.CONFLICT -> IngredientPickerIssue.CONTEXT_CHANGED; FailureReason.INVALID_DATA -> IngredientPickerIssue.INVALID_REPLY
                        else -> IngredientPickerIssue.STORAGE }, reason, retry)
            } }
            return PortResult.Failure(reason, retry)
        }
    }

    private fun publish(searchPhase: IngredientPickerPhase = mutable.value.searchPhase,
        pantryPhase: IngredientPickerPhase = mutable.value.pantryPhase, issue: IngredientPickerIssue = IngredientPickerIssue.NONE,
        failure: FailureReason? = null, retry: Long? = null): IngredientPickerState {
        if (closed || !boundary.isCurrent(access.lease)) return IngredientPickerState.unavailable().also { mutable.value = it }
        // Age is a visibility bound, not evidence that pantry stock is still present. Do not
        // retain expired visible labels simply because only the other pane was refreshed.
        val currentTime = clock.nowMillis()
        fun usable(item: CachedIngredient) = currentTime >= item.checked && currentTime - item.checked <= policy.cacheRetentionMillis
        val visibleMatches = matches.filter(::usable)
        val all = (cache.items.filter(::usable) + visibleMatches).associateBy { it.id }
        val known = all.values.map { it.option(it.id !in liveLabels || !online()) }
        val byId = known.associateBy { it.id }
        return IngredientPickerState(query, searchPhase, pantryPhase,
            visibleMatches.map { it.option(it.id !in liveLabels || !online()) }, pantry.map { row -> row.option(byId[row.ingredientId],
                !online() || row.checked?.let { currentTime < it || currentTime - it > policy.cacheRetentionMillis } == true) }, known,
            searchCursor != null && searchPages < policy.maxPages, pantryCursor != null && pantryPages < policy.maxPages,
            issue, failure, retry).also { mutable.value = it }
    }
    private fun claim() {
        if (!claimed) {
            if (owners.any { it.lease === access.lease && it.origin == origin }) mealFail(FailureReason.CONFLICT)
            owners += Owner(access.lease, origin, this); claimed = true
        }
    }
    private fun redact() {
        lifecycle = Any(); searchGeneration = Any(); pantryGeneration = Any(); query = ""
        cache = IngredientCache(0, emptyList()); matches = emptyList(); pantry = emptyList(); liveLabels.clear()
        searchCursor = null; pantryCursor = null; seenSearchCursors.clear(); seenPantryCursors.clear()
        if (claimed) { owners.removeAll { it.controller === this }; claimed = false }
        mutable.value = IngredientPickerState.unavailable()
    }
    private enum class Channel { ALL, SEARCH, PANTRY }
    private class Token(val lifecycle: Any, val channel: Channel, val channelGeneration: Any)
    private class Owner(val lease: SessionLease, val origin: String, val controller: IngredientPickerController)
    private class PickerFailure(val reason: FailureReason, val retryAfter: Long?, val issue: IngredientPickerIssue? = null) : Exception("Ingredient picker unavailable")
    private class PantryPage(val items: List<PantryRow>, val next: String?)
    private class PantryRow(val document: WireDocument, val checked: Long?, val historical: Boolean) {
        private val json = document.json().jsonObject
        val id = uuid(json.getValue("id").jsonPrimitive.content)
        val ingredientId = uuid(json.getValue("ingredientId").jsonPrimitive.content)
        fun option(ingredient: IngredientOption?, offline: Boolean) = PantryOption(id, ingredientId,
            json.getValue("presence").jsonPrimitive.content, field("confirmationStatus"), field("confirmedAt"),
            json.getValue("staple").jsonPrimitive.boolean, ingredient, document, checked, historical || offline)
        private fun field(name: String): WireField<String> = when (val value = json[name]) {
            null -> WireField.Missing; JsonNull -> WireField.Null; else -> WireField.Value(value.jsonPrimitive.content)
        }
    }
    private companion object { val owners = mutableListOf<Owner>() }
}
