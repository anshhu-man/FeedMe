package com.feedme.server.staff

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Reads actual owner-managed qualifications. The caller separately authenticates
 * the reviewer, binds the attestation to the reviewed material and retains this
 * proof through its final same-transaction authorization check. A reference is not
 * external credential verification and this store never issues an attestation. */
internal object SupabaseStaffRecipeQualificationStore {
    fun check(c: Connection, environment: String, actorId: UUID, policyVersion: String,
        reviewedAt: Instant, attestation: JsonObject): SupabaseStaffRecipeQualificationProof = qualificationSafe {
        if (!environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) || !reference(policyVersion, 128) ||
            attestation.keys != setOf("qualificationId", "qualificationVersion", "scope", "evidenceReference")) qualificationDenied()
        val idText = attestation["qualificationId"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: qualificationDenied()
        if (!idText.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) qualificationDenied()
        val id = UUID.fromString(idText)
        val version = attestation["qualificationVersion"]?.jsonPrimitive?.takeIf { !it.isString }
            ?.content?.toBigDecimalOrNull()?.let { try { it.longValueExact() } catch (_: ArithmeticException) { null } }
            ?.takeIf { it > 0 } ?: qualificationDenied()
        if (attestation["scope"] != JsonPrimitive("nutritionReview")) qualificationDenied()
        val evidence = attestation["evidenceReference"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: qualificationDenied()
        if (!reference(evidence, 256)) qualificationDenied()
        SupabaseStaffRecipeQualificationServingCompatibility.check(c)
        val observed = observe(c, environment, id)
        val row = observed.row
        if (row.actorId != actorId || row.version != version || row.policyVersion != policyVersion) qualificationDenied()
        validate(row, reviewedAt, observed.now)
        SupabaseStaffRecipeQualificationProof(c, Thread.currentThread(), observed.transactionId,
            environment, row, reviewedAt, observed.now)
    }

    internal fun revalidate(c: Connection, proof: SupabaseStaffRecipeQualificationProof) = qualificationSafe {
        if (Thread.currentThread() !== proof.thread || c !== proof.connection) qualificationDenied()
        SupabaseStaffRecipeQualificationServingCompatibility.check(c)
        val observed = observe(c, proof.environment, proof.row.id)
        if (observed.transactionId != proof.transactionId || observed.row != proof.row || observed.now < proof.checkedAt) qualificationDenied()
        validate(observed.row, proof.reviewedAt, observed.now)
    }

    private fun observe(c: Connection, environment: String, id: UUID): QualificationObservation {
        requireTransaction(c)
        val row = c.prepareStatement("SELECT qualification_id,version,actor_id,scope,evidence_reference,policy_version," +
            "verified_at,not_before,valid_until,enabled FROM ONLY staff.reviewer_qualifications " +
            "WHERE environment=? AND qualification_id=? FOR SHARE").use { s ->
            s.setString(1, environment); s.setObject(2, id)
            s.executeQuery().use { r ->
                if (!r.next()) qualificationDenied()
                QualificationRow(r.getObject(1, UUID::class.java), r.getLong(2), r.getObject(3, UUID::class.java),
                    r.getString(4), r.getString(5), r.getString(6), r.getObject(7, OffsetDateTime::class.java).toInstant(),
                    r.getObject(8, OffsetDateTime::class.java).toInstant(), r.getObject(9, OffsetDateTime::class.java).toInstant(),
                    r.getBoolean(10)).also { if (r.next()) qualificationUnavailable() }
            }
        }
        return c.createStatement().use { s -> s.executeQuery("SELECT txid_current(),clock_timestamp()").use { r ->
            if (!r.next()) qualificationUnavailable()
            QualificationObservation(row, r.getLong(1), r.getObject(2, OffsetDateTime::class.java).toInstant())
                .also { if (r.next()) qualificationUnavailable() }
        } }
    }

    private fun validate(row: QualificationRow, reviewedAt: Instant, now: Instant) {
        if (row.version <= 0 || row.scope != "nutritionReview" || !reference(row.evidenceReference, 256) ||
            !reference(row.policyVersion, 128) || row.notBefore >= row.validUntil || row.verifiedAt >= row.validUntil)
            qualificationUnavailable()
        if (!row.enabled || row.verifiedAt > reviewedAt || row.notBefore > reviewedAt || reviewedAt >= row.validUntil ||
            reviewedAt > now || row.notBefore > now || now >= row.validUntil) qualificationDenied()
    }
    private fun reference(value: String, max: Int) = value.isNotBlank() && value.length <= max && value.none(Char::isISOControl)
    private fun requireTransaction(c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff qualification interrupted")
        if (c.isClosed || c.autoCommit || c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) qualificationDenied()
    }
    private data class QualificationObservation(val row: QualificationRow, val transactionId: Long, val now: Instant)
}

/** An in-transaction observation, not a bearer capability or reusable credential. */
internal class SupabaseStaffRecipeQualificationProof internal constructor(
    internal val connection: Connection,
    internal val thread: Thread,
    internal val transactionId: Long,
    internal val environment: String,
    internal val row: QualificationRow,
    internal val reviewedAt: Instant,
    internal val checkedAt: Instant,
) {
    val validUntil: Instant get() = row.validUntil
    fun revalidate(c: Connection) = SupabaseStaffRecipeQualificationStore.revalidate(c, this)
    override fun toString() = "SupabaseStaffRecipeQualificationProof(<redacted>)"
}

internal data class QualificationRow(val id: UUID, val version: Long, val actorId: UUID, val scope: String,
    val evidenceReference: String, val policyVersion: String, val verifiedAt: Instant, val notBefore: Instant,
    val validUntil: Instant, val enabled: Boolean) {
    override fun toString() = "QualificationRow(<redacted>)"
}

private fun qualificationDenied(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.INDEPENDENT_REVIEW_REQUIRED)
private fun qualificationUnavailable(): Nothing = throw SupabaseStaffRecipeFailure(SupabaseStaffRecipeFailureCode.STORAGE_UNAVAILABLE)
private fun <T> qualificationSafe(action: () -> T): T = try { action() }
    catch (f: SupabaseStaffRecipeFailure) { throw f }
    catch (f: CancellationException) { throw f }
    catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
    catch (f: SQLException) { if (f.sqlState in setOf("40001", "40P01")) throw f; qualificationUnavailable() }
    catch (_: Exception) { qualificationUnavailable() }
