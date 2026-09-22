package com.feedme.server.social.reports

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.identity.AccountProfileStore
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Actual account-owned complaints, independent of social onboarding/Terms. Evidence,
 * untriaged case, minimal queue event and the original received receipt commit together.
 * Exact retry never needs the reported target to remain visible or unchanged. No target
 * removal, staff assignment, moderation decision, notification or encryption is implied. */
internal class AccountReportStore(private val environment: String, private val transactions: PgTransactions,
    private val accounts: AccountProfileStore, private val targets: ReportTargetAuthority, val policy: ReportServicePolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && accounts.environment == environment) }

    fun createReport(subject: VerifiedSupabaseSubject, device: UUID, key: UUID, body: JsonObject): CommandResult = safe {
        val input = request(body)
        val targetType = input.getValue("targetType").jsonPrimitive.content
        val targetId = UUID.fromString(input.getValue("targetId").jsonPrimitive.content)
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, owner), "createReport", key, body = input)
            var originalEvidence: ReportTargetEvidence? = null
            val result = commands.executeInTransaction(c, command, { current(it, subject, device, owner) }, {},
                { db, cached ->
                    val report = byCommand(db, owner, key) ?: fail(ReportFailureCode.STORAGE_UNAVAILABLE)
                    validateOriginal(report, command, input)
                    val original = reply("createReport", 201, json(report, original = true), 1)
                    validateReply("createReport", cached)
                    if (cached.status != original.status || cached.body != original.body || cached.etag != original.etag)
                        fail(ReportFailureCode.STORAGE_UNAVAILABLE)
                }) { db ->
                val captured = capture(db, owner, targetType, targetId)
                val at = now(db)
                validAt(captured, at)
                originalEvidence = captured
                val id = UUID.randomUUID(); val caseId = UUID.randomUUID(); val eventId = UUID.randomUUID()
                exec(db, "INSERT INTO safety.moderation_cases(environment,id,report_id,target_type,target_id,version,priority,status,action,created_at,updated_at) " +
                    "VALUES(?,?,?,?,?,1,'normal','open','none',?,?)") {
                    setString(1, environment); setObject(2, caseId); setObject(3, id); setString(4, targetType); setObject(5, targetId)
                    setObject(6, time(at)); setObject(7, time(at))
                }
                exec(db, "INSERT INTO safety.reports(environment,reporter_user_id,id,case_id,command_key,request_sha256," +
                    "target_type,target_id,reason,description,status,version,creation_event_id,created_at,updated_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,'received',1,?,?,?)") {
                    setString(1, environment); setObject(2, owner); setObject(3, id); setObject(4, caseId); setObject(5, key)
                    setString(6, command.requestHash); setString(7, targetType); setObject(8, targetId)
                    setString(9, input.getValue("reason").jsonPrimitive.content); setString(10, input["description"]?.jsonPrimitive?.content)
                    setObject(11, eventId); setObject(12, time(at)); setObject(13, time(at))
                }
                val evidence = evidence(captured)
                exec(db, "INSERT INTO safety.report_evidence(environment,reporter_user_id,report_id,target_type,target_id," +
                    "target_owner_id,target_version,evidence_text,evidence_sha256,valid_until,captured_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)") {
                    setString(1, environment); setObject(2, owner); setObject(3, id); setString(4, captured.targetType); setObject(5, captured.targetId)
                    setObject(6, captured.targetOwnerId); setLong(7, captured.version); setString(8, evidence); setString(9, hash(evidence))
                    setObject(10, captured.validUntil?.let(::time)); setObject(11, time(at))
                }
                outbox.append(db, EventDraft(eventId, "safety.report.created.v1", 1, "report", id, 1, "safety", key.toString(), key,
                    buildJsonObject { put("reportId", id.toString()); put("caseId", caseId.toString()); put("priority", "normal") }))
                val report = read(db, owner, id) ?: fail(ReportFailureCode.STORAGE_UNAVAILABLE)
                validateOriginal(report, command, input)
                reply("createReport", 201, json(report, original = true), 1)
            }
            // AFTER receipt persistence: changed/withdrawn capture never becomes a successful
            // complaint about different material. A historical retry intentionally skips this.
            if (result is CommandResult.Applied) {
                val first = originalEvidence ?: fail(ReportFailureCode.STORAGE_UNAVAILABLE)
                val fresh = capture(c, owner, targetType, targetId)
                if (evidence(first) != evidence(fresh)) fail(ReportFailureCode.TARGET_UNAVAILABLE)
            }
            current(c, subject, device, owner)
            val finalTime = now(c)
            if (subject.expiresAtEpochSeconds <= finalTime.epochSecond) fail(ReportFailureCode.UNAUTHENTICATED)
            originalEvidence?.let { validAt(it, finalTime) }
            result
        }
    }

    fun getMyReport(subject: VerifiedSupabaseSubject, device: UUID, reportId: UUID): StoredReply = safe {
        transactions.run { c ->
            val owner = accounts.lockAccountSafety(c, subject, device)
            val report = read(c, owner, reportId) ?: fail(ReportFailureCode.REPORT_UNAVAILABLE)
            val reply = reply("getMyReport", 200, json(report), report.version)
            current(c, subject, device, owner)
            if (subject.expiresAtEpochSeconds <= now(c).epochSecond) fail(ReportFailureCode.UNAUTHENTICATED)
            reply
        }
    }

    private fun capture(c: Connection, reporter: UUID, type: String, id: UUID): ReportTargetEvidence =
        targets.capture(c, reporter, type, id).also {
            if (it.targetType != type || it.targetId != id) fail(ReportFailureCode.STORAGE_UNAVAILABLE)
        }
    private fun current(c: Connection, subject: VerifiedSupabaseSubject, device: UUID, owner: UUID) {
        if (accounts.lockAccountSafety(c, subject, device) != owner) fail(ReportFailureCode.UNAUTHENTICATED)
    }
    private fun validAt(evidence: ReportTargetEvidence, at: Instant) {
        if (evidence.validUntil?.let { at >= it } == true) fail(ReportFailureCode.TARGET_UNAVAILABLE)
    }
    private class Row(val id: UUID, val commandKey: UUID, val requestHash: String, val targetType: String,
        val targetId: UUID, val reason: String, val description: String?, val status: String, val version: Long,
        val createdAt: Instant, val updatedAt: Instant)
    private fun read(c: Connection, owner: UUID, id: UUID): Row? = query(c,
        "SELECT * FROM safety.reports WHERE environment=? AND reporter_user_id=? AND id=? FOR SHARE NOWAIT",
        { setString(1, environment); setObject(2, owner); setObject(3, id) }, ::one)
    private fun byCommand(c: Connection, owner: UUID, key: UUID): Row? = query(c,
        "SELECT * FROM safety.reports WHERE environment=? AND reporter_user_id=? AND command_key=? FOR SHARE NOWAIT",
        { setString(1, environment); setObject(2, owner); setObject(3, key) }, ::one)
    private fun one(r: ResultSet): Row? = if (!r.next()) null else Row(r.getObject("id", UUID::class.java),
        r.getObject("command_key", UUID::class.java), r.getString("request_sha256"), r.getString("target_type"),
        r.getObject("target_id", UUID::class.java), r.getString("reason"), r.getString("description"), r.getString("status"), r.getLong("version"),
        r.getObject("created_at", OffsetDateTime::class.java).toInstant(), r.getObject("updated_at", OffsetDateTime::class.java).toInstant()
    ).also { if (r.next()) fail(ReportFailureCode.STORAGE_UNAVAILABLE) }
    private fun validateOriginal(row: Row, command: CommandIdentity, input: JsonObject) {
        if (row.commandKey != command.key || row.requestHash != command.requestHash ||
            row.targetType != input.getValue("targetType").jsonPrimitive.content ||
            row.targetId != UUID.fromString(input.getValue("targetId").jsonPrimitive.content) ||
            row.reason != input.getValue("reason").jsonPrimitive.content || row.description != input["description"]?.jsonPrimitive?.content)
            fail(ReportFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun json(row: Row, original: Boolean = false) = buildJsonObject {
        put("id", row.id.toString()); put("version", if (original) 1 else row.version)
        put("createdAt", row.createdAt.toString()); put("updatedAt", (if (original) row.createdAt else row.updatedAt).toString())
        put("targetType", row.targetType); put("targetId", row.targetId.toString()); put("reason", row.reason)
        row.description?.let { put("description", it) }; put("status", if (original) "received" else row.status)
    }
    private fun evidence(value: ReportTargetEvidence) = buildJsonObject {
        put("formatVersion", 1); put("targetType", value.targetType); put("targetId", value.targetId.toString())
        put("targetOwnerId", value.targetOwnerId.toString()); put("version", value.version); put("material", value.material)
        put("validUntil", value.validUntil?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    }.toString()
    private fun request(body: JsonObject): JsonObject {
        val bytes = try { body.toString().encodeToByteArray(throwOnInvalidSequence = true) }
            catch (_: CharacterCodingException) { fail(ReportFailureCode.INPUT_INVALID) }
            catch (_: IllegalArgumentException) { fail(ReportFailureCode.INPUT_INVALID) }
        if (bytes.size > 65536 || validator.validateRequest("createReport", bytes, "application/json") != BodyValidationResult.Valid)
            fail(ReportFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }
    private fun reply(operation: String, status: Int, body: JsonObject, version: Long) =
        StoredReply(status, body, "\"$version\"").also { validateReply(operation, it) }
    private fun validateReply(operation: String, value: StoredReply) {
        val bytes = value.body?.toString()?.encodeToByteArray(throwOnInvalidSequence = true)
        if ((bytes?.size ?: 0) > policy.maxResponseBytes ||
            validator.validateResponse(operation, value.status, bytes, if (bytes == null) null else "application/json") != BodyValidationResult.Valid)
            fail(ReportFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    private fun time(value: Instant) = OffsetDateTime.ofInstant(value, ZoneOffset.UTC)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray(throwOnInvalidSequence = true))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun exec(c: Connection, statement: String, bind: PreparedStatement.() -> Unit) = sql {
        c.prepareStatement(statement).use { it.bind(); check(it.executeUpdate() == 1) }
    }
    private fun <T> query(c: Connection, statement: String, bind: PreparedStatement.() -> Unit, body: (ResultSet) -> T): T = sql {
        c.prepareStatement(statement).use { it.bind(); it.executeQuery().use(body) }
    }
    private fun <T> sql(action: () -> T): T = try { action() } catch (failure: SQLException) {
        if (failure.sqlState != "55P03") throw failure
        throw SQLException("Report state contended", "40001").also { retry -> failure.suppressed.forEach(retry::addSuppressed) }
    }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: ReportFailure) { throw failure }
        catch (failure: AccountFailure) { throw ReportFailure(when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> ReportFailureCode.UNAUTHENTICATED
            AccountFailureCode.NOT_CONFIGURED -> ReportFailureCode.NOT_CONFIGURED
            else -> ReportFailureCode.STORAGE_UNAVAILABLE
        }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) } }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: Exception) { throw ReportFailure(ReportFailureCode.STORAGE_UNAVAILABLE).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) } }
    override fun toString() = "AccountReportStore(<redacted>)"
    companion object {
        private val validator by lazy { ContractBodyValidator.bundled() }
        private fun fail(code: ReportFailureCode): Nothing = throw ReportFailure(code)
    }
}
