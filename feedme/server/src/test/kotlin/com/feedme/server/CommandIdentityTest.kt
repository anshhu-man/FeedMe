package com.feedme.server

import com.feedme.server.db.*
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class CommandIdentityTest {
    private val scope = PrincipalScope("local", CommandActor.ACCOUNT, UUID.randomUUID())
    private val key = UUID.randomUUID()
    private fun hash(body: String?) = CommandIdentity(scope,"createPlan",key,body=body?.let(Json::parseToJsonElement)).requestHash

    @Test fun canonicalHashIgnoresObjectOrderingAndNumericFormattingButNotArrayOrdering() {
        assertEquals(hash("{\"b\":1.0,\"a\":{\"c\":2}}"),hash("{\"a\":{\"c\":2.00},\"b\":1}"))
        assertNotEquals(hash("[1,2]"),hash("[2,1]"))
    }
    @Test fun missingBodyJsonNullAndAbsentFieldsAreDistinct() {
        assertNotEquals(hash(null),hash("null"))
        assertNotEquals(hash("{}"),hash("{\"confirmedAt\":null}"))
    }
    @Test fun targetQueryAndExpectedVersionAreBoundToFingerprint() {
        fun command(id:String,version:String?,query:Map<String,List<String>> = emptyMap()) = CommandIdentity(scope,"updateCookSession",key,
            pathParameters=mapOf("sessionId" to id),queryParameters=query,ifMatch=version).requestHash
        assertNotEquals(command("one","1"),command("two","1"))
        assertNotEquals(command("one","1"),command("one","2"))
        assertNotEquals(command("one","1"),command("one","1",mapOf("mode" to listOf("different"))))
    }
    @Test fun rejectsUnsupportedIdentityDomainsUnknownOperationsAndMissingPath() {
        assertFailsWith<IllegalArgumentException> { CommandIdentity(scope,"createGuestSession",key) }
        assertFailsWith<IllegalArgumentException> { CommandIdentity(scope,"getServiceHealth",key) }
        assertFailsWith<IllegalArgumentException> { CommandIdentity(scope,"inventedOperation",key) }
        assertFailsWith<IllegalArgumentException> { CommandIdentity(scope,"updateCookSession",key) }
        assertFailsWith<IllegalArgumentException> { CommandIdentity(PrincipalScope("local",CommandActor.GUEST,UUID.randomUUID()),"publishPost",key) }
    }
    @Test fun responsePreservesNoContentAndJsonNullDistinction() {
        assertNull(StoredReply(204).body)
        assertEquals(JsonNull,StoredReply(200,JsonNull).body)
        assertFailsWith<IllegalArgumentException> { StoredReply(204,JsonNull) }
        assertFailsWith<IllegalArgumentException> { StoredReply(200) }
        assertFailsWith<IllegalArgumentException> { StoredReply(503,JsonObject(emptyMap())) }
    }
    @Test fun rejectsUnsafeHeaderAndBoundedPayloadAndRedactsDiagnostics() {
        assertFailsWith<IllegalArgumentException> { StoredReply(200,JsonNull,"bad\r\nheader") }
        assertFailsWith<IllegalArgumentException> { hash("\""+"a".repeat(262145)+"\"") }
        assertFalse(StoredReply(200,JsonPrimitive("secret"),"private").toString().contains("secret"))
        assertFalse(scope.toString().contains(scope.storageKey))
        assertFalse(CommandIdentity(scope,"createPlan",key).toString().contains(key.toString()))
    }
}
