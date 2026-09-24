package com.feedme.server.media.processing

import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.SupabaseAuthErasureClient
import com.feedme.server.identity.SupabasePostgresAuthority
import com.feedme.server.media.processing.codec.PhotoDecodeProcess
import com.feedme.server.media.supabase.SupabaseDerivativeHttp
import com.feedme.server.media.supabase.SupabaseStorageHttp
import com.feedme.server.media.supabase.SupabaseStorageHttpConfiguration
import java.time.Clock
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Independently executable, single-owner worker. Construction checks DB admission but does not
 * fetch private objects, start a scheduler, mint uploads, publish posts or clean up storage.
 * The mandatory borrowed assessor must actually establish BOTH malware-scan or validated CDR
 * approval, and content approval,
 * for these exact hashes. No assessor implementation or permission to export images is supplied.
 * Caller owns transactions/provider/dispatcher/assessor; this owner closes its two HTTP clients
 * and assessment boundary. Keep one retained owner; a timed-out uncooperative assessor can retain
 * one daemon thread until it exits, never a growing queue or an accepted late verdict. */
class SupabasePhotoWorker private constructor(
    private val config: SupabasePhotoWorkerConfiguration,
    private val dispatcher: CoroutineDispatcher,
    private val store: MediaProcessingStore,
    private val processor: MediaProcessor,
    private val discovery: SupabasePhotoWorkDiscovery,
    private val sourceObjects: SupabaseStorageHttp,
    private val derivatives: SupabaseDerivativeHttp,
    private val assessment: WorkerSafetyAssessment,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val running = AtomicBoolean()
    private val lifecycle = Any()
    private var active: Job? = null

    /** Only acknowledged current leases authorize effects. Unknown DB commits stop before any
     * subsequent network step; a later explicit invocation recovers the existing DB originals.
     * Timed-out/cancelled POST is never retried: the persisted one-shot intent permits inspection
     * only. Batch timeout is not proof that a remote acceptance was prevented or rolled back. */
    suspend fun runBatch(): SupabasePhotoBatch {
        currentCoroutineContext().ensureActive()
        if (closed.get()) return SupabasePhotoBatch(SupabasePhotoBatchStatus.CLOSED)
        if (!running.compareAndSet(false, true)) return SupabasePhotoBatch(SupabasePhotoBatchStatus.BUSY)
        val counts = Counts()
        try {
            return withTimeout(config.batchTimeoutMillis) {
                withContext(dispatcher) {
                    val operation = currentCoroutineContext().job
                    synchronized(lifecycle) {
                        if (closed.get()) return@withContext counts.result(SupabasePhotoBatchStatus.CLOSED)
                        active = operation
                    }
                    try {
                        runInterruptible {
                            for (event in discovery.events()) {
                                processingCurrent()
                                counts.attempt { store.ingest(event); counts.ingested++ }
                            }
                            // Resume earlier HOLDs first. Newly held jobs are picked up on the
                            // next bounded pass, including after crash/lost hold acknowledgement.
                            for (jobId in discovery.heldJobs()) {
                                processingCurrent()
                                counts.attempt {
                                    store.claimSupabaseReady(jobId)?.let { lease ->
                                        val receipt = processor.promoteSupabase(lease)
                                        if (receipt.result == MediaProcessingTerminal.READY) counts.ready++
                                        else processingFail(MediaProcessingFailureCode.CONFLICT)
                                    }
                                }
                            }
                            repeat(config.materializationsPerBatch) {
                                processingCurrent()
                                val lease = store.claimSupabase(derivatives.bucket) ?: return@runInterruptible
                                counts.attempt { processor.materializeSupabase(lease); counts.held++ }
                            }
                        }
                        counts.result(SupabasePhotoBatchStatus.FINISHED)
                    } finally {
                        synchronized(lifecycle) { if (active === operation) active = null }
                    }
                }
            }
        } catch (_: CommitOutcomeUnknown) {
            return counts.result(SupabasePhotoBatchStatus.OUTCOME_UNKNOWN)
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // Do not swallow an outer caller's cancellation.
            return counts.result(SupabasePhotoBatchStatus.TIMED_OUT)
        } finally { running.set(false) }
    }

    /** Explicit opt-in loop. Parent cancellation propagates; caller must close the worker.
     * A missing dependency, timeout or ambiguous commit returns control for reconciliation
     * rather than silently starting another batch. Reports contain no private identifiers. */
    suspend fun runUntilStopped(report: (SupabasePhotoBatch) -> Unit): SupabasePhotoBatch {
        while (true) {
            val result = runBatch()
            report(result)
            if (result.status !in setOf(SupabasePhotoBatchStatus.FINISHED, SupabasePhotoBatchStatus.BUSY) ||
                MediaProcessingFailureCode.NOT_CONFIGURED in result.failures) return result
            delay(config.pollMillis)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lifecycle) { active?.cancel(CancellationException("Photo worker closed")) }
        // Closing cancels actual HTTP calls too; it does not revoke a dispatched remote write.
        try { assessment.close() } finally {
            try { derivatives.close() } finally { sourceObjects.close() }
        }
    }
    override fun toString() = "SupabasePhotoWorker(<redacted>)"

    private class Counts {
        var ingested = 0; var held = 0; var ready = 0
        private val failures = mutableMapOf<MediaProcessingFailureCode, Int>()
        fun attempt(action: () -> Unit) {
            try { action() }
            catch (failure: MediaProcessingFailure) { failures[failure.code] = (failures[failure.code] ?: 0) + 1 }
        }
        fun result(status: SupabasePhotoBatchStatus) = SupabasePhotoBatch(status, ingested, held, ready, failures)
    }

    companion object {
        /** Uses production HTTPS factories only. No loopback, alternate endpoint, credentials
         * from environment, accepting safety fallback, DDL or GRANT occurs here. Call off UI. */
        fun create(config: SupabasePhotoWorkerConfiguration, transactions: PgTransactions,
            provider: SupabasePostgresAuthority, storage: SupabaseStorageHttpConfiguration,
            safety: MediaSafetyAssessment, dispatcher: CoroutineDispatcher, clock: Clock): SupabasePhotoWorker {
            require(storage.environment == config.environment && storage.projectOrigin + "/auth/v1" == provider.deployment.verification.issuer &&
                provider.deployment.verification.issuer == SupabaseAuthErasureClient.APPROVED_ISSUER)
            require(storage.maxObjectBytes >= maxOf(config.processing.maxSourceBytes, config.processing.maxDerivativeBytes))
            require(config.processing.leaseSeconds * 1000L > storage.callTimeoutMillis + config.account.statementTimeoutMillis)
            require(config.batchTimeoutMillis > storage.callTimeoutMillis)
            val codec = PhotoDecodeProcess(config.javaExecutable, config.classpath, config.photo, config.process)
            processingSafe { transactions.run { c ->
                SupabasePhotoWorkerCompatibility.check(c)
                provider.checkCompatibility(c)
            } }
            val sources = SupabaseStorageHttp.create(storage, clock)
            var outputs: SupabaseDerivativeHttp? = null
            var boundedSafety: WorkerSafetyAssessment? = null
            try {
                outputs = SupabaseDerivativeHttp.create(storage, clock)
                boundedSafety = WorkerSafetyAssessment(safety, config.assessmentTimeoutMillis)
                val authority = AccountMediaProcessingAuthority(config.environment, config.account, provider)
                val store = MediaProcessingStore(config.environment, transactions, authority, config.processing)
                val processor = MediaProcessor(store, codec, null, boundedSafety, sources, outputs)
                return SupabasePhotoWorker(config, dispatcher, store, processor,
                    SupabasePhotoWorkDiscovery(config, transactions, storage.bucket), sources, outputs, boundedSafety)
            } catch (failure: Throwable) {
                boundedSafety?.close(); outputs?.close(); sources.close()
                throw failure
            }
        }
    }
}

