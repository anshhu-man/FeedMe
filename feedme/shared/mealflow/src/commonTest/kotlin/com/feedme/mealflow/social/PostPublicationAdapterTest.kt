package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.MealFailure
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure synthetic request/response fixtures. No authority, transport, durable queue, local ACK,
 * SQLite, PostgreSQL, provider or native implementation is configured or implied by these tests. */
class PostPublicationAdapterTest {
    @Test fun directReceiptKeepsCompleteActualResponseAndNeverAddsDraftEchoes() {
        val original = call()
        val raw = "  " + post().toString() + "\n"
        val result = adapter.receipt(original, ACCOUNT, reply(raw = raw))
        assertContentEquals(raw.encodeToByteArray(), result.document.encodeUtf8())
        assertEquals("\"1\"", result.etag)
        val actual = json(result.document)
        for (field in listOf("clientDraftId", "draftId", "draftVersion")) assertFalse(field in actual)
        assertEquals(post(), actual)
    }

    @Test fun savedReceiptPreservesExactLargeOriginalVersionWithoutRequiringEchoFields() {
        val body = write().with("draftId", JsonPrimitive(DRAFT)).with("draftVersion", number("9007199254740993"))
        val original = call(body)
        val before = original.body!!.copyForCodec()
        assertEquals(post(), json(adapter.receipt(original, ACCOUNT, reply()).document))
        assertContentEquals(before, original.body!!.copyForCodec())
        assertEquals("9007199254740993", body.getValue("draftVersion").jsonPrimitive.content)
        assertEquals(setOf("draftId", "draftVersion"), body.keys - write().keys)
    }

    @Test fun halfDraftPairsAreRejectedEvenThoughTheCanonicalSchemaAllowsThem() {
        for (body in listOf(write().with("draftId", JsonPrimitive(DRAFT)), write().with("draftVersion", number("1"))))
            invalid { adapter.receipt(call(body), ACCOUNT, reply()) }
    }

    @Test fun directProjectionNormalizesExactlyServiceUuidFieldsAndStripsClientBindings() {
        val attachment = catalog(RECIPE.uppercase())
        val audience = circles(listOf(CIRCLE_B.uppercase(), CIRCLE_A.uppercase())).with("bindings", JsonArray(listOf(
            binding(CIRCLE_A, "9007199254740993"))))
        val body = write().with("mediaIds", strings(listOf(MEDIA_B.uppercase(), MEDIA_A.uppercase())))
            .with("audience", audience).with("attachment", attachment).with("sourcePostId", JsonPrimitive(SOURCE.uppercase()))
        val original = call(body, raw = " \n" + body.toString() + " ")
        val before = original.body!!.copyForCodec()
        val expected = write().without("clientDraftId")
            .with("mediaIds", strings(listOf(MEDIA_B, MEDIA_A)))
            .with("audience", circles(listOf(CIRCLE_B, CIRCLE_A)))
            .with("attachment", catalog(RECIPE)).with("sourcePostId", JsonPrimitive(SOURCE))
        assertEquals(expected, json(adapter.expectedSelection(original)))
        val actual = post().with("mediaIds", expected.getValue("mediaIds"))
            .with("audience", circles(listOf(CIRCLE_B, CIRCLE_A)).with("bindings", JsonArray(listOf(binding(CIRCLE_A), binding(CIRCLE_B)))))
            .with("attachment", catalog(RECIPE)).with("sourcePostId", JsonPrimitive(SOURCE))
        assertEquals(actual, json(adapter.receipt(original, ACCOUNT, reply(actual)).document))
        assertContentEquals(before, original.body!!.copyForCodec())
        assertNull(original.ifMatch)
    }

    @Test fun savedUppercasePlanAndSourceIdsBindNormalizedPostAndReplayOriginalBytes() {
        val body = write().with("draftId", JsonPrimitive(DRAFT.uppercase())).with("draftVersion", number("9223372036854775806"))
            .with("attachment", catalog(PLAN.uppercase(), plan = true)).with("sourcePostId", JsonPrimitive(SOURCE.uppercase()))
        val original = call(body, raw = "\n" + body.toString() + " ")
        val bytes = original.body!!.copyForCodec()
        val key = original.idempotencyKey!!.use { it }
        val actual = post().with("attachment", catalog(PLAN, plan = true)).with("sourcePostId", JsonPrimitive(SOURCE))
        repeat(2) {
            assertEquals(actual, json(adapter.receipt(original, ACCOUNT, reply(actual)).document))
            assertContentEquals(bytes, original.body!!.copyForCodec())
            assertEquals(key, original.idempotencyKey!!.use { it })
            assertTrue(original.pathParameters.isEmpty()); assertNull(original.ifMatch)
        }
    }

