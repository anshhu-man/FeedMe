package com.feedme.server.media.processing

import com.feedme.server.media.LEGACY_MEDIA_PROTOCOL
import com.feedme.server.media.SUPABASE_MEDIA_PROTOCOL
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** A current locked proof, not media/audience/object access. The transaction owner must
 * intersect validUntil with its other grants and check it after all remaining waits. */
internal class MediaSafetyCurrent internal constructor(val recordSha256: String, val evidence: MediaSafetyEvidence,
    val policyRevision: String, val codecRevision: String) {
    val validUntil: Instant get() = evidence.validUntil
    override fun toString() = "MediaSafetyCurrent(<redacted>)"
}

/** An exact requested administrative action; construction does not authenticate its operator. */
class MediaSafetyRevocation(val environment: String, val jobId: UUID, val recordSha256: String,
    val revocationId: UUID, val operatorId: UUID, val authorizationReference: String) {
    internal val exactDocument: String
    val requestSha256: String
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && recordSha256.matches(HASH))
        require(authorizationReference.isNotBlank() && authorizationReference.length <= 256 &&
            authorizationReference.none(Char::isISOControl))
        exactDocument = buildJsonObject {
            put("formatVersion", 1); put("environment", environment); put("jobId", jobId.toString())
            put("recordSha256", recordSha256); put("revocationId", revocationId.toString())
            put("operatorId", operatorId.toString()); put("authorizationReference", authorizationReference)
        }.toString()
        requestSha256 = processingHash(exactDocument.encodeToByteArray())
    }
    override fun toString() = "MediaSafetyRevocation(<redacted>)"
}

/** Required independent authenticated operator authority, with no accepting implementation.
 * Lock current administrative identity/approval before the safety row; repeat after writes.
 * Same transaction only: no network, commit, mutation of evidence or inferred approval. */
interface MediaSafetyRevocationAuthority {
    fun lockRevocation(connection: Connection, original: MediaSafetyRevocation)
    fun revalidateRevocation(connection: Connection, original: MediaSafetyRevocation)
}
class MediaSafetyRevocationReceipt internal constructor(val jobId: UUID, val revocationId: UUID,
    val requestSha256: String, val replayed: Boolean) {
    override fun toString() = "MediaSafetyRevocationReceipt(<redacted>)"
}

/** Exact worker acceptance evidence. record MUST follow the real worker safety authority
 * and current lease admission in the same transaction. V037 additionally binds actual job,
 * source, outputs and the atomic final ready/private-held state; it supplies no provider.
 * No backfill, hidden transaction, mutable renewal, object access or default safety policy. */
internal object MediaSafetyRecords {
    fun record(c: Connection, source: MediaProcessingSource, jobId: UUID, policy: MediaProcessingPolicy,
        evidence: MediaSafetyEvidence, outputs: List<MediaDerivativeIntent>, stage: String, mediaVersion: Long) {
        transaction(c)
        val image = image(source, jobId, policy.revision, policy.codecRevision, evidence, outputs, stage, mediaVersion)
        val prior = read(c, jobId, exclusive = true)
        if (prior != null) {
            if (prior.text != image.text) fail(MediaProcessingFailureCode.CONFLICT)
            // Exact historical replay never renews evidence or returns a current grant.
            return
        }
        val at = pnow(c)
        if (evidence.assessedAt > at || evidence.validUntil <= at) fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        c.prepareStatement("INSERT INTO platform.media_safety_records(job_id,environment,owner_user_id,media_id," +
            "source_text,source_sha256,policy_revision,codec_revision,evidence_text,evidence_sha256,outputs_text,outputs_sha256," +
            "stage,media_version,record_text,record_sha256,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
            s.setObject(1, jobId); s.setString(2, source.owner.environment); s.setObject(3, source.owner.ownerId); s.setObject(4, source.mediaId)
            s.setString(5, image.sourceText); s.setString(6, sha(image.sourceText)); s.setString(7, policy.revision); s.setString(8, policy.codecRevision)
            s.setString(9, image.evidenceText); s.setString(10, sha(image.evidenceText)); s.setString(11, image.outputsText); s.setString(12, sha(image.outputsText))
            s.setString(13, stage); s.setLong(14, mediaVersion); s.setString(15, image.text); s.setString(16, sha(image.text)); s.setObject(17, pt(at))
            if (s.executeUpdate() != 1) fail(MediaProcessingFailureCode.CONFLICT)
        }
        val saved = read(c, jobId, exclusive = true) ?: fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
        if (saved.text != image.text) fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
        if (evidence.validUntil <= pnow(c)) fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        transaction(c)
    }

