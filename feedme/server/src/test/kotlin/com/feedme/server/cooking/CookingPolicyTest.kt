package com.feedme.server.cooking

import com.feedme.contracts.WireDocument
import com.feedme.server.db.*
import com.feedme.server.planning.*
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Input/configuration tests only. Every test adapter rejects database/authority execution. */
class CookingPolicyTest {
    @Test fun accountRequiresOnlyVerifiedDeviceAndGuestRequiresOnlyInternalVerifiedSession() {
        val id = UUID.randomUUID(); val device = UUID.randomUUID()
        assertFailsWith<IllegalArgumentException> { VerifiedCookingPrincipal("test", CommandActor.ACCOUNT, id, null) }
        assertFailsWith<IllegalArgumentException> { VerifiedCookingPrincipal("test", CommandActor.ACCOUNT, id, device, device) }
        assertFailsWith<IllegalArgumentException> { VerifiedCookingPrincipal("test", CommandActor.GUEST, id, null) }
        assertFailsWith<IllegalArgumentException> { VerifiedCookingPrincipal("test", CommandActor.GUEST, id, device, device) }
        assertEquals(device, VerifiedCookingPrincipal("test", CommandActor.ACCOUNT, id, device).deviceSessionId)
        assertEquals(device, VerifiedCookingPrincipal("test", CommandActor.GUEST, id, null, device).guestSessionId)
    }
    @Test fun staffAndMalformedEnvironmentsAreNeverCookingPrincipals() {
        for (env in listOf("", "UPPER", "test/private", "t".repeat(41)))
            assertFailsWith<IllegalArgumentException> { VerifiedCookingPrincipal(env, CommandActor.ACCOUNT, UUID.randomUUID(), UUID.randomUUID()) }
        assertFailsWith<IllegalArgumentException> { VerifiedCookingPrincipal("test", CommandActor.STAFF, UUID.randomUUID(), UUID.randomUUID()) }
    }
    @Test fun responseAndRetentionPoliciesAreExplicitBoundedAndDoNotInventDefaultRetention() {
        for (size in listOf(0, -1, 262145)) assertFailsWith<IllegalArgumentException> { CookingServicePolicy(size, 60) }
        for (seconds in listOf(0, 59, 2_592_001)) assertFailsWith<IllegalArgumentException> { CookingServicePolicy(65536, seconds) }
        assertEquals(262144, CookingServicePolicy(262144, 60).maxResponseBytes)
        assertEquals(2_592_000, CookingServicePolicy(1, 2_592_000).sessionRetentionSeconds)
    }
    @Test fun storeCannotComposePlanningFromAnotherEnvironment() {
        val f = Fixture("other")
        assertFailsWith<IllegalArgumentException> { CookingStore("test", f.transactions, f.authority, f.plans, CookingServicePolicy(65536, 86400)) }
        assertEquals(0, f.connections)
    }
    @Test fun debugAndTypedFailureMessagesExcludeIdentitiesInstructionsAndPolicyMaterial() {
        val principal = actor(); val values = listOf(principal.toString(), CookingServicePolicy(65536, 86400).toString(),
            CookingFailure(CookingFailureCode.INPUT_INVALID).toString())
        values.forEach { assertFalse(it.contains(principal.principalId.toString())); assertFalse(it.contains(principal.deviceSessionId.toString())) }
    }
    @Test fun unknownFieldsNullsAndWrongScalarTypesRejectBeforeAnyDatabaseAccess() {
        val f = Fixture(); val store = f.store()
        for (body in listOf(parse("{}"), parse("{\"planId\":null}"), parse("{\"planId\":5}"),
            buildJsonObject { put("planId", UUID.randomUUID().toString()); put("ownerId", UUID.randomUUID().toString()) }))
            denied(CookingFailureCode.INPUT_INVALID) { store.createCookSession(actor(), UUID.randomUUID(), body) }
        assertEquals(0, f.connections)
    }
    @Test fun mutationSequencesRequireExactNonnegativeLongIntegersWithoutRounding() {
        val f = Fixture(); val store = f.store()
        for (sequence in listOf("-1", "0.1", "9223372036854775808", "1e100", "\"1\"")) {
            denied(CookingFailureCode.INPUT_INVALID) { store.createCookSession(actor(), UUID.randomUUID(), parse("{\"planId\":\"${UUID.randomUUID()}\",\"deviceSequence\":$sequence}")) }
            denied(CookingFailureCode.INPUT_INVALID) { store.completeCookSession(actor(), UUID.randomUUID(), UUID.randomUUID(), parse("{\"makeAgain\":false,\"deviceSequence\":$sequence}")) }
        }
        assertEquals(0, f.connections)
    }
    @Test fun malformedOriginalVersionHeadersAreRejectedWithoutAReceipt() {
        val f = Fixture()
        for (etag in listOf("1", "W/\"1\"", "\"0\"", "*", "\"1\",\"2\"", "\"9223372036854775808\""))
            denied(CookingFailureCode.INPUT_INVALID) { f.store().updateCookSession(actor(), UUID.randomUUID(), UUID.randomUUID(), etag, parse("{\"deviceSequence\":1}")) }
        assertEquals(0, f.connections)
    }
    @Test fun malformedUnicodeAndOversizeRequestsStaySanitizedAndHaveNoEffects() {
        val f = Fixture()
        for (text in listOf("\uD800", "x".repeat(65536))) {
            val request = buildJsonObject { put("deviceSequence", 1); put("currentStepId", text) }
            denied(CookingFailureCode.INPUT_INVALID) { f.store().updateCookSession(actor(), UUID.randomUUID(), UUID.randomUUID(), "\"1\"", request) }
        }
        assertEquals(0, f.connections)
    }
    @Test fun makeAgainTrueFailsHonestlyBeforeCreatingAnyCompletionReceiptOrSave() {
        val f = Fixture()
        denied(CookingFailureCode.NOT_CONFIGURED) { f.store().completeCookSession(actor(), UUID.randomUUID(), UUID.randomUUID(), parse("{\"makeAgain\":true,\"deviceSequence\":1}")) }
        assertEquals(0, f.connections)
    }
    @Test fun validRequestCannotSucceedThroughUnavailableAdapters() {
        val f = Fixture()
        denied(CookingFailureCode.STORAGE_UNAVAILABLE) { f.store().createCookSession(actor(), UUID.randomUUID(), buildJsonObject { put("planId", UUID.randomUUID().toString()) }) }
        assertEquals(1, f.connections)
    }
    @Test fun cookingEventsMatchRegisteredDataFieldsAndDoNotAddTimerOrInstructionPayloads() {
        val registry = checkNotNull(javaClass.getResourceAsStream("/canonical-events.md")).use { it.readBytes().toString(Charsets.UTF_8) }
        for ((name, fields) in mapOf("started" to "principalId, sessionId, planId", "progressed" to "principalId, sessionId, deviceSequence", "completed" to "principalId, sessionId, planId")) {
            val row = registry.lineSequence().single { it.startsWith("| cooking.session.$name.v1 |") }
            assertEquals(fields, row.split('|')[3].trim())
        }
    }
    private class Fixture(planningEnvironment: String = "test") {
        var connections = 0
        val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ ->
            connections++; throw SQLException("Explicit unavailable test data source")
        } as DataSource
        val transactions = PgTransactions(source)
        val authority = object : CookingAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedCookingPrincipal): Unit = error("No accepting test authority")
            override fun requireNewCookingEnabled(connection: Connection, principal: VerifiedCookingPrincipal): Unit = error("No accepting test authority")
            override fun validatePersonalNotes(connection: Connection, principal: VerifiedCookingPrincipal, notes: JsonArray): Unit = error("No accepting test authority")
        }
        val plans = PlansStore(planningEnvironment, transactions, object : PlanningAuthority {
            override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal): Unit = error("No accepting test authority")
            override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal): Unit = error("No accepting test authority")
            override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument): PlanningEvidenceSnapshot = error("No accepting test catalog")
        }, PlanningServicePolicy("test", false, false, 86400, 60), PlanningCursors("key", mapOf("key" to ByteArray(32) { 1 })))
        fun store() = CookingStore("test", transactions, authority, plans, CookingServicePolicy(65536, 86400))
    }
    companion object {
        private fun actor() = VerifiedCookingPrincipal("test", CommandActor.ACCOUNT, UUID.randomUUID(), UUID.randomUUID())
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun denied(code: CookingFailureCode, action: () -> Any?) = assertEquals(code, assertFailsWith<CookingFailure> { action() }.code)
    }
}