    @Test fun nestedPersonalRecipeUuidsAreNotSilentlyNormalizedByTheProjection() {
        val attachment = personal(recipe(quantity = "1", ingredientId = MEDIA_A.uppercase()))
        val original = call(write().with("attachment", attachment))
        assertEquals(attachment, json(adapter.expectedSelection(original))["attachment"])
        assertEquals(attachment, json(adapter.receipt(original, ACCOUNT, reply(post().with("attachment", attachment))).document)["attachment"])
        invalid { adapter.receipt(original, ACCOUNT, reply(post().with("attachment", personal(recipe(ingredientId = MEDIA_A))))) }
    }

    @Test fun altAbsenceEmptyAndPresentAreDistinctAcrossAllNinePairs() {
        val variants = listOf<JsonElement?>(null, JsonPrimitive(""), JsonPrimitive("Meaningful alt"))
        for (left in variants) for (right in variants) {
            val original = call(write().optional("altText", left))
            val actual = reply(post().optional("altText", right))
            if (left == right) adapter.receipt(original, ACCOUNT, actual)
            else invalid { adapter.receipt(original, ACCOUNT, actual) }
        }
    }

    @Test fun optionalAttachmentAndSourceCannotBeAddedDroppedOrReplaced() {
        for ((field, value) in listOf("attachment" to catalog(RECIPE), "sourcePostId" to JsonPrimitive(SOURCE))) {
            adapter.receipt(call(write().with(field, value)), ACCOUNT, reply(post().with(field, value)))
            invalid { adapter.receipt(call(), ACCOUNT, reply(post().with(field, value))) }
            invalid { adapter.receipt(call(write().with(field, value)), ACCOUNT, reply()) }
        }
        invalid { adapter.receipt(call(write().with("sourcePostId", JsonPrimitive(SOURCE))), ACCOUNT,
            reply(post().with("sourcePostId", JsonPrimitive(RECIPE)))) }
    }

    @Test fun captionAndKeepOnPlateMustMatchTheOriginalExactly() {
        for ((field, value) in listOf("caption" to JsonPrimitive("Other caption"), "keepOnPlate" to JsonPrimitive(true)))
            invalid { adapter.receipt(call(), ACCOUNT, reply(post().with(field, value))) }
        val body = write().with("keepOnPlate", JsonPrimitive(true))
        adapter.receipt(call(body), ACCOUNT, reply(post().with("keepOnPlate", JsonPrimitive(true))))
    }

    @Test fun selectedMediaOrderAndDuplicatesCannotBeSubstituted() {
        val original = call(write().with("mediaIds", strings(listOf(MEDIA_B, MEDIA_A))))
        adapter.receipt(original, ACCOUNT, reply(post().with("mediaIds", strings(listOf(MEDIA_B, MEDIA_A)))))
        for (ids in listOf(listOf(MEDIA_A, MEDIA_B), listOf(MEDIA_B), listOf(MEDIA_B, MEDIA_B)))
            invalid { adapter.receipt(original, ACCOUNT, reply(post().with("mediaIds", strings(ids)))) }
    }

    @Test fun circleSelectionOrderRemainsSeparateFromServerMembershipBindingOrder() {
        val selected = circles(listOf(CIRCLE_B, CIRCLE_A))
        val original = call(write().with("audience", selected))
        val actual = selected.with("bindings", JsonArray(listOf(binding(CIRCLE_A), binding(CIRCLE_B))))
        adapter.receipt(original, ACCOUNT, reply(post().with("audience", actual)))
        invalid { adapter.receipt(original, ACCOUNT, reply(post().with("audience", actual.with("circleIds", strings(listOf(CIRCLE_A, CIRCLE_B)))))) }
    }

    @Test fun clientMembershipGenerationsAreNeverTreatedAsResponseAuthority() {
        val body = write().with("audience", circles(listOf(CIRCLE_A)).with("bindings", JsonArray(listOf(binding(CIRCLE_A, "3")))))
        val actual = post().with("audience", circles(listOf(CIRCLE_A)).with("bindings", JsonArray(listOf(binding(CIRCLE_A, "9007199254740993")))))
        val result = adapter.receipt(call(body), ACCOUNT, reply(actual))
        assertEquals("9007199254740993", json(result.document).getValue("audience").jsonObject.getValue("bindings")
            .jsonArray[0].jsonObject.getValue("authorMembershipGeneration").jsonPrimitive.content)
    }