    fun requireCurrent(c: Connection, source: MediaProcessingSource, jobId: UUID,
        policyRevision: String, codecRevision: String, requiredSafetyRevision: String,
        outputs: List<MediaDerivativeIntent>, stage: String, mediaVersion: Long): MediaSafetyCurrent {
        transaction(c)
        if (!requiredSafetyRevision.matches(REVISION)) fail(MediaProcessingFailureCode.NOT_CONFIGURED)
        val first = pnow(c)
        val row = read(c, jobId) ?: fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        val expected = image(source, jobId, policyRevision, codecRevision, row.evidence, outputs, stage, mediaVersion)
        if (row.text != expected.text) fail(MediaProcessingFailureCode.CONFLICT)
        if (row.evidence.revision != requiredSafetyRevision || row.evidence.assessedAt > first ||
            row.evidence.validUntil <= first) fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        if (revocation(c, jobId) != null) fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        val final = pnow(c)
        if (final < first || row.evidence.assessedAt > final || row.evidence.validUntil <= final)
            fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        transaction(c)
        return MediaSafetyCurrent(sha(row.text), row.evidence, policyRevision, codecRevision)
    }

    /** Reuses the immutable accepted HOLD proof without renewing it. The worker MUST
     * independently call its current authority.requireSafety on this evidence; the record's
     * own revision is historical input, never a configured policy selected by this helper. */
    fun requireHeldForPromotion(c: Connection, source: MediaProcessingSource, jobId: UUID,
        policy: MediaProcessingPolicy, outputs: List<MediaDerivativeIntent>): MediaSafetyCurrent {
        if (source.storageProtocol != SUPABASE_MEDIA_PROTOCOL) fail(MediaProcessingFailureCode.CONFLICT)
        val original = read(c, jobId) ?: fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        return requireCurrent(c, source, jobId, policy.revision, policy.codecRevision,
            original.evidence.revision, outputs, "privateHeld", source.completionVersion)
    }

    fun revoke(c: Connection, original: MediaSafetyRevocation, authority: MediaSafetyRevocationAuthority): MediaSafetyRevocationReceipt {
        transaction(c)
        authority.lockRevocation(c, original); transaction(c)
        val record = read(c, original.jobId, exclusive = true) ?: fail(MediaProcessingFailureCode.SAFETY_UNAVAILABLE)
        if (record.value.getValue("environment") != JsonPrimitive(original.environment) || sha(record.text) != original.recordSha256)
            fail(MediaProcessingFailureCode.CONFLICT)
        val before = revocation(c, original.jobId)
        if (before != null && before != original.exactDocument) fail(MediaProcessingFailureCode.CONFLICT)
        if (before == null) c.prepareStatement("INSERT INTO platform.media_safety_revocations(job_id,environment,record_sha256," +
            "revocation_id,operator_id,authorization_reference,request_text,request_sha256,revoked_at) VALUES(?,?,?,?,?,?,?,?,clock_timestamp())").use { s ->
            s.setObject(1, original.jobId); s.setString(2, original.environment); s.setString(3, original.recordSha256)
            s.setObject(4, original.revocationId); s.setObject(5, original.operatorId); s.setString(6, original.authorizationReference)
            s.setString(7, original.exactDocument); s.setString(8, original.requestSha256)
            if (s.executeUpdate() != 1) fail(MediaProcessingFailureCode.CONFLICT)
        }
        if (revocation(c, original.jobId) != original.exactDocument) fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
        authority.revalidateRevocation(c, original); transaction(c)
        return MediaSafetyRevocationReceipt(original.jobId, original.revocationId, original.requestSha256, before != null)
    }

