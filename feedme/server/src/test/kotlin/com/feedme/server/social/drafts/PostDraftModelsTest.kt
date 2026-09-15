package com.feedme.server.social.drafts

import com.feedme.server.social.VerifiedSocialAccount
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.Test

class PostDraftModelsTest {
    @Test fun servicePolicyRequiresExplicitBoundedLifetimeResponseAndCursor() {
        PostDraftServicePolicy(262144,2_592_000,86400)
        for(v in listOf(0,262145))assertFailsWith<IllegalArgumentException>{PostDraftServicePolicy(v,1,1)}
        for(v in listOf(0,2_592_001))assertFailsWith<IllegalArgumentException>{PostDraftServicePolicy(1,v,1)}
        for(v in listOf(0,86401))assertFailsWith<IllegalArgumentException>{PostDraftServicePolicy(1,1,v)}
    }
    @Test fun cursorKeysRequireExplicitValidIdentityAndDefensiveCopies() {
        val keys=ByteArray(32){1};val c=PostDraftCursors("k",mapOf("k" to keys));val a=actor();val id=UUID.randomUUID()
        val token=c.encode(a,9,2,id,NOW.plusSeconds(30));keys.fill(9)
        assertEquals(id,c.decode(a,9,2,token,NOW))
        assertFailsWith<IllegalArgumentException>{PostDraftCursors("none",mapOf("k" to keys))}
        assertFailsWith<IllegalArgumentException>{PostDraftCursors("k",mapOf("k" to ByteArray(31)))}
    }
    @Test fun cursorPinsOwnerEnvironmentHeadLimitAndCanonicalUuid() {
        val c=cursors();val a=actor();val id=UUID.randomUUID();val t=c.encode(a,2,3,id,NOW.plusSeconds(30))
        assertEquals(id,c.decode(a,2,3,t,NOW))
        val otherDevice=VerifiedSocialAccount(a.environment,a.accountId,UUID.randomUUID())
        assertEquals(id,c.decode(otherDevice,2,3,t,NOW)) // Current device is independently reverified by the store.
        for(f in listOf<()->Unit>({c.decode(actor(),2,3,t,NOW)}, {c.decode(VerifiedSocialAccount("other",a.accountId,a.deviceSessionId),2,3,t,NOW)},
            {c.decode(a,3,3,t,NOW)},{c.decode(a,2,4,t,NOW)},{c.decode(a,2,3,t.replace(id.toString(),id.toString().uppercase()),NOW)}))
            assertEquals(PostDraftFailureCode.CURSOR_INVALID,assertFailsWith<PostDraftFailure>{f()}.code)
    }
    @Test fun expiryNeverTurnsPaginationIntoLifetimeRenewal() {
        val c=cursors();val a=actor();val t=c.encode(a,1,1,UUID.randomUUID(),NOW)
        assertEquals(PostDraftFailureCode.CURSOR_EXPIRED,assertFailsWith<PostDraftFailure>{c.decode(a,1,1,t,NOW)}.code)
        assertNull(c.decode(a,1,1,null,NOW))
    }
    @Test fun malformedOversizedUnknownKeyAndNoncanonicalNumbersAreRejected() {
        val c=cursors();val a=actor();val t=c.encode(a,1,1,UUID.randomUUID(),NOW.plusSeconds(10))
        for(v in listOf("", "x".repeat(2049),t.replaceFirst("k.","unknown."),t.replaceFirst(".1.",".01."),t+".extra",t.dropLast(1)+"!"))
            assertEquals(PostDraftFailureCode.CURSOR_INVALID,assertFailsWith<PostDraftFailure>{c.decode(a,1,1,v,NOW)}.code)
    }
    @Test fun longPageRevisionsRemainExactAboveDoublePrecision() {
        val c=cursors();val a=actor();val id=UUID.randomUUID();val t=c.encode(a,Long.MAX_VALUE,50,id,NOW.plusSeconds(1))
        assertEquals(id,c.decode(a,Long.MAX_VALUE,50,t,NOW))
        assertEquals(PostDraftFailureCode.CURSOR_INVALID,assertFailsWith<PostDraftFailure>{c.decode(a,Long.MAX_VALUE-1,50,t,NOW)}.code)
    }
    @Test fun publicDiagnosticsContainOnlyFinitePolicyNamesNotOwnerCursorOrContent() {
        val a=actor();val diagnostics=listOf(a.toString(),cursors().toString(),PostDraftServicePolicy(1024,300,30).toString(),PostDraftFailure(PostDraftFailureCode.FORBIDDEN).toString())
        diagnostics.forEach{assertFalse(it.contains(a.accountId.toString()));assertFalse(it.contains(a.deviceSessionId.toString()))}
        assertEquals(403,PostDraftFailureCode.FORBIDDEN.status);assertEquals(410,PostDraftFailureCode.DRAFT_EXPIRED.status)
    }
    companion object {
        private val NOW=Instant.parse("2026-09-14T00:00:00Z")
        private fun actor()=VerifiedSocialAccount("test",UUID.randomUUID(),UUID.randomUUID())
        private fun cursors()=PostDraftCursors("k",mapOf("k" to ByteArray(32){1}))
    }
}