    @Test fun actualMembershipSetMustBeCompleteUniqueAndBoundToSelectedCircles() {
        invalid { adapter.receipt(call(), ACCOUNT, reply(post().with("audience", post().getValue("audience").jsonObject.without("bindings")))) }
        val original = call(write().with("audience", circles(listOf(CIRCLE_A))))
        for (bindings in listOf(emptyList(), listOf(binding(CIRCLE_B)), listOf(binding(CIRCLE_A), binding(CIRCLE_A))))
            invalid { adapter.receipt(original, ACCOUNT, reply(post().with("audience", circles(listOf(CIRCLE_A)).with("bindings", JsonArray(bindings))))) }
    }

    @Test fun savePermissionAndDisclosureMustMatchWithoutInventingDefaults() {
        val body = write().with("attachment", catalog(RECIPE)).with("allowRecipeSaves", JsonPrimitive(true))
        val valid = post().with("attachment", catalog(RECIPE)).with("savePolicy", savePolicy(true))
        adapter.receipt(call(body), ACCOUNT, reply(valid))
        invalid { adapter.receipt(call(body), ACCOUNT, reply(valid.with("savePolicy", savePolicy(false)))) }
        invalid { adapter.receipt(call(body), ACCOUNT, reply(valid.with("savePolicy", savePolicy(true).with("disclosureVersion", JsonPrimitive("other"))))) }
        invalid { adapter.receipt(call(write().without("saveDisclosureVersion")), ACCOUNT, reply()) }
    }

    @Test fun confirmedChangesOrderRightsAndSourceMaterialCannotChange() {
        val selected = catalog(RECIPE).with("confirmedChanges", strings(listOf("a", "b")))
        val original = call(write().with("attachment", selected))
        for (changed in listOf(selected.with("confirmedChanges", strings(listOf("b", "a"))),
            selected.with("recipeVersionId", JsonPrimitive(PLAN)), selected.with("rightsBasis", JsonPrimitive("creatorOriginal"))))
            invalid { adapter.receipt(original, ACCOUNT, reply(post().with("attachment", changed))) }
    }

    @Test fun mathematicalNumericEqualityDoesNotRoundAndPreservesActualLexemes() {
        val expected = personal(recipe(quantity = "9007199254740993", servings = "0.10000000000000000001"))
        val actual = personal(recipe(quantity = "9007199254740993.000", servings = "1.0000000000000000001e-1"))
        val response = reply(post().with("attachment", actual))
        val result = adapter.receipt(call(write().with("attachment", expected)), ACCOUNT, response)
        assertContentEquals(response.body!!.copyForCodec(), result.document.encodeUtf8())
        assertTrue(result.document.encodeUtf8().decodeToString().contains("9007199254740993.000"))
    }

    @Test fun neighboringBigIntegersAndTinyFractionChangesAreNotEqual() {
        val original = call(write().with("attachment", personal(recipe(quantity = "9007199254740993", servings = "0.10000000000000000001"))))
        for (r in listOf(recipe(quantity = "9007199254740992", servings = "0.10000000000000000001"),
            recipe(quantity = "9007199254740993", servings = "0.10000000000000000002")))
            invalid { adapter.receipt(original, ACCOUNT, reply(post().with("attachment", personal(r)))) }
    }

    @Test fun numericExponentLimitsAndNegativeZeroKeepCanonicalValueSemantics() {
        val original = call(write().with("attachment", personal(recipe(quantity = "-0.0", servings = "1e10000"))))
        val actual = post().with("attachment", personal(recipe(quantity = "0e0", servings = "10e9999")))
        adapter.receipt(original, ACCOUNT, reply(actual))
        invalid { adapter.receipt(original, ACCOUNT, reply(post().with("attachment", personal(recipe(quantity = "0", servings = "1e9999"))))) }
    }

    @Test fun postVersionMustBeExactlyOneWhileItsActualNumericTokenIsPreserved() {
        for (token in listOf("1", "1.0", "1e0")) {
            val actual = post().with("version", number(token))
            assertEquals(token, json(adapter.receipt(call(), ACCOUNT, reply(actual)).document).getValue("version").jsonPrimitive.content)
        }
        for (token in listOf("0", "2", "9007199254740993", "1.0000000000000000001"))
            invalid { adapter.receipt(call(), ACCOUNT, reply(post().with("version", number(token)))) }
    }

