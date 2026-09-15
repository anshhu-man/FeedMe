package com.feedme.mealflow.social

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.ORIGIN
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.SERVER
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.response
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.value
import kotlinx.serialization.json.*
import kotlin.test.*

class PostDraftRecordsTest {
    @Test fun createReceiptAndPureGetCorrelationBindAbsentEmptyAndPresentAltTextExactly() {
        val adapter = PostDraftAdapter(policy())
        for (submitted in listOf(null, "", "Original description")) for (returned in listOf(null, "", "Original description")) {
            val request = WireDocument.parse(buildJsonObject {
                put("clientDraftId", CLIENT); put("caption", "Original"); put("mediaIds", JsonArray(emptyList()))
                put("audience", postSelfAudience()); put("keepOnPlate", false); put("allowRecipeSaves", false)
                submitted?.let { put("altText", it) }
            }.toString())
            val original = PostOriginal(number(10), "createPostDraft", CLIENT, 1, request, null, null, 1)
            val observed = document(draft(CLIENT, "Original").json().jsonObject +
                (returned?.let { mapOf("altText" to JsonPrimitive(it)) } ?: emptyMap()))
            if (submitted == returned) {
                assertNotNull(adapter.receipt(original, response(observed, 201).value()))
                adapter.possibleOriginalResult(original, observed, "\"1\"")
            } else {
                assertFails { adapter.receipt(original, response(observed, 201).value()) }
                assertFails { adapter.possibleOriginalResult(original, observed, "\"1\"") }
            }
        }
    }
    @Test fun largeDecimalVersionsAndOriginalEtagsRemainExactWithoutFloatingPoint() {
        val body = draft(CLIENT, version = "9007199254740993123456789")
        val tag = "\"9007199254740993123456789\""
        val adapter = PostDraftAdapter(policy()); val observed = adapter.observation("getPostDraft", response(body, etag = tag).value(), SERVER)
        assertEquals(tag, observed.etag)
        val codec = PostDraftCodec(ORIGIN, policy())
        val original = PostOriginal(number(10), "updatePostDraft", CLIENT, 1, WireDocument.parse("{\"caption\":\"Next\"}"), body, tag, 10)
        val value = PostRecord(10, listOf(PostLocal(CLIENT, 1, "A draft", null, body, tag)), listOf(CLIENT, number(10)), command = original)
        val decoded = codec.decode(codec.encode(value)); assertEquals(tag, decoded.command!!.etag)
        assertContentEquals(body.encodeUtf8(), decoded.command!!.baseline!!.encodeUtf8())
        assertFails { adapter.observation("getPostDraft", response(body, etag = "\"9007199254740993000000000\"").value(), SERVER) }
        assertEquals("1000", postVersion(draft(CLIENT, version = "1e3")))
    }
    @Test fun terminalIdentityAndTombstoneCapacityNeverPrunesOnTimePassage() {
        val codec = PostDraftCodec(ORIGIN, policy())
        val value = PostRecord(Long.MAX_VALUE, issued = listOf(CLIENT), tombstones = listOf(PostTerminal(CLIENT, SERVER, null)))
        val result = codec.decode(codec.encode(value)); assertEquals(listOf(CLIENT), result.issued); assertEquals(CLIENT, result.tombstones.single().clientId)
        assertFails { codec.encode(value.copy(locals = listOf(PostLocal(CLIENT, 1, "Resurrected", null)))) }
    }
    @Test fun textOnlyPatchPreservesEveryUnownedRemoteFieldAndRejectsSilentLoss() {
        val baseline = document(draft(CLIENT).json().jsonObject + mapOf(
            "altText" to JsonPrimitive("Keep description"), "sourcePostId" to JsonPrimitive(number(20)),
            "saveDisclosureVersion" to JsonPrimitive("reviewed-v1"), "keepOnPlate" to JsonPrimitive(true),
            "allowRecipeSaves" to JsonPrimitive(true), "mediaIds" to JsonArray(listOf(JsonPrimitive(number(21)))),
            "audience" to buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(listOf(JsonPrimitive(number(22)))))
                put("bindings", buildJsonArray { add(buildJsonObject { put("circleId", number(22)); put("authorMembershipGeneration", 7) }) }) }
        ))
        val original = PostOriginal(number(10), "updatePostDraft", CLIENT, 4, WireDocument.parse("{\"caption\":\"Next\"}"), baseline, "\"1\"", 1)
        val next = document(baseline.json().jsonObject + mapOf("caption" to JsonPrimitive("Next"), "version" to JsonPrimitive(2)))
        val adapter = PostDraftAdapter(policy()); assertNotNull(adapter.receipt(original, response(next).value()))
        for (field in listOf("altText", "sourcePostId", "saveDisclosureVersion", "mediaIds", "audience", "allowRecipeSaves", "keepOnPlate")) {
            val damaged = document(next.json().jsonObject - field)
            assertFails { adapter.receipt(original, response(damaged).value()) }
        }
    }
    @Test fun patchCannotTurnIntoAudienceUploadAttachmentOrPublishRequest() {
        val codec = PostDraftCodec(ORIGIN, policy()); val baseline = draft(CLIENT)
        for (body in listOf("{\"caption\":\"Next\",\"keepOnPlate\":true}", "{\"caption\":\"Next\",\"mediaIds\":[]}",
            "{\"caption\":\"Next\",\"clientDraftId\":\"$CLIENT\"}")) {
            val original = PostOriginal(number(10), "updatePostDraft", CLIENT, 1, WireDocument.parse(body), baseline, "\"1\"", 1)
            assertFails { codec.encode(PostRecord(1, listOf(PostLocal(CLIENT, 1, "A draft", null, baseline, "\"1\"")), listOf(CLIENT, number(10)), command = original)) }
        }
    }
    @Test fun boundDeleteRequiresActualBodylessAndEtagless204ForOriginalOperation() {
        val adapter = PostDraftAdapter(policy())
        val original = PostOriginal(number(10), "deletePostDraft", CLIENT, 1, null, draft(CLIENT), "\"1\"", 1)
        assertNull(adapter.receipt(original, ApiReply(204, null)))
        for (reply in listOf(ApiReply(204, PrivateBytes(byteArrayOf())), ApiReply(204, null, etag = "\"1\""),
            ApiReply(200, PrivateBytes(draft(CLIENT).encodeUtf8()), etag = "\"1\"", contentType = "application/json"))) assertFails { adapter.receipt(original, reply) }
    }
    @Test fun matchingContentWrongClientWrongServerAndWrongLifecycleNeverBecomeSaveReceipt() {
        val baseline = draft(CLIENT); val original = PostOriginal(number(10), "updatePostDraft", CLIENT, 1,
            WireDocument.parse("{\"caption\":\"Next\"}"), baseline, "\"1\"", 1)
        val next = document(baseline.json().jsonObject + mapOf("caption" to JsonPrimitive("Next"), "version" to JsonPrimitive(2)))
        for ((field, value) in listOf("clientDraftId" to number(99), "id" to number(99), "status" to "published")) {
            val altered = document(next.json().jsonObject + (field to JsonPrimitive(value)))
            assertFails { PostDraftAdapter(policy()).receipt(original, response(altered).value()) }
        }
    }
    @Test fun getCanObservePublishedButCannotAcknowledgeOrSynthesizePublishingTransition() {
        val published = document(draft(CLIENT).json().jsonObject + mapOf("status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(number(20))))
        val adapter = PostDraftAdapter(policy())
        assertEquals("published", adapter.observation("getPostDraft", response(published).value(), SERVER).status)
        assertFails { adapter.observation("updatePostDraft", response(published).value(), SERVER) }
    }
    @Test fun sameVersionChangedBodyAndVersionRegressionCannotReplaceObservedBaseline() {
        val adapter = PostDraftAdapter(policy()); val baseline = draft(CLIENT, version = "9007199254740993")
        assertFails { adapter.monotone(baseline, draft(CLIENT, "Different", "9007199254740993")) }
        assertFails { adapter.monotone(baseline, draft(CLIENT, version = "9007199254740992")) }
        adapter.monotone(baseline, draft(CLIENT, "Next", "9007199254740994"))
    }
    @Test fun privateRecordRejectsUnknownFieldsSchemasOriginMalformedUtf8AndOversize() {
        val codec = PostDraftCodec(ORIGIN, policy()); val bytes = codec.encode(PostRecord(1))
        val root = WireDocument.decode(bytes.copyForCodec()).json().jsonObject
        for (changed in listOf(root + ("extra" to JsonPrimitive(true)), root + ("schema" to JsonPrimitive(2)), root + ("origin" to JsonPrimitive(SERVER)))) {
            assertFails { codec.decode(PrivateBytes(JsonObject(changed).toString().encodeToByteArray())) }
        }
        assertFails { codec.decode(PrivateBytes(byteArrayOf(0xc3.toByte(), 0x28))) }
        assertFails { codec.decode(PrivateBytes(ByteArray(1_048_577) { 32 })) }
    }
    @Test fun completionAndPendingOriginalCannotCoexistOrLoseStableIssuedIdentity() {
        val codec = PostDraftCodec(ORIGIN, policy()); val baseline = draft(CLIENT)
        val original = PostOriginal(number(10), "deletePostDraft", CLIENT, 1, null, baseline, "\"1\"", 1)
        val record = PostRecord(1, listOf(PostLocal(CLIENT, 1, "A draft", null, baseline, "\"1\"")), listOf(CLIENT, number(10)), command = original)
        codec.decode(codec.encode(record))
        assertFails { codec.encode(record.copy(completion = PostCompletion(number(10), "deletePostDraft", CLIENT, false))) }
        assertFails { codec.encode(record.copy(issued = listOf(CLIENT))) }
    }
    @Test fun updateReceiptBindsExactDecimalSuccessorNotAnyLaterMatchingSnapshot() {
        val baseline = draft(CLIENT, version = "9007199254740993999999999")
        val original = PostOriginal(number(10), "updatePostDraft", CLIENT, 1, WireDocument.parse("{\"caption\":\"Next\"}"),
            baseline, "\"9007199254740993999999999\"", 1)
        val adapter = PostDraftAdapter(policy())
        assertNotNull(adapter.receipt(original, response(draft(CLIENT, "Next", "9007199254740994000000000")).value()))
        assertFails { adapter.receipt(original, response(draft(CLIENT, "Next", "9007199254740994000000001")).value()) }
        assertEquals("100000000000000000000000000", postSuccessor("99999999999999999999999999"))
    }
}
