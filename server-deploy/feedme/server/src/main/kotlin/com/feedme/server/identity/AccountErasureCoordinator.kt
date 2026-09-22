package com.feedme.server.identity

import com.feedme.server.db.PgTransactions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

internal enum class AccountErasureCoordinatorState { NEW, STARTING, RUNNING, STOPPING, STOPPED, FAILED }

/** Fixed stage observations only: no account IDs, lease tokens, receipt or completion status. */
internal data class AccountErasureCoordinatorSnapshot(
    val state: AccountErasureCoordinatorState,
    val core: AccountErasureCoreRun? = null,
    val provider: AccountProviderErasureRun? = null,
)

internal class AccountErasureCoordinatorFailure : IllegalStateException("Account erasure coordinator unavailable")

/** Scheduling seam for focused lifecycle tests. Production construction below always uses
 * the actual compatibility checks and existing durable worker implementations. */
internal interface AccountErasureCoordinatorSteps {
    suspend fun checkCompatibility()
    suspend fun runCore(): AccountErasureCoreRun
    suspend fun runProvider(): AccountProviderErasureRun?
}

/** Explicitly started, single serial worker lifetime. Owns its scope/job only; the caller
 * retains the DataSource, DB/control dispatchers, authority and optional provider client.
 * Close/join this coordinator BEFORE retiring those borrowed dependencies.
 *
 * One core claim/purge/defer and one optional provider step run per cadence. No HTTP occurs
 * inside a database transaction. Any unexpected failure (including an unknown commit)
 * stops this lifetime; a new explicitly constructed coordinator can recover durable leases.
 * There is no automatic provider retry adapter, media scan, route/launcher registration,
 * credential loading, retention override or whole-account completion transition.
 */