    @Test fun etagMustBeTheActualStrongVersionOneTag() {
        for (tag in listOf(null, "1", "W/\"1\"", "\"01\"", "\"2\"", "\"1.0\""))
            invalid { adapter.receipt(call(), ACCOUNT, reply(tag = tag)) }
    }

    @Test fun onlyPublished201ResponseIsCorrelatedNotGetOrOtherSuccess() {
        for (status in listOf(200, 202, 204, 400, 401, 409, 410, 412, 422, 503))
            invalid { adapter.receipt(call(), ACCOUNT, reply(status = status)) }
        for (status in listOf("hidden", "deleted"))
            invalid { adapter.receipt(call(), ACCOUNT, reply(post().with("status", JsonPrimitive(status)))) }
    }

    @Test fun expectedAuthorIsComparisonDataAndMustMatchTheCanonicalResponse() {
        adapter.receipt(call(), ACCOUNT.uppercase(), reply())
        invalid { adapter.receipt(call(), SOURCE, reply()) }
        for (value in listOf("provider-subject", "", " ", ACCOUNT + "\n"))
            invalid { adapter.receipt(call(), value, reply()) }
    }

    @Test fun completeServerManagedFieldsAndLargeCountsSurviveUnchanged() {
        val actual = post().with("aclVersion", number("9007199254740993"))
            .with("savePolicy", savePolicy().with("policyVersion", number("9007199254740995")))
            .with("reactionCounts", JsonArray(listOf(buildJsonObject { put("kind", "yum"); put("count", number("9007199254740997")) })))
            .with("capabilities", strings(listOf("view", "delete", "report")))
        val raw = JsonObject(actual.entries.reversed().associate { it.toPair() }).toString()
        assertContentEquals(raw.encodeToByteArray(), adapter.receipt(call(), ACCOUNT, reply(raw = raw)).document.encodeUtf8())
    }

    @Test fun originalEnvelopeCannotBorrowAnotherOperationPathQueryOrIfMatch() {
        val base = call()
        val bad = listOf(ApiCall("getPost", body = base.body, idempotencyKey = base.idempotencyKey),
            ApiCall("publishPost", mapOf("draftId" to DRAFT), body = base.body, idempotencyKey = base.idempotencyKey),
            ApiCall("publishPost", queryParameters = mapOf("x" to listOf("1")), body = base.body, idempotencyKey = base.idempotencyKey),
            ApiCall("publishPost", body = base.body, idempotencyKey = base.idempotencyKey, ifMatch = "\"1\""),
            ApiCall("publishPost", body = base.body), ApiCall("publishPost", idempotencyKey = base.idempotencyKey),
            ApiCall("publishPost", body = base.body, idempotencyKey = SecretText("not-a-uuid")))
        for (original in bad) invalid { adapter.receipt(original, ACCOUNT, reply()) }
    }

    @Test fun malformedDuplicateUnknownAndMissingOriginalFieldsAreRejected() {
        for (raw in listOf("{}", "{", write().toString().dropLast(1) + ",\"caption\":\"duplicate\"}",
            write().with("acceptance", JsonPrimitive(true)).toString(), write().without("caption").toString()))
            invalid { adapter.receipt(call(raw = raw), ACCOUNT, reply()) }
    }

    @Test fun canonicalResponseRejectsMissingUnknownNullAndWrongTypeFields() {
        for (actual in listOf(post().without("author"), post().without("savePolicy"), post().with("clientDraftId", JsonPrimitive(CLIENT)),
            post().with("altText", JsonNull), post().with("version", JsonPrimitive("1")),
            post().with("author", post().getValue("author").jsonObject.with("privateEmail", JsonPrimitive("secret")))))
            invalid { adapter.receipt(call(), ACCOUNT, reply(actual)) }
    }

