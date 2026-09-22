package com.feedme.server.reuse

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.db.*
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.planning.PlanningServiceFailure
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class AccountReusePolicy(val maxResponseBytes: Int, val maxCandidates: Long, val maxCatalogPages: Long,
    val maxRelationships: Int, val proposalLifetimeSeconds: Int, val cursorLifetimeSeconds: Int,
    val maxRequestsPerUtcDay: Int) {
    init {
        require(maxResponseBytes in 4096..262144 && maxCandidates in 1..100000 && maxCatalogPages in 1..100000)
        require(maxRelationships in 1..1000 && proposalLifetimeSeconds in 60..86400 && cursorLifetimeSeconds in 1..600)
        require(cursorLifetimeSeconds <= proposalLifetimeSeconds && maxRequestsPerUtcDay in 1..10000)
    }
    internal fun document() = buildJsonObject { put("version", 1); put("maxResponseBytes", maxResponseBytes); put("maxCandidates", maxCandidates)
        put("maxCatalogPages", maxCatalogPages); put("maxRelationships", maxRelationships); put("proposalLifetimeSeconds", proposalLifetimeSeconds)
        put("cursorLifetimeSeconds", cursorLifetimeSeconds); put("maxRequestsPerUtcDay", maxRequestsPerUtcDay) }
    override fun toString() = "AccountReusePolicy(<redacted>)"
}
internal enum class ReuseFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), FORBIDDEN(403), RATE_LIMITED(429), SOURCE_UNAVAILABLE(404),
    INPUTS_CHANGED(409), CURSOR_INVALID(409), CURSOR_EXPIRED(410), PROPOSAL_EXPIRED(410),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503), RESPONSE_TOO_LARGE(503)
}
internal class ReuseFailure(val code: ReuseFailureCode) : RuntimeException("Reuse unavailable: ${code.name}")
internal fun reuseFail(code: ReuseFailureCode = ReuseFailureCode.STORAGE_UNAVAILABLE): Nothing = throw ReuseFailure(code)
internal fun reuseSha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray(throwOnInvalidSequence = true))
    .joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun reuseCanonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${reuseCanonical(it.value)}" }
    is JsonArray -> value.joinToString(",", "[", "]") { reuseCanonical(it) }
    is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString() else BigDecimal(value.content).stripTrailingZeros().toPlainString()
}
internal fun reuseJson(text: String, maximum: Int = 1048576): JsonObject = Json.parseToJsonElement(
    WireDocument.parse(text, WireLimits(maximum, 40)).encodeUtf8().decodeToString()).jsonObject
internal fun JsonObject.reuseText(key: String) = getValue(key).jsonPrimitive.let { if (!it.isString) reuseFail(); it.content }
internal fun reuseUuid(text: String): UUID { if (!CanonicalFormats.accepts("uuid", text)) reuseFail(ReuseFailureCode.INPUT_INVALID); return UUID.fromString(text) }
internal fun JsonObject.reuseId(key: String): UUID = reuseUuid(reuseText(key))
internal fun <T> reuseSafe(action: () -> T): T = try { if (Thread.currentThread().isInterrupted) throw InterruptedException("Reuse interrupted"); action() }
    catch (f: ReuseFailure) { throw f }
    catch (f: AccountFailure) { reuseFail(when (f.code) { AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> ReuseFailureCode.UNAUTHENTICATED
        AccountFailureCode.POLICY_BLOCKED -> ReuseFailureCode.FORBIDDEN; AccountFailureCode.NOT_CONFIGURED -> ReuseFailureCode.NOT_CONFIGURED; else -> ReuseFailureCode.STORAGE_UNAVAILABLE }) }
    catch (f: PlanningServiceFailure) { reuseFail(when (f.code.status) { 401 -> ReuseFailureCode.UNAUTHENTICATED; 403 -> ReuseFailureCode.FORBIDDEN
        404,410 -> ReuseFailureCode.SOURCE_UNAVAILABLE; 503 -> ReuseFailureCode.NOT_CONFIGURED; else -> ReuseFailureCode.INPUTS_CHANGED }) }
    catch (f: CommitOutcomeUnknown) { throw f }
    catch (f: CancellationException) { throw f }
    catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
    catch (f: SQLException) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Reuse interrupted")
        if (f.sqlState in setOf("40001","40P01")) throw f; reuseFail() }
    catch (_: Exception) { if (Thread.currentThread().isInterrupted) throw InterruptedException("Reuse interrupted"); reuseFail() }
