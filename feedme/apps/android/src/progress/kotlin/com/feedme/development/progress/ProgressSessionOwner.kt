@file:OptIn(com.feedme.storage.SessionControlRecoveryCompositionApi::class)

package com.feedme.development.progress

import android.content.Context
import android.content.ComponentName
import android.os.Build
import android.os.Looper
import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.*
import com.feedme.mealflow.timers.*
import com.feedme.kitchen.PrivateKitchenSession
import com.feedme.session.*
import com.feedme.storage.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned real native lifetime. Construction does not open storage or authenticate.
 * Activity disposal never owns this object. All public calls use the serialized UI dispatcher. */
internal class ProgressSessionOwner(private val context: Context,
    private val stageObserver: suspend (ProgressNativeStage) -> Unit = {}) {
    val dispatcher = Dispatchers.Main.immediate
    val boundary = SessionBoundary()
    private val clock = EpochClock { System.currentTimeMillis() }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ProgressHostState())
    val states: StateFlow<ProgressHostState> = mutable.asStateFlow()
    private val visible = MutableStateFlow<MealFlowExperience?>(null)
    val experience: StateFlow<MealFlowExperience?> = visible.asStateFlow()
    private var retainedExperience: MealFlowExperience? = null
    private var reviewedIntegration: ProgressReviewedPostIntegration? = null
    private var service: ProgressCanonicalService? = null
    private var access: PrivateSessionAccess? = null
    private var root: SessionApplicationComposition? = null
    private var reservation: SessionCompositionReservation? = null
    private var probe: ExistingSessionControlRecoveryStore? = null
    private var control: EncryptedSessionControlStore? = null
    private var work: EncryptedSessionWorkStore? = null
    private var data: EncryptedStateDatabase? = null
    private var credentials: AndroidCredentialStore? = null
    private var runtime: PrivateSessionRuntime? = null
    private var timerClock: AndroidProcessCookingTimerClock? = null
    private var timerPolicy: CookingTimerExecutionPolicy? = null
    private var timerAdapter: AndroidForegroundCookingTimerAdapter? = null
    private var timerKitchen: PrivateKitchenSession? = null
    private var timerFacade: SessionCookingTimers? = null
    private var timerAttachment: AndroidTimerForegroundAttachment? = null
    private var foregroundHost: ProgressForegroundHost? = null
    private var hostForeground = false
    private var recovery: SessionSetupRecoveryOwner? = null
    private var recoveryReady = false
    private var preparedAbort: PreparedSessionSetupAbort? = null
    private var invalidation: SessionInvalidationSubscription? = null
    private var consent = ProgressConsentOwner()
    @Volatile private var generation = Any()
    @Volatile private var closing = false
    @Volatile private var operationOwner: Any? = null
    private var activeOperation: Any? = null
    private var acquisitionUncertain = false
    private var expectedInvalidation = false
    private var liveCreate = false
    private var retirementRequested = false
    private var abortAttempted = false
    private var offline = false

    /** Only read-only positive inventory and the retained existing CONTROL classifier. */
    suspend fun inspectStartup(): PortResult<Unit> = operation(setOf(ProgressHostPhase.NEW, ProgressHostPhase.CLOSED)) {
        if (Build.VERSION.SDK_INT < 27) { phase(ProgressHostPhase.UNSUPPORTED); return@operation }
        check(context.packageName == ProgressNativeInventory.PACKAGE)
        root = value(SessionApplicationComposition.create(boundary, dispatcher, ProgressIdentity.configurationBinding))
        reservation = value(checkNotNull(root).reserve())
        phase(ProgressHostPhase.CHECKING)
        when (call { ProgressNativeInventory.inspect(context) }) {
            ProgressInventoryKind.FRESH -> phase(ProgressHostPhase.START_AVAILABLE)
            ProgressInventoryKind.EXISTING -> when (classifyExisting()) {
                SessionStartupControlKind.TERMINAL -> phase(ProgressHostPhase.RESUME_AVAILABLE)
                SessionStartupControlKind.PENDING_SETUP -> phase(ProgressHostPhase.SETUP_RECOVERY)
                SessionStartupControlKind.RECOVERY_REQUIRED -> phase(ProgressHostPhase.RECOVERY_REQUIRED,
                    "Existing control requires a supported explicit recovery path; no normal-open fallback.")
            }
        }
    }

    suspend fun start(): PortResult<Unit> = operation(setOf(ProgressHostPhase.START_AVAILABLE)) {
        if (runtime == null) openOrdinary(fresh = true)
        if (call { runtime!!.recover() } != PrivateSessionPhase.SIGNED_OUT) fail(FailureReason.CONFLICT)
        liveCreate = true
        val current = call { runtime!!.create() }
        liveCreate = false; compose(current, allowInitialize = true)
    }

    /** Existing terminal control is re-inspected before ordinary opens. No inference from errors. */
    suspend fun resume(): PortResult<Unit> = operation(setOf(ProgressHostPhase.RESUME_AVAILABLE)) {
        openOrdinary(fresh = false)
        when (call { runtime!!.recover() }) {
            PrivateSessionPhase.SIGNED_OUT -> phase(ProgressHostPhase.START_AVAILABLE)
            PrivateSessionPhase.RESTORE_REQUIRED -> compose(call { runtime!!.restore() }, allowInitialize = false)
            else -> fail(FailureReason.CONFLICT)
        }
    }

    suspend fun retryCreate(): PortResult<Unit> = operation(setOf(ProgressHostPhase.RECOVERY_REQUIRED)) {
        if (!liveCreate || runtime == null) fail(FailureReason.CONFLICT)
        val current = call { runtime!!.retryCreate() }; liveCreate = false; compose(current, allowInitialize = true)
    }

    private suspend fun openOrdinary(fresh: Boolean) {
        check(control == null && work == null && data == null && credentials == null && runtime == null && recovery == null)
        val inventory = call { ProgressNativeInventory.inspect(context) }
        if (fresh) {
            if (inventory != ProgressInventoryKind.FRESH) fail(FailureReason.CONFLICT)
        } else if (inventory != ProgressInventoryKind.EXISTING || classifyExisting() != SessionStartupControlKind.TERMINAL)
            fail(FailureReason.CONFLICT)
        acquire({ AndroidSessionControlStore.open(context) }) { control = it }
        stage(ProgressNativeStage.CONTROL_ACQUIRED)
        // Recheck current terminal control before opening any ordinary subordinate store.
        if (call { SessionStartupControlInspector.inspect(control!!) } != SessionStartupControlKind.TERMINAL)
            fail(FailureReason.CONFLICT)
        acquire({ AndroidSessionWorkStore.open(context) }) { work = it }
        stage(ProgressNativeStage.WORK_ACQUIRED)
        acquire({ AndroidStateDatabase.open(context) }) { data = it }
        stage(ProgressNativeStage.DATA_ACQUIRED)
        acquire({ AndroidCredentialStore.open(context) }) { credentials = it }
        stage(ProgressNativeStage.CREDENTIALS_ACQUIRED)
        // Native cancellation is constructed only after positive startup routing and retained
        // reservation/stores. This concrete timer-only delegate performs real exact OS cleanup;
        // it neither initializes WorkManager nor accepts worker cancellation as a no-op.
        timerClock = AndroidProcessCookingTimerClock()
        timerPolicy = CookingTimerExecutionPolicy()
        val cancellation = value(AndroidNativeWorkCancellation.createTimerOnly(context,
            ComponentName(context, ProgressTimerCancellationReceiver::class.java)))
        timerAdapter = value(AndroidForegroundCookingTimerAdapter.create(cancellation, timerClock!!))
        acquire({ PrivateSessionRuntime.openReserved(checkNotNull(reservation), control!!, work!!, data!!, credentials!!,
            ProgressIdentity, timerAdapter!!, NativeWorkIdSource { UUID.randomUUID().toString() }, timerPolicy!!) }) { runtime = it }
        stage(ProgressNativeStage.RUNTIME_ACQUIRED)
    }

    private suspend fun classifyExisting(): SessionStartupControlKind {
        check(probe == null && runtime == null && recovery == null)
        probe = AndroidSessionControlStore.createRecoveryStore(context) // Retain before open.
        call { probe!!.open() }
        val result = call { SessionStartupControlInspector.inspect(probe!!) }
        stage(ProgressNativeStage.BEFORE_PROBE_CLOSE)
        call { probe!!.close() }; probe = null // Never drop a failed-close owner.
        return result
    }

    private suspend fun compose(current: PrivateSessionAccess, allowInitialize: Boolean) {
        if (current.scope != ProgressIdentity.scope || !boundary.isCurrent(current.lease) ||
            call { runtime!!.currentAccess().let { PortResult.Value(it) } } !== current) fail(FailureReason.STALE_SESSION)
        access = current
        invalidation = boundary.onInvalidated(current.lease) {
            fencePosts() // Backstop; owner-initiated changes revoke before native invalidation.
            fenceTimers()
            visible.value = null; consent.dismiss(); preparedAbort = null
            if (!expectedInvalidation) {
                generation = Any(); closing = true
                mutable.value = mutable.value.copy(phase = ProgressHostPhase.CLOSE_ONLY, confirmation = null,
                    failure = FailureReason.STALE_SESSION, detail = "Session changed. Close the retained owner before continuing.")
            }
        }
        service = ProgressCanonicalService(current, boundary, dispatcher, clock)
        call { service!!.open(allowInitialize) }
        // This repository facade uses the same native store/origin and existing durable journal,
        // not another queue namespace or a reconstructed session. All optional write hooks deny.
        timerKitchen = PrivateKitchenSession(current.scope, current.store, boundary, dispatcher,
            clock, service!!, current.originBinding)
        acquire({ timerAdapter!!.bindForUi(runtime!!, current, timerKitchen!!, boundary, timerPolicy!!) { due ->
            retainedExperience?.onTimerDue(due) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
        } }) { timerFacade = it }
        val actualService = checkNotNull(service)
        val actualRuntime = checkNotNull(runtime)
        val connectivity = ConnectivityPort { if (offline) Connectivity.OFFLINE else Connectivity.ONLINE }
        val ids = MealOperationIds { UUID.randomUUID().toString() }
        val mealPolicy = MealFlowPolicy(2_592_000_000, 518_400_000, 10, 512)
        val ingredientPolicy = IngredientPickerPolicy(20, 3, 128, 86_400_000)
        val kitchenPolicy = KitchenInputPolicy(20, 3, 128, 32, ProgressCatalog.maxResponseBytes)
        val cookingPolicy = CookingFlowPolicy(300_000, ProgressCatalog.maxResponseBytes, 131_072)
        val cookbookPolicy = CookbookPolicy(50, 60_000)
        retainedExperience = if (actualService.socialAvailable) {
            check(reviewedIntegration == null)
            // The factory wraps native access ONCE. Retain the integration over that exact
            // wrapper before creating children; never construct a second accepting access.
            MealFlowExperience.fromSessionWithReviewedPosts(current, actualService, boundary, dispatcher, clock,
                connectivity, ids, mealPolicy, ingredientPolicy, ProgressCatalog.choices, kitchenPolicy,
                cookingPolicy, cookbookPolicy, ProgressReviewedPostPolicies.drafts, ProgressReviewedPostPolicies.publications,
                integrationForAccess = { actual ->
                    check(reviewedIntegration == null)
                    ProgressReviewedPostIntegration(current, actual, boundary, actualService) {
                        !closing && access === current && service === actualService && runtime === actualRuntime
                    }.also { reviewedIntegration = it }
                })
        } else {
            // Resume with an absent old social ledger keeps cooking available. It never
            // initializes that ledger, upgrades legacy drafts, resets identity or forces setup.
            MealFlowExperience.fromSession(current, actualService, boundary, dispatcher, clock,
                connectivity, ids, mealPolicy, ingredientPolicy, ProgressCatalog.choices, kitchenPolicy,
                cookingPolicy, cookbookPolicy)
        }
        call { retainedExperience!!.attachTimers(current, timerFacade!!, MealOperationIds { UUID.randomUUID().toString() }) }
        checkCurrent()
        value(bindForegroundIfReady())
        visible.value = retainedExperience
        phase(ProgressHostPhase.ACTIVE)
    }

    suspend fun openSetupRecovery(): PortResult<Unit> = operation(setOf(ProgressHostPhase.SETUP_RECOVERY)) {
        if (recovery != null) fail(FailureReason.CONFLICT)
        if (call { ProgressNativeInventory.inspect(context) } != ProgressInventoryKind.EXISTING ||
            classifyExisting() != SessionStartupControlKind.PENDING_SETUP) fail(FailureReason.CONFLICT)
        recovery = AndroidSessionSetupRecovery.createOwner(context, checkNotNull(reservation))
        call { recovery!!.open() }
        recoveryReady = true
        val report = call { recovery!!.inspect() }
        abortAttempted = report.finding == InterruptedSetupFinding.ABORT_REQUESTED
        phase(ProgressHostPhase.SETUP_RECOVERY, report.finding.name)
    }

    suspend fun prepareSetupAbort(): PortResult<Unit> = operation(setOf(ProgressHostPhase.SETUP_RECOVERY)) {
        val owner = recovery ?: fail(FailureReason.CONFLICT)
        preparedAbort = call { owner.prepareAbort() }
        mutable.value = mutable.value.copy(confirmation = consent.prepare(ProgressConfirmationKind.ABORT_SETUP))
    }

    suspend fun confirmSetupAbort(ticket: ProgressConfirmation): PortResult<Unit> = operation(setOf(ProgressHostPhase.SETUP_RECOVERY)) {
        val owner = recovery ?: fail(FailureReason.CONFLICT)
        val prepared = preparedAbort ?: fail(FailureReason.CONFLICT)
        if (ticket.kind != ProgressConfirmationKind.ABORT_SETUP || !consent.consume(ticket)) fail(FailureReason.CONFLICT)
        preparedAbort = null; mutable.value = mutable.value.copy(confirmation = null); abortAttempted = true
        call { owner.confirmAbort(prepared) }
        closeOwners(); phase(ProgressHostPhase.CLOSED)
    }

    suspend fun retrySetupAbort(): PortResult<Unit> = operation(setOf(ProgressHostPhase.SETUP_RECOVERY, ProgressHostPhase.RECOVERY_REQUIRED)) {
        val owner = recovery ?: fail(FailureReason.CONFLICT)
        if (!abortAttempted) fail(FailureReason.CONFLICT)
        call { owner.retryAbort() }; closeOwners(); phase(ProgressHostPhase.CLOSED)
    }

    fun requestReset() {
        if (!closing && mutable.value.phase in setOf(ProgressHostPhase.ACTIVE, ProgressHostPhase.RECOVERY_REQUIRED) && !mutable.value.busy &&
            !retirementRequested && runtime != null &&
            access?.let { boundary.isCurrent(it.lease) } == true)
            mutable.value = mutable.value.copy(confirmation = consent.prepare(ProgressConfirmationKind.RESET))
    }
    fun dismissConfirmation() {
        consent.dismiss(); preparedAbort = null; mutable.value = mutable.value.copy(confirmation = null)
    }
    fun setServiceOffline(value: Boolean) {
        if (!closing && mutable.value.phase == ProgressHostPhase.ACTIVE) {
            offline = value; mutable.value = mutable.value.copy(serviceOffline = value)
        }
    }

    /** Development harness only. The client learns withdrawal from subsequent canonical replies,
     * not this return value. No UI consent, client recall, queue or cooking state is modified. */
    suspend fun withdrawSyntheticRecipe(recipeVersionId: String): PortResult<Unit> = operation(setOf(ProgressHostPhase.ACTIVE)) {
        if (offline) fail(FailureReason.OFFLINE)
        val current = access ?: fail(FailureReason.STALE_SESSION)
        val configured = service ?: fail(FailureReason.NOT_CONFIGURED)
        call { configured.withdrawSyntheticRecipe(current.lease, recipeVersionId) }
    }

    /** Activity identity, not a native ticket. Attachment before explicit preview startup records
     * lifecycle only: no storage, session, callback or timer is created by this method.
     */
    fun attachForegroundHost(): ProgressForegroundHost {
        requireMain()
        val host = ProgressForegroundHost()
        foregroundHost = host; hostForeground = false
        if (timerFacade != null && !closing) {
            when (val result = timerAdapter!!.attachForeground()) {
                is PortResult.Value -> timerAttachment = result.value
                is PortResult.Failure -> fenceTimers()
            }
        }
        return host
    }

    fun setForegroundHost(host: ProgressForegroundHost, foreground: Boolean): PortResult<Unit> {
        requireMain()
        if (foregroundHost !== host) return PortResult.Failure(FailureReason.STALE_SESSION)
        hostForeground = foreground
        val attachment = timerAttachment ?: return PortResult.Value(Unit) // lifecycle only, not alert acknowledgement
        return timerAdapter?.setForeground(attachment, foreground) ?: PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }

    fun detachForegroundHost(host: ProgressForegroundHost): PortResult<Unit> {
        requireMain()
        if (foregroundHost !== host) return PortResult.Failure(FailureReason.STALE_SESSION)
        hostForeground = false
        val result = timerAttachment?.let { timerAdapter?.detachForeground(it) }
            ?: PortResult.Value(Unit)
        if (result is PortResult.Value) { foregroundHost = null; timerAttachment = null }
        return result
    }

    private fun bindForegroundIfReady(): PortResult<Unit> {
        requireMain()
        if (foregroundHost == null) return PortResult.Value(Unit)
        val adapter = timerAdapter ?: return PortResult.Failure(FailureReason.NOT_CONFIGURED)
        return when (val result = adapter.attachForeground()) {
            is PortResult.Failure -> result
            is PortResult.Value -> { timerAttachment = result.value; adapter.setForeground(result.value, hostForeground) }
        }
    }
    private fun fenceTimers() { requireMain(); timerAdapter?.close() }
    private fun fencePosts() { requireMain(); reviewedIntegration?.revokeBeforeOwnerChange() }
    private fun requireMain() { check(Looper.myLooper() === Looper.getMainLooper()) { "Progress lifecycle requires Main" } }

    suspend fun confirmReset(ticket: ProgressConfirmation): PortResult<Unit> = operation(setOf(ProgressHostPhase.ACTIVE, ProgressHostPhase.RECOVERY_REQUIRED)) {
        val current = access ?: fail(FailureReason.CONFLICT)
        if (ticket.kind != ProgressConfirmationKind.RESET || !consent.consume(ticket) || !boundary.isCurrent(current.lease))
            fail(FailureReason.CONFLICT)
        fencePosts()
        mutable.value = mutable.value.copy(confirmation = null); visible.value = null
        fenceTimers()
        retainedExperience?.let { call { it.close() }; retainedExperience = null }
        reviewedIntegration?.let { it.close(); reviewedIntegration = null }
        service?.let { call { it.close() }; service = null }
        retirementRequested = true; expectedInvalidation = true
        val progress = call { runtime!!.retire(current, UUID.randomUUID().toString()) }
        if (progress.phase != LocalRetirementPhase.COMPLETE || progress.remaining.isNotEmpty() || progress.failures.isNotEmpty())
            fail(FailureReason.STORAGE_FAILURE)
        retirementRequested = false; closeOwners(); phase(ProgressHostPhase.CLOSED)
    }

    suspend fun retryRetirement(): PortResult<Unit> = operation(setOf(ProgressHostPhase.RECOVERY_REQUIRED)) {
        if (!retirementRequested || runtime == null) fail(FailureReason.CONFLICT)
        fencePosts()
        expectedInvalidation = true
        if (call { runtime!!.recover() } != PrivateSessionPhase.SIGNED_OUT) fail(FailureReason.CONFLICT)
        retirementRequested = false; closeOwners(); phase(ProgressHostPhase.CLOSED)
    }

    /** Immediate lifecycle fence, then truthful ordered release. No retirement or directory erase. */
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        fencePosts()
        fenceTimers()
        closing = true; generation = Any(); operationOwner = Any(); consent.close(); preparedAbort = null; visible.value = null
        expectedInvalidation = true
        root?.let { owner ->
            when (val invalidated = owner.invalidate()) {
                is PortResult.Value -> Unit
                is PortResult.Failure -> {
                    phase(ProgressHostPhase.CLOSE_ONLY, "Composition invalidation was not acknowledged; ownership is retained.")
                    mutable.value = mutable.value.copy(failure = invalidated.reason)
                    return@withContext invalidated
                }
            }
        }
        phase(ProgressHostPhase.CLOSING)
        mutex.withLock {
            try { closeOwners(); phase(ProgressHostPhase.CLOSED); PortResult.Value(Unit) }
            catch (_: Exception) {
                phase(ProgressHostPhase.CLOSE_ONLY, "A native owner is still retained; retry close. No repair or reset was inferred.")
                mutable.value = mutable.value.copy(failure = FailureReason.STORAGE_FAILURE)
                PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
        }
    }

    private suspend fun closeOwners() = withContext(NonCancellable + dispatcher) {
        fencePosts()
        expectedInvalidation = true
        fenceTimers()
        retainedExperience?.let { value(it.close()); retainedExperience = null }
        reviewedIntegration?.let { it.close(); reviewedIntegration = null }
        service?.let { value(it.close()); service = null }
        runtime?.let { stageObserver(ProgressNativeStage.BEFORE_RUNTIME_CLOSE); value(it.close()); runtime = null }
        recovery?.let { stageObserver(ProgressNativeStage.BEFORE_RECOVERY_CLOSE); value(it.close()); recovery = null; recoveryReady = false }
        probe?.let { stageObserver(ProgressNativeStage.BEFORE_PROBE_CLOSE); value(it.close()); probe = null }
        work?.let { stageObserver(ProgressNativeStage.BEFORE_WORK_CLOSE); value(it.close()); work = null }
        data?.let { stageObserver(ProgressNativeStage.BEFORE_DATA_CLOSE); value(it.close()); data = null }
        credentials?.let { stageObserver(ProgressNativeStage.BEFORE_CREDENTIALS_CLOSE); value(it.close()); credentials = null }
        control?.let { stageObserver(ProgressNativeStage.BEFORE_CONTROL_CLOSE); value(it.close()); control = null }
        if (acquisitionUncertain) fail(FailureReason.STORAGE_FAILURE)
        invalidation?.close(); invalidation = null; access = null
        reservation?.let { stageObserver(ProgressNativeStage.BEFORE_RESERVATION_RELEASE); value(it.release()); reservation = null }
        root?.let { value(it.close()); root = null }
        timerFacade = null; timerKitchen = null; timerAdapter = null; timerPolicy = null; timerClock = null; timerAttachment = null
        visible.value = null; consent.close(); preparedAbort = null
    }

    private suspend fun <T> acquire(open: suspend () -> PortResult<T>, retain: (T) -> Unit) {
        checkCurrent(); acquisitionUncertain = true
        withContext(NonCancellable) { retain(value(open())); acquisitionUncertain = false }
        checkCurrent()
    }
    private suspend fun <T> call(block: suspend () -> PortResult<T>): T {
        checkCurrent(); val result = block(); checkCurrent(); return value(result)
    }
    private suspend fun stage(value: ProgressNativeStage) { checkCurrent(); stageObserver(value); checkCurrent() }
    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (closing || activeOperation !== generation) fail(FailureReason.STALE_SESSION)
    }
    private fun phase(value: ProgressHostPhase, detail: String? = null) {
        mutable.value = mutable.value.copy(phase = value, detail = detail, recoveryOpen = recovery != null,
            canRetryAbort = abortAttempted && recovery != null, canRetryCreate = liveCreate,
            canRetryRetirement = retirementRequested,
            canReset = !closing && !retirementRequested && runtime != null &&
                access?.let { boundary.isCurrent(it.lease) } == true)
    }

    private suspend fun operation(allowed: Set<ProgressHostPhase>, block: suspend () -> Unit): PortResult<Unit> {
        var admitted: Any? = null
        var admittedOwner: Any? = null
        try {
            val result = withContext(dispatcher) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (mutable.value.phase !in allowed || (closing && mutable.value.phase != ProgressHostPhase.CLOSED))
                        return@withLock PortResult.Failure(FailureReason.CONFLICT)
                    if (mutable.value.phase in setOf(ProgressHostPhase.NEW, ProgressHostPhase.CLOSED)) {
                        check(root == null && reservation == null && !acquisitionUncertain)
                        closing = false; expectedInvalidation = false; generation = Any(); consent = ProgressConsentOwner()
                        liveCreate = false; retirementRequested = false; abortAttempted = false
                    }
                    val token = generation; admitted = token; activeOperation = token
                    admittedOwner = Any().also { operationOwner = it }
                    mutable.value = mutable.value.copy(busy = true, failure = null)
                    try { block(); checkCurrent(); PortResult.Value(Unit) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: ProgressFailure) { recordFailure(failure.reason); PortResult.Failure(failure.reason) }
                    catch (_: Exception) { recordFailure(FailureReason.STORAGE_FAILURE); PortResult.Failure(FailureReason.STORAGE_FAILURE) }
                    finally { if (activeOperation === token) { activeOperation = null; mutable.value = mutable.value.copy(busy = false) } }
                }
            }
            currentCoroutineContext().ensureActive()
            return if (admitted != null && (closing || admitted !== generation)) PortResult.Failure(FailureReason.STALE_SESSION) else result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + dispatcher) {
                if (admitted != null && admitted === generation && admittedOwner === operationOwner) {
                    fencePosts()
                    closing = true; generation = Any(); visible.value = null; consent.close(); preparedAbort = null
                    expectedInvalidation = true; root?.invalidate()
                    phase(ProgressHostPhase.CLOSE_ONLY, "Cancelled operation: native ownership is retained. Close before continuing.")
                    mutable.value = mutable.value.copy(busy = false)
                }
            }
            throw cancelled
        }
    }
    private fun recordFailure(reason: FailureReason) {
        fencePosts()
        visible.value = null
        val closeOnly = acquisitionUncertain || probe != null ||
            (recovery != null && !recoveryReady) ||
            (runtime == null && (control != null || work != null || data != null || credentials != null))
        phase(if (closeOnly) ProgressHostPhase.CLOSE_ONLY else ProgressHostPhase.RECOVERY_REQUIRED,
            "No success, new identity, cleanup or ordinary-open fallback was inferred.")
        mutable.value = mutable.value.copy(failure = reason, confirmation = null)
        consent.dismiss(); preparedAbort = null
    }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> throw ProgressFailure(result.reason)
    }
    private fun fail(reason: FailureReason): Nothing = throw ProgressFailure(reason)
    private class ProgressFailure(val reason: FailureReason) : Exception("Progress operation unavailable")
    override fun toString() = "ProgressSessionOwner(<synthetic-development-only>)"
}