    @Test fun responseBodySyntaxUtf8AndMediaTypeAreNotGuessed() {
        for (type in listOf(null, "text/plain", "application/problem+json"))
            invalid { adapter.receipt(call(), ACCOUNT, reply().copy(contentType = type)) }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0xc3.toByte(), 0x28), "{".encodeToByteArray(), "null".encodeToByteArray()))
            invalid { adapter.receipt(call(), ACCOUNT, reply().copy(body = PrivateBytes(bytes))) }
        invalid { adapter.receipt(call(), ACCOUNT, reply().copy(body = null)) }
        invalid { adapter.receipt(call(), ACCOUNT, reply(raw = post().toString().dropLast(1) + ",\"caption\":\"duplicate\"}")) }
    }

    @Test fun attachmentCardinalityAndSubmittedDescriptorContradictionsFailLocally() {
        val empty = catalog(RECIPE).without("recipeVersionId")
        for (attachment in listOf(empty, catalog(RECIPE).with("planId", JsonPrimitive(PLAN)),
            catalog(RECIPE).with("reviewStatus", JsonPrimitive("personal")),
            personal(recipe()).with("rightsBasis", JsonPrimitive("catalogRedistributable"))))
            invalid { adapter.expectedSelection(call(write().with("attachment", attachment))) }
        invalid { adapter.expectedSelection(call(write().with("allowRecipeSaves", JsonPrimitive(true)))) }
    }

    @Test fun normalizedDuplicateSelectionsAndSelfCircleContradictionsFailLocally() {
        for (body in listOf(write().with("mediaIds", strings(listOf(MEDIA_A, MEDIA_A.uppercase()))),
            write().with("audience", circles(listOf(CIRCLE_A, CIRCLE_A.uppercase()))),
            write().with("audience", circles(emptyList())),
            write().with("audience", circles(listOf(CIRCLE_A)).with("kind", JsonPrimitive("self")))))
            invalid { adapter.expectedSelection(call(body)) }
    }

    @Test fun savedVersionUsesExactPositiveServiceBigintBounds() {
        for (token in listOf("1", "9007199254740993", "9223372036854775807", "9.007199254740993e15"))
            adapter.expectedSelection(call(write().with("draftId", JsonPrimitive(DRAFT)).with("draftVersion", number(token))))
        for (token in listOf("0", "-1", "1.1", "9223372036854775808", "1e10000"))
            invalid { adapter.expectedSelection(call(write().with("draftId", JsonPrimitive(DRAFT)).with("draftVersion", number(token)))) }
    }

    @Test fun requestAndResponseResourceBoundsRejectRatherThanTruncate() {
        unavailable { adapter.receipt(call(raw = " ".repeat(65_536) + write().toString()), ACCOUNT, reply()) }
        val response = reply()
        val length = response.body!!.copyForCodec().size
        unavailable { PostPublicationAdapter(length - 1).receipt(call(), ACCOUNT, response) }
        PostPublicationAdapter(length).receipt(call(), ACCOUNT, response)
        assertFailsWith<IllegalArgumentException> { PostPublicationAdapter(0) }
        assertFailsWith<IllegalArgumentException> { PostPublicationAdapter(262_145) }
    }

    @Test fun detachedInputsOutputsAndDiagnosticsNeverMutateOrPrintPrivateMaterial() {
        val original = call(); val originalBytes = original.body!!.copyForCodec(); val response = reply()
        val result = adapter.receipt(original, ACCOUNT, response)
        val escaped = result.document.encodeUtf8(); escaped.fill(0)
        val responseCopy = response.body!!.copyForCodec(); responseCopy.fill(0)
        assertContentEquals(originalBytes, original.body!!.copyForCodec())
        assertEquals(post(), json(result.document))
        for (value in listOf(adapter.toString(), result.toString(), result.document.toString())) {
            assertFalse(value.contains("Synthetic caption")); assertFalse(value.contains(ACCOUNT)); assertFalse(value.contains("test-disclosure"))
        }
    }

    private val adapter = PostPublicationAdapter(262_144)
    private fun call(body: JsonObject = write(), raw: String = body.toString()) = ApiCall("publishPost",
        body = PrivateBytes(raw.encodeToByteArray()), idempotencyKey = SecretText(COMMAND))
    private fun reply(body: JsonObject = post(), status: Int = 201, tag: String? = "\"1\"", raw: String = body.toString()) =
        ApiReply(status, PrivateBytes(raw.encodeToByteArray()), etag = tag, contentType = "application/json")
    private fun write() = buildJsonObject {
        put("clientDraftId", CLIENT); put("caption", "Synthetic caption"); put("mediaIds", JsonArray(emptyList()))
        put("audience", buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) })
        put("keepOnPlate", false); put("allowRecipeSaves", false); put("saveDisclosureVersion", "test-disclosure")
    }
    private fun post() = buildJsonObject {
        put("id", POST); put("version", 1); put("createdAt", "2026-09-14T12:00:00Z"); put("updatedAt", "2026-09-14T12:00:00Z")
        put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic Cook"); put("handle", "synthetic"); put("avatarMediaId", MEDIA_A) })
        put("caption", "Synthetic caption"); put("mediaIds", JsonArray(emptyList()))
        put("audience", buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())); put("bindings", JsonArray(emptyList())) })
        put("status", "published"); put("publishedAt", "2026-09-14T12:00:00Z"); put("expiresAt", "2026-09-15T12:00:00Z")
        put("keepOnPlate", false); put("savePolicy", savePolicy()); put("aclVersion", 1)
        put("capabilities", strings(listOf("view", "delete"))); put("reactionCounts", JsonArray(emptyList()))
    }
    private fun savePolicy(allow: Boolean = false) = buildJsonObject {
        put("allowFutureSaves", allow); put("policyVersion", 1); put("disclosureVersion", "test-disclosure")
    }
    private fun circles(ids: List<String>) = buildJsonObject { put("kind", "circles"); put("circleIds", strings(ids)) }
    private fun binding(id: String, generation: String = "7") = buildJsonObject { put("circleId", id); put("authorMembershipGeneration", number(generation)) }
    private fun catalog(id: String, plan: Boolean = false) = buildJsonObject {
        put(if (plan) "planId" else "recipeVersionId", id); put("confirmedChanges", JsonArray(emptyList()))
        put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable")
    }
    private fun personal(recipe: JsonObject) = buildJsonObject {
        put("personalRecipe", recipe); put("confirmedChanges", JsonArray(emptyList())); put("reviewStatus", "personal"); put("rightsBasis", "creatorOriginal")
    }
    private fun recipe(quantity: String = "1", servings: String = "1", ingredientId: String = MEDIA_A) = buildJsonObject {
        put("title", "Synthetic personal recipe")
        put("ingredients", JsonArray(listOf(buildJsonObject { put("ingredientId", ingredientId); put("quantity", number(quantity)); put("unit", "g"); put("optional", false) })))
        put("steps", JsonArray(listOf(buildJsonObject { put("stepId", "mix"); put("position", 1); put("instruction", "Mix")
            put("ingredientIds", strings(listOf(ingredientId))); put("requiredEquipmentIds", strings(listOf("bowl"))); put("mandatorySafetyStep", false) })))
        put("servings", number(servings)); put("activeMinutes", 1); put("totalMinutes", 1); put("utensilCount", 1)
        put("equipmentIds", strings(listOf("bowl"))); put("modes", strings(listOf("assemble")))
    }
    private fun number(raw: String) = Json.parseToJsonElement(raw)
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun json(document: WireDocument) = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    private fun JsonObject.with(field: String, value: JsonElement) = JsonObject(this + (field to value))
    private fun JsonObject.without(field: String) = JsonObject(filterKeys { it != field })
    private fun JsonObject.optional(field: String, value: JsonElement?) = if (value == null) without(field) else with(field, value)
    private fun invalid(action: () -> Unit) { assertEquals(FailureReason.INVALID_DATA, assertFailsWith<MealFailure> { action() }.reason) }
    private fun unavailable(action: () -> Unit) { assertEquals(FailureReason.UNAVAILABLE, assertFailsWith<MealFailure> { action() }.reason) }
    private companion object {
        const val ACCOUNT = "aaaaaaaa-1111-4111-8111-111111111111"
        const val CLIENT = "bbbbbbbb-2222-4222-8222-222222222222"
        const val COMMAND = "cccccccc-3333-4333-8333-333333333333"
        const val DRAFT = "dddddddd-4444-4444-8444-444444444444"
        const val POST = "eeeeeeee-5555-4555-8555-555555555555"
        const val SOURCE = "ffffffff-6666-4666-8666-666666666666"
        const val RECIPE = "aaaaaaaa-7777-4777-8777-777777777777"
        const val PLAN = "bbbbbbbb-8888-4888-8888-888888888888"
        const val MEDIA_A = "cccccccc-9999-4999-8999-999999999999"
        const val MEDIA_B = "dddddddd-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val CIRCLE_A = "eeeeeeee-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val CIRCLE_B = "ffffffff-cccc-4ccc-8ccc-cccccccccccc"
    }
}