    private class Image(val sourceText: String, val evidenceText: String, val outputsText: String, val text: String)
    private class Row(val text: String, val value: JsonObject, val evidence: MediaSafetyEvidence)
    private fun image(source: MediaProcessingSource, jobId: UUID, policyRevision: String, codecRevision: String,
        evidence: MediaSafetyEvidence, outputs: List<MediaDerivativeIntent>, stage: String, mediaVersion: Long): Image {
        if (!policyRevision.matches(REVISION) || !codecRevision.matches(REVISION) || stage !in setOf("ready", "privateHeld") ||
            mediaVersion != source.completionVersion + (if (stage == "ready") 1 else 0))
            fail(MediaProcessingFailureCode.CONFLICT)
        val sourceText = source.document().toString()
        if (processingSource(sourceText).document() != source.document() || evidence.sourceSha256 != source.sha256 ||
            outputs.size != 2 || outputs.map { it.variant }.toSet() != PhotoVariant.entries.toSet() ||
            evidence.derivativeSha256 != outputs.associate { it.variant to it.sha256 }) fail(MediaProcessingFailureCode.CONFLICT)
        val outputText = JsonArray(outputs.sortedBy { it.variant.wire }.map { output ->
            if (output.jobId != jobId || output.source.document() != source.document() || output.storageProtocol != source.storageProtocol ||
                output.bucket != source.bucket || !output.sha256.matches(HASH) || output.bytes !in 1..10_000_000 ||
                output.width <= 0 || output.height <= 0 || output.contentType !in setOf("image/jpeg", "image/png") ||
                output.acknowledgedAt == null || output.objectKey != "derivatives/${source.owner.environment}/${source.mediaId}/${output.id}")
                fail(MediaProcessingFailureCode.CONFLICT)
            if (stage == "ready") {
                if (source.storageProtocol != LEGACY_MEDIA_PROTOCOL || output.objectVersionId.isNullOrEmpty() ||
                    output.objectVersionId.encodeToByteArray().size > 4096 || output.writeAttemptedAt != null)
                    fail(MediaProcessingFailureCode.CONFLICT)
            } else if (source.storageProtocol != SUPABASE_MEDIA_PROTOCOL || output.objectVersionId != null ||
                output.writeAttemptedAt == null || output.writeAttemptedAt >= output.acceptanceDeadline ||
                output.acknowledgedAt < output.writeAttemptedAt) fail(MediaProcessingFailureCode.CONFLICT)
            buildJsonObject {
                put("id", output.id.toString()); put("jobId", jobId.toString()); put("variant", output.variant.wire)
                put("key", output.objectKey); put("sha256", output.sha256); put("bytes", output.bytes); put("contentType", output.contentType)
                put("width", output.width); put("height", output.height); put("acceptanceDeadline", output.acceptanceDeadline.toString())
                put("objectVersionId", output.objectVersionId?.let(::JsonPrimitive) ?: JsonNull)
                put("protocol", output.storageProtocol); put("bucket", output.bucket?.let(::JsonPrimitive) ?: JsonNull)
                put("writeAttemptedAt", output.writeAttemptedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("acknowledgedAt", output.acknowledgedAt.toString())
            }
        }).toString()
        val evidenceText = buildJsonObject {
            put("receiptId", evidence.receiptId); put("revision", evidence.revision); put("sourceSha256", evidence.sourceSha256)
            put("derivativeSha256", buildJsonObject { evidence.derivativeSha256.entries.sortedBy { it.key.wire }.forEach { put(it.key.wire, it.value) } })
            put("assessedAt", evidence.assessedAt.toString()); put("validUntil", evidence.validUntil.toString())
        }.toString()
        val text = buildJsonObject {
            put("formatVersion", 1); put("jobId", jobId.toString()); put("environment", source.owner.environment)
            put("ownerId", source.owner.ownerId.toString()); put("mediaId", source.mediaId.toString())
            put("source", Json.parseToJsonElement(sourceText)); put("policyRevision", policyRevision); put("codecRevision", codecRevision)
            put("evidence", Json.parseToJsonElement(evidenceText)); put("outputs", Json.parseToJsonElement(outputText))
            put("stage", stage); put("mediaVersion", mediaVersion)
        }.toString()
        if (sourceText.encodeToByteArray().size > 16384 || evidenceText.encodeToByteArray().size > 4096 ||
            outputText.encodeToByteArray().size > 32768 || text.encodeToByteArray().size > 65536) fail(MediaProcessingFailureCode.LIMIT_EXCEEDED)
        return Image(sourceText, evidenceText, outputText, text)
    }

    private fun read(c: Connection, jobId: UUID, exclusive: Boolean = false): Row? = query(c,
        "SELECT * FROM platform.media_safety_records WHERE job_id=? FOR ${if (exclusive) "UPDATE" else "SHARE"} NOWAIT", jobId) { r ->
        if (!r.next()) null else {
            val text = r.getString("record_text"); val value = Json.parseToJsonElement(text).jsonObject
            val sourceText = r.getString("source_text"); val evidenceText = r.getString("evidence_text"); val outputsText = r.getString("outputs_text")
            if (sha(text) != r.getString("record_sha256") || sha(sourceText) != r.getString("source_sha256") ||
                sha(evidenceText) != r.getString("evidence_sha256") || sha(outputsText) != r.getString("outputs_sha256") ||
                value.keys != setOf("formatVersion", "jobId", "environment", "ownerId", "mediaId", "source", "policyRevision", "codecRevision", "evidence", "outputs", "stage", "mediaVersion") ||
                value["formatVersion"] != JsonPrimitive(1) || value["jobId"] != JsonPrimitive(jobId.toString()) ||
                value["environment"] != JsonPrimitive(r.getString("environment")) || value["ownerId"] != JsonPrimitive(r.getObject("owner_user_id", UUID::class.java).toString()) ||
                value["mediaId"] != JsonPrimitive(r.getObject("media_id", UUID::class.java).toString()) ||
                value["source"] != Json.parseToJsonElement(sourceText) || value["evidence"] != Json.parseToJsonElement(evidenceText) ||
                value["outputs"] != Json.parseToJsonElement(outputsText) || value["policyRevision"] != JsonPrimitive(r.getString("policy_revision")) ||
                value["codecRevision"] != JsonPrimitive(r.getString("codec_revision")) || value["stage"] != JsonPrimitive(r.getString("stage")) ||
                value["mediaVersion"] != JsonPrimitive(r.getLong("media_version"))) fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
            val e = value.getValue("evidence").jsonObject
            if (e.keys != setOf("receiptId", "revision", "sourceSha256", "derivativeSha256", "assessedAt", "validUntil"))
                fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
            val hashes = e.getValue("derivativeSha256").jsonObject
            if (hashes.keys != setOf("thumbnail", "display")) fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
            val evidence = MediaSafetyEvidence(e.getValue("receiptId").jsonPrimitive.content, e.getValue("revision").jsonPrimitive.content,
                e.getValue("sourceSha256").jsonPrimitive.content, PhotoVariant.entries.associateWith { hashes.getValue(it.wire).jsonPrimitive.content },
                Instant.parse(e.getValue("assessedAt").jsonPrimitive.content), Instant.parse(e.getValue("validUntil").jsonPrimitive.content))
            val recorded = r.getObject("recorded_at", OffsetDateTime::class.java).toInstant()
            if (recorded < evidence.assessedAt || recorded >= evidence.validUntil || r.next()) fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
            Row(text, value, evidence)
        }
    }
    private fun revocation(c: Connection, jobId: UUID): String? = query(c,
        "SELECT r.*,s.record_sha256 AS original_sha FROM platform.media_safety_revocations r " +
            "JOIN platform.media_safety_records s ON s.job_id=r.job_id WHERE r.job_id=?", jobId) { r ->
        if (!r.next()) null else {
            val original = MediaSafetyRevocation(r.getString("environment"), jobId, r.getString("record_sha256"),
                r.getObject("revocation_id", UUID::class.java), r.getObject("operator_id", UUID::class.java), r.getString("authorization_reference"))
            if (original.exactDocument != r.getString("request_text") || original.requestSha256 != r.getString("request_sha256") ||
                original.recordSha256 != r.getString("original_sha") || r.next()) fail(MediaProcessingFailureCode.STORAGE_UNAVAILABLE)
            original.exactDocument
        }
    }
    private fun <T> query(c: Connection, sql: String, id: UUID, body: (ResultSet) -> T): T = try {
        c.prepareStatement(sql).use { it.setObject(1, id); it.executeQuery().use(body) }
    } catch (failure: SQLException) {
        if (failure.sqlState != "55P03") throw failure
        throw SQLException("Media safety record contended", "40001").also { retry -> failure.suppressed.forEach(retry::addSuppressed) }
    }
    private fun transaction(c: Connection) {
        processingCurrent()
        check(!c.isClosed && !c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
    }
    private fun sha(text: String) = processingHash(text.encodeToByteArray(throwOnInvalidSequence = true))
    private fun fail(code: MediaProcessingFailureCode): Nothing = processingFail(code)
}
