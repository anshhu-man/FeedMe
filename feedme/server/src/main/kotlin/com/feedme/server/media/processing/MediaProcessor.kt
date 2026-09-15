package com.feedme.server.media.processing

import com.feedme.server.db.CommitOutcomeUnknown
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException

/** Explicit invocation only: no background loop/Main/provider registration. Tests may explicitly
 * inject synthetic safety/object ports; production has no accepting implementation. External I/O
 * is outside transactions. Each retry uses the same immutable input and persisted destinations. */
class MediaProcessor(private val store: MediaProcessingStore, private val codec: PhotoCodec,
    private val objects: MediaProcessingObjects, private val safety: MediaSafetyAssessment) {
    init { require(codec.revision == store.policy.codecRevision) }

    fun process(lease: MediaProcessingLease): MediaProcessingReceipt {
        try {
            processingCurrent(); store.begin(lease)?.let { return it }
            if (codec.revision != store.policy.codecRevision) processingFail(MediaProcessingFailureCode.NOT_CONFIGURED)
            val source = readSource(lease.source)
            store.renew(lease)
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
            // DB-only defer is best effort, never compensation for an unknown commit/object write.
            // A lost defer receipt leaves the original lease/job for exact reconciliation.
            try { store.defer(lease, e.code) } catch (unknown: CommitOutcomeUnknown) { throw unknown }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
            catch (_: MediaProcessingFailure) { /* expired/deleted/terminal lease remains durable */ }
            throw e
        }
    }
    private fun readSource(source: MediaProcessingSource): ByteArray {
        if (source.bytes !in 1..store.policy.maxSourceBytes.toLong()) processingFail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
        val bytes = external {
            objects.read(source).use { input ->
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
