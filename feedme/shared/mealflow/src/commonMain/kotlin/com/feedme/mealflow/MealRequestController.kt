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
import kotlinx.serialization.json.*

/**
 * Borrowed, encrypted owner-scoped manual planning flow. The SAME serialized identity dispatcher
 * owns this controller and SessionBoundary. No demo matching, credential reads, server identity,
 * background retry, native work or store activation occurs here. HTTP success alone is not enough:
 * canonical operation binding, exact request constraints, lifecycle, and local CAS are checked.
 * A pending command survives cancellation; only explicit retry sends its original key/body.
 */
class MealRequestController(
    private val access: AuthenticatedMealPlanningAccess,
    private val boundary: SessionBoundary,
    private val dispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
    private val connectivity: ConnectivityPort,
    private val ids: MealOperationIds,
    private val policy: MealFlowPolicy,
) {
    private val mutex = Mutex()
    private val builder = MealRequestBuilder()
    private val binder = CanonicalResponseBinder()
    private val requests = MobileRequestValidator()
    private val codec = MealFlowCodec(builder, uuid(access.origin))
    private val key = RecordKey("mealflow.v1", uuid(access.origin))
    private val mutable = MutableStateFlow(MealRequestState(MealFlowPhase.EDITING, MealFlowScreen.REQUEST,
        null, null, null, null, emptyList(), MealFlowIssue.CONTEXT_REQUIRED, null, false, false))
    val states: StateFlow<MealRequestState> = mutable.asStateFlow()
    private var generation = Any()
    private var closed = false
    private var busy: Any? = null
    private var last: FlowRecord? = null
    private var contextFresh = false
    private var claimed = false
    val draftReadiness = MealDraftReadiness(this, access, boundary)
    private var readinessAttempt: MealDraftReadiness.Attempt? = null
    private val recalled = mutableSetOf<String>()
    // The actual identity owner synchronously invalidates this lease, including same-account swaps.
    private val subscription = boundary.onInvalidated(access.lease) { invalidate() }

    init { require(access.lease.scope.actorKind != ActorKind.DEMO) }

    suspend fun restore(): PortResult<MealRequestState> = run {
        val entry = read(it)
        if (entry.data.command == null && now() - entry.data.updated > policy.draftRetentionMillis) {
            val empty = write(entry, FlowRecord(now(), now(), usedIds = entry.data.usedIds,
                recalledVersions = entry.data.recalledVersions), it)
            publish(empty.data, MealFlowPhase.EDITING, issue = MealFlowIssue.DRAFT_EXPIRED)
        } else publish(entry.data, phase(entry.data), historical = true)
    }

    /** Pantry pages are explicitly bounded and retain their nextCursor. Unfetched items are not
     * declared absent; usual/uncertain entries are never promoted into confirmed request IDs. */
    suspend fun refreshContext(): PortResult<MealRequestState> = run { token ->
        val entry = read(token)
        if (!online()) return@run publish(entry.data, MealFlowPhase.OFFLINE_DRAFT)
        publish(entry.data, MealFlowPhase.LOADING)
        val preferences = preferences(token, entry.data.preferences)
        val pantry = fetch("listPantry", ApiCall("listPantry", queryParameters = mapOf("limit" to listOf("50"))), token)
        val repeated = preferences(token, preferences)
        if (!equal(preferences, repeated)) mealFail(FailureReason.CONFLICT)
        val saved = write(entry, entry.data.copy(preferences = preferences, pantry = pantry,
            updated = now(), lastClock = now()), token)
        contextFresh = true
        publish(saved.data, phase(saved.data), historical = true)
    }

    /** Edits fence late responses immediately, preserve an unresolved earlier command verbatim,
     * and invalidate all old continuation eligibility. They never erase an explicit exclusion. */
    suspend fun edit(draft: ManualMealDraft): PortResult<MealRequestState> = run(fence = true, acknowledgeDraft = true) { token ->
        mealValue(attempt { builder.manual(draft) })
        val entry = read(token)
        val saved = write(entry, entry.data.copy(draft = draft, updated = now(), lastClock = now()), token)
        publish(saved.data, if (saved.data.command != null) MealFlowPhase.RESOLVING else if (online()) MealFlowPhase.EDITING else MealFlowPhase.OFFLINE_DRAFT,
            screen = MealFlowScreen.REQUEST, historical = true,
            issue = if (draft.preferencesPendingSync) MealFlowIssue.PREFERENCES_PENDING else MealFlowIssue.NONE)
    }

    suspend fun submit(): PortResult<MealRequestState> = run(command = true) { token ->
        val entry = read(token)
        val draft = entry.data.draft ?: mealFail(FailureReason.INVALID_DATA)
        if (inputPreferencesPending(token)) return@run preferenceBlocked(entry.data)
        if (entry.data.command != null) return@run publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.REQUEST_UNRESOLVED)
        if (!online()) return@run publish(entry.data, MealFlowPhase.OFFLINE_DRAFT)
        if (draft.preferencesPendingSync) return@run publish(entry.data, MealFlowPhase.NEEDS_CONFIRMATION, issue = MealFlowIssue.PREFERENCES_PENDING)
        publish(entry.data, MealFlowPhase.LOADING)
        val preferences = preferences(token, entry.data.preferences)
        if (inputPreferencesPending(token)) return@run preferenceBlocked(entry.data)
        val body = mealValue(builder.build(draft, preferences))
        val id = newId(entry.data, token)
        val command = FlowCommand(id, "createPlan", body, builder.manual(draft), preferences, null, now(), now())
        contextFresh = true
        val prepared = write(entry, entry.data.copy(preferences = preferences, command = command,
            usedIds = entry.data.usedIds + FlowIssuedId(id, command.created), updated = now(), lastClock = now()), token)
        send(prepared, token)
    }

    /** Never rotates the key or replaces an unresolved body. Expired/clock-rollback/context-change
     * cases remain blocked for explicit reconciliation; a readback is not a write acknowledgement. */
    suspend fun retrySubmitted(): PortResult<MealRequestState> = run(command = true) { token ->
        val entry = read(token)
        val pending = entry.data.command ?: mealFail(FailureReason.CONFLICT)
        if (inputPreferencesPending(token)) return@run preferenceBlocked(entry.data)
        if (!online()) return@run publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.REQUEST_UNRESOLVED)
        if (now() < pending.created || now() - pending.created >= policy.replayWindowMillis)
            return@run publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.REPLAY_EXPIRED)
        if (now() < pending.retryAt) return@run publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.RETRY_LATER)
        val current = preferences(token, pending.preferences)
        if (inputPreferencesPending(token)) return@run preferenceBlocked(entry.data)
        if (!samePreferences(current, pending.preferences) || entry.data.draft?.preferencesPendingSync == true)
            return@run publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.CONTEXT_CHANGED)
        // Fresh changed CAS + exact readback precedes every retry, even a now-visible prior commit.
        val acknowledged = write(entry, entry.data.copy(lastClock = now()), token)
        send(acknowledged, token)
    }

    /** Canonical nextPlan deliberately has no If-Match header: the opaque parent/cursor is pinned
     * by the backend. Already-fetched immediate children can be browsed without network effects. */
    suspend fun nextAlternative(): PortResult<MealRequestState> = run(command = true) { token ->
        val entry = read(token); val data = entry.data
        if (inputPreferencesPending(token)) return@run preferenceBlocked(data)
        if (data.command != null) return@run publish(data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.REQUEST_UNRESOLVED)
        val current = data.plans.getOrNull(data.selected) ?: mealFail(FailureReason.CONFLICT)
        val draft = data.draft ?: mealFail(FailureReason.CONFLICT)
        if (!matchesDraft(data, current.request)) return@run publish(data, MealFlowPhase.NEEDS_CONFIRMATION, issue = MealFlowIssue.CONTEXT_CHANGED)
        val plan = PlanWire.from(current.body)
        if (recalled(current, data)) mealFail(FailureReason.CONFLICT)
        val cached = data.plans.getOrNull(data.selected + 1)
        if (cached != null && equal(cached.request, current.request) &&
            (PlanWire.from(cached.body).parentPlanId as? WireField.Value)?.value?.value?.lowercase() == plan.id.value.lowercase()) {
            val saved = write(entry, data.copy(selected = data.selected + 1, lastClock = now()), token)
            return@run publish(saved.data, phase(saved.data), screen = MealFlowScreen.RECOMMENDATIONS, historical = true)
        }
        if (!online()) return@run publish(data, MealFlowPhase.OFFLINE_DRAFT, issue = MealFlowIssue.NO_MORE_ALTERNATIVES)
        val cursor = (plan.nextAlternativeCursor as? WireField.Value)?.value
        if (cursor.isNullOrBlank()) return@run publish(data, phase(data), issue = MealFlowIssue.NO_MORE_ALTERNATIVES)
        if (cursor.length > 2048 || plan.status != "ready") mealFail(FailureReason.INVALID_DATA)
        val preferences = preferences(token, data.preferences)
        if (inputPreferencesPending(token)) return@run preferenceBlocked(data)
        val request = mealValue(builder.build(draft, preferences))
        if (!equal(request, current.request)) return@run publish(data, MealFlowPhase.NEEDS_CONFIRMATION, issue = MealFlowIssue.CONTEXT_CHANGED)
        // Only this parent's retained ancestry was presented in this lineage. Independent roots
        // can have identical constraints/catalogs without authorizing their version IDs here.
        val excluded = ancestry(data, current).mapNotNull { (PlanWire.from(it.body).recipeVersionId as? WireField.Value)?.value?.value }.distinct()
        val body = doc(buildJsonObject {
            put("constraints", plan.constraints.json()); put("reason", "alternative"); put("continuationCursor", cursor)
            put("excludeRecipeVersionIds", JsonArray(excluded.map(::JsonPrimitive)))
        })
        builder.schema("AdaptRequest", body)
        val id = newId(data, token)
        val pending = FlowCommand(id, "nextPlan", body, builder.manual(draft), preferences, plan.id.value, now(), now())
        val prepared = write(entry, data.copy(preferences = preferences, command = pending, usedIds = data.usedIds + FlowIssuedId(id, pending.created),
            updated = now(), lastClock = now()), token)
        send(prepared, token)
    }

    suspend fun previousPlan(): PortResult<MealRequestState> = run(fence = true) { token ->
        val entry = read(token)
        if (entry.data.selected <= 0) mealFail(FailureReason.CONFLICT)
        val saved = write(entry, entry.data.copy(selected = entry.data.selected - 1, lastClock = now()), token)
        publish(saved.data, phase(saved.data), screen = MealFlowScreen.RECOMMENDATIONS, historical = true)
    }
    /** Local Back is immediate even while transport is suspended. Only the last acknowledged
     * or validated read is visible; pending command, selected plan and draft are not rewritten. */
    suspend fun backToDraft(): PortResult<MealRequestState> = navigate(MealFlowScreen.REQUEST)
    /** Back from a recipe to the SAME recommendation, not the previous alternative. */
    suspend fun returnToRecommendations(): PortResult<MealRequestState> = navigate(MealFlowScreen.RECOMMENDATIONS)
    private suspend fun navigate(screen: MealFlowScreen): PortResult<MealRequestState> = run(fence = true, cached = true) { token ->
        val data = last ?: read(token).data
        if (screen == MealFlowScreen.RECOMMENDATIONS && data.plans.getOrNull(data.selected) == null) mealFail(FailureReason.CONFLICT)
        val visible = mutable.value
        publish(data, if (data.command != null) MealFlowPhase.RESOLVING else if (screen == MealFlowScreen.REQUEST) MealFlowPhase.EDITING else phase(data),
            screen = screen, historical = true, issue = visible.issue, failure = visible.failureReason)
    }
    /** Historical read-only recipe navigation, never createCookSession/saveRecipe or cooking
     * permission. Current recall/rights still require the separately owned cooking workflow. */
    suspend fun openRecipe(): PortResult<MealRequestState> = run(fence = true) { token ->
        val entry = read(token); val p = entry.data.plans.getOrNull(entry.data.selected) ?: mealFail(FailureReason.CONFLICT)
        if (PlanWire.from(p.body).status != "ready" || recalled(p, entry.data)) mealFail(FailureReason.CONFLICT)
        publish(entry.data, phase(entry.data), screen = MealFlowScreen.RECIPE, historical = true)
    }

    suspend fun close(): PortResult<Unit> = withContext(dispatcher) {
        invalidate(); closed = true
        draftReadiness.close()
        subscription.close()
        if (claimed) { owners.removeAll { it.controller === this@MealRequestController }; claimed = false }
        PortResult.Value(Unit) // Borrowed stores/transport/session are never closed or retired here.
    }

    private suspend fun send(prepared: FlowEntry, token: Any): MealRequestState {
        var entry = prepared
        var pending = entry.data.command ?: mealFail(FailureReason.CONFLICT)
        if (inputPreferencesPending(token)) return preferenceBlocked(entry.data)
        if (!online()) return publish(entry.data, MealFlowPhase.RESOLVING)
        if (now() < pending.created || now() - pending.created >= policy.replayWindowMillis)
            return publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.REPLAY_EXPIRED)
        if (!requests.accepts(pending.call(), principal())) mealFail(FailureReason.INVALID_DATA)
        val final = read(token)
        if (!sameEntry(entry, final)) mealFail(FailureReason.CONFLICT)
        // This changed acknowledged record precedes network admission. If cancellation or a
        // crash follows, future attempts cannot infer noncommit from a current-policy Problem.
        entry = write(final, final.data.copy(command = pending.admitted(), lastClock = now()), token)
        pending = entry.data.command!!
        check(token)
        if (inputPreferencesPending(token)) return preferenceBlocked(entry.data)
        if (!online()) return publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.REQUEST_UNRESOLVED)
        if (now() < pending.created || now() - pending.created >= policy.replayWindowMillis)
            return publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.REPLAY_EXPIRED)
        publish(entry.data, MealFlowPhase.LOADING)
        // Durable intent already exists. An exception/cancellation from this point is not rollback.
        val outcome = try { access.transport.execute(access.lease, pending.call()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
        check(token)
        if (inputPreferencesPending(token)) return preferenceBlocked(entry.data)
        if (outcome is PortResult.Failure) return publish(entry.data, MealFlowPhase.RESOLVING,
            issue = MealFlowIssue.REQUEST_UNRESOLVED, failure = outcome.reason)
        val reply = (outcome as PortResult.Value).value
        if (!bound(pending.operation, reply)) return publish(entry.data, MealFlowPhase.RESOLVING,
            issue = MealFlowIssue.INVALID_REPLY, failure = FailureReason.OUTCOME_UNKNOWN)
        if (reply.status != if (pending.operation == "createPlan") 201 else 200) {
            if (reply.status == 429 || reply.status >= 500) {
                val delay = maxOf(reply.retryAfterSeconds ?: 0, problemDelay(reply))
                val retried = write(entry, entry.data.copy(command = pending.withRetryAt(add(now(), delay.coerceAtMost(518_400) * 1000)), lastClock = now()), token)
                return publish(retried.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.RETRY_LATER, failure = httpFailure(reply.status))
            }
            val problem = WireDocument.decode(reply.body!!.copyForCodec()).json().jsonObject
            if (problem["code"]?.jsonPrimitive?.content == "RECIPE_RECALLED" && pending.parentId != null) {
                entry.data.plans.firstOrNull { PlanWire.from(it.body).id.value == pending.parentId }?.let { original ->
                    (PlanWire.from(original.body).recipeVersionId as? WireField.Value)?.value?.value?.lowercase()?.let(recalled::add)
                }
            }
            val definitiveFirstInputFailure = pending.dispatches == 1L && reply.status in setOf(400, 422)
            val rejected = write(entry, entry.data.copy(command = if (definitiveFirstInputFailure) null else pending, lastClock = now(),
                recalledVersions = (entry.data.recalledVersions + recalled).distinct()), token)
            return publish(rejected.data, if (reply.status in setOf(401, 403, 404, 410)) MealFlowPhase.UNAVAILABLE
                else if (definitiveFirstInputFailure) MealFlowPhase.ERROR else MealFlowPhase.RESOLVING,
                issue = if (definitiveFirstInputFailure) MealFlowIssue.NONE else MealFlowIssue.REQUEST_UNRESOLVED, failure = httpFailure(reply.status))
        }
        val body = WireDocument.decode(reply.body!!.copyForCodec(), WireLimits(maxBytes = MealFlowCodec.MAX_PLAN_BYTES))
        val request = mealValue(builder.build(builder.decodeManual(pending.manual, false), pending.preferences))
        val plan = validatePlan(body, reply.etag, request, pending)
        if (plan.status == "recalled") (plan.recipeVersionId as? WireField.Value)?.value?.value?.lowercase()?.let(recalled::add)
        if (entry.data.plans.any { PlanWire.from(it.body).id == plan.id })
            return publish(entry.data, MealFlowPhase.RESOLVING, issue = MealFlowIssue.INVALID_REPLY, failure = FailureReason.CONFLICT)
        val plans = (entry.data.plans + FlowPlan(body, reply.etag, now(), request)).takeLast(policy.historyLimit)
        val completed = write(entry, entry.data.copy(command = null, plans = plans, selected = plans.lastIndex,
            updated = now(), lastClock = now(), recalledVersions = (entry.data.recalledVersions + recalled).distinct()), token)
        if (inputPreferencesPending(token)) return preferenceBlocked(completed.data)
        val current = matchesDraft(completed.data, request)
        return publish(completed.data, if (current) phase(completed.data) else MealFlowPhase.EDITING,
            screen = if (current) MealFlowScreen.RECOMMENDATIONS else MealFlowScreen.REQUEST, historical = !current)
    }

    private suspend fun inputPreferencesPending(token: Any): Boolean {
        check(token)
        val pending = KitchenInputPreferenceGuard.pending(access)
        check(token)
        return pending
    }
    private fun preferenceBlocked(data: FlowRecord) = publish(data,
        if (data.command != null) MealFlowPhase.RESOLVING else MealFlowPhase.NEEDS_CONFIRMATION,
        screen = MealFlowScreen.REQUEST, historical = true, issue = MealFlowIssue.PREFERENCES_PENDING)

    private fun validatePlan(body: WireDocument, etag: String?, request: WireDocument, pending: FlowCommand?): PlanWire {
        builder.schema("Plan", body)
        val p = PlanWire.from(body)
        checkEtag(body, etag)
        if (!sameConstraints(p.constraints.json().jsonObject, request.json().jsonObject.getValue("constraints").jsonObject)) mealFail(FailureReason.INVALID_DATA)
        val source = p.sourcePostId
        if (source !is WireField.Missing) mealFail(FailureReason.INVALID_DATA)
        val parent = (p.parentPlanId as? WireField.Value)?.value?.value?.lowercase()
        if (pending != null && parent != pending.parentId?.lowercase()) mealFail(FailureReason.INVALID_DATA)
        val requestedMode = request.json().jsonObject.getValue("mode").jsonPrimitive.content
        val returnedMode = body.json().jsonObject["mode"]?.jsonPrimitive?.content
        if (returnedMode != null && requestedMode != "auto" && returnedMode != requestedMode) mealFail(FailureReason.INVALID_DATA)
        if (p.status == "ready") {
            val recipe = (p.recipeSnapshot as? WireField.Value)?.value ?: mealFail(FailureReason.INVALID_DATA)
            val version = (p.recipeVersionId as? WireField.Value)?.value ?: mealFail(FailureReason.INVALID_DATA)
            if (recipe.id.value.lowercase() != version.value.lowercase() || recipe.reviewStatus != "published" || recipe.reviewedAt !is WireField.Value) mealFail(FailureReason.INVALID_DATA)
            if (numeric(recipe.document.json().jsonObject.getValue("servings")) != numeric(p.constraints.json().jsonObject.getValue("servings"))) mealFail(FailureReason.INVALID_DATA)
            if (pending?.operation == "nextPlan" && pending.body.json().jsonObject.getValue("excludeRecipeVersionIds").jsonArray
                    .any { it.jsonPrimitive.content.lowercase() == version.value.lowercase() }) mealFail(FailureReason.INVALID_DATA)
        } else if (p.status != "recalled" && (p.recipeSnapshot !is WireField.Missing || p.recipeVersionId !is WireField.Missing)) mealFail(FailureReason.INVALID_DATA)
        return p
    }

    private suspend fun preferences(token: Any, prior: WireDocument?): WireDocument {
        val current = fetch("getPreferences", ApiCall("getPreferences"), token)
        if (prior != null) {
            val compared = compareIntegers(integer(current.json().jsonObject.getValue("version")), integer(prior.json().jsonObject.getValue("version")))
            if (compared < 0 || (compared == 0 && !samePreferences(current, prior))) mealFail(FailureReason.CONFLICT)
        }
        return current
    }
    private suspend fun fetch(operation: String, call: ApiCall, token: Any): WireDocument {
        check(token)
        if (!online()) mealFail(FailureReason.OFFLINE)
        if (!requests.accepts(call, principal())) mealFail(FailureReason.INVALID_DATA)
        val result = access.transport.execute(access.lease, call)
        check(token)
        val reply = mealValue(result)
        if (!bound(operation, reply)) mealFail(FailureReason.INVALID_DATA)
        if (reply.status != 200) mealFail(httpFailure(reply.status))
        val body = WireDocument.decode(reply.body!!.copyForCodec(), WireLimits(maxBytes = 131_072))
        if (operation == "getPreferences") checkEtag(body, reply.etag)
        return body
    }
    private fun bound(operation: String, reply: ApiReply) =
        binder.bind(operation, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) is ResponseBindingResult.Accepted

    private suspend fun read(token: Any): FlowEntry {
        check(token); claim()
        val result = access.store.read(access.lease.scope, key)
        check(token)
        val record = mealValue(result)
        if (record != null && record.schemaVersion != 1) mealFail(FailureReason.INVALID_DATA)
        val data = record?.let { codec.decode(it.payload) } ?: FlowRecord(now(), now())
        if (data.plans.size > policy.historyLimit || data.usedIds.size > policy.issuedIdCapacity || now() < data.lastClock ||
            data.updated > data.lastClock || data.usedIds.any { it.created > data.lastClock }) mealFail(FailureReason.CONFLICT)
        data.plans.forEach { validatePlan(it.body, it.etag, it.request, null) }
        data.command?.let { if (!requests.accepts(it.call(), principal())) mealFail(FailureReason.INVALID_DATA) }
        last = data
        readinessAttempt?.let { draftReadiness.observed(it, record) }
        recalled += data.recalledVersions
        return FlowEntry(record, data)
    }
    private suspend fun write(before: FlowEntry, value: FlowRecord, token: Any): FlowEntry {
        check(token)
        val data = bounded(value)
        data.command?.let { pending ->
            val request = mealValue(builder.build(builder.decodeManual(pending.manual, false), pending.preferences))
            if (!codec.replyFits(data, request)) mealFail(FailureReason.INVALID_DATA)
        }
        val bytes = codec.encode(data)
        readinessAttempt?.let(draftReadiness::beforeWrite)
        val result = access.store.commit(access.lease.scope, listOf(StoreMutation.Put(key, before.record?.revision, 1, bytes)))
        check(token)
        val receipt = mealValue(result)
        val revision = receipt[key]
        if (receipt.keys != setOf(key) || revision == null || revision <= (before.record?.revision ?: 0)) mealFail(FailureReason.STORAGE_FAILURE)
        val observed = access.store.read(access.lease.scope, key)
        check(token)
        val record = mealValue(observed) ?: mealFail(FailureReason.STORAGE_FAILURE)
        if (record.revision != revision || record.schemaVersion != 1 || !record.payload.copyForCodec().contentEquals(bytes.copyForCodec())) mealFail(FailureReason.CONFLICT)
        last = data
        readinessAttempt?.let { draftReadiness.observed(it, record) }
        return FlowEntry(record, data)
    }
    private fun sameEntry(a: FlowEntry, b: FlowEntry) = a.record?.revision == b.record?.revision &&
        (a.record?.payload?.copyForCodec()?.contentEquals(b.record?.payload?.copyForCodec() ?: byteArrayOf()) == true)
    private suspend fun newId(data: FlowRecord, token: Any): String {
        val retained = retainedIds(data)
        if (retained.size >= policy.issuedIdCapacity) mealFail(FailureReason.RATE_LIMITED)
        check(token); val id = ids.next(); check(token)
        return uuid(id).also { if (retained.any { prior -> prior.id == it }) mealFail(FailureReason.CONFLICT) }
    }
    private fun principal() = if (access.lease.scope.actorKind == ActorKind.GUEST) PrincipalClass.GUEST else PrincipalClass.ACCOUNT
    private fun online() = access.onlineAllowed && connectivity.current() == Connectivity.ONLINE
    private fun now() = clock.nowMillis().also { if (it < 0) mealFail(FailureReason.INVALID_DATA) }
    private suspend fun check(token: Any) {
        currentCoroutineContext().ensureActive()
        if (closed || token !== generation || !boundary.isCurrent(access.lease)) mealFail(FailureReason.STALE_SESSION)
    }
    private fun invalidate() {
        generation = Any(); last = null; contextFresh = false; recalled.clear()
        if (claimed) { owners.removeAll { it.controller === this }; claimed = false }
        mutable.value = MealRequestState.unavailable()
    }

    /** Evict only resolved, nonselected history, then the optional pantry cache. The current
     * snapshot, pending exact command and replay/recall fences are never trimmed to fit. */
    private fun bounded(value: FlowRecord): FlowRecord {
        var result = value.copy(usedIds = retainedIds(value))
        while (result.plans.size > policy.historyLimit || !codec.fits(result)) {
            val evict = result.plans.indices.firstOrNull { it != result.selected }
            if (evict != null) {
                result = result.copy(plans = result.plans.filterIndexed { index, _ -> index != evict },
                    selected = result.selected - if (evict < result.selected) 1 else 0)
            } else if (result.pantry != null) result = result.copy(pantry = null)
            else mealFail(FailureReason.INVALID_DATA)
        }
        return result
    }
    private fun retainedIds(data: FlowRecord) = data.usedIds.filter {
        it.id == data.command?.id || now() < it.created || now() - it.created < ISSUED_ID_RETENTION
    }
    private fun ancestry(data: FlowRecord, current: FlowPlan): List<FlowPlan> {
        val result = mutableListOf<FlowPlan>(); val visited = mutableSetOf<String>()
        var next: FlowPlan? = current
        while (next != null) {
            val wire = PlanWire.from(next.body)
            if (!visited.add(wire.id.value.lowercase())) mealFail(FailureReason.INVALID_DATA)
            result += next
            val parent = (wire.parentPlanId as? WireField.Value)?.value?.value?.lowercase()
            next = data.plans.firstOrNull { PlanWire.from(it.body).id.value.lowercase() == parent }
        }
        return result
    }
    private fun claim() {
        if (!claimed) {
            if (owners.any { it.lease === access.lease && it.origin == access.origin }) mealFail(FailureReason.CONFLICT)
            owners += Owner(access.lease, access.origin, this); claimed = true
        }
    }

    private suspend fun run(fence: Boolean = false, command: Boolean = false, cached: Boolean = false, acknowledgeDraft: Boolean = false,
        action: suspend (Any) -> MealRequestState): PortResult<MealRequestState> {
        var token: Any? = null
        var ownedBusy: Any? = null
        var readiness: MealDraftReadiness.Attempt? = null
        try {
            val outcome = withContext(dispatcher) {
                if (closed || !boundary.isCurrent(access.lease)) return@withContext PortResult.Failure(FailureReason.STALE_SESSION)
                if (command && busy != null) return@withContext PortResult.Failure(FailureReason.CONFLICT)
                if (fence) generation = Any()
                val current = generation; token = current
                if (command) ownedBusy = Any().also { busy = it }
                try {
                    if (cached && last != null) { check(current); val result = action(current); check(current); PortResult.Value(result) }
                    else mutex.withLock {
                        check(current)
                        val attempt = draftReadiness.begin(acknowledgeDraft)
                        readiness = attempt; readinessAttempt = attempt
                        try { val result = action(current); check(current); PortResult.Value(result) }
                        finally { if (readinessAttempt === attempt) readinessAttempt = null }
                    }
                }
                finally { if (ownedBusy != null && busy === ownedBusy) busy = null }
            }
            currentCoroutineContext().ensureActive()
            withContext(dispatcher) {
                token?.let { check(it) }
                if (outcome is PortResult.Value) readiness?.let(draftReadiness::succeeded)
            }
            currentCoroutineContext().ensureActive()
            if (closed || !boundary.isCurrent(access.lease) || (token != null && token !== generation)) mealFail(FailureReason.STALE_SESSION)
            return outcome
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) {
                readiness?.let(draftReadiness::failed)
                if (!closed && token === generation && boundary.isCurrent(access.lease)) {
                    generation = Any()
                    last?.let { publish(it, if (it.command != null) MealFlowPhase.RESOLVING else phase(it), issue = MealFlowIssue.REQUEST_UNRESOLVED) }
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            val reason = (failure as? MealFailure)?.reason ?: FailureReason.STORAGE_FAILURE
            withContext(NonCancellable + dispatcher) {
                readiness?.let(draftReadiness::failed)
                if (!closed && token === generation && boundary.isCurrent(access.lease)) last?.let {
                    publish(it, if (it.command != null) MealFlowPhase.RESOLVING else MealFlowPhase.ERROR,
                        issue = when (reason) { FailureReason.INVALID_DATA -> MealFlowIssue.INVALID_REPLY
                            FailureReason.RATE_LIMITED -> MealFlowIssue.RETRY_LATER; else -> MealFlowIssue.STORAGE }, failure = reason)
                }
            }
            return PortResult.Failure(reason)
        }
    }

    private fun phase(data: FlowRecord): MealFlowPhase = if (data.command != null) MealFlowPhase.RESOLVING else
        when (data.plans.getOrNull(data.selected)?.let { PlanWire.from(it.body).status }) {
            "ready" -> MealFlowPhase.READY; "needsConfirmation" -> MealFlowPhase.NEEDS_CONFIRMATION
            "noMatch" -> MealFlowPhase.NO_MATCH; "recalled" -> MealFlowPhase.UNAVAILABLE
            else -> MealFlowPhase.EDITING
        }
    private fun matchesDraft(data: FlowRecord, request: WireDocument): Boolean {
        val draft = data.draft ?: return false; val pref = data.preferences ?: return false
        val result = builder.build(draft, pref)
        return result is PortResult.Value && equal(result.value, request)
    }
    private fun publish(data: FlowRecord, phase: MealFlowPhase, screen: MealFlowScreen = mutable.value.screen,
        historical: Boolean = !contextFresh, issue: MealFlowIssue = MealFlowIssue.NONE, failure: FailureReason? = null): MealRequestState {
        if (closed || !boundary.isCurrent(access.lease)) return MealRequestState.unavailable().also { mutable.value = it }
        val selected = data.plans.getOrNull(data.selected)
        val permitted = selected?.takeIf { !recalled(it, data) }
        val current = permitted?.let { matchesDraft(data, it.request) } == true
        val snapshots = data.plans.filterNot { recalled(it, data) }.map { it.snapshot(true) }
        val cursor = permitted?.let { (PlanWire.from(it.body).nextAlternativeCursor as? WireField.Value)?.value }
        val pendingMatches = data.command?.let { c -> data.draft?.let { equal(builder.manual(it), c.manual) } } ?: false
        val visiblePhase = if (selected != null && permitted == null && data.command == null) MealFlowPhase.UNAVAILABLE else phase
        return MealRequestState(visiblePhase, screen, data.draft, data.preferences, data.pantry, permitted?.snapshot(historical || !current),
            snapshots, issue, failure, visiblePhase == MealFlowPhase.READY && current && data.command == null && !cursor.isNullOrBlank(), pendingMatches,
            data.command?.retryAt?.takeIf { it > now() } ?: if (failure == FailureReason.RATE_LIMITED)
                retainedIds(data).minOfOrNull { add(it.created, ISSUED_ID_RETENTION) } else null).also { mutable.value = it }
    }
    private data class Owner(val lease: SessionLease, val origin: String, val controller: MealRequestController)
    private fun recalled(plan: FlowPlan, data: FlowRecord): Boolean {
        val wire = PlanWire.from(plan.body)
        val version = (wire.recipeVersionId as? WireField.Value)?.value?.value?.lowercase()
        return wire.status == "recalled" || version in recalled || version in data.recalledVersions
    }
    private companion object {
        val owners = mutableListOf<Owner>()
        const val ISSUED_ID_RETENTION = 604_800_000L
    }
}

private fun checkEtag(body: WireDocument, etag: String?) {
    if (etag == null) return
    if (etag.length !in 3..4098 || etag.first() != '"' || etag.last() != '"') mealFail(FailureReason.INVALID_DATA)
    val raw = etag.substring(1, etag.lastIndex)
    if (raw.any { it !in '0'..'9' } || raw.trimStart('0') != integer(body.json().jsonObject.getValue("version"))) mealFail(FailureReason.INVALID_DATA)
}
private fun samePreferences(a: WireDocument, b: WireDocument) = a.json() == b.json()
private fun compareIntegers(a: String, b: String) = if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
private fun numeric(value: JsonElement): Pair<String, Int> {
    val p = value.jsonPrimitive
    if (p.isString || p.content.length > 4096) mealFail(FailureReason.INVALID_DATA)
    val parts = p.content.lowercase().split('e'); val mantissa = parts[0]
    if (mantissa.startsWith('-')) mealFail(FailureReason.INVALID_DATA)
    var digits = mantissa.replace(".", "").trimStart('0')
    val exponent = parts.getOrNull(1)?.toLongOrNull() ?: if (parts.size == 1) 0 else mealFail(FailureReason.INVALID_DATA)
    val powerLong = exponent - mantissa.substringAfter('.', "").length
    if (powerLong !in -4096L..4096L) mealFail(FailureReason.INVALID_DATA)
    var power = powerLong.toInt()
    if (digits.isEmpty()) return "0" to 0
    while (digits.endsWith('0')) { digits = digits.dropLast(1); if (power == Int.MAX_VALUE) mealFail(FailureReason.INVALID_DATA); power++ }
    return digits to power
}
private fun integer(value: JsonElement): String {
    val (digits, power) = numeric(value)
    if (power < 0 || power > 4096 || digits.length + power > 4096 || digits == "0") mealFail(FailureReason.INVALID_DATA)
    return digits + "0".repeat(power)
}
private fun sameConstraints(a: JsonObject, b: JsonObject): Boolean = a.keys == b.keys && a.all { (key, left) ->
    val right = b.getValue(key)
    if (left is JsonArray && right is JsonArray) {
        fun values(array: JsonArray) = array.map { if (key in setOf("ingredientIds", "hardExcludedIngredientIds")) it.jsonPrimitive.content.lowercase() else it.jsonPrimitive.content }.sorted()
        values(left) == values(right)
    }
    else if (left is JsonPrimitive && right is JsonPrimitive && !left.isString && !right.isString) numeric(left) == numeric(right)
    else left == right
}
private fun httpFailure(status: Int) = when (status) {
    401 -> FailureReason.UNAUTHENTICATED; 403 -> FailureReason.FORBIDDEN; 404, 410 -> FailureReason.NOT_FOUND
    409, 412 -> FailureReason.CONFLICT; 429 -> FailureReason.RATE_LIMITED; 500, 503 -> FailureReason.UNAVAILABLE
    else -> FailureReason.INVALID_DATA
}
private fun problemDelay(reply: ApiReply): Long {
    val body = reply.body?.let { WireDocument.decode(it.copyForCodec()).json().jsonObject } ?: return 0
    val value = body["retryAfterSeconds"] ?: return 0
    if (numeric(value).first == "0") return 0
    val digits = integer(value)
    return if (digits.length > 6) 518_400 else digits.toLong().coerceAtMost(518_400)
}
private fun add(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
