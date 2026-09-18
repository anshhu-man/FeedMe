package com.feedme.server.runtime

import com.feedme.server.auth.SupabaseJwksHttpPolicy
import com.feedme.server.catalog.IngredientCatalogStore
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.http.ConfiguredSupabaseAccountPantryAssembly
import com.feedme.server.http.BoundedHttpObservationSink
import com.feedme.server.http.HttpDiagnosticSnapshot
import com.feedme.server.http.createRuntimeHttpDiagnostics
import com.feedme.server.http.feedMeLocalService
import com.feedme.server.identity.AccountPendingProfileRules
import com.feedme.server.identity.SupabaseAuthorityDeployment
import com.feedme.server.kitchen.KitchenCursorCodec
import com.feedme.server.kitchen.KitchenServicePolicy
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.BindException
import java.time.Clock
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Explicit LOCAL pantry-only composition. No default Main change, migrations, fixture
 * credentials, account eligibility grants, planning routes or deployment occur here.
 * The database/catalog/cursors are borrowed. This instance owns its listener, provider
 * authority/key client and database executor. Health remains local/degraded, not readiness.
 */
class AccountPantryRuntime private constructor(
    val port: Int,
    private val resources: PantryRuntimeResources,
    private val listenerStopped: CountDownLatch,
    private val diagnostics: BoundedHttpObservationSink,
) : AutoCloseable {
    fun awaitTermination() = listenerStopped.await()
    /** Local diagnostics only; not a readiness, authentication or delivery proof. */
    fun diagnosticSnapshot(): HttpDiagnosticSnapshot = diagnostics.snapshot()
    override fun close() = resources.close()
    override fun toString() = "AccountPantryRuntime(<redacted>)"

    companion object {
        fun start(listener: LocalServerConfig, environment: String, database: DataSource,
            deployment: SupabaseAuthorityDeployment, accountRules: AccountPendingProfileRules,
            keyPolicy: SupabaseJwksHttpPolicy, catalog: IngredientCatalogStore,
            cursors: KitchenCursorCodec, policy: KitchenServicePolicy, databaseParallelism: Int,
            clock: Clock): AccountPantryRuntime {
            if (environment != "local" || catalog.environment != environment ||
                listener.host != "127.0.0.1" || !listener.hasExplicitRequestLimit ||
                databaseParallelism !in 1..8) throw AccountPantryRuntimeFailure()
            val number = AtomicInteger()
            val executor = Executors.newFixedThreadPool(databaseParallelism) { task ->
                Thread(task, "feedme-pantry-db-${number.incrementAndGet()}").apply { isDaemon = true }
            }
            val dispatcher = executor.asCoroutineDispatcher()
            val lifecycle = ServiceLifecycle()
            val stopped = CountDownLatch(1)
            val resources = PantryRuntimeResources(executor, lifecycle, { dispatcher.close() }, stopped)
            try {
                // All real schema/provider compatibility checks complete BEFORE binding.
                val assembly = ConfiguredSupabaseAccountPantryAssembly.open(environment, database,
                    deployment, accountRules, keyPolicy, catalog, cursors, policy, dispatcher, clock)
                resources.assembly = assembly
                val diagnostics = createRuntimeHttpDiagnostics()
                resources.diagnostics = diagnostics
                val server = embeddedServer(CIO, port = listener.port, host = listener.host) {
                    feedMeLocalService(listener, clock = clock, accountPantry = assembly.http,
                        lifecycle = lifecycle, healthMode = ServiceHealthMode.LOCAL_LIVENESS,
                        observationSink = diagnostics)
                    monitor.subscribe(ApplicationStopped) { diagnostics.close(); stopped.countDown() }
                }
                // Own the engine before start: failed binds still retire everything opened.
                resources.stopListener = { server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000) }
                startPantryListener {
                    server.start(wait = false)
                    runBlocking {
                        withTimeout(10_000) {
                            val connector = server.engine.resolvedConnectors().single()
                            check(connector.host == listener.host && connector.port == listener.port)
                        }
                    }
                }
                return AccountPantryRuntime(listener.port, resources, stopped, diagnostics)
            } catch (failure: Throwable) {
                runCatching { resources.close() }
                when (failure) {
                    is CancellationException -> throw failure
                    is InterruptedException -> { Thread.currentThread().interrupt(); throw failure }
                    is Error -> throw failure
                    else -> throw AccountPantryRuntimeFailure()
                }
            }
        }
    }
}

class AccountPantryRuntimeFailure : IllegalStateException("Local pantry runtime unavailable")

/** CIO can report a failed socket bind as cancellation of its own server job. Only that
 * positively identified listener-start failure is sanitized. Provider/database cancellation,
 * ordinary caller cancellation and interruption still follow their existing cleanup path.
 * Do not inspect messages or unbounded/cyclic cause chains, or retry on a different port. */
internal fun startPantryListener(action: () -> Unit) {
    try { action() }
    catch (failure: CancellationException) {
        if (Thread.currentThread().isInterrupted) throw failure
        val seen = IdentityHashMap<Throwable, Boolean>()
        var cause: Throwable? = failure
        repeat(32) {
            val current = cause ?: throw failure
            if (seen.put(current, true) != null) throw failure
            if (current is BindException) throw AccountPantryRuntimeFailure()
            if (current is InterruptedException || current is Error) throw failure
            cause = current.cause
        }
        throw failure
    }
}

/** Lifetime helper only; it cannot construct a verifier or grant HTTP/data access. Tests
 * use owned inert resources to check shutdown ordering without supplying production auth.
 * If blocking JDBC work outlives the bounded drain, close reports failure, retires authority
 * and does not claim rollback or a known command outcome. CIO's bounded stop may cancel
 * admitted request coroutines (including interruptible JDBC); original command identities
 * must be retained for reconciliation. This helper adds no shutdownNow interruption.
 * Borrowed database/catalog resources are never closed.
 */
internal class PantryRuntimeResources(
    private val executor: ExecutorService,
    private val lifecycle: ServiceLifecycle,
    private val closeDispatcher: () -> Unit,
    private val listenerStopped: CountDownLatch,
    private val drainMillis: Long = 60_000,
) : AutoCloseable {
    var assembly: AutoCloseable? = null
    var stopListener: (() -> Unit)? = null
    var diagnostics: AutoCloseable? = null
    private var closed = false

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        lifecycle.beginDraining()
        var failed = false
        var interrupted = Thread.interrupted()
        fun cleanupFailure(failure: Throwable) {
            failed = true
            if (failure is InterruptedException) interrupted = true
        }
        try {
            try { stopListener?.invoke() } catch (failure: Throwable) { cleanupFailure(failure) }
            executor.shutdown()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainMillis)
            // Preserve interruption, but do not let it skip owned-resource cleanup.
            while (!executor.isTerminated) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) { failed = true; break }
                try { if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) failed = true }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            try { assembly?.close() } catch (failure: Throwable) { cleanupFailure(failure) }
            try { closeDispatcher() } catch (failure: Throwable) { cleanupFailure(failure) }
            try { diagnostics?.close() } catch (failure: Throwable) { cleanupFailure(failure) }
            lifecycle.markStopped()
            listenerStopped.countDown()
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (failed) throw AccountPantryRuntimeFailure()
    }
}
