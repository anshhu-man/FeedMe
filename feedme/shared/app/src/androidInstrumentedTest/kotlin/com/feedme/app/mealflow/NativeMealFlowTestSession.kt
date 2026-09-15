package com.feedme.app.mealflow

import android.content.Context
import android.os.Build
import android.util.Log
import com.feedme.core.ports.*
import com.feedme.session.*
import com.feedme.storage.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Instrumentation-only native session, not a provider or production sign-in entry point.
 * Retain this object BEFORE open. Activity recreation borrows the same object; it never opens
 * another set of native stores. The transport fixture is deliberately supplied by the caller.
 */
internal class NativeMealFlowTestSession(private val context: Context) {
    val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    val boundary = SessionBoundary()
    val clock: EpochClock = EpochClock { 1_850_000_000_000L }
    val scope: StorageScope = StorageScope(ENVIRONMENT, ActorKind.ACCOUNT, ACCOUNT_ID)
    val runtime: PrivateSessionRuntime get() = checkNotNull(runtimeOwner) { "Native test session is not open" }
    /** Fixed non-private label only; never exception details, paths, aliases or credential bytes. */
    val diagnosticStage: String get() = stage.name
    @Volatile private var stage = NativeMealFixtureStage.NEW

    private val mutex = Mutex()
    private var sandbox: NativeMealFlowTestSandbox? = null
    private var root: SessionApplicationComposition? = null
    private var reservation: SessionCompositionReservation? = null
    private var control: EncryptedSessionControlStore? = null
    private var work: EncryptedSessionWorkStore? = null
    private var data: EncryptedStateDatabase? = null
    private var credentials: AndroidCredentialStore? = null
    private var runtimeOwner: PrivateSessionRuntime? = null
    private var attempted = false
    private var created = false
    private var invalidated = false
    private var retired = false
    private var removed = false
    private var acquisitionUncertain = false
    private var selectedAliases: Set<String>? = null
    private val retirementOperation = UUID.randomUUID().toString()