internal class AccountErasureCoordinator private constructor(
    private val steps: AccountErasureCoordinatorSteps,
    controlDispatcher: CoroutineDispatcher,
    private val intervalMillis: Long,
) : AutoCloseable {
    private val monitor = Any()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + controlDispatcher + CoroutineName("feedme-account-erasure"))
    private val ready = CompletableDeferred<Boolean>()
    private var observation = AccountErasureCoordinatorSnapshot(AccountErasureCoordinatorState.NEW)
    private var task: Deferred<Unit>? = null
    private var stopRequested = false
    private var terminalFailure: Throwable? = null

    init { require(intervalMillis in 10..60_000) }

    fun snapshot(): AccountErasureCoordinatorSnapshot = synchronized(monitor) { observation }

    /** Compatibility is read-only and completes before the first claim. Awaiting startup
     * is not a readiness/retention attestation and does not activate a public handler. */
    suspend fun start() {
        currentCoroutineContext().ensureActive()
        val started = synchronized(monitor) {
            check(observation.state == AccountErasureCoordinatorState.NEW) { "Account erasure coordinator already started or closed" }
            observation = observation.copy(state = AccountErasureCoordinatorState.STARTING)
            scope.async(start = CoroutineStart.LAZY) { runLoop() }.also { selected ->
                task = selected
                selected.invokeOnCompletion {
                    synchronized(monitor) {
                        if (observation.state != AccountErasureCoordinatorState.FAILED) {
                            observation = observation.copy(state = if (stopRequested)
                                AccountErasureCoordinatorState.STOPPED else AccountErasureCoordinatorState.FAILED)
                            if (!stopRequested) terminalFailure = AccountErasureCoordinatorFailure()
                        }
                    }
                    ready.complete(false)
                    owner.cancel()
                }
            }
        }
        started.start()
        val didStart = try {
            ready.await().also { currentCoroutineContext().ensureActive() }
        } catch (failure: CancellationException) {
            requestStop()
            throw failure
        }
        if (!didStart) throw synchronized(monitor) { terminalFailure }
            ?: CancellationException("Account erasure coordinator stopped before readiness")
    }

    private suspend fun runLoop() {
        try {
            steps.checkCompatibility()
            currentCoroutineContext().ensureActive()
            synchronized(monitor) {
                if (stopRequested) throw CancellationException("Account erasure coordinator stopped")
                observation = observation.copy(state = AccountErasureCoordinatorState.RUNNING)
            }
            ready.complete(true)
            while (true) {
                currentCoroutineContext().ensureActive()
                val core = steps.runCore()
                synchronized(monitor) {
                    if (!stopRequested) observation = observation.copy(core = core)
                }
                currentCoroutineContext().ensureActive()
                val provider = steps.runProvider()
                synchronized(monitor) {
                    if (!stopRequested) observation = observation.copy(provider = provider)
                }
                delay(intervalMillis)
            }
        } catch (failure: Throwable) {
            val outgoing = when (failure) {
                is CancellationException, is InterruptedException, is Error -> failure
                else -> AccountErasureCoordinatorFailure()
            }
            synchronized(monitor) {
                val expectedStop = failure is CancellationException && stopRequested
                if (!expectedStop) {
                    terminalFailure = outgoing
                    observation = observation.copy(state = AccountErasureCoordinatorState.FAILED)
                }
            }
            // Deferred retains the terminal result without an uncaught-exception logger.
            // SQL/provider errors and their sensitive messages/causes are never attached.
            // Only values cross readiness/join boundaries: Deferred.await stack recovery
            // would copy control exceptions and attach causes to fixed redacted failures.
            ready.complete(false)
            throw outgoing
        }
    }

    private fun requestStop(): Deferred<Unit>? = synchronized(monitor) {
        stopRequested = true
        if (observation.state == AccountErasureCoordinatorState.NEW) {
            observation = observation.copy(state = AccountErasureCoordinatorState.STOPPED)
            owner.cancel()
        } else if (observation.state !in setOf(AccountErasureCoordinatorState.STOPPED, AccountErasureCoordinatorState.FAILED)) {
            observation = observation.copy(state = AccountErasureCoordinatorState.STOPPING)
        }
        ready.complete(false)
        task?.also { it.cancel(CancellationException("Account erasure coordinator stopping")) }
    }

    /** A false return means the task has NOT joined; no rollback or stopped claim follows.
     * Unexpected terminal failures are rethrown (ordinary failures are already redacted).
     * Caller cancellation/interruption is never converted into a successful shutdown. */
    suspend fun stopAndJoin(timeoutMillis: Long = 5_000): Boolean {
        require(timeoutMillis in 1..60_000)
        val selected = requestStop()
        currentCoroutineContext().ensureActive()
        if (selected == null) return true
        if (withTimeoutOrNull(timeoutMillis) { selected.join(); true } != true) return false
        currentCoroutineContext().ensureActive()
        synchronized(monitor) { terminalFailure }?.let { throw it }
        return true
    }

    /** Call from the runtime owner, not from this worker's dispatcher thread. */
    override fun close() {
        try {
            if (!runBlocking { stopAndJoin() }) throw AccountErasureCoordinatorFailure()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
    }

    override fun toString() = "AccountErasureCoordinator(<redacted>)"

    companion object {
        fun create(environment: String, transactions: PgTransactions, authority: SupabasePostgresAuthority,
            databaseDispatcher: CoroutineDispatcher, authClient: SupabaseAuthErasureClient? = null,
            intervalMillis: Long = 1_000, controlDispatcher: CoroutineDispatcher = Dispatchers.Default): AccountErasureCoordinator {
            require(authority.deployment.verification.issuer == SupabaseAuthErasureClient.APPROVED_ISSUER)
            val work = AccountErasureWorkStore(environment, transactions)
            val core = AccountErasureCoreStore(environment, transactions)
            val coreWorker = AccountErasureCoreWorker(work, core)
            val provider = authClient?.let { AccountProviderErasureStore(environment, transactions, authority) }
            val providerWorker = provider?.let { AccountProviderErasureWorker(it, checkNotNull(authClient), databaseDispatcher) }
            return AccountErasureCoordinator(object : AccountErasureCoordinatorSteps {
                override suspend fun checkCompatibility() {
                    if (authClient != null && authClient.configuredIssuer != SupabaseAuthErasureClient.APPROVED_ISSUER)
                        throw AccountErasureCoordinatorFailure()
                    runInterruptible(databaseDispatcher) { transactions.run { c ->
                        authority.checkCompatibility(c)
                        core.checkCompatibility(c)
                        provider?.checkCompatibility(c)
                    } }
                }
                override suspend fun runCore() = runInterruptible(databaseDispatcher) { coreWorker.runOne() }
                override suspend fun runProvider() = providerWorker?.runOne()
            }, controlDispatcher, intervalMillis)
        }

        internal fun forLifecycleTest(steps: AccountErasureCoordinatorSteps, controlDispatcher: CoroutineDispatcher,
            intervalMillis: Long = 1_000) = AccountErasureCoordinator(steps, controlDispatcher, intervalMillis)
    }
}
