package com.feedme.sync

import com.feedme.contracts.CanonicalResponseBinder
import com.feedme.contracts.ContractCatalog
import com.feedme.contracts.PrincipalClass
import com.feedme.contracts.ResponseBindingResult
import com.feedme.contracts.WireBody
import com.feedme.contracts.WireField
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.transport.MobileRequestValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Durable intent/receipt journal. Requires an already owner-fenced encrypted store and the SAME
 * serialized dispatcher/SessionBoundary as transport. It never activates identities, creates keys,
 * refreshes credentials, invents a new command ID, rebases an ETag or claims domain success.
 * This is not a background scheduler; the application worker calls one bounded operation at a time.
 * Cooking composition must supply its domain gate AND reply observer; the observer default is
 * only for generic journals with no required domain response side effects.
 */
class DurableCommandQueue(
    private val scope: StorageScope,
    private val store: PrivateStateStore,
    private val boundary: SessionBoundary,
    private val ownerDispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
    private val gate: CommandExecutionGate,
    private val transport: AccountTransport,
    private val replyObserver: CommandReplyObserver = CommandReplyObserver { _, _, _ -> PortResult.Value(Unit) },
) {
    private val catalog = ContractCatalog.bundled()
    private val validator = MobileRequestValidator(catalog)
    private val binder = CanonicalResponseBinder()
    private val policy = CommandPolicy(catalog)
    private val mutex = Mutex()

    /** Persist draft changes, exact request body, metadata and discoverable queue index atomically. */
    suspend fun enqueue(lease: SessionLease, intent: CommandIntent, draftChanges: List<StoreMutation> = emptyList()): PortResult<CommandView> {
        val changes = draftChanges.take(61)
        return guarded(lease) {
            mutex.withLock {
                if (changes.size > 60 || !safeDomainChanges(changes) || !validIntent(intent)) fail(FailureReason.INVALID_DATA)
                val snapshot = snapshot(lease)
                if (snapshot.index.ids.size >= MAX_PENDING) fail(FailureReason.UNAVAILABLE)
                if (read(lease, metaKey(intent.commandId)) != null) fail(FailureReason.CONFLICT)
                for (dependency in intent.dependencyCommandIds) {
                    val parent = readCommand(lease, dependency) ?: fail(FailureReason.INVALID_DATA)
                    if (parent.value.phase == CommandPhase.DISCARDED) fail(FailureReason.CONFLICT)
                }
                val now = now()
                if (now < snapshot.index.lastObservedMillis) fail(FailureReason.INVALID_DATA)
                val sequence = sequenceMutation(lease, intent)
                val call = intent.call
                val command = JournalCommand(intent.commandId, intent.originBinding, scope.environment, scope.actorKind.name,
                    scope.actorId, RequestMetadata(call.operationId, call.pathParameters, call.queryParameters, call.ifMatch, call.body != null),
                    intent.dependencyCommandIds, now, null, now,
                    if (policy.resumption(call.operationId) == CommandResumption.AUTOMATIC_WITH_PREFLIGHT) CommandPhase.READY
                    else CommandPhase.AWAITING_CONFIRMATION, 0, CommandIssue.NONE)
                val mutations = changes + listOf(
                    put(metaKey(command.id), null, JournalCodec.encodeCommand(command)),
                    indexMutation(snapshot, snapshot.index.ids + command.id, now),
                ) + listOfNotNull(sequence, call.body?.let { put(bodyKey(command.id), null, it) })
                val revisions = commit(lease, mutations)
                view(command, checkNotNull(revisions[metaKey(command.id)]))
            }
        }
    }

    suspend fun pending(lease: SessionLease): PortResult<List<CommandView>> = guarded(lease) {
        mutex.withLock { snapshot(lease).entries.map { view(it.value, it.revision) } }
    }

    /** Read one exact journal identity, including applied/discarded tombstones; never dispatch. */
    suspend fun command(lease: SessionLease, commandId: String): PortResult<CommandView?> = guarded(lease) {
        mutex.withLock {
            if (!commandUuid.matches(commandId)) fail(FailureReason.INVALID_DATA)
            val entry = readCommand(lease, commandId) ?: return@withLock null
            view(entry.value, entry.revision)
        }
    }

    /** Read the original validated intent without dispatching or granting rebase authority.
     * Terminal tombstones deliberately have no payload and return null. Domain receipt adapters
     * use this exact origin/body/header evidence before atomically applying a successful reply. */
    suspend fun intent(lease: SessionLease, commandId: String): PortResult<CommandIntent?> = guarded(lease) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val entry = readCommand(lease, commandId) ?: return@withLock null
            currentCoroutineContext().ensureActive()
            if (entry.value.phase in setOf(CommandPhase.APPLIED, CommandPhase.DISCARDED)) return@withLock null
            val original = call(lease, entry.value)
            currentCoroutineContext().ensureActive()
            val repeatedCall = call(lease, entry.value)
            currentCoroutineContext().ensureActive()
            if (original.body?.copyForCodec()?.toList() != repeatedCall.body?.copyForCodec()?.toList()) fail(FailureReason.CONFLICT)
            val repeated = readCommand(lease, commandId) ?: fail(FailureReason.CONFLICT)
            currentCoroutineContext().ensureActive()
            if (repeated.revision != entry.revision) fail(FailureReason.CONFLICT)
            CommandIntent(entry.value.id, entry.value.originBinding, original, entry.value.dependencies)
        }
    }

    /** Never send from this method. Interrupted attempts keep their original body/key and age. */
    suspend fun recoverInterrupted(lease: SessionLease): PortResult<Int> = guarded(lease) {
        mutex.withLock {
            var count = 0
            // One store batch is limited to 64 mutations. Recover in bounded independent batches;
            // every committed batch remains valid if a later batch fails or the process stops.
            while (true) {
                val snapshot = snapshot(lease)
                val interrupted = snapshot.entries.filter {
                    it.value.phase == CommandPhase.IN_FLIGHT && !ProcessCommandClaims.isActive(scope, it.value.id)
                }.take(32)
                if (interrupted.isEmpty()) break
                val now = now()
                val updates = interrupted.map { entry ->
                    val updated = retry(entry.value, now, CommandIssue.OUTCOME_UNKNOWN, null, snapshot.index.lastObservedMillis)
                    put(metaKey(updated.id), entry.revision, JournalCodec.encodeCommand(updated))
                } + indexMutation(snapshot, snapshot.index.ids, maxOf(now, snapshot.index.lastObservedMillis))
                commit(lease, updates)
                count += interrupted.size
            }
            count
        }
    }

    /**
     * Recover only this exact orphaned IN_FLIGHT command after an explicit domain recovery
     * decision. Never dispatch, repair another lane, change the original, or refund an attempt.
     * A live process claim and any stale metadata/index CAS both fail closed.
     */
    suspend fun recoverInterrupted(lease: SessionLease, commandId: String, expectedRevision: Long): PortResult<CommandView> =
        guarded(lease) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!commandUuid.matches(commandId) || expectedRevision <= 0) fail(FailureReason.INVALID_DATA)
                val snapshot = snapshot(lease)
                currentCoroutineContext().ensureActive()
                val entry = snapshot.entries.singleOrNull { it.value.id == commandId } ?: fail(FailureReason.NOT_FOUND)
                if (entry.revision != expectedRevision || entry.value.phase != CommandPhase.IN_FLIGHT ||
                    ProcessCommandClaims.isActive(scope, commandId)) fail(FailureReason.CONFLICT)
                // Reject obsolete/damaged original payload, but never rewrite or re-encode it.
                val original = call(lease, entry.value)
                currentCoroutineContext().ensureActive()
                val repeated = call(lease, entry.value)
                if (original.body?.copyForCodec()?.toList() != repeated.body?.copyForCodec()?.toList()) fail(FailureReason.CONFLICT)
                currentCoroutineContext().ensureActive()
                current(lease)
                if (ProcessCommandClaims.isActive(scope, commandId)) fail(FailureReason.CONFLICT)
                val now = now()
                val recovered = retry(entry.value, now, CommandIssue.OUTCOME_UNKNOWN, null, snapshot.index.lastObservedMillis)
                currentCoroutineContext().ensureActive()
                val revision = update(lease, snapshot, entry, recovered, now)
                currentCoroutineContext().ensureActive()
                view(recovered, revision)
            }
        }

    /** Worker entry: only operations with explicit resumable policy can be selected automatically. */
    suspend fun dispatchNext(lease: SessionLease): PortResult<CommandView?> = dispatch(lease, targetId = null, confirmed = false)

    /**
     * Worker entry for one materialized command. A target is not user confirmation: automatic
     * policy, FIFO, dependencies, retry deadlines and the execution gate still apply. Returns null
     * when this exact command cannot be selected; never falls back to another queued command.
     */
    suspend fun dispatchAutomatic(lease: SessionLease, commandId: String): PortResult<CommandView?> =
        dispatch(lease, targetId = commandId, confirmed = false)

    /** Call only from a new, explicit user confirmation; still cannot bypass conflicts or retention. */
    suspend fun dispatchConfirmed(lease: SessionLease, commandId: String): PortResult<CommandView?> =
        dispatch(lease, targetId = commandId, confirmed = true)

    /**
     * The owner coordinator has repaired an authentication/configuration/prerequisite wait. This
     * only re-enables selection: it does NOT send or change the original request, retry deadline,
     * origin, ETag, attempt history or identity. Dispatch still requires a fresh execution gate.
     * Unknown outcomes, conflicts, expired keys and permanent rejection need domain reconciliation;
     * they cannot be cleared by this API. Fresh-confirmation actions still need a new user tap.
     */
    suspend fun resumeAfterResolution(lease: SessionLease, commandId: String, expectedRevision: Long): PortResult<CommandView> = guarded(lease) {
        mutex.withLock {
            if (!commandUuid.matches(commandId)) fail(FailureReason.INVALID_DATA)
            val snapshot = snapshot(lease)
            val entry = snapshot.entries.find { it.value.id == commandId } ?: fail(FailureReason.NOT_FOUND)
            val value = entry.value
            if (entry.revision != expectedRevision || value.phase != CommandPhase.NEEDS_RESOLUTION || value.issue !in setOf(
                    CommandIssue.AUTH_REQUIRED, CommandIssue.NOT_CONFIGURED, CommandIssue.DOMAIN_RECHECK_REQUIRED,
                    CommandIssue.OFFLINE, CommandIssue.TEMPORARILY_UNAVAILABLE)) fail(FailureReason.CONFLICT)
            call(lease, value) // Reject damaged/obsolete intentions, never repair their payload.
            val now = now()
            val issue = eligibilityIssue(value, snapshot.index.lastObservedMillis, now)
            val updated = if (issue != null) value.copy(issue = issue)
                else value.copy(phase = resumablePhase(value), issue = CommandIssue.NONE)
            view(updated, update(lease, snapshot, entry, updated, now))
        }
    }

    suspend fun receipt(lease: SessionLease, commandId: String): PortResult<CommandReceipt?> = guarded(lease) {
        mutex.withLock {
            if (!commandUuid.matches(commandId)) fail(FailureReason.INVALID_DATA)
            val entry = readCommand(lease, commandId) ?: return@withLock null
            if (entry.value.phase != CommandPhase.RECEIPT_READY) return@withLock null
            val reply = reply(lease, entry.value)
            CommandReceipt(view(entry.value, entry.revision), reply)
        }
    }

    /**
     * Domain adapter has interpreted/re-authorized this exact successful receipt. Commit its domain
     * changes and acknowledgement atomically. Wrong/stale CAS fails; receipt remains recoverable.
     */
    suspend fun applyReceipt(lease: SessionLease, commandId: String, expectedRevision: Long,
        domainChanges: List<StoreMutation>): PortResult<Unit> {
        val changes = domainChanges.take(61)
        return guarded(lease) {
            mutex.withLock {
                if (changes.size > 60 || !safeDomainChanges(changes)) fail(FailureReason.INVALID_DATA)
                val snapshot = snapshot(lease)
                val entry = snapshot.entries.find { it.value.id == commandId } ?: fail(FailureReason.NOT_FOUND)
                if (entry.revision != expectedRevision || entry.value.phase != CommandPhase.RECEIPT_READY) fail(FailureReason.CONFLICT)
                reply(lease, entry.value) // Validate persisted receipt again before acknowledging it.
                archive(lease, snapshot, entry, CommandPhase.APPLIED, changes)
            }
        }
    }

    /** Only never-attempted intentions can be locally discarded as unsent. */
    suspend fun discardUnsent(lease: SessionLease, commandId: String, expectedRevision: Long,
        domainChanges: List<StoreMutation> = emptyList()): PortResult<Unit> {
        val changes = domainChanges.take(61)
        return guarded(lease) {
            mutex.withLock {
                if (changes.size > 60 || !safeDomainChanges(changes)) fail(FailureReason.INVALID_DATA)
                val snapshot = snapshot(lease)
                val entry = snapshot.entries.find { it.value.id == commandId } ?: fail(FailureReason.NOT_FOUND)
                if (entry.revision != expectedRevision || entry.value.attempts != 0) fail(FailureReason.CONFLICT)
                archive(lease, snapshot, entry, CommandPhase.DISCARDED, changes)
            }
        }
    }

    private suspend fun dispatch(lease: SessionLease, targetId: String?, confirmed: Boolean): PortResult<CommandView?> = guarded(lease) {
        if (targetId != null && !commandUuid.matches(targetId)) fail(FailureReason.INVALID_DATA)
        var reservedId: String? = null
        try {
        val candidate = mutex.withLock { select(lease, snapshot(lease), targetId, confirmed) } ?: return@guarded null
        // No queue mutex across feature preflight. Any intervening queue/draft transaction changes
        // the index CAS, invalidates this candidate and prevents dispatch under a stale preflight.
        val decision = try { gate.evaluate(lease, candidate.intent) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ExecutionDecision.Wait(CommandIssue.NOT_CONFIGURED) }
        current(lease)
        val claimed = mutex.withLock {
            val fresh = snapshot(lease)
            if (fresh.revision != candidate.snapshot.revision) fail(FailureReason.CONFLICT)
            val entry = fresh.entries.single { it.value.id == candidate.entry.value.id }
            if (entry.revision != candidate.entry.revision) fail(FailureReason.CONFLICT)
            val now = now()
            val issue = eligibilityIssue(entry.value, fresh.index.lastObservedMillis, now)
            if (issue != null || decision is ExecutionDecision.Wait) {
                val reason = issue ?: (decision as ExecutionDecision.Wait).issue.let {
                    // This protocol-specific issue is minted only from a bound Problem below.
                    if (it in setOf(CommandIssue.NONE, CommandIssue.RECIPE_RECALLED)) CommandIssue.DOMAIN_RECHECK_REQUIRED else it
                }
                val updated = entry.value.copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = reason)
                update(lease, fresh, entry, updated, now)
                return@withLock null
            }
            val updated = entry.value.copy(phase = CommandPhase.IN_FLIGHT, attempts = entry.value.attempts + 1,
                firstAttemptAt = entry.value.firstAttemptAt ?: now, retryAt = now, issue = CommandIssue.NONE)
            // One live process must not recover another queue instance's genuine active attempt.
            // Reserve before the suspending claim write; an unsuccessful write releases in finally.
            if (!ProcessCommandClaims.acquire(scope, updated.id)) fail(FailureReason.CONFLICT)
            reservedId = updated.id
            val revision = update(lease, fresh, entry, updated, now)
            Claim(updated, revision, candidate.intent)
        } ?: return@guarded mutex.withLock {
            val entry = readCommand(lease, candidate.entry.value.id) ?: fail(FailureReason.STORAGE_FAILURE)
            view(entry.value, entry.revision)
        }
            current(lease)
            // Claim persistence can suspend (including an app background/resume). Recheck the
            // replay cutoff before handing the command to transport, not just before the write.
            val beforeSend = now()
            val staleTime = eligibilityIssue(claimed.command.copy(attempts = claimed.command.attempts - 1),
                maxOf(candidate.snapshot.index.lastObservedMillis, claimed.command.retryAt), beforeSend)
            if (staleTime != null) return@guarded mutex.withLock {
                val snapshot = snapshot(lease)
                val entry = snapshot.entries.find { it.value.id == claimed.command.id } ?: fail(FailureReason.CONFLICT)
                if (entry.revision != claimed.revision) fail(FailureReason.CONFLICT)
                val updated = refundUnsentAttempt(entry.value).copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = staleTime)
                view(updated, update(lease, snapshot, entry, updated, beforeSend))
            }
            val response = try { transport.execute(lease, claimed.intent.call) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
            current(lease)
            val bound = bindOutcome(claimed.intent.call.operationId, response)
            if (bound.response is PortResult.Value) {
                // A domain fence may persist independently, or consult this queue. Never hold
                // its mutex over the observer and never record an outcome if observing failed.
                current(lease)
                val observed = try { replyObserver.observe(lease, claimed.intent, bound.response.value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { current(lease); fail(FailureReason.STORAGE_FAILURE) }
                current(lease)
                unwrap(observed)
            }
            mutex.withLock {
                val snapshot = snapshot(lease)
                val entry = snapshot.entries.find { it.value.id == claimed.command.id } ?: fail(FailureReason.CONFLICT)
                if (entry.revision != claimed.revision || entry.value.phase != CommandPhase.IN_FLIGHT) fail(FailureReason.CONFLICT)
                recordOutcome(lease, snapshot, entry, bound)
            }
        } finally { reservedId?.let { ProcessCommandClaims.release(scope, it) } }
    }

    private suspend fun select(lease: SessionLease, snapshot: Snapshot, targetId: String?, confirmed: Boolean): Candidate? {
        val blockedLanes = mutableSetOf<String>()
        val now = now()
        for (entry in snapshot.entries) {
            val call = call(lease, entry.value)
            val lane = policy.lane(call)
            if (!blockedLanes.add(lane)) continue
            if (targetId != null && entry.value.id != targetId) continue
            val value = entry.value
            if (value.phase !in setOf(CommandPhase.READY, CommandPhase.RETRY_WAIT, CommandPhase.AWAITING_CONFIRMATION)) continue
            if (value.phase == CommandPhase.AWAITING_CONFIRMATION && !confirmed) continue
            if (!confirmed && policy.resumption(call.operationId) != CommandResumption.AUTOMATIC_WITH_PREFLIGHT) continue
            val issue = eligibilityIssue(value, snapshot.index.lastObservedMillis, now)
            if (issue != null) {
                update(lease, snapshot, entry, value.copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = issue), now)
                return null
            }
            if (now < value.retryAt) continue // User confirmation cannot bypass Retry-After/backoff.
            var waiting = false
            for (dependency in value.dependencies) {
                val parent = readCommand(lease, dependency) ?: fail(FailureReason.STORAGE_FAILURE)
                if (parent.value.phase == CommandPhase.DISCARDED) {
                    update(lease, snapshot, entry, value.copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = CommandIssue.DEPENDENCY_FAILED), now)
                    return null
                }
                if (parent.value.phase != CommandPhase.APPLIED) waiting = true
            }
            if (waiting) continue
            return Candidate(snapshot, entry, CommandIntent(value.id, value.originBinding, call, value.dependencies))
        }
        return null
    }

    /** Invalid bodies never reach domain observers or manufacture typed domain outcomes. */
    private fun bindOutcome(operationId: String, outcome: PortResult<ApiReply>): BoundOutcome {
        if (outcome !is PortResult.Value) return BoundOutcome(outcome)
        val reply = outcome.value
        val binding = try { binder.bind(operationId, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) }
        catch (_: Exception) { null }
        if (binding !is ResponseBindingResult.Accepted) return BoundOutcome(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN))
        val document = (binding.response.body as? WireBody.Present)?.document
        val bodyRetryAfter = if (reply.status in setOf(429, 503)) {
            val token = (document?.field("retryAfterSeconds") as? WireField.Value)?.value?.numberTokenOrNull()
            token?.let(::saturatingRetrySeconds)
        } else null
        val recalled = reply.status !in 200..299 &&
            reply.contentType?.substringBefore(';')?.trim()?.lowercase() == "application/problem+json" &&
            (document?.field("code") as? WireField.Value)?.value?.stringOrNull() == "RECIPE_RECALLED"
        return BoundOutcome(outcome, bodyRetryAfter, recalled)
    }

    private suspend fun recordOutcome(lease: SessionLease, snapshot: Snapshot, entry: Entry, outcome: BoundOutcome): CommandView {
        val now = now()
        val response = outcome.response
        if (response is PortResult.Value && response.value.status in 200..299) {
            val reply = response.value
            val metadata = ReplyMetadata(reply.status, reply.etag, reply.traceId, reply.retryAfterSeconds, reply.contentType, reply.body != null)
            val updated = entry.value.copy(phase = CommandPhase.RECEIPT_READY, issue = CommandIssue.NONE, reply = metadata)
            val mutations = listOf(put(metaKey(updated.id), entry.revision, JournalCodec.encodeCommand(updated)),
                indexMutation(snapshot, snapshot.index.ids, maxOf(now, snapshot.index.lastObservedMillis))) +
                listOfNotNull(reply.body?.let { put(replyKey(updated.id), null, it) })
            val revisions = commit(lease, mutations)
            return view(updated, checkNotNull(revisions[metaKey(updated.id)]))
        }
        val updated = when (response) {
            is PortResult.Failure -> when (response.reason) {
                FailureReason.OUTCOME_UNKNOWN, FailureReason.UNAVAILABLE -> retry(entry.value, now, CommandIssue.OUTCOME_UNKNOWN, response.retryAfterSeconds, snapshot.index.lastObservedMillis)
                // These two local outcomes are proven pre-dispatch in AccountTransport, unlike an
                // HTTP 401 or a lost response. Do not exhaust an unsent command while offline.
                FailureReason.OFFLINE -> retry(refundUnsentAttempt(entry.value), now, CommandIssue.OFFLINE, response.retryAfterSeconds, snapshot.index.lastObservedMillis)
                FailureReason.UNAUTHENTICATED -> refundUnsentAttempt(entry.value).copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = CommandIssue.AUTH_REQUIRED)
                FailureReason.RATE_LIMITED -> retry(entry.value, now, CommandIssue.TEMPORARILY_UNAVAILABLE, response.retryAfterSeconds, snapshot.index.lastObservedMillis)
                else -> entry.value.copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = when (response.reason) {
                    FailureReason.STALE_SESSION -> CommandIssue.AUTH_REQUIRED
                    FailureReason.FORBIDDEN -> CommandIssue.PERMISSION_CHANGED
                    FailureReason.NOT_CONFIGURED -> CommandIssue.NOT_CONFIGURED
                    FailureReason.INVALID_DATA -> CommandIssue.INVALID_REQUEST
                    else -> CommandIssue.OUTCOME_UNKNOWN
                })
            }
            is PortResult.Value -> {
                val reply = response.value
                if (outcome.recipeRecalled) entry.value.copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = CommandIssue.RECIPE_RECALLED)
                else if (reply.status in setOf(429, 503)) retry(entry.value, now, CommandIssue.TEMPORARILY_UNAVAILABLE,
                    maxOf(reply.retryAfterSeconds ?: 0, outcome.bodyRetryAfter ?: 0), snapshot.index.lastObservedMillis)
                else entry.value.copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = when (reply.status) {
                    401 -> CommandIssue.AUTH_REQUIRED
                    403 -> CommandIssue.PERMISSION_CHANGED
                    404 -> CommandIssue.NOT_FOUND
                    409 -> CommandIssue.CONFLICT
                    410 -> CommandIssue.GONE
                    412 -> CommandIssue.VERSION_CONFLICT
                    400, 422, 428 -> CommandIssue.INVALID_REQUEST
                    in 500..599 -> CommandIssue.OUTCOME_UNKNOWN
                    else -> CommandIssue.REMOTE_REJECTED
                })
            }
        }
        return view(updated, update(lease, snapshot, entry, updated, now))
    }

    private fun retry(value: JournalCommand, now: Long, issue: CommandIssue, retryAfterSeconds: Long?, watermark: Long): JournalCommand {
        val ineligible = eligibilityIssue(value, watermark, now)
        if (ineligible != null) return value.copy(phase = CommandPhase.NEEDS_RESOLUTION, issue = ineligible)
        val base = minOf(30_000L, 1000L shl (value.attempts - 1).coerceIn(0, 5))
        val jittered = base + Random.nextLong(0, base / 2 + 1)
        val delay = maxOf(jittered, saturatingMultiply(retryAfterSeconds ?: 0, 1000))
        return value.copy(phase = resumablePhase(value), issue = issue, retryAt = saturatingAdd(now, delay))
    }

    private fun resumablePhase(value: JournalCommand) = when {
        policy.resumption(value.request.operationId) != CommandResumption.AUTOMATIC_WITH_PREFLIGHT -> CommandPhase.AWAITING_CONFIRMATION
        value.attempts == 0 -> CommandPhase.READY
        else -> CommandPhase.RETRY_WAIT
    }

    private fun refundUnsentAttempt(value: JournalCommand): JournalCommand = value.copy(
        attempts = value.attempts - 1, firstAttemptAt = if (value.attempts == 1) null else value.firstAttemptAt)

    private fun eligibilityIssue(value: JournalCommand, watermark: Long, now: Long): CommandIssue? = when {
        now < watermark || now < value.createdAt || (value.firstAttemptAt != null && now < value.firstAttemptAt) -> CommandIssue.CLOCK_CHANGED
        value.attempts >= MAX_ATTEMPTS -> CommandIssue.RETRY_EXHAUSTED
        value.firstAttemptAt != null && now - value.firstAttemptAt >= REPLAY_WINDOW_MILLIS -> CommandIssue.RECONCILIATION_REQUIRED
        else -> null
    }

    private suspend fun archive(lease: SessionLease, snapshot: Snapshot, entry: Entry, phase: CommandPhase, changes: List<StoreMutation>) {
        val old = entry.value
        val tombstone = old.copy(phase = phase, issue = CommandIssue.NONE, reply = null, dependencies = emptyList(),
            request = old.request.copy(path = emptyMap(), query = emptyMap(), ifMatch = null, hasBody = false))
        val body = read(lease, bodyKey(old.id))
        val receipt = read(lease, replyKey(old.id))
        val mutations = changes + listOf(put(metaKey(old.id), entry.revision, JournalCodec.encodeCommand(tombstone)),
            indexMutation(snapshot, snapshot.index.ids - old.id, maxOf(now(), snapshot.index.lastObservedMillis))) +
            listOfNotNull(body?.let { StoreMutation.Delete(bodyKey(old.id), it.revision) },
                receipt?.let { StoreMutation.Delete(replyKey(old.id), it.revision) })
        commit(lease, mutations)
    }

    private suspend fun update(lease: SessionLease, snapshot: Snapshot, entry: Entry, value: JournalCommand, now: Long): Long =
        checkNotNull(commit(lease, listOf(put(metaKey(value.id), entry.revision, JournalCodec.encodeCommand(value)),
            indexMutation(snapshot, snapshot.index.ids, maxOf(now, snapshot.index.lastObservedMillis))))[metaKey(value.id)])

    private suspend fun snapshot(lease: SessionLease): Snapshot {
        val stored = read(lease, INDEX_KEY)
        val index = if (stored == null) QueueIndex(emptyList(), 0) else {
            if (stored.schemaVersion != JOURNAL_SCHEMA) fail(FailureReason.INVALID_DATA)
            JournalCodec.decodeIndex(stored.payload)
        }
        val entries = index.ids.map { id ->
            val entry = readCommand(lease, id) ?: fail(FailureReason.STORAGE_FAILURE)
            if (entry.value.phase in setOf(CommandPhase.APPLIED, CommandPhase.DISCARDED)) fail(FailureReason.STORAGE_FAILURE)
            entry
        }
        if (read(lease, INDEX_KEY)?.revision != stored?.revision) fail(FailureReason.CONFLICT)
        return Snapshot(index, stored?.revision, entries)
    }

    private suspend fun readCommand(lease: SessionLease, id: String): Entry? {
        if (!commandUuid.matches(id)) fail(FailureReason.INVALID_DATA)
        val record = read(lease, metaKey(id)) ?: return null
        if (record.schemaVersion != JOURNAL_SCHEMA) fail(FailureReason.INVALID_DATA)
        val command = JournalCodec.decodeCommand(record.payload)
        if (command.id != id || command.scopeEnvironment != scope.environment || command.scopeActorKind != scope.actorKind.name || command.scopeActorId != scope.actorId)
            fail(FailureReason.STALE_SESSION)
        return Entry(command, record.revision)
    }

    private suspend fun call(lease: SessionLease, value: JournalCommand): ApiCall {
        val body = read(lease, bodyKey(value.id))
        if ((body != null) != value.request.hasBody || (body != null && body.schemaVersion != JOURNAL_SCHEMA)) fail(FailureReason.INVALID_DATA)
        val call = ApiCall(value.request.operationId, value.request.path, value.request.query, body?.payload, SecretText(value.id), value.request.ifMatch)
        if (policy.resumption(call.operationId) == null || !validator.accepts(call, principal())) fail(FailureReason.INVALID_DATA)
        return call
    }

    private suspend fun reply(lease: SessionLease, value: JournalCommand): ApiReply {
        val metadata = value.reply ?: fail(FailureReason.INVALID_DATA)
        val body = read(lease, replyKey(value.id))
        if ((body != null) != metadata.hasBody || (body != null && body.schemaVersion != JOURNAL_SCHEMA)) fail(FailureReason.INVALID_DATA)
        val reply = ApiReply(metadata.status, body?.payload, metadata.etag, metadata.traceId, metadata.retryAfterSeconds, metadata.contentType)
        if (binder.bind(value.request.operationId, reply.status, reply.body?.copyForCodec(), reply.contentType, reply.traceId) !is ResponseBindingResult.Accepted)
            fail(FailureReason.INVALID_DATA)
        return reply
    }

    private fun validIntent(intent: CommandIntent): Boolean = commandUuid.matches(intent.commandId) && commandUuid.matches(intent.originBinding) &&
        intent.call.idempotencyKey?.use { it == intent.commandId } == true &&
        intent.dependencyCommandIds.size <= MAX_DEPENDENCIES && intent.dependencyCommandIds.toSet().size == intent.dependencyCommandIds.size &&
        intent.dependencyCommandIds.all { commandUuid.matches(it) && it != intent.commandId } &&
        policy.resumption(intent.call.operationId) != null && validator.accepts(intent.call, principal())

    /**
     * Cooking requests arrive with an already assigned deviceSequence. Accept only increasing
     * values per original session/device binding, atomically with intent; never renumber/reorder
     * immutable commands. The watermark survives acknowledgement/discard/restart. Domain adapters
     * must still initialize from the current owned server pin and reject stale terminal changes.
     */
    private suspend fun sequenceMutation(lease: SessionLease, intent: CommandIntent): StoreMutation.Put? {
        if (intent.call.operationId !in setOf("updateCookSession", "completeCookSession")) return null
        val document = WireDocument.decode(checkNotNull(intent.call.body).copyForCodec())
        val token = (document.field("deviceSequence") as WireField.Value).value.numberTokenOrNull()!!
        val sequence = boundedIntegerLongOrNull(token) ?: fail(FailureReason.INVALID_DATA)
        val sessionId = intent.call.pathParameters.getValue("sessionId").lowercase()
        val key = RecordKey("${RESERVED_PREFIX}cook-sequence", "${intent.originBinding}:$sessionId")
        val previous = read(lease, key)
        if (previous != null) {
            val bytes = previous.payload.copyForCodec()
            if (previous.schemaVersion != JOURNAL_SCHEMA || bytes.size !in 1..19) fail(FailureReason.INVALID_DATA)
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            if (!text.matches(Regex("0|[1-9][0-9]*"))) fail(FailureReason.INVALID_DATA)
            val highWatermark = text.toLongOrNull() ?: fail(FailureReason.INVALID_DATA)
            if (sequence <= highWatermark) fail(FailureReason.CONFLICT)
        }
        return put(key, previous?.revision, PrivateBytes(sequence.toString().encodeToByteArray()))
    }

    private fun safeDomainChanges(changes: List<StoreMutation>) = changes.none { it.key.collection.startsWith(RESERVED_PREFIX) } &&
        changes.map { it.key }.toSet().size == changes.size
    private fun principal(): PrincipalClass = when (scope.actorKind) {
        ActorKind.ACCOUNT -> PrincipalClass.ACCOUNT
        ActorKind.GUEST -> PrincipalClass.GUEST
        ActorKind.DEMO -> fail(FailureReason.UNAUTHENTICATED)
    }
    private fun current(lease: SessionLease) {
        if (lease.scope != scope || !boundary.isCurrent(lease)) fail(FailureReason.STALE_SESSION)
        principal()
    }
    private suspend fun read(lease: SessionLease, key: RecordKey): PrivateRecord? {
        current(lease)
        val value = store.read(scope, key)
        current(lease)
        return unwrap(value)
    }
    private suspend fun commit(lease: SessionLease, changes: List<StoreMutation>): Map<RecordKey, Long?> {
        current(lease)
        val result = store.commit(scope, changes)
        current(lease)
        return unwrap(result)
    }
    private suspend fun <T> guarded(lease: SessionLease, action: suspend () -> T): PortResult<T> = withContext(ownerDispatcher) {
        try { current(lease); PortResult.Value(action()).also { current(lease) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: QueueFailure) { PortResult.Failure(failure.reason) }
        catch (_: JournalDecodingException) { PortResult.Failure(FailureReason.INVALID_DATA) }
        catch (_: Exception) { PortResult.Failure(FailureReason.STORAGE_FAILURE) }
    }
    private fun <T> unwrap(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> fail(result.reason)
    }
    private fun now(): Long = clock.nowMillis().also { if (it < 0) fail(FailureReason.INVALID_DATA) }
    private fun indexMutation(snapshot: Snapshot, ids: List<String>, now: Long) = put(INDEX_KEY, snapshot.revision, JournalCodec.encodeIndex(QueueIndex(ids, now)))
    private fun view(value: JournalCommand, revision: Long) = CommandView(value.id, value.request.operationId, value.phase, value.attempts, value.issue, value.retryAt, revision)
    private data class Entry(val value: JournalCommand, val revision: Long)
    private data class BoundOutcome(val response: PortResult<ApiReply>, val bodyRetryAfter: Long? = null, val recipeRecalled: Boolean = false)
    private data class Snapshot(val index: QueueIndex, val revision: Long?, val entries: List<Entry>)
    private data class Candidate(val snapshot: Snapshot, val entry: Entry, val intent: CommandIntent)
    private data class Claim(val command: JournalCommand, val revision: Long, val intent: CommandIntent)

    companion object {
        private const val RESERVED_PREFIX = "feedme.command."
        private val INDEX_KEY = RecordKey("${RESERVED_PREFIX}index", "v1")
        private fun metaKey(id: String) = RecordKey("${RESERVED_PREFIX}metadata", id)
        private fun bodyKey(id: String) = RecordKey("${RESERVED_PREFIX}request", id)
        private fun replyKey(id: String) = RecordKey("${RESERVED_PREFIX}receipt", id)
        private fun put(key: RecordKey, revision: Long?, bytes: PrivateBytes) = StoreMutation.Put(key, revision, JOURNAL_SCHEMA, bytes)
        private const val MAX_ATTEMPTS = 8
        // Conservative CLIENT policy inside the canonical seven-day result guarantee; not a
        // server-time proof. Clock rollback/expired uncertainty needs reconciliation, never new IDs.
        private const val REPLAY_WINDOW_MILLIS = 6L * 24 * 60 * 60 * 1000
        private fun saturatingMultiply(value: Long, factor: Long) = if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor
        private fun saturatingAdd(value: Long, increment: Long) = if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
        private fun fail(reason: FailureReason): Nothing = throw QueueFailure(reason)
    }
    private class QueueFailure(val reason: FailureReason) : Exception("Command queue operation failed")
}
