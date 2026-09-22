package com.feedme.server.media.processing

import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.media.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException

/** Explicit invocation only: the separate runner owns scheduling/provider registration. Tests may explicitly
 * inject synthetic safety/object ports; production has no accepting implementation. External I/O
 * is outside transactions. Each retry uses the same immutable input and persisted destinations. */
class MediaProcessor(private val store: MediaProcessingStore, private val codec: PhotoCodec,
    private val objects: MediaProcessingObjects?, private val safety: MediaSafetyAssessment,
    private val supabase: SupabaseMediaStorage? = null,
    private val supabaseDerivatives: SupabaseDerivativeObjects? = null) {
    init {
        require(codec.revision == store.policy.codecRevision)
        require(objects != null || supabase != null && supabaseDerivatives != null)
    }

    /** Explicit Supabase private-output stage. It never returns a terminal media receipt.
     * Successful work is durably held, not reclaimed in an attempt-exhausting retry loop. An exact
     * replay returns the historical checkpoint, NOT a current availability or publication grant.
     * The legacy terminal-processing entry point below remains unchanged and refuses Supabase.
     */
    fun materializeSupabase(lease: MediaProcessingLease): SupabasePrivateMaterialization {
        try {
            processingCurrent()
            if (lease.source.storageProtocol != SUPABASE_MEDIA_PROTOCOL)
                processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            store.beginSupabaseMaterialization(lease)?.let { return it }
            val provider = supabaseDerivatives ?: processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            if (provider.bucket != lease.source.bucket || codec.revision != store.policy.codecRevision)
                processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            val source = readSource(lease.source)
            store.renew(lease)
            val decoded = codec.decode(source, lease.source.contentType); processingCurrent()
            // Rejection-to-terminal integration is not implied by private materialization.
            // Never reinterpret a decoder/content rejection as an accepted derivative.
            val variants = when (decoded) {
                is PhotoDecodeResult.Decoded -> decoded.variants
                is PhotoDecodeResult.Rejected -> {
                    store.quarantineSupabaseRejection(lease, decoded.reason)
                    processingFail(MediaProcessingFailureCode.PHOTO_REJECTED)
                }
                is PhotoDecodeResult.Unavailable -> processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            }
            store.validateDecoded(variants)
            store.renew(lease)
            val proof = when (val assessment = external { safety.assess(lease.source, variants) }) {
                is MediaSafetyResult.Approved -> assessment.evidence
                is MediaSafetyResult.Rejected -> {
                    store.quarantineSupabaseRejection(lease, assessment.reason)
                    processingFail(MediaProcessingFailureCode.SAFETY_REJECTED)
                }
                MediaSafetyResult.Pending, MediaSafetyResult.Unavailable -> processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
            }
            store.validateSafety(lease, variants, proof)
            val intents = store.prepareDerivatives(lease, variants)
            for (intent in intents) {
                processingCurrent(); store.renew(lease)
                val existing = external { provider.inspect(intent) }
                val receipt = if (existing != null) existing else {
                    // Only a positively acknowledged FIRST durable claim permits a POST. A lost
                    // database commit acknowledgement escapes without dispatch. An earlier attempt
                    // (including one with missing readback) never grants a second POST/replacement.
                    val claimed = store.claimSupabaseWrite(lease, intent)
                        ?: processingFail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE)
                    processingCurrent()
                    val bytes = variants.single { it.variant == intent.variant }.copyBytes()
                    external { provider.create(claimed, bytes) }
                    external { provider.inspect(claimed) }
                        ?: processingFail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE)
                }
                store.acknowledgeSupabase(intent, receipt)
            }
            return store.holdSupabaseMaterialization(lease, proof).also { processingCurrent() }
        } catch (e: CommitOutcomeUnknown) { throw e }
        catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (e: MediaProcessingFailure) { deferFailure(lease, e); throw e }
    }

    fun process(lease: MediaProcessingLease): MediaProcessingReceipt {
        try {
            val objects = objects ?: processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            processingCurrent(); store.begin(lease)?.let { return it }
            if (codec.revision != store.policy.codecRevision) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            val source = readSource(lease.source)
            store.renew(lease)
            // Supabase private materialization has its own non-terminal entry point. It cannot
            // use the legacy immutable-version READY/publication contract.
            if(lease.source.storageProtocol==SUPABASE_MEDIA_PROTOCOL) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            val decoded = codec.decode(source, lease.source.contentType); processingCurrent()
            val variants = when (decoded) {
                is PhotoDecodeResult.Rejected -> return store.reject(lease, decoded.reason)
                is PhotoDecodeResult.Unavailable -> processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
                is PhotoDecodeResult.Decoded -> decoded.variants
            }
            store.validateDecoded(variants)
            store.renew(lease)
            val assessment = external { safety.assess(lease.source, variants) }
            val proof = when (assessment) {
                is MediaSafetyResult.Approved -> assessment.evidence
                is MediaSafetyResult.Rejected -> return store.reject(lease, assessment.reason)
                MediaSafetyResult.Pending, MediaSafetyResult.Unavailable -> processingFail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
            }
            store.validateSafety(lease, variants, proof)
            val intents = store.prepareDerivatives(lease, variants)
            for (intent in intents) {
                processingCurrent(); store.renew(lease)
                val existing = external { objects.inspect(intent) }
                val receipt = if (existing != null) existing else {
                    store.authorizeWrite(lease, intent)
                    val bytes = variants.single { it.variant == intent.variant }.copyBytes()
                    val written = external { objects.create(intent, bytes) }
                    // Confirm exact immutable bytes through the provider's bounded read/verifier,
                    // not a mutable metadata acknowledgement from a successful PUT alone.
                    val verified = external { objects.inspect(intent) } ?: processingFail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE)
                    if (written.objectKey != verified.objectKey || written.objectVersionId != verified.objectVersionId ||
                        written.sha256 != verified.sha256 || written.bytes != verified.bytes || written.contentType != verified.contentType)
                        processingFail(MediaProcessingFailureCode.OBJECT_MISMATCH)
                    verified
                }
                store.acknowledge(intent, receipt)
            }
            return store.ready(lease, proof).also { processingCurrent() }
        } catch (e: CommitOutcomeUnknown) { throw e }
        catch (e: CancellationException) { throw e }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (e: MediaProcessingFailure) {
            deferFailure(lease, e)
            throw e
        }
    }

    /** Explicit HOLD -> READY promotion. Re-inspect the same two private destinations;
     * never POST, replace a key, infer immutable versions, or renew the retained safety proof. */
    fun promoteSupabase(lease: MediaProcessingLease): MediaProcessingReceipt {
        try {
            processingCurrent()
            if (lease.source.storageProtocol != SUPABASE_MEDIA_PROTOCOL) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            store.begin(lease)?.let { return it }
            val provider = supabaseDerivatives ?: processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            if (provider.bucket != lease.source.bucket) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            val inspection = store.prepareSupabaseReady(lease)
            val receipts = inspection.outputs.map { output ->
                processingCurrent(); store.renew(lease)
                external { provider.inspect(output) } ?: processingFail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE)
            }
            return store.finishSupabaseReady(inspection, receipts).also { processingCurrent() }
        } catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: MediaProcessingFailure) { deferFailure(lease, failure); throw failure }
    }
    private fun deferFailure(lease: MediaProcessingLease, failure: MediaProcessingFailure) {
        if (failure.code in setOf(MediaProcessingFailureCode.PHOTO_REJECTED, MediaProcessingFailureCode.SAFETY_REJECTED)) return
        // DB-only defer is best effort, never compensation for an unknown commit/object write.
        // A lost defer receipt leaves the original lease/job for exact reconciliation.
        try { store.defer(lease, failure.code) } catch (unknown: CommitOutcomeUnknown) { throw unknown }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (_: MediaProcessingFailure) { /* expired/deleted/terminal/held lease remains durable */ }
    }
    private fun readSource(source: MediaProcessingSource): ByteArray {
        if (source.bytes !in 1..store.policy.maxSourceBytes.toLong()) processingFail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
        val bytes = external {
            val stream = when(source.storageProtocol) {
                LEGACY_MEDIA_PROTOCOL -> (objects ?: processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)).read(source)
                SUPABASE_MEDIA_PROTOCOL -> {
                    val provider=supabase ?: processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
                    if(provider.bucket!=source.bucket) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
                    provider.read(SupabaseObjectRequest(source.owner.environment,source.owner.ownerId,source.mediaId,
                        source.objectKey,source.bytes,source.contentType,source.sha256))
                }
                else -> processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            }
            stream.use { input ->
                val output = ByteArrayOutputStream(minOf(source.bytes.toInt(), 65536)); val buffer = ByteArray(16384)
                while (true) {
                    processingCurrent(); val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) {
                        val one = input.read(); if (one < 0) break
                        if (output.size().toLong() >= source.bytes) processingFail(MediaProcessingFailureCode.OBJECT_MISMATCH)
                        output.write(one)
                    } else {
                        if (output.size().toLong() + n > source.bytes) processingFail(MediaProcessingFailureCode.OBJECT_MISMATCH)
                        output.write(buffer, 0, n)
                    }
                }
                output.toByteArray()
            }
        }
        if (bytes.size.toLong() != source.bytes || processingHash(bytes) != source.sha256) processingFail(MediaProcessingFailureCode.OBJECT_MISMATCH)
        return bytes
    }
    private fun <T> external(block: () -> T): T = try { processingCurrent(); block().also { processingCurrent() } }
        catch (e: CancellationException) { throw e } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (e: MediaProcessingFailure) { throw e } catch (_: Exception) { processingFail(MediaProcessingFailureCode.OBJECT_UNAVAILABLE) }
}
