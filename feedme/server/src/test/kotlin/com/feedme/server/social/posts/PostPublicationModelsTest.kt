package com.feedme.server.social.posts

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class PostPublicationModelsTest {
    @Test fun responseBudgetIsExplicitAndBounded() {
        for(n in listOf(0,-1,262145))assertFailsWith<IllegalArgumentException> { PostPublicationPolicy(n) }
        assertEquals(1,PostPublicationPolicy(1).maxResponseBytes);assertEquals(262144,PostPublicationPolicy(262144).maxResponseBytes)
    }
    @Test fun attachmentAndActualRecipeSnapshotMustBePaired() {
        val data=buildJsonObject { put("id",UUID.randomUUID().toString()) }
        assertFailsWith<IllegalArgumentException> { evidence(attachment=data) }
        assertFailsWith<IllegalArgumentException> { evidence(snapshot=data) }
        assertEquals(data,evidence(attachment=data,snapshot=data).recipeSnapshot)
    }
    @Test fun evidenceIsDetachedFromMutableCallerMapsAndSets() {
        val author=mutableMapOf<String,JsonElement>("displayName" to JsonPrimitive("private"))
        val memberships=mutableMapOf(UUID.randomUUID() to 1L);val capabilities=mutableSetOf("view")
        val e=PostPublicationEvidence(JsonObject(author),memberships,null,null,capabilities,Instant.MAX)
        author.clear();memberships.clear();capabilities.clear()
        assertEquals(1,e.author.size);assertEquals(1,e.memberships.size);assertEquals(setOf("view"),e.capabilities)
    }
    @Test fun generationsAndCapabilitiesCannotBecomeUnboundedAuthority() {
        assertFailsWith<IllegalArgumentException> { PostPublicationEvidence(buildJsonObject{},mapOf(UUID.randomUUID() to 0L),null,null,setOf("view"),Instant.MAX) }
        assertFailsWith<IllegalArgumentException> { PostPublicationEvidence(buildJsonObject{},emptyMap(),null,null,setOf("owner"),Instant.MAX) }
    }
    @Test fun diagnosticStringsDoNotRevealPrivateMaterial() {
        val e=PostPublicationEvidence(buildJsonObject { put("displayName","private secret") },emptyMap(),null,null,setOf("view"),Instant.MAX)
        assertFalse(e.toString().contains("private secret"));assertFalse(PostPublicationPolicy(65432).toString().contains("65432"))
        assertEquals(422,PostPublicationFailureCode.INPUT_INVALID.status);assertEquals(503,PostPublicationFailureCode.NOT_CONFIGURED.status)
    }
    private fun evidence(attachment: JsonObject?=null,snapshot: JsonObject?=null)=PostPublicationEvidence(buildJsonObject{},emptyMap(),attachment,snapshot,emptySet(),Instant.MAX)
}