/** Bounded assessment invocation only; never synthesizes a verdict. A cancelled Future discards
 * a late return. SynchronousQueue permits no retained backlog, and a stuck assessor occupies
 * the sole thread instead of spawning another. Its own network/resource cancellation remains
 * the concrete assessor's obligation; timeout is unavailability, never rejection or approval. */
private class WorkerSafetyAssessment(private val actual: MediaSafetyAssessment, private val timeoutMillis: Long) :
    MediaSafetyAssessment, AutoCloseable {
    private val closed = AtomicBoolean()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
        ThreadFactory { action -> Thread(action, "feedme-photo-assessment").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    override fun assess(source: MediaProcessingSource, derivatives: List<EncodedPhotoVariant>): MediaSafetyResult {
        processingCurrent()
        if (closed.get()) return MediaSafetyResult.Unavailable
        val future = try { executor.submit<MediaSafetyResult> { actual.assess(source, derivatives) } }
            catch (_: RejectedExecutionException) { return MediaSafetyResult.Unavailable }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS).also {
                processingCurrent()
                if (closed.get()) processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
            }
        } catch (_: TimeoutException) { return MediaSafetyResult.Unavailable }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: ExecutionException) {
            when (val cause = failure.cause) {
                is Error -> throw cause
                is InterruptedException -> { Thread.currentThread().interrupt(); throw cause }
                is java.util.concurrent.CancellationException -> throw cause
                else -> return MediaSafetyResult.Unavailable
            }
        } finally { if (!future.isDone) future.cancel(true) }
    }
    override fun close() { if (closed.compareAndSet(false, true)) executor.shutdownNow() }
}