    /** No native I/O occurs until the retained composition root has reserved this lifetime. */
    suspend fun open(): PortResult<Unit> = serialized {
        if (attempted) fail(FailureReason.CONFLICT)
        attempted = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) fail(FailureReason.NOT_CONFIGURED)
        reserveRoot()
        // Factories return their owners rather than exposing retained initialization handles.
        // Assign inside NonCancellable so cancellation cannot lose a successful returned owner.
        // A factory failure is a preserved repair/ownership gate, not cleanup authority.
        withContext(NonCancellable + Dispatchers.IO) {
            sandbox = NativeMealFlowTestSandbox.create(context) { stage = it }
        }
        currentCoroutineContext().ensureActive()
        openNativeStores()
        openRuntime()
        stage = NativeMealFixtureStage.RECOVER
        check(value(runtime.recover()) == PrivateSessionPhase.SIGNED_OUT)
        stage = NativeMealFixtureStage.CREATE
        val access = value(runtime.create())
        check(access.scope == scope && boundary.isCurrent(access.lease))
        withContext(NonCancellable + Dispatchers.IO) {
            stage = NativeMealFixtureStage.INVENTORY
            selectedAliases = checkNotNull(sandbox).selectedInventory()
            created = true
        }
        stage = NativeMealFixtureStage.READY
        currentCoroutineContext().ensureActive()
    }

    /**
     * Controlled TEST runtime/store reopen, not Activity recreation or OS process death.
     * Caller first closes every controller and revokes its synthetic principal generation.
     * Retains only this successfully created sandbox; no new directory, key or credentials.
     */
    suspend fun reopenRetainedForTest(): PortResult<Unit> = serialized {
        if (!created || acquisitionUncertain || retired || removed) fail(FailureReason.STORAGE_FAILURE)
        val original = runtimeOwner?.currentAccess()
        val priorLease = original?.lease
        val priorOrigin = original?.originBinding
        closeOwners()
        check(boundary.current() == null)
        withContext(Dispatchers.IO) {
            checkNotNull(sandbox).requireSelectedInventory(checkNotNull(selectedAliases))
        }
        reserveRoot()
        invalidated = false
        openNativeStores()
        openRuntime()
        stage = NativeMealFixtureStage.RECOVER
        check(value(runtime.recover()) == PrivateSessionPhase.RESTORE_REQUIRED)
        stage = NativeMealFixtureStage.RESTORE
        val restored = value(runtime.restore())
        check(restored.scope == scope && boundary.isCurrent(restored.lease))
        check(priorLease == null || restored.lease !== priorLease)
        check(priorOrigin == null || restored.originBinding == priorOrigin)
        stage = NativeMealFixtureStage.READY
        currentCoroutineContext().ensureActive()
    }

    suspend fun currentAccess(): PrivateSessionAccess? = withContext(dispatcher) {
        mutex.withLock { runtimeOwner?.currentAccess() }
    }

    /** Real synchronous lease/private-observer invalidation on the composition dispatcher. */
    suspend fun invalidate(): PortResult<Unit> = serialized {
        value(checkNotNull(root).invalidate())
        invalidated = true
        check(boundary.current() == null)
    }

    /**
     * Explicit destructive TEST cleanup. If the UI invalidated/closed the root, reopen only this
     * previously successful fixture and synthetically verify its exact stored account before
     * actual runtime retirement. Never infer cleanup consent for incomplete initial composition.
     */
    suspend fun retireAndClose(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        serialized {
            if (removed) return@serialized
            if (!created || acquisitionUncertain) fail(FailureReason.STORAGE_FAILURE)
            if (!retired) {
                if (invalidated || runtimeOwner == null || runtime.currentAccess() == null) {
                    closeOwners()
                    withContext(Dispatchers.IO) {
                        checkNotNull(sandbox).requireSelectedInventory(checkNotNull(selectedAliases))
                    }
                    reserveRoot()
                    invalidated = false
                    openNativeStores()
                    openRuntime()
                    stage = NativeMealFixtureStage.RECOVER
                    check(value(runtime.recover()) == PrivateSessionPhase.RESTORE_REQUIRED)
                    stage = NativeMealFixtureStage.RESTORE
                    value(runtime.restore())
                }
                val access = runtime.currentAccess() ?: fail(FailureReason.STALE_SESSION)
                stage = NativeMealFixtureStage.RETIRE
                val progress = value(runtime.retire(access, retirementOperation))
                if (progress.phase != LocalRetirementPhase.COMPLETE || progress.remaining.isNotEmpty() ||
                    progress.failures.isNotEmpty()) fail(FailureReason.STORAGE_FAILURE)
                check(runtime.currentAccess() == null && boundary.current() == null)
                check(value(checkNotNull(credentials).state()).owner == null)
                val privateOwner = value(checkNotNull(data).inspectOwnerState(scope))
                check(privateOwner.target == null && !privateOwner.hasRecords)
                check(value(runtime.recover()) == PrivateSessionPhase.SIGNED_OUT)
                retired = true
            }
            closeOwners()
            stage = NativeMealFixtureStage.REMOVE_RETIRED_FIXTURE
            withContext(Dispatchers.IO) { checkNotNull(sandbox).removeAfterRetirement() }
            removed = true
            stage = NativeMealFixtureStage.REMOVED
        }
    }

    /** Truthful close only: encrypted state remains until explicit retireAndClose succeeds. */
    suspend fun close(): PortResult<Unit> = withContext(NonCancellable + dispatcher) {
        serialized { closeOwners() }
    }

    private fun reserveRoot() {
        stage = NativeMealFixtureStage.RESERVE
        check(root == null && reservation == null && runtimeOwner == null)
        root = value(SessionApplicationComposition.create(boundary, dispatcher, CONFIGURATION_BINDING))
        reservation = value(checkNotNull(root).reserve())
    }

    private suspend fun openNativeStores() {
        val nativeContext = checkNotNull(sandbox).context
        stage = NativeMealFixtureStage.CONTROL_OPEN
        acquire({ AndroidSessionControlStore.open(nativeContext) }) { control = it }
        stage = NativeMealFixtureStage.WORK_OPEN
        acquire({ AndroidSessionWorkStore.open(nativeContext) }) { work = it }
        stage = NativeMealFixtureStage.DATA_OPEN
        acquire({ AndroidStateDatabase.open(nativeContext) }) { data = it }
        stage = NativeMealFixtureStage.CREDENTIAL_OPEN
        acquire({ AndroidCredentialStore.open(nativeContext) }) { credentials = it }
    }

    private suspend fun <T> acquire(open: suspend () -> PortResult<T>, retain: (T) -> Unit) {
        acquisitionUncertain = true
        withContext(NonCancellable) {
            retain(value(open()))
            acquisitionUncertain = false
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun openRuntime() {
        stage = NativeMealFixtureStage.RUNTIME_OPEN
        acquire({
            PrivateSessionRuntime.openReserved(checkNotNull(reservation), checkNotNull(control),
                checkNotNull(work), checkNotNull(data), checkNotNull(credentials), syntheticVerifier,
                // This host fixture installs no native work. Unexpected effects must fail closed;
                // these callbacks never claim a real AlarmManager/WorkManager cancellation.
                NativeWorkCancellationPort { PortResult.Failure(FailureReason.NOT_CONFIGURED) },
                NativeWorkIdSource { UUID.randomUUID().toString() },
                NativeWorkExecutionPolicy { _, _, _, _ -> PortResult.Failure(FailureReason.NOT_CONFIGURED) })
        }) { runtimeOwner = it }
    }

    private suspend fun closeOwners() {
        // Acknowledge each stage before dropping its only owner. Failed stages remain retryable;
        // no later close, reservation release or fixture deletion can hide that failure.
        runtimeOwner?.let { stage = NativeMealFixtureStage.RUNTIME_CLOSE; value(it.close()); runtimeOwner = null }
        work?.let { stage = NativeMealFixtureStage.WORK_CLOSE; value(it.close()); work = null }
        data?.let { stage = NativeMealFixtureStage.DATA_CLOSE; value(it.close()); data = null }
        credentials?.let { stage = NativeMealFixtureStage.CREDENTIAL_CLOSE; value(it.close()); credentials = null }
        control?.let { stage = NativeMealFixtureStage.CONTROL_CLOSE; value(it.close()); control = null }
        // Ordinary initial factories cannot return their partial owner after a failed open.
        // Preserve the composition reservation in that boundary; do not pretend close succeeded.
        if (acquisitionUncertain) fail(FailureReason.STORAGE_FAILURE)
        reservation?.let { value(it.release()); reservation = null }
        root?.let { value(it.close()); root = null }
    }

    private val syntheticVerifier = object : NativeSessionVerifier {
        override suspend fun acquire(): PortResult<StoredCredentials> = PortResult.Value(syntheticCredentials())
        override suspend fun restore(snapshot: CredentialSnapshot): PortResult<PrivateSessionAccessMode> {
            val candidate = snapshot.credentials as? StoredCredentials.Account
                ?: return PortResult.Failure(FailureReason.UNAUTHENTICATED)
            val matches = candidate.scope == scope && candidate.expiresAtMillis == Long.MAX_VALUE &&
                candidate.accessToken.use { it == SYNTHETIC_ACCESS } &&
                candidate.refreshToken?.use { it == SYNTHETIC_REFRESH } == true &&
                candidate.deviceSessionId?.use { it == DEVICE_SESSION_ID } == true
            return if (matches) PortResult.Value(PrivateSessionAccessMode.ONLINE)
                else PortResult.Failure(FailureReason.UNAUTHENTICATED)
        }
    }

    private suspend fun serialized(action: suspend () -> Unit): PortResult<Unit> = withContext(dispatcher) {
        mutex.withLock {
            try { action(); PortResult.Value(Unit) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: NativeMealFixtureFailure) {
                Log.e("FeedMeNativeMealFixture", "stage=${stage.name};reason=${failure.reason.name}")
                PortResult.Failure(failure.reason)
            }
            catch (_: Exception) {
                Log.e("FeedMeNativeMealFixture", "stage=${stage.name};reason=STORAGE_FAILURE")
                PortResult.Failure(FailureReason.STORAGE_FAILURE)
            }
        }
    }

    override fun toString() = "NativeMealFlowTestSession(<synthetic-test-only>)"

    companion object {
        const val ENVIRONMENT = "synthetic-native-meal-host"
        const val ACCOUNT_ID = "00000000-0000-4000-8000-00000000a101"
        const val DEVICE_SESSION_ID = "00000000-0000-4000-8000-00000000a102"
        private val CONFIGURATION_BINDING = "6".repeat(64)
        private const val SYNTHETIC_ACCESS = "synthetic-meal-host-access-not-a-provider-token"
        private const val SYNTHETIC_REFRESH = "synthetic-meal-host-refresh-not-a-provider-token"

        fun syntheticCredentials(): StoredCredentials.Account = StoredCredentials.Account(
            StorageScope(ENVIRONMENT, ActorKind.ACCOUNT, ACCOUNT_ID), SecretText(SYNTHETIC_ACCESS),
            SecretText(SYNTHETIC_REFRESH), Long.MAX_VALUE, SecretText(DEVICE_SESSION_ID))

        private fun fail(reason: FailureReason): Nothing = throw NativeMealFixtureFailure(reason)
        private fun <T> value(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail(result.reason)
        }
    }
}

private class NativeMealFixtureFailure(val reason: FailureReason) : Exception("Native meal test fixture unavailable")

internal enum class NativeMealFixtureStage {
    NEW, RESERVE, SANDBOX_IDENTITY, SANDBOX_NAMESPACE, SANDBOX_PARENT, SANDBOX_MKDIR,
    CONTROL_OPEN, WORK_OPEN, DATA_OPEN, CREDENTIAL_OPEN, RUNTIME_OPEN, RECOVER, CREATE,
    INVENTORY, READY, RESTORE, RETIRE, RUNTIME_CLOSE, WORK_CLOSE, DATA_CLOSE,
    CREDENTIAL_CLOSE, CONTROL_CLOSE, REMOVE_RETIRED_FIXTURE, REMOVED,
}
