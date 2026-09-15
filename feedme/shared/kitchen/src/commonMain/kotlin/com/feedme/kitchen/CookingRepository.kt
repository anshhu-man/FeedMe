package com.feedme.kitchen

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.sync.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Durable private cooking adapter, not an identity provider or background scheduler. Compose it
 * with the encrypted owner store, serialized owner dispatcher and a queue whose required gate
 * delegates cooking commands to executionDecision. The application must honor availability;
 * snapshots are domain records, not a permission to render recalled instructions or start timers.
 * Local integrity hashes do not replace the still-missing server content manifest.
 */
class CookingRepository(
    scope: StorageScope, store: PrivateStateStore, boundary: SessionBoundary,
    dispatcher: CoroutineDispatcher, clock: EpochClock, transport: AccountTransport,
    originBinding: String, private val commands: DurableCommandQueue,
) {
    private val context = KitchenContext(scope, store, boundary, dispatcher, clock, transport)
    private val origin = normalizedId(originBinding)
    private val mutex = Mutex()

    /** Download the owned session and its materialized plan, never substitute a catalog recipe. */
    suspend fun download(lease: SessionLease, sessionId: String): PortResult<CookingSnapshot> = context.guarded(lease) {
        mutex.withLock {
            val id = normalizedId(sessionId)
            val baseline = load(lease, id)
            val index = baseline?.index ?: index(lease)
            val sessionReply = fetchPinned(lease, baseline, "getCookSession", mapOf("sessionId" to id))
            val serverBytes = sessionReply.body ?: PrivateJson.invalid()
            val server = CookSessionWire.from(context.document("CookSession", serverBytes))
            if (normalizedId(server.id.value) != id) PrivateJson.invalid()
            val planId = normalizedId(server.planId.value)
            if (baseline != null && baseline.header.planId != planId) kitchenFail(FailureReason.CONFLICT)
            val planReply = fetchPinned(lease, baseline, "getPlan", mapOf("planId" to planId))
            val planBytes = planReply.body ?: PrivateJson.invalid()
            val plan = PlanWire.from(context.document("Plan", planBytes))
            if (normalizedId(plan.id.value) != planId) PrivateJson.invalid()
            val recipe = recipe(plan)
            if (recipe?.reviewStatus == "recalled" || plan.status == "recalled") {
                val pinned = baseline?.let { recipe(it.plan) } ?: recipe
                if (pinned != null) context.markRecall(lease, "version", pinned.id.value)
            }
            val etag = checkedEtag(server.document, sessionReply.etag)
            checkedEtag(plan.document, planReply.etag)
            val incomingProgress = CookingRecords.fromServer(server)
            validatePin(plan, server, incomingProgress)
            if (baseline != null) {
                compatiblePlan(baseline.plan, plan)
                val order = compareVersions(documentVersion(server.document), documentVersion(baseline.remote.document))
                if (order < 0 || (order == 0 && server.document.json() != baseline.remote.document.json()))
                    kitchenFail(FailureReason.CONFLICT)
                if (terminal(baseline.remote.status) && baseline.remote.status != server.status) kitchenFail(FailureReason.CONFLICT)
                baseline.conflict?.let {
                    if (compareVersions(documentVersion(server.document), documentVersion(it.document)) < 0)
                        kitchenFail(FailureReason.CONFLICT)
                }
            }
            val remoteChanged = baseline != null && server.document.json() != baseline.remote.document.json()
            // A refresh is not an acknowledgement of any local command, even if it looks similar.
            val conflict = baseline != null && (baseline.header.conflictHash != null ||
                (baseline.header.pending.isNotEmpty() && remoteChanged))
            val progress = if (baseline?.header?.pending?.isNotEmpty() == true || conflict) baseline.progress else incomingProgress
            val progressBytes = CookingRecords.encodeProgress(progress)
            CookingRecords.decodeProgress(progressBytes, context)
            val storedServer = if (conflict) baseline.serverRecord.payload else serverBytes
            val checkedAt = maxOf(baseline?.header?.checkedAt ?: 0, context.now())
            val header = CookHeader(id, planId, baseline?.header?.originBinding ?: origin,
                privateDigest(planBytes), privateDigest(storedServer), privateDigest(progressBytes),
                if (conflict) baseline.header.etag else etag, checkedAt, baseline?.header?.pending.orEmpty(),
                if (conflict) privateDigest(serverBytes) else null, if (conflict) etag else null)
            val changes = mutableListOf<StoreMutation>(
                kitchenPut(key("plan", id), baseline?.planRecord?.revision, planBytes),
                kitchenPut(key("server", id), baseline?.serverRecord?.revision, storedServer),
                kitchenPut(key("progress", id), baseline?.progressRecord?.revision, progressBytes),
                kitchenPut(key("metadata", id), baseline?.metadataRecord?.revision, CookingRecords.encodeHeader(header)),
                indexChange(index, id),
            )
            if (conflict) changes += kitchenPut(key("conflict", id), baseline.conflictRecord?.revision, serverBytes)
            context.commit(lease, changes)
            view(lease, requireLoaded(lease, id))
        }
    }

    suspend fun read(lease: SessionLease, sessionId: String): PortResult<CookingSnapshot?> = context.guarded(lease) {
        mutex.withLock { load(lease, normalizedId(sessionId))?.let { view(lease, it) } }
    }

    /** Known local blocking evidence only. False does not establish current safety or rights. */
    suspend fun hasRecipeRecall(lease: SessionLease, recipeVersionId: String): PortResult<Boolean> = context.guarded(lease) {
        mutex.withLock { context.isRecalled(lease, "version", recipeVersionId) }
    }

    /** Complete owner index, bounded to 64 pins; corrupt entries fail closed, never disappear. */
    suspend fun list(lease: SessionLease): PortResult<List<CookingSnapshot>> = context.guarded(lease) {
        mutex.withLock {
            val index = index(lease)
            val values = index.ids.map { view(lease, requireLoaded(lease, it)) }
            checkIndex(lease, index)
            values
        }
    }

    /** Save domain intent and visible progress in one CAS transaction before acknowledging a tap. */
    suspend fun edit(lease: SessionLease, sessionId: String, expectedLocalRevision: Long,
        commandId: String, edit: CookingEdit): PortResult<CookingSnapshot> = editInternal(lease, sessionId,
            expectedLocalRevision, commandId, edit, null)

    /** Purpose-fixed atomic timer progress + immutable command + private scheduling intent. No OS effect. */
    suspend fun editTimersWithMetadata(lease: SessionLease, sessionId: String, expectedLocalRevision: Long,
        commandId: String, timers: List<WireDocument>, expectedMetadata: PrivateRecord?,
        metadata: PrivateBytes): PortResult<CookingSnapshot> = editInternal(lease, sessionId, expectedLocalRevision,
            commandId, CookingEdit.ReplaceTimers(timers), TimerExtra(expectedMetadata, metadata))

    /** Observation, not scheduling acknowledgement. A changed timer body invalidates old mappings. */
    suspend fun readTimerMetadata(lease: SessionLease, sessionId: String): PortResult<CookingTimerMetadata?> = context.guarded(lease) {
        mutex.withLock {
            val bundle = requireLoaded(lease, normalizedId(sessionId))
            if (bundle.header.originBinding != origin) kitchenFail(FailureReason.CONFLICT)
            timerMetadata(lease, bundle)
        }
    }

    /** Read-only local eligibility, not a server/OS grant. Missing materialized queue evidence is
     * corruption/conflict, never interchangeable with an unmaterialized immutable local action.
     */
    suspend fun timerExecutionCheck(lease: SessionLease, sessionId: String): PortResult<Unit> = context.guarded(lease) {
        mutex.withLock {
            val bundle = requireLoaded(lease, normalizedId(sessionId))
            requireEditable(lease, bundle)
            if (terminal(bundle.progress.status.name.lowercase())) kitchenFail(FailureReason.FORBIDDEN)
            for (id in bundle.header.pending) {
                val action = action(lease, id, bundle.header.id)
                val command = kitchenValue(commands.command(lease, id))
                if (!action.header.materialized) {
                    if (command != null) kitchenFail(FailureReason.CONFLICT)
                    continue
                }
                if (id != bundle.header.pending.first() || command == null || command.operationId != action.header.operationId ||
                    command.phase !in setOf(CommandPhase.READY, CommandPhase.RETRY_WAIT) ||
                    command.issue !in setOf(CommandIssue.NONE, CommandIssue.OFFLINE, CommandIssue.TEMPORARILY_UNAVAILABLE)) kitchenFail(FailureReason.CONFLICT)
                val original = kitchenValue(commands.intent(lease, id)) ?: kitchenFail(FailureReason.CONFLICT)
                val call = original.call
                if (original.commandId != id || original.originBinding != origin || original.dependencyCommandIds.isNotEmpty() ||
                    call.operationId != action.header.operationId || call.pathParameters != mapOf("sessionId" to bundle.header.id) ||
                    call.queryParameters.isNotEmpty() || call.ifMatch != action.header.ifMatch || call.idempotencyKey?.use { it } != id ||
                    call.body?.let(::privateDigest) != action.header.bodyHash) kitchenFail(FailureReason.CONFLICT)
                val after = kitchenValue(commands.command(lease, id)) ?: kitchenFail(FailureReason.CONFLICT)
                if (after.localRevision != command.localRevision) kitchenFail(FailureReason.CONFLICT)
            }
            if (context.read(lease, key("metadata", bundle.header.id))?.revision != bundle.metadataRecord.revision) kitchenFail(FailureReason.CONFLICT)
            requireEditable(lease, bundle)
        }
    }

    /** Fresh changing CAS/readback of the fixed mapping with an exact cooking predecessor.
     * This does not acknowledge an earlier failed edit/command, change timer bytes, or approve alerts.
     * A stale timer binding remains stale, permitting only exact alert cleanup by the trusted facade.
     */
    suspend fun acknowledgeTimerMetadata(lease: SessionLease, sessionId: String, expectedLocalRevision: Long,
        expected: PrivateRecord, metadata: PrivateBytes): PortResult<CookingTimerMetadata> = context.guarded(lease) {
        mutex.withLock {
            val bundle = requireLoaded(lease, normalizedId(sessionId))
            if (bundle.header.originBinding != origin || bundle.metadataRecord.revision != expectedLocalRevision) kitchenFail(FailureReason.CONFLICT)
            val old = timerMetadata(lease, bundle) ?: kitchenFail(FailureReason.CONFLICT)
            exactTimerRecord(old.record, expected)
            val raw = context.read(lease, key("timer-metadata", bundle.header.id)) ?: kitchenFail(FailureReason.CONFLICT)
            val envelope = CookingTimerMetadataCodec.decode(raw.payload)
            val encoded = CookingTimerMetadataCodec.encode(origin, envelope.timerHash, metadata)
            context.commit(lease, listOf(kitchenPut(key("timer-metadata", bundle.header.id), raw.revision, encoded),
                kitchenPut(key("metadata", bundle.header.id), bundle.metadataRecord.revision, bundle.metadataRecord.payload),
                kitchenPut(key("progress", bundle.header.id), bundle.progressRecord.revision, bundle.progressRecord.payload),
                indexChange(bundle.index, bundle.header.id)))
            timerMetadata(lease, requireLoaded(lease, bundle.header.id)) ?: kitchenFail(FailureReason.STORAGE_FAILURE)
        }
    }

    private suspend fun editInternal(lease: SessionLease, sessionId: String, expectedLocalRevision: Long,
        commandId: String, edit: CookingEdit, timerExtra: TimerExtra?): PortResult<CookingSnapshot> = context.guarded(lease) {
        mutex.withLock {
            val bundle = requireLoaded(lease, normalizedId(sessionId))
            requireEditable(lease, bundle)
            if (bundle.metadataRecord.revision != expectedLocalRevision) kitchenFail(FailureReason.CONFLICT)
            if (terminal(bundle.progress.status.name.lowercase()) || bundle.header.pending.size >= 64) kitchenFail(FailureReason.CONFLICT)
            val command = normalizedId(commandId)
            if (kitchenValue(commands.command(lease, command)) != null) kitchenFail(FailureReason.CONFLICT)
            if (context.read(lease, key("action", command)) != null || context.read(lease, key("action-body", command)) != null ||
                context.read(lease, key("used-action", command)) != null)
                kitchenFail(FailureReason.CONFLICT)
            val sequence = bundle.progress.deviceSequence.toLongOrNull() ?: kitchenFail(FailureReason.INVALID_DATA)
            if (sequence == Long.MAX_VALUE) kitchenFail(FailureReason.INVALID_DATA)
            val progress = reduce(bundle.progress, edit, (sequence + 1).toString())
            if (!validatePin(bundle.plan, bundle.remote, progress)) kitchenFail(FailureReason.INVALID_DATA)
            val progressBytes = CookingRecords.encodeProgress(progress)
            CookingRecords.decodeProgress(progressBytes, context)
            val operation = if (edit is CookingEdit.Complete) "completeCookSession" else "updateCookSession"
            val body = if (edit is CookingEdit.Complete) PrivateBytes(buildJsonObject {
                put("deviceSequence", sequence + 1); put("makeAgain", edit.makeAgain)
                edit.finishedAtClient?.let { put("finishedAtClient", it) }
            }.toString().encodeToByteArray()) else CookingRecords.patch(progress)
            context.document(if (edit is CookingEdit.Complete) "Completion" else "CookPatch", body)
            val action = CookActionHeader(command, bundle.header.id, operation, privateDigest(body), context.now(), false, null)
            val header = bundle.header.copy(progressHash = privateDigest(progressBytes), pending = bundle.header.pending + command)
            val timerChanges = timerExtra?.let {
                val existing = timerMetadata(lease, bundle)
                exactTimerRecord(existing?.record, it.expected)
                listOf(kitchenPut(key("timer-metadata", bundle.header.id), it.expected?.revision,
                    CookingTimerMetadataCodec.encode(origin, timerDigest(progress), it.payload)))
            }.orEmpty()
            context.commit(lease, listOf(
                kitchenPut(key("progress", header.id), bundle.progressRecord.revision, progressBytes),
                kitchenPut(key("metadata", header.id), bundle.metadataRecord.revision, CookingRecords.encodeHeader(header)),
                kitchenPut(key("action", command), null, CookingRecords.encodeAction(action)),
                kitchenPut(key("action-body", command), null, body),
                kitchenPut(key("used-action", command), null, PrivateBytes(byteArrayOf(1))), indexChange(bundle.index, header.id),
            ) + timerChanges)
            view(lease, requireLoaded(lease, header.id))
        }
    }

    /**
     * Bind only the head domain intent to the latest acknowledged server ETag. Subsequent local
     * edits are NOT queued with a guessed future ETag. Once materialized, body/key/ETag never change.
     */
    suspend fun materializeNext(lease: SessionLease, sessionId: String): PortResult<CommandView?> = context.guarded(lease) {
        mutex.withLock {
            val bundle = requireLoaded(lease, normalizedId(sessionId))
            requireEditable(lease, bundle)
            val id = bundle.header.pending.firstOrNull() ?: return@withLock null
            val action = action(lease, id, bundle.header.id)
            if (action.header.materialized) return@withLock kitchenValue(commands.pending(lease)).singleOrNull { it.commandId == id }
                ?: kitchenFail(FailureReason.CONFLICT)
            val etag = if (action.header.operationId == "updateCookSession") bundle.header.etag
                ?: kitchenFail(FailureReason.NOT_CONFIGURED) else null
            val updated = action.header.copy(materialized = true, ifMatch = etag)
            val intent = intent(updated, action.bodyRecord.payload)
            kitchenValue(commands.enqueue(lease, intent, listOf(
                kitchenPut(key("action", id), action.metadataRecord.revision, CookingRecords.encodeAction(updated)),
                kitchenPut(key("metadata", bundle.header.id), bundle.metadataRecord.revision, CookingRecords.encodeHeader(bundle.header)),
                indexChange(bundle.index, bundle.header.id),
            )))
        }
    }

    /** Required queue gate. Does not approve any other module, origin or altered request. */
    suspend fun executionDecision(lease: SessionLease, command: CommandIntent): ExecutionDecision {
        val result = context.guarded(lease) {
            mutex.withLock {
                val call = command.call
                if (command.originBinding != origin || call.operationId !in setOf("updateCookSession", "completeCookSession") ||
                    call.queryParameters.isNotEmpty() || command.dependencyCommandIds.isNotEmpty() ||
                    call.pathParameters.keys != setOf("sessionId")) kitchenFail(FailureReason.CONFLICT)
                val bundle = requireLoaded(lease, normalizedId(call.pathParameters.getValue("sessionId")))
                requireEditable(lease, bundle)
                if (bundle.header.pending.firstOrNull() != command.commandId) kitchenFail(FailureReason.CONFLICT)
                val action = action(lease, command.commandId, bundle.header.id)
                if (!action.header.materialized || action.header.operationId != call.operationId ||
                    action.header.ifMatch != call.ifMatch || call.idempotencyKey?.use { it } != action.header.id ||
                    call.body?.let(::privateDigest) != action.header.bodyHash) kitchenFail(FailureReason.CONFLICT)
                if (call.operationId == "updateCookSession" && call.ifMatch != bundle.header.etag) kitchenFail(FailureReason.CONFLICT)
            }
        }
        return when (result) {
            is PortResult.Value -> ExecutionDecision.Ready
            is PortResult.Failure -> ExecutionDecision.Wait(when (result.reason) {
                FailureReason.STALE_SESSION, FailureReason.UNAUTHENTICATED -> CommandIssue.AUTH_REQUIRED
                FailureReason.NOT_CONFIGURED -> CommandIssue.NOT_CONFIGURED
                else -> CommandIssue.DOMAIN_RECHECK_REQUIRED
            })
        }
    }

    /** Required cooking reply observer in the queue composition, before ordinary outcome storage. */
    suspend fun observeReply(lease: SessionLease, command: CommandIntent, reply: ApiReply): PortResult<Unit> = context.guarded(lease) {
        mutex.withLock {
            val binding = CanonicalResponseBinder().bind(command.call.operationId, reply.status,
                reply.body?.copyForCodec(), reply.contentType, reply.traceId)
            if (binding !is ResponseBindingResult.Accepted) PrivateJson.invalid()
            val document = (binding.response.body as? WireBody.Present)?.document
            val code = (document?.field("code") as? WireField.Value)?.value?.stringOrNull()
            if (reply.status in 200..299 || code != "RECIPE_RECALLED") return@withLock Unit
            val call = command.call
            if (command.originBinding != origin || call.operationId !in setOf("updateCookSession", "completeCookSession") ||
                call.pathParameters.keys != setOf("sessionId") || call.queryParameters.isNotEmpty() || command.dependencyCommandIds.isNotEmpty())
                kitchenFail(FailureReason.CONFLICT)
            val bundle = requireLoaded(lease, normalizedId(call.pathParameters.getValue("sessionId")))
            if (bundle.header.originBinding != origin || bundle.header.pending.firstOrNull() != command.commandId) kitchenFail(FailureReason.CONFLICT)
            val action = action(lease, command.commandId, bundle.header.id)
            if (!action.header.materialized || action.header.operationId != call.operationId || action.header.ifMatch != call.ifMatch ||
                call.idempotencyKey?.use { it } != action.header.id || call.body?.let(::privateDigest) != action.header.bodyHash)
                kitchenFail(FailureReason.CONFLICT)
            recipe(bundle.plan)?.let { context.markRecall(lease, "version", it.id.value) } ?: PrivateJson.invalid()
        }
    }

    /** Apply an exact successful head receipt atomically with journal acknowledgement. */
    suspend fun applyReceipt(lease: SessionLease, sessionId: String): PortResult<CookingSnapshot> = context.guarded(lease) {
        mutex.withLock {
            val bundle = requireLoaded(lease, normalizedId(sessionId))
            if (bundle.header.originBinding != origin) kitchenFail(FailureReason.CONFLICT)
            if (availability(lease, bundle) !in setOf(CookingAvailability.AVAILABLE, CookingAvailability.CONFLICT))
                kitchenFail(FailureReason.FORBIDDEN)
            val id = bundle.header.pending.firstOrNull() ?: kitchenFail(FailureReason.NOT_FOUND)
            val action = action(lease, id, bundle.header.id)
            if (!action.header.materialized) kitchenFail(FailureReason.CONFLICT)
            val receipt = kitchenValue(commands.receipt(lease, id)) ?: kitchenFail(FailureReason.NOT_FOUND)
            if (receipt.command.operationId != action.header.operationId || receipt.reply.status != 200) PrivateJson.invalid()
            val bytes = receipt.reply.body ?: PrivateJson.invalid()
            val remote = CookSessionWire.from(context.document("CookSession", bytes))
            val etag = checkedEtag(remote.document, receipt.reply.etag)
            if (normalizedId(remote.id.value) != bundle.header.id || normalizedId(remote.planId.value) != bundle.header.planId)
                PrivateJson.invalid()
            if (bundle.conflict != null && bundle.conflict.document.json() != remote.document.json()) kitchenFail(FailureReason.CONFLICT)
            val progress = CookingRecords.fromServer(remote)
            validatePin(bundle.plan, remote, progress)
            val request = context.document(if (action.header.operationId == "completeCookSession") "Completion" else "CookPatch", action.bodyRecord.payload)
            val requestSequence = integerValue(request.json().jsonObject.getValue("deviceSequence").jsonPrimitive.content)
            val expectedStatus = if (action.header.operationId == "completeCookSession") "completed"
                else request.json().jsonObject.getValue("status").jsonPrimitive.content
            val acknowledged = progress.deviceSequence == requestSequence && remote.status == expectedStatus &&
                compareVersions(documentVersion(remote.document), documentVersion(bundle.remote.document)) > 0 &&
                (action.header.operationId != "updateCookSession" || matchesPatch(request, remote))
            if (!acknowledged) {
                val header = bundle.header.copy(conflictHash = privateDigest(bytes), conflictEtag = etag)
                context.commit(lease, listOf(
                    kitchenPut(key("conflict", header.id), bundle.conflictRecord?.revision, bytes),
                    kitchenPut(key("metadata", header.id), bundle.metadataRecord.revision, CookingRecords.encodeHeader(header)),
                    indexChange(bundle.index, header.id),
                ))
                kitchenFail(FailureReason.CONFLICT)
            }
            val pending = bundle.header.pending.drop(1)
            val progressBytes = if (pending.isEmpty()) CookingRecords.encodeProgress(progress) else bundle.progressRecord.payload
            val header = bundle.header.copy(serverHash = privateDigest(bytes), progressHash = privateDigest(progressBytes),
                etag = etag, pending = pending, checkedAt = maxOf(bundle.header.checkedAt, context.now()), conflictHash = null, conflictEtag = null)
            kitchenValue(commands.applyReceipt(lease, id, receipt.command.localRevision, listOf(
                kitchenPut(key("server", header.id), bundle.serverRecord.revision, bytes),
                kitchenPut(key("progress", header.id), bundle.progressRecord.revision, progressBytes),
                kitchenPut(key("metadata", header.id), bundle.metadataRecord.revision, CookingRecords.encodeHeader(header)),
                StoreMutation.Delete(key("action", id), action.metadataRecord.revision),
                StoreMutation.Delete(key("action-body", id), action.bodyRecord.revision), indexChange(bundle.index, header.id),
            ) + listOfNotNull(bundle.conflictRecord?.let { StoreMutation.Delete(key("conflict", header.id), it.revision) })))
            view(lease, requireLoaded(lease, header.id))
        }
    }

    private suspend fun fetchPinned(lease: SessionLease, baseline: Bundle?, operation: String, path: Map<String, String>): ApiReply = try {
        context.fetch(lease, operation, path)
    } catch (failure: KitchenFailure) {
        if (failure.problemCode == "RECIPE_RECALLED") baseline?.let { recipe(it.plan) }?.let {
            context.markRecall(lease, "version", it.id.value)
        }
        throw failure
    }

    private fun reduce(old: CookingProgress, edit: CookingEdit, sequence: String): CookingProgress {
        var status = old.status; var step = old.currentStepId; var completed = old.completedStepIds
        var timers = old.timers; var notes = old.personalNotes
        when (edit) {
            is CookingEdit.MoveTo -> step = edit.stepId
            is CookingEdit.MarkStepComplete -> completed = (completed + edit.stepId).distinct()
            is CookingEdit.SetStatus -> {
                if (edit.status == CookingStatus.COMPLETED) kitchenFail(FailureReason.INVALID_DATA)
                status = edit.status
            }
            is CookingEdit.ReplaceTimers -> timers = edit.timers.map { TimerStateWire.from(context.document("TimerState", PrivateBytes(it.encodeUtf8()))) }
            is CookingEdit.ReplaceNotes -> notes = edit.notes
            is CookingEdit.Complete -> status = CookingStatus.COMPLETED
        }
        return CookingProgress(status, step, completed, timers, notes, sequence)
    }

    private fun matchesPatch(request: WireDocument, remote: CookSessionWire): Boolean {
        val patch = request.json().jsonObject; val result = remote.document.json().jsonObject
        return listOf("currentStepId", "completedStepIds", "timers", "personalNotes").all { it !in patch || patch[it] == result[it] }
    }

    private suspend fun requireEditable(lease: SessionLease, bundle: Bundle) {
        if (bundle.header.originBinding != origin) kitchenFail(FailureReason.CONFLICT)
        if (availability(lease, bundle) != CookingAvailability.AVAILABLE) kitchenFail(FailureReason.FORBIDDEN)
    }

    private suspend fun timerMetadata(lease: SessionLease, bundle: Bundle): CookingTimerMetadata? {
        val stored = context.read(lease, key("timer-metadata", bundle.header.id)) ?: return null
        if (stored.schemaVersion != 1) PrivateJson.invalid()
        val envelope = CookingTimerMetadataCodec.decode(stored.payload)
        if (envelope.origin != origin) kitchenFail(FailureReason.CONFLICT)
        return CookingTimerMetadata(PrivateRecord(stored.revision, 1, envelope.payload), envelope.timerHash == timerDigest(bundle.progress))
    }
    private fun timerDigest(progress: CookingProgress) = privateDigest(PrivateBytes(JsonArray(progress.timers.map { it.document.json() }).toString().encodeToByteArray()))
    private fun exactTimerRecord(actual: PrivateRecord?, expected: PrivateRecord?) {
        if ((actual == null) != (expected == null) || (actual != null && expected != null &&
            (actual.revision != expected.revision || actual.schemaVersion != expected.schemaVersion || privateDigest(actual.payload) != privateDigest(expected.payload))))
            kitchenFail(FailureReason.CONFLICT)
    }
    private class TimerExtra(val expected: PrivateRecord?, val payload: PrivateBytes)

    private suspend fun availability(lease: SessionLease, bundle: Bundle): CookingAvailability {
        val recipe = recipe(bundle.plan)
        if (bundle.plan.status == "recalled" || (recipe != null && (recipe.reviewStatus == "recalled" || context.isRecalled(lease, "version", recipe.id.value))))
            return CookingAvailability.RECALLED
        if (bundle.header.conflictHash != null) return CookingAvailability.CONFLICT
        if (!validatePin(bundle.plan, bundle.remote, bundle.progress)) return CookingAvailability.INCOMPLETE
        if (recipe?.reviewStatus == "personal") return CookingAvailability.PERSONAL_UNREVIEWED
        if (recipe?.reviewStatus !in setOf("published", "retired")) return CookingAvailability.INCOMPLETE
        return CookingAvailability.AVAILABLE
    }

    private suspend fun view(lease: SessionLease, bundle: Bundle) = CookingSnapshot(bundle.header.id, bundle.plan, bundle.remote,
        bundle.progress, availability(lease, bundle), bundle.header.checkedAt, bundle.metadataRecord.revision,
        bundle.header.etag, bundle.header.pending, bundle.conflict, bundle.header.originBinding == origin)

    private fun compatiblePlan(old: PlanWire, incoming: PlanWire) {
        val order = compareVersions(documentVersion(incoming.document), documentVersion(old.document))
        if (order < 0 || (order == 0 && old.document.json() != incoming.document.json())) kitchenFail(FailureReason.CONFLICT)
        fun immutable(plan: PlanWire): JsonObject {
            val root = plan.document.json().jsonObject
            return JsonObject((root - setOf("version", "updatedAt", "status", "recipeSnapshot")) +
                listOfNotNull(recipe(plan)?.let { "recipeSnapshot" to immutableRecipe(it.document) }).toMap())
        }
        if (immutable(old) != immutable(incoming)) kitchenFail(FailureReason.CONFLICT)
        val oldRecipe = recipe(old); val newRecipe = recipe(incoming)
        if (oldRecipe != null && newRecipe != null) {
            val recipeOrder = compareVersions(documentVersion(newRecipe.document), documentVersion(oldRecipe.document))
            if (recipeOrder < 0 || (recipeOrder == 0 && oldRecipe.document.json() != newRecipe.document.json()))
                kitchenFail(FailureReason.CONFLICT)
        }
    }

    /** Schema projections alone do not prove that stable steps/timers refer to this pin. */
    private fun validatePin(plan: PlanWire, remote: CookSessionWire, progress: CookingProgress): Boolean {
        if (normalizedId(remote.planId.value) != normalizedId(plan.id.value)) PrivateJson.invalid()
        val recipe = recipe(plan) ?: return false
        val declaredVersion = (plan.recipeVersionId as? WireField.Value)?.value?.value
        if (declaredVersion != null && normalizedId(declaredVersion) != normalizedId(recipe.id.value)) PrivateJson.invalid()
        val root = recipe.document.json().jsonObject
        val steps = root.getValue("steps").jsonArray.map { it.jsonObject.getValue("stepId").jsonPrimitive.content }
        if (steps.distinct().size != steps.size) PrivateJson.invalid()
        if (steps.isEmpty() || recipe.ingredients.isEmpty()) return false
        val ingredients = root.getValue("ingredients").jsonArray.map { normalizedId(it.jsonObject.getValue("ingredientId").jsonPrimitive.content) }
        if (ingredients.distinct().size != ingredients.size) PrivateJson.invalid()
        val positions = root.getValue("steps").jsonArray.map { integerValue(it.jsonObject.getValue("position").jsonPrimitive.content) }
        if (positions.distinct().size != positions.size) PrivateJson.invalid()
        for (step in root.getValue("steps").jsonArray) {
            if (step.jsonObject.getValue("ingredientIds").jsonArray.any { normalizedId(it.jsonPrimitive.content) !in ingredients }) PrivateJson.invalid()
        }
        if (progress.currentStepId !in steps || progress.completedStepIds.any { it !in steps } ||
            progress.completedStepIds.distinct().size != progress.completedStepIds.size) PrivateJson.invalid()
        val timerIds = progress.timers.map { it.timerId.value }
        if (timerIds.distinct().size != timerIds.size) PrivateJson.invalid()
        for (timer in progress.timers) {
            if (timer.stepId.value !in steps) PrivateJson.invalid()
            val data = timer.document.json().jsonObject
            if (timer.status == "running" && data["endAt"] !is JsonPrimitive) return false
            if (timer.status == "paused" && data["pausedRemainingSeconds"] !is JsonPrimitive) return false
            val remaining = data["pausedRemainingSeconds"]?.jsonPrimitive?.content
            if (remaining != null && compareVersions(integerValue(remaining), integerValue(timer.durationSeconds.jsonToken)) > 0) PrivateJson.invalid()
        }
        return plan.status == "ready"
    }

    private suspend fun load(lease: SessionLease, id: String): Bundle? {
        val index = index(lease)
        val metadata = context.read(lease, key("metadata", id))
        val planRecord = context.read(lease, key("plan", id))
        val serverRecord = context.read(lease, key("server", id))
        val progressRecord = context.read(lease, key("progress", id))
        val conflictRecord = context.read(lease, key("conflict", id))
        if (metadata == null && planRecord == null && serverRecord == null && progressRecord == null && conflictRecord == null && id !in index.ids) {
            checkIndex(lease, index); return null
        }
        if (id !in index.ids || metadata?.schemaVersion != 1) PrivateJson.invalid()
        val header = CookingRecords.decodeHeader(metadata.payload)
        if (header.id != id) PrivateJson.invalid()
        val plan = PlanWire.from(context.document("Plan", readVerified(planRecord, header.planHash)))
        val remote = CookSessionWire.from(context.document("CookSession", readVerified(serverRecord, header.serverHash)))
        val progress = CookingRecords.decodeProgress(readVerified(progressRecord, header.progressHash), context)
        if (normalizedId(plan.id.value) != header.planId || normalizedId(remote.id.value) != id) PrivateJson.invalid()
        checkedEtag(remote.document, header.etag)
        validatePin(plan, remote, CookingRecords.fromServer(remote)); validatePin(plan, remote, progress)
        val conflict = header.conflictHash?.let { digest ->
            CookSessionWire.from(context.document("CookSession", readVerified(conflictRecord, digest))).also {
                if (normalizedId(it.id.value) != id || normalizedId(it.planId.value) != header.planId) PrivateJson.invalid()
                checkedEtag(it.document, header.conflictEtag)
            }
        }
        if ((header.conflictHash == null) != (conflictRecord == null)) PrivateJson.invalid()
        if (context.read(lease, key("metadata", id))?.revision != metadata.revision) kitchenFail(FailureReason.CONFLICT)
        checkIndex(lease, index)
        return Bundle(index, metadata, planRecord!!, serverRecord!!, progressRecord!!, conflictRecord, header, plan, remote, progress, conflict)
    }

    private suspend fun requireLoaded(lease: SessionLease, id: String): Bundle = load(lease, id) ?: kitchenFail(FailureReason.NOT_FOUND)
    private suspend fun action(lease: SessionLease, id: String, sessionId: String): Action {
        val metadata = context.read(lease, key("action", id)) ?: PrivateJson.invalid()
        if (metadata.schemaVersion != 1) PrivateJson.invalid()
        val header = CookingRecords.decodeAction(metadata.payload)
        if (header.id != id || header.sessionId != sessionId) PrivateJson.invalid()
        val body = context.read(lease, key("action-body", id))
        context.document(if (header.operationId == "completeCookSession") "Completion" else "CookPatch", readVerified(body, header.bodyHash))
        return Action(metadata, body!!, header)
    }
    private fun intent(action: CookActionHeader, body: PrivateBytes) = CommandIntent(action.id, origin,
        ApiCall(action.operationId, mapOf("sessionId" to action.sessionId), body = body, idempotencyKey = SecretText(action.id), ifMatch = action.ifMatch))
    private suspend fun index(lease: SessionLease): Index {
        val record = context.read(lease, INDEX) ?: return Index(null, emptyList())
        if (record.schemaVersion != 1) PrivateJson.invalid()
        return Index(record, CookingRecords.index(record.payload))
    }
    private suspend fun checkIndex(lease: SessionLease, index: Index) {
        if (context.read(lease, INDEX)?.revision != index.record?.revision) kitchenFail(FailureReason.CONFLICT)
    }
    private fun indexChange(index: Index, id: String): StoreMutation = kitchenPut(INDEX, index.record?.revision,
        CookingRecords.index(if (id in index.ids) index.ids else index.ids + id))
    private class Index(val record: PrivateRecord?, val ids: List<String>)
    private class Action(val metadataRecord: PrivateRecord, val bodyRecord: PrivateRecord, val header: CookActionHeader)
    private class Bundle(val index: Index, val metadataRecord: PrivateRecord, val planRecord: PrivateRecord,
        val serverRecord: PrivateRecord, val progressRecord: PrivateRecord, val conflictRecord: PrivateRecord?,
        val header: CookHeader, val plan: PlanWire, val remote: CookSessionWire, val progress: CookingProgress, val conflict: CookSessionWire?)
    private companion object {
        val INDEX = RecordKey("feedme.kitchen.cook.index", "v1")
        fun key(part: String, id: String) = RecordKey("feedme.kitchen.cook.$part", id)
        fun recipe(plan: PlanWire): RecipeVersionWire? = (plan.recipeSnapshot as? WireField.Value)?.value
        fun terminal(status: String) = status in setOf("completed", "abandoned")
    }
}
