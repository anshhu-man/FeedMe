package com.feedme.development.progress

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.planning.*
import com.feedme.transport.MobileRequestValidator
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Bounded synthetic server, never a second client command queue or a production auth adapter.
 * One encrypted CAS atomically retains synthetic state and original command results. Exact key
 * reuse re-acknowledges the stored receipt; a lost acknowledgement stays OUTCOME_UNKNOWN.
 * No eviction/recreation of uncertain commands. At the development capacity limit require an
 * explicitly confirmed preview reset; this is not a product save quota or retention policy.
 */
internal class ProgressServiceLedger(
    private val store: PrivateStateStore,
    private val scope: StorageScope,
    private val origin: String,
    private val clock: EpochClock,
    private val requireCurrent: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val validator = CanonicalBodyValidator.bundled()
    private val requests = MobileRequestValidator()
    private val planner = DeterministicPlanner(PlanningPolicy(ProgressCatalog.policyVersion, false, false), validator)
    private val adapter = CanonicalPlanningAdapter(validator)
    private var opened = false
    private var closed = false

    suspend fun open(allowInitialize: Boolean): PortResult<Unit> = guarded {
        if (opened || closed) previewFail(FailureReason.CONFLICT)
        if (scope != ProgressIdentity.scope || !origin.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            previewFail(FailureReason.UNAUTHENTICATED)
        val existing = previewValue(store.read(scope, KEY))
        requireCurrent()
        if (existing == null) {
            if (!allowInitialize) previewFail(FailureReason.NOT_FOUND)
            write(null, fresh())
        } else decode(existing)
        requireCurrent(); opened = true
    }
    suspend fun close(): PortResult<Unit> = mutex.withLock { closed = true; opened = false; PortResult.Value(Unit) }

    /** Development-only service action, never a client recall assertion or a live moderation API.
     * Monotone, scoped and acknowledged by the same CAS as service commands. Retrying after a lost
     * ACK requires another changed CAS; a matching read alone cannot report success. */
    suspend fun withdrawSyntheticRecipe(recipeVersionId: String): PortResult<Unit> = guarded {
        if (!opened || closed) previewFail(FailureReason.NOT_CONFIGURED)
        if (recipeVersionId != ProgressCatalog.recipeVersionId) previewFail(FailureReason.INVALID_DATA)
        val record = previewValue(store.read(scope, KEY)) ?: previewFail(FailureReason.STORAGE_FAILURE)
        requireCurrent()
        val state = decode(record)
        val now = clock.nowMillis()
        if (now < state.long("lastMillis") || now < 0) previewFail(FailureReason.CONFLICT)
        val withdrawn = state.obj("withdrawnRecipeVersions")
        val firstWithdrawal = withdrawn[recipeVersionId] ?: JsonPrimitive(Instant.ofEpochMilli(now).toString())
        write(record.revision, JsonObject(state + mapOf(
            "withdrawnRecipeVersions" to JsonObject(withdrawn + (recipeVersionId to firstWithdrawal)),
            "lastMillis" to JsonPrimitive(now))))
    }

    suspend fun execute(call: ApiCall): PortResult<ApiReply> = guarded {
        if (!opened || closed) previewFail(FailureReason.NOT_CONFIGURED)
        if (!requests.accepts(call, PrincipalClass.ACCOUNT) || (call.body?.copyForCodec()?.size ?: 0) > 65_536)
            previewFail(FailureReason.INVALID_DATA)
        if (call.operationId !in OPERATIONS) previewFail(FailureReason.NOT_CONFIGURED)
        val record = previewValue(store.read(scope, KEY)) ?: previewFail(FailureReason.STORAGE_FAILURE)
        requireCurrent()
        val state = decode(record)
        val now = clock.nowMillis()
        if (now < state.long("lastMillis") || now < 0) previewFail(FailureReason.CONFLICT)
        val key = call.idempotencyKey?.use(::hash)
        val fingerprint = fingerprint(call)
        val receipts = state.obj("receipts")
        if (key != null) {
            receipts[key]?.jsonObject?.let { saved ->
                if (saved.string("fingerprint") != fingerprint || saved.string("operation") != call.operationId)
                    return@guarded problem(FailureReason.CONFLICT)
                val witness = saved["witness"]?.takeUnless { it == JsonNull }?.jsonObject
                if (witness != null && resourceHash(state, witness.string("kind"), witness.string("id")) != witness.string("hash"))
                    return@guarded problem(FailureReason.CONFLICT)
                val response = decodeReply(saved.obj("reply"))
                // Withdrawal is separate from original command/resource identity. Keep the
                // stored receipt intact, but never replay now-withdrawn positive content.
                if (response.status in 200..299 && withdrawnResponse(state, call.operationId, response))
                    return@guarded recalledProblem().also { validateReply(call.operationId, it) }
                // A new changed CAS acknowledgement, not mere matching readback, resolves replay.
                write(record.revision, JsonObject(state + ("lastMillis" to JsonPrimitive(now))))
                return@guarded response
            }
            if (receipts.size >= MAX_COMMANDS) previewFail(FailureReason.RATE_LIMITED)
        }
        val outcome = try { apply(state, call, Instant.ofEpochMilli(now).toString()) }
        catch (_: PreviewVersionConflict) { Outcome(state, problem(FailureReason.CONFLICT, versionConflict = true)) }
        catch (_: PreviewRecipeRecalled) { Outcome(state, recalledProblem()) }
        catch (failure: PreviewServiceFailure) {
            if (failure.reason !in setOf(FailureReason.CONFLICT, FailureReason.NOT_FOUND, FailureReason.INVALID_DATA,
                    FailureReason.NOT_CONFIGURED, FailureReason.RATE_LIMITED)) throw failure
            Outcome(state, problem(failure.reason))
        }
        validateReply(call.operationId, outcome.reply)
        if (key == null) return@guarded outcome.reply
        val saved = buildJsonObject {
            put("fingerprint", fingerprint); put("operation", call.operationId); put("reply", encodeReply(outcome.reply))
            put("witness", witness(outcome.state, call, outcome.reply))
        }
        val updated = JsonObject(outcome.state + mapOf("receipts" to JsonObject(receipts + (key to saved)), "lastMillis" to JsonPrimitive(now)))
        write(record.revision, updated)
        outcome.reply
    }

    private class Outcome(val state: JsonObject, val reply: ApiReply)
    private fun apply(state: JsonObject, call: ApiCall, now: String): Outcome {
        var next = state
        val body = call.body?.let { WireDocument.decode(it.copyForCodec()).json() }
        val preference = state.obj("preferences")
        val pantry = state.obj("pantry")
        val plans = state.obj("plans")
        val sessions = state.obj("sessions")
        fun result(document: JsonObject, status: Int = 200, etag: Boolean = false) = reply(document, status, if (etag) version(document) else null)
        fun page(items: List<JsonElement>): ApiReply {
            // This configured catalog currently has one ingredient/pantry entry. Unknown cursors
            // must not silently restart pagination or imply a complete larger collection.
            if ("cursor" in call.queryParameters) previewFail(FailureReason.INVALID_DATA)
            val limit = call.queryParameters["limit"]?.single()?.toInt() ?: 20
            if (items.size > limit) previewFail(FailureReason.NOT_CONFIGURED)
            return reply(buildJsonObject { put("items", JsonArray(items)); put("nextCursor", JsonNull); put("serverTime", now) })
        }
        val response = when (call.operationId) {
            "saveRecipe", "getSavedRecipe", "listSavedRecipes", "deleteSavedRecipe" -> {
                val cookbook = ProgressCookbookLedger.apply(state, call, now)
                next = cookbook.first; cookbook.second
            }
            "getPreferences" -> result(preference, etag = true)
            "listPantry" -> page(pantry.values.toList())
            "searchIngredients" -> {
                val query = call.queryParameters["q"]?.single().orEmpty().lowercase()
                val match = query.isEmpty() || listOf("synthetic cucumber", "preview ingredient").any { query in it }
                page(if (match) listOf(ProgressCatalog.ingredient) else emptyList())
            }
            "updatePreferences" -> {
                checkVersion(call, preference)
                val patch = checkNotNull(body)
                for (field in listOf("hardExcludedIngredientIds", "dislikedIngredientIds"))
                    if (patch[field]?.jsonArray?.any { it.jsonPrimitive.content != ProgressCatalog.ingredientId } == true)
                        previewFail(FailureReason.NOT_CONFIGURED)
                if (patch["equipmentIds"]?.jsonArray?.any { it.jsonPrimitive.content != "bowl" } == true ||
                    patch["dietaryPatterns"]?.jsonArray?.isNotEmpty() == true) previewFail(FailureReason.NOT_CONFIGURED)
                val changed = increment(JsonObject(preference + patch), now)
                next = JsonObject(state + ("preferences" to changed)); result(changed, etag = true)
            }
            "upsertPantryItem" -> {
                val write = checkNotNull(body)
                val id = write.string("ingredientId")
                if (id != ProgressCatalog.ingredientId || write["unit"]?.jsonPrimitive?.content?.let { it != "g" } == true)
                    previewFail(FailureReason.NOT_CONFIGURED)
                val previous = pantry[id]?.jsonObject
                if (write["expectedVersion"] != previous?.get("version")) previewFail(FailureReason.CONFLICT)
                val generations = state.obj("pantryGenerations")
                val generation = (generations[id]?.jsonPrimitive?.long ?: 0L) + 1L
                if (generation <= 0) previewFail(FailureReason.STORAGE_FAILURE)
                val changed = JsonObject((write - "expectedVersion") + mapOf("id" to (previous?.get("id") ?: JsonPrimitive(UUID.randomUUID().toString())),
                    "version" to JsonPrimitive(generation), "createdAt" to (previous?.get("createdAt") ?: JsonPrimitive(now)),
                    "updatedAt" to JsonPrimitive(now), "confirmedAt" to (write["confirmedAt"] ?: previous?.get("confirmedAt") ?: JsonNull),
                    "staple" to (write["staple"] ?: previous?.get("staple") ?: JsonPrimitive(false))))
                next = JsonObject(state + mapOf("pantry" to JsonObject(pantry + (id to changed)),
                    "pantryGenerations" to JsonObject(generations + (id to JsonPrimitive(generation)))))
                result(changed, etag = true)
            }
            "removePantryItem" -> {
                val id = call.pathParameters.getValue("ingredientId")
                val previous = pantry[id]?.jsonObject ?: previewFail(FailureReason.NOT_FOUND)
                checkVersion(call, previous)
                next = JsonObject(state + ("pantry" to JsonObject(pantry - id))); ApiReply(204, null)
            }
            "createPlan" -> {
                if (plans.size >= MAX_PLANS) previewFail(FailureReason.RATE_LIMITED)
                val context = PlanningContext(PlanningPreferences(preference.getValue("version").jsonPrimitive.content,
                    preference.arrayStrings("hardExcludedIngredientIds"), preference.arrayStrings("dislikedIngredientIds")),
                    pantry.values.map { item -> val p = item.jsonObject
                        ReportedIngredient(p.string("ingredientId"), when {
                            p.string("presence") == "out" -> PlanningAvailability.UNAVAILABLE
                            p.string("presence") in setOf("available", "low") &&
                                p["confirmationStatus"]?.jsonPrimitive?.content == "confirmed" && freshConfirmation(p, now) -> PlanningAvailability.CONFIRMED
                            else -> PlanningAvailability.UNCERTAIN
                        }) })
                val decision = previewValue(planner.plan(WireDocument.parse(checkNotNull(body).toString()), context, ProgressCatalog.planning)).decision
                val id = UUID.randomUUID().toString()
                val plan = previewValue(adapter.materialize(decision, PlanningReceipt(id, "1", now, now))).document.json()
                plan["recipeSnapshot"]?.jsonObject?.let { ProgressCookbookLedger.requireAvailable(state, it) }
                next = JsonObject(state + ("plans" to JsonObject(plans + (id to plan))))
                result(plan, 201, true)
            }
            "getPlan" -> {
                val plan = plans[call.pathParameters.getValue("planId")]?.jsonObject ?: previewFail(FailureReason.NOT_FOUND)
                plan["recipeSnapshot"]?.jsonObject?.let { ProgressCookbookLedger.requireAvailable(state, it) }
                result(plan, etag = true)
            }
            "createCookSession" -> {
                if (sessions.size >= MAX_PLANS) previewFail(FailureReason.RATE_LIMITED)
                val request = checkNotNull(body)
                if (request["deviceSequence"]?.jsonPrimitive?.long?.let { it != 0L } == true) previewFail(FailureReason.CONFLICT)
                val planId = request.string("planId")
                val plan = plans[planId]?.jsonObject ?: previewFail(FailureReason.NOT_FOUND)
                if (plan.string("status") != "ready") previewFail(FailureReason.CONFLICT)
                ProgressCookbookLedger.requireAvailable(state, plan.obj("recipeSnapshot"))
                val id = UUID.randomUUID().toString()
                val first = plan.obj("recipeSnapshot").getValue("steps").jsonArray.first().jsonObject.string("stepId")
                val created = buildJsonObject {
                    put("id", id); put("version", 1); put("createdAt", now); put("updatedAt", now); put("planId", planId)
                    put("status", "active"); put("currentStepId", first); put("completedStepIds", JsonArray(emptyList()))
                    put("timers", JsonArray(emptyList())); put("deviceSequence", 0)
                }
                next = JsonObject(state + ("sessions" to JsonObject(sessions + (id to created))))
                result(created, 201, true)
            }
            "getCookSession" -> {
                val session = sessions[call.pathParameters.getValue("sessionId")]?.jsonObject ?: previewFail(FailureReason.NOT_FOUND)
                ProgressCookbookLedger.requireAvailable(state, plans.getValue(session.string("planId")).jsonObject.obj("recipeSnapshot"))
                result(session, etag = true)
            }
            "updateCookSession", "completeCookSession" -> {
                val id = call.pathParameters.getValue("sessionId")
                val current = sessions[id]?.jsonObject ?: previewFail(FailureReason.NOT_FOUND)
                ProgressCookbookLedger.requireAvailable(state, plans.getValue(current.string("planId")).jsonObject.obj("recipeSnapshot"))
                val change = checkNotNull(body)
                if (current.string("status") in setOf("completed", "abandoned") || change.long("deviceSequence") != current.long("deviceSequence") + 1)
                    previewFail(FailureReason.CONFLICT)
                val completing = call.operationId == "completeCookSession"
                if (!completing) checkVersion(call, current)
                if (completing && change["makeAgain"]?.jsonPrimitive?.boolean != false) previewFail(FailureReason.NOT_CONFIGURED)
                if (change["personalNotes"]?.jsonArray?.isNotEmpty() == true)
                    previewFail(FailureReason.NOT_CONFIGURED)
                val recipe = plans.getValue(current.string("planId")).jsonObject.obj("recipeSnapshot")
                val stepIds = recipe.getValue("steps").jsonArray.map { it.jsonObject.string("stepId") }.toSet()
                if (change["currentStepId"]?.jsonPrimitive?.content?.let { it !in stepIds } == true) previewFail(FailureReason.INVALID_DATA)
                change["completedStepIds"]?.jsonArray?.let { values ->
                    if (values.map { it.jsonPrimitive.content }.distinct().size != values.size || values.any { it.jsonPrimitive.content !in stepIds })
                        previewFail(FailureReason.INVALID_DATA)
                }
                change["timers"]?.jsonArray?.let { timers ->
                    // Canonical retained progress only. This synthetic service never schedules
                    // an alert, infers readiness or advances a cooking step when a timer is due.
                    val ids = timers.map { it.jsonObject.string("timerId").lowercase() }
                    if (timers.size > 32 || ids.distinct().size != ids.size) previewFail(FailureReason.INVALID_DATA)
                    for (timer in timers) {
                        val fields = timer.jsonObject
                        if (fields.string("stepId") !in stepIds) previewFail(FailureReason.INVALID_DATA)
                        val seconds = try { fields.getValue("durationSeconds").jsonPrimitive.content.toBigDecimal().longValueExact() }
                            catch (_: Exception) { previewFail(FailureReason.INVALID_DATA) }
                        if (seconds !in 1..86_400) previewFail(FailureReason.INVALID_DATA)
                    }
                }
                val changed = increment(JsonObject(current + (change - "makeAgain" - "finishedAtClient") +
                    if (completing) mapOf("status" to JsonPrimitive("completed"), "completedAt" to JsonPrimitive(now)) else emptyMap()), now)
                next = JsonObject(state + ("sessions" to JsonObject(sessions + (id to changed))))
                result(changed, etag = true)
            }
            else -> previewFail(FailureReason.NOT_CONFIGURED)
        }
        return Outcome(next, response)
    }

    private suspend fun write(expected: Long?, state: JsonObject) {
        val bytes = state.toString().encodeToByteArray()
        if (bytes.size > MAX_BYTES) previewFail(FailureReason.RATE_LIMITED)
        requireCurrent()
        val acknowledgement = try { store.commit(scope, listOf(StoreMutation.Put(KEY, expected, 1, PrivateBytes(bytes)))) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { previewFail(FailureReason.OUTCOME_UNKNOWN) }
        requireCurrent(); currentCoroutineContext().ensureActive()
        val acknowledged = (acknowledgement as? PortResult.Value)?.value
        val revision = acknowledged?.get(KEY)
        if (revision == null || revision <= 0 || acknowledged != mapOf(KEY to revision) ||
            (expected != null && (expected == Long.MAX_VALUE || revision != expected + 1L)))
            previewFail(FailureReason.OUTCOME_UNKNOWN)
        val readback = try { (store.read(scope, KEY) as? PortResult.Value)?.value ?: previewFail(FailureReason.OUTCOME_UNKNOWN) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { previewFail(FailureReason.OUTCOME_UNKNOWN) }
        requireCurrent()
        if (readback.revision != revision || readback.schemaVersion != 1 || !readback.payload.copyForCodec().contentEquals(bytes))
            previewFail(FailureReason.OUTCOME_UNKNOWN)
    }

    private fun fresh(): JsonObject = buildJsonObject {
        put("schema", 1); put("configuration", ProgressIdentity.configurationBinding); put("origin", origin)
        put("environment", scope.environment); put("actorId", scope.actorId); put("actorKind", scope.actorKind.name)
        put("lastMillis", clock.nowMillis().also { if (it < 0) previewFail(FailureReason.INVALID_DATA) })
        put("preferences", Json.parseToJsonElement("""{"id":"00000000-0000-4000-8000-000000000103","version":1,
            "createdAt":"${ProgressCatalog.fixedTime}","updatedAt":"${ProgressCatalog.fixedTime}","hardExcludedIngredientIds":[],
            "dietaryPatterns":[],"dislikedIngredientIds":[],"equipmentIds":["bowl"]}"""))
        for (key in listOf("pantry", "pantryGenerations", "plans", "sessions", "receipts", "savedRecipes", "savedLatest", "withdrawnRecipeVersions")) put(key, JsonObject(emptyMap()))
    }

    private fun decode(record: PrivateRecord): JsonObject {
        if (record.schemaVersion != 1) previewFail(FailureReason.STORAGE_FAILURE)
        val stored = WireDocument.decode(record.payload.copyForCodec(), WireLimits(maxBytes = MAX_BYTES, maxDepth = 32)).json()
        val legacyKeys = setOf("schema", "configuration", "origin", "environment", "actorId", "actorKind", "lastMillis", "preferences",
            "pantry", "pantryGenerations", "plans", "sessions", "receipts")
        val cookbookKeys = legacyKeys + setOf("savedRecipes", "savedLatest")
        if (stored.keys != legacyKeys && stored.keys != cookbookKeys && stored.keys != cookbookKeys + "withdrawnRecipeVersions")
            previewFail(FailureReason.STORAGE_FAILURE)
        // Additive in-memory interpretation of older preview ledgers. Read/open never writes a
        // migration or loses existing receipts. The next acknowledged command persists additions.
        val cookbook = if (stored.keys == legacyKeys) JsonObject(stored + mapOf("savedRecipes" to JsonObject(emptyMap()), "savedLatest" to JsonObject(emptyMap()))) else stored
        val state = if ("withdrawnRecipeVersions" !in cookbook) JsonObject(cookbook + ("withdrawnRecipeVersions" to JsonObject(emptyMap()))) else cookbook
        if (state.long("schema") != 1L ||
            state.string("configuration") != ProgressIdentity.configurationBinding || state.string("origin") != origin ||
            state.string("environment") != scope.environment || state.string("actorId") != scope.actorId || state.string("actorKind") != scope.actorKind.name ||
            scope != ProgressIdentity.scope || state.long("lastMillis") < 0) previewFail(FailureReason.STORAGE_FAILURE)
        for ((id, timestamp) in state.obj("withdrawnRecipeVersions")) {
            if (id != ProgressCatalog.recipeVersionId || !timestamp.jsonPrimitive.isString) previewFail(FailureReason.STORAGE_FAILURE)
            val at = Instant.parse(timestamp.jsonPrimitive.content)
            if (at.toString() != timestamp.jsonPrimitive.content || at.toEpochMilli() !in 0..state.long("lastMillis"))
                previewFail(FailureReason.STORAGE_FAILURE)
        }
        validateReply("getPreferences", reply(state.obj("preferences"), etag = version(state.obj("preferences"))))
        if (state.obj("pantry").size > 1 || state.obj("pantryGenerations").size > 1 || state.obj("plans").size > MAX_PLANS ||
            state.obj("sessions").size > MAX_PLANS || state.obj("receipts").size > MAX_COMMANDS) previewFail(FailureReason.STORAGE_FAILURE)
        for ((id, item) in state.obj("pantry")) {
            if (id != ProgressCatalog.ingredientId || item.jsonObject.string("ingredientId") != id ||
                state.obj("pantryGenerations")[id] != item.jsonObject["version"]) previewFail(FailureReason.STORAGE_FAILURE)
            if (validator.validateSchema("PantryItem", item.toString().encodeToByteArray()) != ContractValidationResult.Valid)
                previewFail(FailureReason.STORAGE_FAILURE)
        }
        for ((id, value) in state.obj("plans")) {
            if (value.jsonObject.string("id") != id) previewFail(FailureReason.STORAGE_FAILURE)
            validateReply("getPlan", reply(value.jsonObject, etag = version(value.jsonObject)))
        }
        for ((id, value) in state.obj("sessions")) {
            if (value.jsonObject.string("id") != id || value.jsonObject.string("planId") !in state.obj("plans")) previewFail(FailureReason.STORAGE_FAILURE)
            validateReply("getCookSession", reply(value.jsonObject, etag = version(value.jsonObject)))
        }
        ProgressCookbookLedger.validate(state, validator)
        for ((key, value) in state.obj("receipts")) {
            val receipt = value.jsonObject
            if (!HASH.matches(key) || receipt.keys != setOf("fingerprint", "operation", "reply", "witness") ||
                !HASH.matches(receipt.string("fingerprint")) || receipt.string("operation") !in COMMANDS) previewFail(FailureReason.STORAGE_FAILURE)
            val response = decodeReply(receipt.obj("reply"))
            validateReply(receipt.string("operation"), response)
            if (response.status in 200..299) {
                val witness = receipt.obj("witness")
                if (witness.keys != setOf("kind", "id", "hash") || !HASH.matches(witness.string("hash")) ||
                    witness.string("kind") !in setOf("preferences", "pantry", "plans", "sessions", "savedRecipes")) previewFail(FailureReason.STORAGE_FAILURE)
            } else if (receipt["witness"] != JsonNull) previewFail(FailureReason.STORAGE_FAILURE)
        }
        return state
    }
    private fun validateReply(operation: String, reply: ApiReply) {
        if ((reply.body?.copyForCodec()?.size ?: 0) > ProgressCatalog.maxResponseBytes ||
            CanonicalResponseBinder(validator).bind(operation, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted)
            previewFail(FailureReason.INVALID_DATA)
    }
    private fun freshConfirmation(pantry: JsonObject, now: String): Boolean {
        val value = pantry["confirmedAt"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: return false
        val age = Instant.parse(now).toEpochMilli() - Instant.parse(value).toEpochMilli()
        return age in 0L..86_400_000L
    }
    private fun witness(state: JsonObject, call: ApiCall, reply: ApiReply): JsonElement {
        if (reply.status !in 200..299) return JsonNull
        val response = reply.body?.let { WireDocument.decode(it.copyForCodec()).json() }
        val (kind, id) = when (call.operationId) {
            "updatePreferences" -> "preferences" to "current"
            "upsertPantryItem" -> "pantry" to checkNotNull(response).string("ingredientId")
            "removePantryItem" -> "pantry" to call.pathParameters.getValue("ingredientId")
            "createPlan" -> "plans" to checkNotNull(response).string("id")
            "createCookSession", "updateCookSession", "completeCookSession" -> "sessions" to checkNotNull(response).string("id")
            "saveRecipe" -> "savedRecipes" to checkNotNull(response).string("id")
            "deleteSavedRecipe" -> "savedRecipes" to call.pathParameters.getValue("savedRecipeId")
            else -> previewFail(FailureReason.INVALID_DATA)
        }
        return buildJsonObject { put("kind", kind); put("id", id); put("hash", resourceHash(state, kind, id)) }
    }
    private fun resourceHash(state: JsonObject, kind: String, id: String): String = hash(when (kind) {
        "preferences" -> state.obj("preferences").toString()
        "pantry" -> buildJsonObject { put("item", state.obj("pantry")[id] ?: JsonNull)
            put("generation", state.obj("pantryGenerations")[id] ?: JsonNull) }.toString()
        "plans", "sessions" -> (state.obj(kind)[id] ?: JsonNull).toString()
        "savedRecipes" -> ProgressCookbookLedger.witness(state, id).toString()
        else -> previewFail(FailureReason.STORAGE_FAILURE)
    })
    private fun withdrawnResponse(state: JsonObject, operation: String, response: ApiReply): Boolean {
        val body = response.body?.let { WireDocument.decode(it.copyForCodec()).json() } ?: return false
        val recipe = when (operation) {
            "saveRecipe" -> body.obj("snapshot")
            "createPlan" -> body["recipeSnapshot"]?.jsonObject
            "createCookSession", "updateCookSession", "completeCookSession" ->
                state.obj("plans").getValue(body.string("planId")).jsonObject.obj("recipeSnapshot")
            else -> null
        }
        return recipe != null && ProgressCookbookLedger.isWithdrawn(state, recipe)
    }
    private fun recalledProblem(): ApiReply {
        val trace = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("type", "https://example.invalid/feedme/progress/recipe-recalled")
            put("title", "Synthetic recipe withdrawn"); put("status", 409); put("code", "RECIPE_RECALLED"); put("traceId", trace)
        }
        return ApiReply(409, PrivateBytes(body.toString().encodeToByteArray()), traceId = trace, contentType = "application/problem+json")
    }
    private fun problem(reason: FailureReason, versionConflict: Boolean = false): ApiReply {
        val status = if (versionConflict) 412 else when (reason) { FailureReason.NOT_FOUND -> 404; FailureReason.CONFLICT -> 409
            FailureReason.INVALID_DATA -> 422; FailureReason.RATE_LIMITED -> 429; else -> 503 }
        val trace = UUID.randomUUID().toString()
        val body = buildJsonObject { put("type", "https://example.invalid/feedme/progress/unavailable")
            put("title", "Synthetic preview operation unavailable"); put("status", status); put("code", if (versionConflict) "VERSION_CONFLICT" else reason.name); put("traceId", trace) }
        return ApiReply(status, PrivateBytes(body.toString().encodeToByteArray()), traceId = trace, contentType = "application/problem+json")
    }
    private fun encodeReply(reply: ApiReply): JsonObject = buildJsonObject {
        put("status", reply.status); put("body", reply.body?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it.copyForCodec())) } ?: JsonNull)
        put("etag", reply.etag?.let(::JsonPrimitive) ?: JsonNull); put("media", reply.contentType?.let(::JsonPrimitive) ?: JsonNull)
        put("trace", reply.traceId?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun decodeReply(value: JsonObject): ApiReply {
        if (value.keys != setOf("status", "body", "etag", "media", "trace")) previewFail(FailureReason.STORAGE_FAILURE)
        return ApiReply(value.long("status").toInt(), value["body"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let {
            PrivateBytes(Base64.getDecoder().decode(it)) },
            value["etag"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            traceId = value["trace"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            contentType = value["media"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
    }
    private fun fingerprint(call: ApiCall): String = hash(buildJsonObject {
        put("operation", call.operationId); put("origin", origin); put("device", ProgressIdentity.deviceSessionId)
        put("path", JsonObject(call.pathParameters.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("query", JsonObject(call.queryParameters.toSortedMap().mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
        put("ifMatch", call.ifMatch?.let(::JsonPrimitive) ?: JsonNull)
        put("body", call.body?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it.copyForCodec())) } ?: JsonNull)
    }.toString())
    private fun reply(body: JsonObject, status: Int = 200, etag: String? = null) =
        ApiReply(status, PrivateBytes(WireDocument.parse(body.toString()).encodeUtf8()), etag, contentType = "application/json")
    private fun increment(value: JsonObject, now: String): JsonObject {
        val next = value.long("version") + 1L
        if (next <= 0) previewFail(FailureReason.STORAGE_FAILURE)
        return JsonObject(value + mapOf("version" to JsonPrimitive(next), "updatedAt" to JsonPrimitive(now)))
    }
    private fun checkVersion(call: ApiCall, current: JsonObject) {
        if (call.ifMatch != version(current)) throw PreviewVersionConflict()
    }
    private fun version(value: JsonObject) = "\"${value.long("version")}\""
    private suspend fun <T> guarded(block: suspend () -> T): PortResult<T> = mutex.withLock {
        try { requireCurrent(); currentCoroutineContext().ensureActive(); val result = block(); requireCurrent(); PortResult.Value(result) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PreviewServiceFailure) { PortResult.Failure(failure.reason) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }
    companion object {
        val KEY = RecordKey("preview-service", "canonical-v1")
        const val MAX_BYTES = 1_000_000
        private const val MAX_PLANS = 24
        private const val MAX_COMMANDS = 128
        private val HASH = Regex("[0-9a-f]{64}")
        private val COMMANDS = setOf("updatePreferences", "upsertPantryItem", "removePantryItem", "createPlan", "createCookSession", "updateCookSession", "completeCookSession", "saveRecipe", "deleteSavedRecipe")
        private val OPERATIONS = COMMANDS + setOf("getPreferences", "listPantry", "searchIngredients", "getPlan", "getCookSession", "getSavedRecipe", "listSavedRecipes")
    }
}

internal class PreviewServiceFailure(val reason: FailureReason) : Exception("Synthetic service unavailable")
internal class PreviewVersionConflict : Exception("Synthetic resource version changed")
internal class PreviewRecipeRecalled : Exception("Synthetic recipe withdrawn")
internal fun previewFail(reason: FailureReason): Nothing = throw PreviewServiceFailure(reason)
internal fun <T> previewValue(result: PortResult<T>): T = when (result) {
    is PortResult.Value -> result.value
    is PortResult.Failure -> previewFail(result.reason)
}
private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
private fun JsonObject.obj(key: String) = getValue(key).jsonObject
private fun JsonObject.arrayStrings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
private fun WireDocument.json() = Json.parseToJsonElement(encodeUtf8().decodeToString()).jsonObject
private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
