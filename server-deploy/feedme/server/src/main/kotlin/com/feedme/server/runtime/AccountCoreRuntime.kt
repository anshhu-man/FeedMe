package com.feedme.server.runtime

import com.feedme.server.config.AccountCoreRuntimeConfig
import com.feedme.server.config.DependencyHoldConfig
import com.feedme.server.contract.ContractCatalog
import com.feedme.server.http.*
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.BindException
import java.time.Clock
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Explicit account core runtime: account/preferences/ingredients/pantry/planning/cooking/
 * Saved. One listener and provider lifetime, bounded DB executor and admission, no migrations,
 * data seeding, eligibility grants or implicit deployment. Container binding does not supply
 * HTTPS termination. Missing content may bind for diagnosis, but dependency health is 503.
 */
class AccountCoreRuntime private constructor(
    val port: Int,
    private val resources: AccountCoreRuntimeResources,
    private val stopped: CountDownLatch,
    private val diagnostics: BoundedHttpObservationSink,
) : AutoCloseable {
    fun awaitTermination() = stopped.await()
    fun diagnosticSnapshot(): HttpDiagnosticSnapshot = diagnostics.snapshot()
    override fun close() = resources.close()
    override fun toString() = "AccountCoreRuntime(<redacted>)"

    companion object {
        /** Real dependencies, closed public ingress. No full account config or product store
         * is constructed. Healthy dependencies still report canonical launch-not-ready 503. */
        internal fun startHeld(config: DependencyHoldConfig, database: DataSource = config.dataSource(),
            clock: Clock = Clock.systemUTC(),
            onStartupStage: (DependencyHoldStartupStage) -> Unit = {}): AccountCoreRuntime {
            val catalog = ContractCatalog.bundled()
            val executor = Executors.newFixedThreadPool(config.databaseParallelism) { task ->
                Thread(task, "feedme-held-db").apply { isDaemon = true }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            val lifecycle = ServiceLifecycle()
            val stopped = CountDownLatch(1)
            val resources = AccountCoreRuntimeResources(executor, lifecycle, { dispatcher.close() }, stopped)
            try {
                val dependencies = DependencyHoldProbe.open(config, database, dispatcher, clock)
                resources.assembly = dependencies
                // Prove actual dependencies before binding; no fallback to an unconfigured server.
                check(runBlocking { dependencies.available(onStartupStage) })
                onStartupStage(DependencyHoldStartupStage.LISTENER)
                val diagnostics = createRuntimeHttpDiagnostics()
                resources.diagnostics = diagnostics
                val listener = config.listener
                val server = embeddedServer(CIO, host = listener.host, port = listener.port) {
                    feedMeLocalService(listener.service, catalog, clock = clock, lifecycle = lifecycle,
                        observationSink = diagnostics, healthMode = ServiceHealthMode.UNCONFIGURED_READINESS,
                        dependencyHold = { dependencies.available() })
                    monitor.subscribe(ApplicationStopped) { diagnostics.close(); stopped.countDown() }
                }
                resources.stopListener = { server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000) }
                startAccountCoreListener {
                    server.start(wait = false)
                    runBlocking { withTimeout(10_000) {
                        val bound = server.engine.resolvedConnectors().single()
                        check(bound.host == listener.host && bound.port == listener.port)
                    } }
                }
                return AccountCoreRuntime(listener.port, resources, stopped, diagnostics)
            } catch (failure: Throwable) {
                val cleanup = try { resources.close(); null } catch (problem: Throwable) { problem }
                rethrowAccountCoreFailure(failure, cleanup)
            }
        }

        fun start(config: AccountCoreRuntimeConfig, database: DataSource = config.dataSource(),
            clock: Clock = Clock.systemUTC()): AccountCoreRuntime {
            val catalog = ContractCatalog.bundled()
            val number = AtomicInteger()
            val executor = Executors.newFixedThreadPool(config.databaseParallelism) { task ->
                Thread(task, "feedme-account-db-${number.incrementAndGet()}").apply { isDaemon = true }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            val lifecycle = ServiceLifecycle()
            val stopped = CountDownLatch(1)
            val resources = AccountCoreRuntimeResources(executor, lifecycle, { dispatcher.close() }, stopped)
            try {
                val assembly = ConfiguredSupabaseAccountCoreAssembly.open(config, database, dispatcher, clock)
                resources.assembly = assembly
                val diagnostics = createRuntimeHttpDiagnostics()
                resources.diagnostics = diagnostics
                val listener = config.listener
                val server = embeddedServer(CIO, host = listener.host, port = listener.port) {
                    feedMeLocalService(listener.service, catalog, clock = clock, lifecycle = lifecycle,
                        observationSink = diagnostics, healthMode = ServiceHealthMode.ACCOUNT_CORE_DEPENDENCIES,
                        accountCoreHealth = assembly.dependencyHealth, account = assembly.account,
                        accountPreferences = assembly.preferences, accountPantry = assembly.pantry,
                        accountPlanning = assembly.planning, accountCooking = assembly.cooking, accountSaved = assembly.saved)
                    monitor.subscribe(ApplicationStopped) { diagnostics.close(); stopped.countDown() }
                }
                resources.stopListener = { server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000) }
                startAccountCoreListener {
                    server.start(wait = false)
                    runBlocking { withTimeout(10_000) {
                        val bound = server.engine.resolvedConnectors().single()
                        check(bound.host == listener.host && bound.port == listener.port)
                    } }
                }
                return AccountCoreRuntime(listener.port, resources, stopped, diagnostics)
            } catch (failure: Throwable) {
                val cleanup = try { resources.close(); null } catch (problem: Throwable) { problem }
                rethrowAccountCoreFailure(failure, cleanup)
            }
        }
    }
}

/** Only a positively identified CIO bind failure is sanitized from its cancellation wrapper.
 * No message matching, retry on another port, unbounded/cyclic cause traversal or conversion
 * of caller cancellation into an ordinary startup result. */
internal fun startAccountCoreListener(action: () -> Unit) {
    try { action() } catch (failure: CancellationException) {
        if (Thread.currentThread().isInterrupted) throw failure
        val seen = IdentityHashMap<Throwable, Boolean>()
        var cause: Throwable? = failure
        repeat(32) {
            val current = cause ?: throw failure
            if (seen.put(current, true) != null) throw failure
            if (current is BindException) throw AccountCoreRuntimeFailure()
            if (current is InterruptedException || current is Error) throw failure
            cause = current.cause
        }
        throw failure
    }
}

internal fun rethrowAccountCoreFailure(vararg failures: Throwable?): Nothing {
    val present = failures.filterNotNull()
    if (present.any { it is InterruptedException }) Thread.currentThread().interrupt()
    (present.firstOrNull { it is Error } ?: present.firstOrNull { it is CancellationException } ?:
        present.firstOrNull { it is InterruptedException })?.let { throw it }
    throw AccountCoreRuntimeFailure()
}
