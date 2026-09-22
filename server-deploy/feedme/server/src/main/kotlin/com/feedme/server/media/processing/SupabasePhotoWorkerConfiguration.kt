package com.feedme.server.media.processing

import com.feedme.server.media.processing.codec.PhotoProcessingPolicy
import com.feedme.server.media.processing.codec.PhotoProcessPolicy
import java.nio.file.Path

/** Explicit worker-only composition, not an environment reader or serving-route switch.
 * No operational/safety defaults, assessor endpoint, credential discovery or grant installer.
 * The source codec may admit larger input dimensions; its emitted PNGs must fit actual Android
 * delivery limits. A batch deadline is independent of each provider/codec/assessment deadline. */
class SupabasePhotoWorkerConfiguration(
    val environment: String,
    val processing: MediaProcessingPolicy,
    val account: AccountMediaProcessingPolicy,
    val photo: PhotoProcessingPolicy,
    val process: PhotoProcessPolicy,
    val javaExecutable: Path,
    val classpath: String,
    val eventsPerBatch: Int,
    val materializationsPerBatch: Int,
    val promotionsPerBatch: Int,
    val pollMillis: Long,
    val batchTimeoutMillis: Long,
    val assessmentTimeoutMillis: Long,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(account.enabled && account.processingRevision == processing.revision && photo.revision == processing.codecRevision)
        require(processing.maxAttempts >= 2) // Materialization and fresh READY inspection are separate leases.
        require(photo.maxSourceBytes == processing.maxSourceBytes && photo.maxDerivativeBytes == processing.maxDerivativeBytes &&
            photo.maxCombinedDerivativeBytes == processing.maxCombinedDerivativeBytes)
        require(photo.outputContentType == "image/png" && photo.displayEdge <= 2048 &&
            photo.displayEdge.toLong() * photo.displayEdge <= 4_194_304 && photo.maxDerivativeBytes <= 4_194_304 &&
            photo.maxCombinedDerivativeBytes <= 8_388_608 && processing.maxDimension in photo.displayEdge..2048)
        require(photo.maxWorkingBytes <= process.heapMiB.toLong() * 1024 * 1024)
        require(eventsPerBatch in 1..100 && materializationsPerBatch in 1..20 && promotionsPerBatch in 1..20)
        require(pollMillis in 1_000..60_000 && batchTimeoutMillis in 1_000..900_000 && assessmentTimeoutMillis in 1..60_000)
        require(batchTimeoutMillis > maxOf(process.timeoutMillis, assessmentTimeoutMillis))
        require(processing.leaseSeconds * 1000L > maxOf(process.timeoutMillis, assessmentTimeoutMillis) + account.statementTimeoutMillis)
    }
    override fun toString() = "SupabasePhotoWorkerConfiguration(<redacted>)"
}

enum class SupabasePhotoBatchStatus { FINISHED, BUSY, CLOSED, OUTCOME_UNKNOWN, TIMED_OUT }

/** Operational counts only, never IDs, object paths, source bytes or safety receipt content.
 * A READY count is a committed media result, not publication, delivery or physical erasure. */
class SupabasePhotoBatch internal constructor(
    val status: SupabasePhotoBatchStatus,
    val ingested: Int = 0,
    val held: Int = 0,
    val ready: Int = 0,
    failures: Map<MediaProcessingFailureCode, Int> = emptyMap(),
) {
    val failures = failures.toMap()
    override fun toString() = "SupabasePhotoBatch(status=$status, ingested=$ingested, held=$held, ready=$ready, failures=$failures)"
}
