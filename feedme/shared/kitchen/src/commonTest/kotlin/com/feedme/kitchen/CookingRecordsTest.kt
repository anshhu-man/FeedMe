package com.feedme.kitchen

import com.feedme.contracts.CookSessionWire
import com.feedme.contracts.TimerStateWire
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import kotlin.test.*

/** Strict local codecs and projections only; no storage or transport may be touched here. */
class CookingRecordsTest {
    @Test fun cookingHeaderRoundTripsExactHashesPendingOrderAndNullableFields() {
        val header = header(pending = listOf(ACTION, ACTION2))
        val bytes = CookingRecords.encodeHeader(header)
        assertEquals(header, CookingRecords.decodeHeader(bytes))
        val root = objectOf(bytes)
        assertEquals(JsonNull, root.getValue("conflictHash"))
        assertEquals(JsonNull, root.getValue("conflictEtag"))
        assertEquals("\"7\"", root.getValue("etag").jsonPrimitive.content)
        assertEquals(listOf(ACTION, ACTION2), CookingRecords.decodeHeader(bytes).pending)
        assertEquals(START, CookingRecords.decodeHeader(bytes).checkedAt)
    }

    @Test fun headerPreservesMissingWritePreconditionAndUnversionedConflictRead() {
        val header = header().copy(etag = null, conflictHash = HASH2, conflictEtag = null)
        assertEquals(header, CookingRecords.decodeHeader(CookingRecords.encodeHeader(header)))
        val versionedConflict = header.copy(conflictEtag = "\"0008\"")
        assertEquals(versionedConflict, CookingRecords.decodeHeader(CookingRecords.encodeHeader(versionedConflict)))
        invalid { CookingRecords.encodeHeader(header().copy(conflictHash = null, conflictEtag = "\"8\"")) }
    }

    @Test fun headerEncodingDetachesPendingListAndPayloadProjections() {
        val pending = mutableListOf(ACTION)
        val bytes = CookingRecords.encodeHeader(header(pending))
        pending += ACTION2
        bytes.copyForCodec().fill(0)
        assertEquals(listOf(ACTION), CookingRecords.decodeHeader(bytes).pending)
    }

    @Test fun headerRequiresVersionOneAndExactRequiredKeys() {
        val root = objectOf(CookingRecords.encodeHeader(header()))
        for (key in root.keys) invalid { CookingRecords.decodeHeader(bytes(JsonObject(root - key).toString())) }
        invalid { CookingRecords.decodeHeader(bytes(JsonObject(root + ("unknown" to JsonPrimitive(true))).toString())) }
        for (version in listOf(JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive("1"), Json.parseToJsonElement("1e0"), JsonNull))
            invalid { CookingRecords.decodeHeader(changed(root, "version", version)) }
        invalid { CookingRecords.decodeHeader(bytes("{\"version\":1,\"version\":1}")) }
    }

    @Test fun headerRejectsNoncanonicalIdsHashesAndTimestamps() {
        val root = objectOf(CookingRecords.encodeHeader(header()))
        for (field in listOf("id", "planId", "originBinding")) {
            invalid { CookingRecords.decodeHeader(changed(root, field, JsonPrimitive(ID.uppercase()))) }
            invalid { CookingRecords.decodeHeader(changed(root, field, JsonPrimitive("not-a-uuid"))) }
            invalid { CookingRecords.decodeHeader(changed(root, field, JsonNull)) }
        }
        for (field in listOf("planHash", "serverHash", "progressHash", "conflictHash")) {
            invalid { CookingRecords.decodeHeader(changed(root, field, JsonPrimitive("AB".repeat(32)))) }
            invalid { CookingRecords.decodeHeader(changed(root, field, JsonPrimitive("a".repeat(63)))) }
            invalid { CookingRecords.decodeHeader(changed(root, field, JsonPrimitive(1))) }
        }
        for (time in listOf(JsonPrimitive(-1), JsonPrimitive("123"), Json.parseToJsonElement("1.0"), Json.parseToJsonElement("9223372036854775808")))
            invalid { CookingRecords.decodeHeader(changed(root, "checkedAt", time)) }
        assertEquals(Long.MAX_VALUE, CookingRecords.decodeHeader(CookingRecords.encodeHeader(header().copy(checkedAt = Long.MAX_VALUE))).checkedAt)
    }

    @Test fun headerPendingIdsAreUniqueBoundedAndCanonical() {
        assertEquals(64, CookingRecords.decodeHeader(CookingRecords.encodeHeader(header(List(64) { uuid(it + 1) }))).pending.size)
        for (pending in listOf(listOf(ACTION, ACTION), listOf(ACTION.uppercase()), listOf("../$ACTION"), List(65) { uuid(it + 1) }))
            invalid { CookingRecords.encodeHeader(header(pending)) }
        val root = objectOf(CookingRecords.encodeHeader(header()))
        invalid { CookingRecords.decodeHeader(changed(root, "pending", JsonNull)) }
        invalid { CookingRecords.decodeHeader(changed(root, "pending", JsonArray(listOf(JsonPrimitive(1))))) }
        invalid { CookingRecords.decodeHeader(changed(root, "etag", JsonPrimitive(7))) }
        invalid { CookingRecords.decodeHeader(changed(root, "conflictEtag", JsonPrimitive(false))) }
    }

    @Test fun cookingIndexRoundTripsOrderAndEmptyIndexWithoutIdentityCreation() {
        assertEquals(emptyList(), CookingRecords.index(CookingRecords.index(emptyList())))
        assertEquals(listOf(ID, PLAN), CookingRecords.index(CookingRecords.index(listOf(ID, PLAN))))
        assertEquals(64, CookingRecords.index(CookingRecords.index(List(64) { uuid(it + 1) })).size)
        val source = mutableListOf(ID)
        val encoded = CookingRecords.index(source)
        source += PLAN
        assertEquals(listOf(ID), CookingRecords.index(encoded))
    }

    @Test fun cookingIndexRejectsUnknownFieldsMissingVersionWrongTypeAndDuplicateIds() {
        for (text in listOf("{}", "[]", "{\"version\":2,\"ids\":[]}", "{\"version\":1.0,\"ids\":[]}",
            "{\"version\":1,\"ids\":null}", "{\"version\":1,\"ids\":[1]}",
            "{\"version\":1,\"ids\":[],\"extra\":true}", "{\"version\":1,\"ids\":[],\"ids\":[]}"))
            invalid { CookingRecords.index(bytes(text)) }
        for (ids in listOf(listOf(ID, ID), listOf(ID.uppercase()), listOf("invalid"), List(65) { uuid(it + 1) }))
            invalid { CookingRecords.index(ids) }
    }

    @Test fun actionHeaderRoundTripsUnmaterializedAndMaterializedUpdateWithoutInventingEtag() {
        val local = action()
        assertFalse(CookingRecords.decodeAction(CookingRecords.encodeAction(local)).materialized)
        assertNull(CookingRecords.decodeAction(CookingRecords.encodeAction(local)).ifMatch)
        val materialized = local.copy(materialized = true, ifMatch = "\"0007\"")
        assertEquals(materialized, CookingRecords.decodeAction(CookingRecords.encodeAction(materialized)))
        assertEquals(ACTION, CookingRecords.decodeAction(CookingRecords.encodeAction(materialized)).id)
    }

    @Test fun completionActionNeverContainsUpdateIfMatchEvenAfterMaterialization() {
        for (materialized in listOf(false, true)) {
            val completion = action().copy(operationId = "completeCookSession", materialized = materialized)
            assertEquals(completion, CookingRecords.decodeAction(CookingRecords.encodeAction(completion)))
            invalid { CookingRecords.encodeAction(completion.copy(ifMatch = "\"7\"")) }
        }
    }

    @Test fun actionVersionOperationAndMaterializationRulesAreStrict() {
        val root = objectOf(CookingRecords.encodeAction(action()))
        for (key in root.keys) invalid { CookingRecords.decodeAction(bytes(JsonObject(root - key).toString())) }
        invalid { CookingRecords.decodeAction(bytes(JsonObject(root + ("extra" to JsonPrimitive(true))).toString())) }
        for (version in listOf(JsonPrimitive(2), JsonPrimitive("1"), Json.parseToJsonElement("1e0")))
            invalid { CookingRecords.decodeAction(changed(root, "version", version)) }
        for (operation in listOf("createCookSession", "getCookSession", "updatePreferences", "unknownOperation"))
            invalid { CookingRecords.encodeAction(action().copy(operationId = operation)) }
        invalid { CookingRecords.decodeAction(changed(root, "materialized", JsonPrimitive("true"))) }
        invalid { CookingRecords.encodeAction(action().copy(ifMatch = "\"7\"")) }
        invalid { CookingRecords.encodeAction(action().copy(materialized = true, ifMatch = null)) }
        for (etag in listOf("7", "W/\"7\"", "*", "\"7.0\"", "\"7e0\"", "\"-7\"", "\"7\",\"8\""))
            invalid { CookingRecords.encodeAction(action().copy(materialized = true, ifMatch = etag)) }
    }

    @Test fun actionIdentifiersHashAndCreationTimeRejectCorruption() {
        for (bad in listOf(action().copy(id = ACTION.uppercase()), action().copy(sessionId = "invalid"),
            action().copy(bodyHash = "0".repeat(63)), action().copy(bodyHash = HASH.uppercase()), action().copy(createdAt = -1)))
            invalid { CookingRecords.encodeAction(bad) }
        val root = objectOf(CookingRecords.encodeAction(action()))
        for (time in listOf(JsonPrimitive("1"), Json.parseToJsonElement("1.0"), Json.parseToJsonElement("9223372036854775808")))
            invalid { CookingRecords.decodeAction(changed(root, "createdAt", time)) }
        invalid { CookingRecords.decodeAction(bytes("{\"version\":1,\"version\":1}")) }
    }

    @Test fun serverProjectionPreservesStatusStepOrderTimersNotesAndExactSequenceValue() {
        val document = serverDocument("paused", "9.007199254740993e15", personalNotes = "[{\"text\":\"private note\",\"label\":\"myNote\"}]")
        val original = document.encodeUtf8()
        val projected = CookingRecords.fromServer(CookSessionWire.from(document))
        assertEquals(CookingStatus.PAUSED, projected.status)
        assertEquals("private-step", projected.currentStepId)
        assertEquals(listOf("step-two", "step-one"), projected.completedStepIds)
        assertEquals("9007199254740993", projected.deviceSequence)
        assertEquals(1, projected.timers.size)
        assertEquals("1e2", projected.timers.single().durationSeconds.jsonToken)
        assertEquals("private note", projected.personalNotes!!.single().json().jsonObject.getValue("text").jsonPrimitive.content)
        assertContentEquals(original, document.encodeUtf8())
    }

    @Test fun allCanonicalServerStatusesMapWithoutInventingCompletionTimestamp() {
        for (status in CookingStatus.entries) {
            val projected = CookingRecords.fromServer(CookSessionWire.from(serverDocument(status.name.lowercase(), "3")))
            assertEquals(status, projected.status)
            assertFalse(objectOf(CookingRecords.encodeProgress(projected)).containsKey("completedAt"))
            assertFalse(objectOf(CookingRecords.encodeProgress(projected)).containsKey("remoteVersion"))
        }
    }

    @Test fun serverProjectionDistinguishesAbsentNotesFromExplicitEmptyNotes() {
        val absent = CookingRecords.fromServer(CookSessionWire.from(serverDocument()))
        val empty = CookingRecords.fromServer(CookSessionWire.from(serverDocument(personalNotes = "[]")))
        assertNull(absent.personalNotes)
        assertEquals(emptyList(), empty.personalNotes)
        assertEquals(JsonNull, objectOf(CookingRecords.encodeProgress(absent)).getValue("personalNotes"))
        assertEquals(JsonArray(emptyList()), objectOf(CookingRecords.encodeProgress(empty)).getValue("personalNotes"))
    }

    @Test fun progressRoundTripPreservesLocalStateAndOptionalNotesWithoutNetwork() {
        val original = progress(notes = listOf(WireDocument.parse("{\"text\":\"private note\",\"label\":\"myNote\"}")))
        val restored = CookingRecords.decodeProgress(CookingRecords.encodeProgress(original), context)
        assertEquals(original.status, restored.status)
        assertEquals(original.currentStepId, restored.currentStepId)
        assertEquals(original.completedStepIds, restored.completedStepIds)
        assertEquals(original.deviceSequence, restored.deviceSequence)
        assertEquals(original.timers.single().document.json(), restored.timers.single().document.json())
        assertEquals(original.personalNotes!!.single().json(), restored.personalNotes!!.single().json())
        assertNull(CookingRecords.decodeProgress(CookingRecords.encodeProgress(progress()), context).personalNotes)
        assertEquals(emptyList(), CookingRecords.decodeProgress(CookingRecords.encodeProgress(progress(notes = emptyList())), context).personalNotes)
    }

    @Test fun progressAndEditCollectionsAreDetachedFromInputsAndGetterMutations() {
        val completed = mutableListOf("step-one")
        val timers = mutableListOf(timer())
        val notes = mutableListOf(WireDocument.parse("{\"text\":\"private note\",\"label\":\"myNote\"}"))
        val progress = CookingProgress(CookingStatus.ACTIVE, "private-step", completed, timers, notes, "3")
        val replaceTimers = CookingEdit.ReplaceTimers(timers.map { it.document }.toMutableList())
        val replaceNotes = CookingEdit.ReplaceNotes(notes)
        completed += "step-two"
        timers.clear()
        notes.clear()
        assertEquals(listOf("step-one"), progress.completedStepIds)
        assertEquals(1, progress.timers.size)
        assertEquals(1, progress.personalNotes!!.size)
        assertEquals(1, replaceTimers.timers.size)
        assertEquals(1, replaceNotes.notes.size)
        (progress.completedStepIds as? MutableList<String>)?.let { runCatching { it.clear() } }
        (progress.timers as? MutableList<TimerStateWire>)?.let { runCatching { it.clear() } }
        (progress.personalNotes as? MutableList<WireDocument>)?.let { runCatching { it.clear() } }
        (replaceNotes.notes as? MutableList<WireDocument>)?.let { runCatching { it.clear() } }
        assertEquals(listOf("step-one"), progress.completedStepIds)
        assertEquals(1, progress.timers.size)
        assertEquals(1, progress.personalNotes!!.size)
        assertEquals(1, replaceNotes.notes.size)
    }

    @Test fun patchUsesCanonicalNumericSequenceAndDoesNotInventWritePreconditions() {
        val patch = objectOf(CookingRecords.patch(progress(sequence = "9007199254740993")))
        assertEquals("9007199254740993", patch.getValue("deviceSequence").jsonPrimitive.content)
        assertFalse(patch.getValue("deviceSequence").jsonPrimitive.isString)
        assertEquals("active", patch.getValue("status").jsonPrimitive.content)
        assertFalse(patch.containsKey("version"))
        assertFalse(patch.containsKey("ifMatch"))
        assertFalse(patch.containsKey("completedAt"))
        assertFalse(patch.containsKey("personalNotes"))
        context.document("CookPatch", CookingRecords.patch(progress(sequence = "9007199254740993")))
    }

    @Test fun completedProgressCannotEmitStatusCompletedInPatch() {
        val completed = progress(status = CookingStatus.COMPLETED)
        val patch = CookingRecords.patch(completed)
        assertFalse(objectOf(patch).containsKey("status"))
        context.document("CookPatch", patch)
        assertEquals(CookingStatus.COMPLETED, CookingRecords.decodeProgress(CookingRecords.encodeProgress(completed), context).status)
        for (status in listOf(CookingStatus.ACTIVE, CookingStatus.PAUSED, CookingStatus.ABANDONED))
            assertEquals(status.name.lowercase(), objectOf(CookingRecords.patch(progress(status))).getValue("status").jsonPrimitive.content)
    }

    @Test fun patchExplicitEmptyNotesRemainAnIntentionalClear() {
        val absent = objectOf(CookingRecords.patch(progress()))
        val clear = objectOf(CookingRecords.patch(progress(notes = emptyList())))
        assertFalse(absent.containsKey("personalNotes"))
        assertEquals(JsonArray(emptyList()), clear.getValue("personalNotes"))
    }

    @Test fun progressRequiresExactSchemaVersionRootKeysAndTypedFields() {
        val root = objectOf(CookingRecords.encodeProgress(progress()))
        for (key in root.keys) invalid { CookingRecords.decodeProgress(bytes(JsonObject(root - key).toString()), context) }
        invalid { CookingRecords.decodeProgress(bytes(JsonObject(root + ("unknown" to JsonPrimitive(true))).toString()), context) }
        for (version in listOf(JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive("1"), Json.parseToJsonElement("1e0")))
            invalid { CookingRecords.decodeProgress(changed(root, "version", version), context) }
        for (status in listOf(JsonPrimitive("active"), JsonPrimitive("UNKNOWN"), JsonPrimitive(1), JsonNull))
            invalid { CookingRecords.decodeProgress(changed(root, "status", status), context) }
        invalid { CookingRecords.decodeProgress(changed(root, "currentStepId", JsonPrimitive(1)), context) }
        invalid { CookingRecords.decodeProgress(changed(root, "completedStepIds", JsonArray(listOf(JsonPrimitive(1)))), context) }
        invalid { CookingRecords.decodeProgress(changed(root, "completedStepIds", JsonPrimitive("step")), context) }
    }

    @Test fun progressSequenceMustBeCanonicalDigitStringWithoutFractionExponentOrLeadingZero() {
        val root = objectOf(CookingRecords.encodeProgress(progress()))
        for (sequence in listOf("", "01", "-0", "-1", "1e0", "1.0", "+1", " 1", "9".repeat(4097)))
            invalid { CookingRecords.decodeProgress(changed(root, "deviceSequence", JsonPrimitive(sequence)), context) }
        for (value in listOf(JsonPrimitive(1), JsonNull, JsonPrimitive(true)))
            invalid { CookingRecords.decodeProgress(changed(root, "deviceSequence", value), context) }
        assertEquals("0", CookingRecords.decodeProgress(CookingRecords.encodeProgress(progress(sequence = "0")), context).deviceSequence)
        assertEquals("9223372036854775808", CookingRecords.decodeProgress(CookingRecords.encodeProgress(progress(sequence = "9223372036854775808")), context).deviceSequence)
    }

    @Test fun remoteExponentCounterBeyondWireTokenLengthRemainsExactInLocalProgress() {
        val server = serverDocument(sequence = "1e1000")
        val original = server.encodeUtf8()
        val expected = "1" + "0".repeat(1000)
        val projected = CookingRecords.fromServer(CookSessionWire.from(server))
        assertEquals(expected, projected.deviceSequence)
        val stored = CookingRecords.encodeProgress(projected)
        val restored = CookingRecords.decodeProgress(stored, context)
        assertEquals(expected, restored.deviceSequence)
        assertEquals(expected, objectOf(stored).getValue("deviceSequence").jsonPrimitive.content)
        assertEquals(projected.completedStepIds, restored.completedStepIds)
        assertEquals(projected.timers.single().document.json(), restored.timers.single().document.json())
        assertEquals("1e1000", CookSessionWire.from(server).deviceSequence.jsonToken)
        assertContentEquals(original, server.encodeUtf8())
    }

    @Test fun largestLocalCounterRoundTripsWithoutReplacingStoredSequenceWithValidationPlaceholder() {
        val expected = "1" + "0".repeat(4095)
        val projected = CookingRecords.fromServer(CookSessionWire.from(serverDocument(sequence = "1e4095")))
        assertEquals(expected, projected.deviceSequence)
        val encoded = CookingRecords.encodeProgress(projected)
        val restored = CookingRecords.decodeProgress(encoded, context)
        assertEquals(expected, restored.deviceSequence)
        assertEquals(4096, restored.deviceSequence.length)
        assertEquals(expected, objectOf(CookingRecords.encodeProgress(restored)).getValue("deviceSequence").jsonPrimitive.content)
    }

    @Test fun hugeReadOnlyCounterDoesNotBypassTimerAndNoteValidation() {
        val root = objectOf(CookingRecords.encodeProgress(progress(sequence = "1" + "0".repeat(1000))))
        invalid { CookingRecords.decodeProgress(changed(root, "timers", JsonArray(listOf(JsonObject(emptyMap())))), context) }
        invalid { CookingRecords.decodeProgress(changed(root, "personalNotes", JsonArray(listOf(JsonPrimitive("not-a-note")))), context) }
        invalid { CookingRecords.decodeProgress(changed(root, "personalNotes", JsonArray(listOf(
            Json.parseToJsonElement("{\"text\":\"${"x".repeat(501)}\",\"label\":\"myNote\"}")))), context) }
        invalid { CookingRecords.decodeProgress(changed(root, "currentStepId", JsonPrimitive(1)), context) }
        assertEquals("1" + "0".repeat(1000), CookingRecords.decodeProgress(bytes(root.toString()), context).deviceSequence)
    }

    @Test fun progressTimersRequireFullCanonicalTimerSchemaNotJustArrayShape() {
        val root = objectOf(CookingRecords.encodeProgress(progress()))
        val timer = objectOf(timer().document)
        val invalidTimers = listOf<JsonElement>(JsonNull, JsonPrimitive("timer"), JsonObject(emptyMap()),
            JsonObject(timer - "durationSeconds"), JsonObject(timer + ("durationSeconds" to JsonPrimitive(0))),
            JsonObject(timer + ("status" to JsonPrimitive("expired"))), JsonObject(timer + ("timerId" to JsonPrimitive("not-id"))),
            JsonObject(timer + ("unknown" to JsonPrimitive(true))), JsonObject(timer + ("pausedRemainingSeconds" to JsonPrimitive(-1))))
        invalidTimers.forEach { bad -> invalid { CookingRecords.decodeProgress(changed(root, "timers", JsonArray(listOf(bad))), context) } }
        invalid { CookingRecords.decodeProgress(changed(root, "timers", JsonNull), context) }
        invalid { CookingRecords.decodeProgress(changed(root, "timers", JsonObject(emptyMap())), context) }
    }

    @Test fun progressNotesRequireCanonicalTextLabelAndBoundedContent() {
        val root = objectOf(CookingRecords.encodeProgress(progress()))
        val malformed = listOf("{}", "null", "1", "{\"text\":\"note\"}", "{\"text\":1,\"label\":\"myNote\"}",
            "{\"text\":\"note\",\"label\":\"approved\"}", "{\"text\":\"${"a".repeat(501)}\",\"label\":\"myNote\"}",
            "{\"text\":\"note\",\"label\":\"myNote\",\"unknown\":true}")
        malformed.forEach { bad -> invalid { CookingRecords.decodeProgress(changed(root, "personalNotes", JsonArray(listOf(Json.parseToJsonElement(bad)))), context) } }
        invalid { CookingRecords.decodeProgress(changed(root, "personalNotes", JsonPrimitive("note")), context) }
        val valid = Json.parseToJsonElement("[{\"text\":\"${"a".repeat(500)}\",\"label\":\"communityTip\",\"shortcutId\":\"$ACTION\"}]")
        assertEquals(1, CookingRecords.decodeProgress(changed(root, "personalNotes", valid), context).personalNotes!!.size)
    }

    @Test fun corruptProgressAndMetadataNeverParseByDroppingDuplicateOrMalformedFields() {
        val encoded = CookingRecords.encodeProgress(progress()).copyForCodec().decodeToString()
        assertFails { CookingRecords.decodeProgress(bytes(encoded.dropLast(1) + ",\"status\":\"PAUSED\"}"), context) }
        assertFails { CookingRecords.decodeProgress(bytes(encoded.replace("private-step", "\\uD800")), context) }
        assertFails { CookingRecords.decodeProgress(PrivateBytes(byteArrayOf(0x7b, 0x80.toByte(), 0x7d)), context) }
        invalid { CookingRecords.decodeHeader(PrivateBytes(byteArrayOf(0x7b, 0x80.toByte(), 0x7d))) }
        invalid { CookingRecords.decodeAction(PrivateBytes(byteArrayOf(0x7b, 0x80.toByte(), 0x7d))) }
        invalid { CookingRecords.index(PrivateBytes(byteArrayOf(0x7b, 0x80.toByte(), 0x7d))) }
    }

    @Test fun progressAndEditDiagnosticsDoNotRevealStepTimerOrNoteContent() {
        val value = progress(notes = listOf(WireDocument.parse("{\"text\":\"secret note\",\"label\":\"myNote\"}")))
        for (text in listOf(value.toString(), CookingEdit.MoveTo("secret step").toString(),
            CookingEdit.MarkStepComplete("secret step").toString(), CookingEdit.ReplaceTimers(value.timers.map { it.document }).toString(),
            CookingEdit.ReplaceNotes(value.personalNotes!!).toString(), CookingEdit.Complete(true, "2026-09-13T12:00:00Z").toString())) {
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("private-step"))
            assertFalse(text.contains(TIMER))
            assertFalse(text.contains("2026-09-13"))
        }
    }

    companion object {
        private const val ID = "123e4567-e89b-12d3-a456-426614174001"
        private const val PLAN = "123e4567-e89b-12d3-a456-426614174002"
        private const val ORIGIN = "123e4567-e89b-12d3-a456-426614174003"
        private const val ACTION = "123e4567-e89b-12d3-a456-426614174004"
        private const val ACTION2 = "123e4567-e89b-12d3-a456-426614174005"
        private const val TIMER = "123e4567-e89b-12d3-a456-426614174006"
        private const val START = 1_800_000_000_000L
        private val HASH = "ab".repeat(32)
        private val HASH2 = "cd".repeat(32)
        private val context = KitchenContext(StorageScope("test", ActorKind.ACCOUNT, "owner"), object : PrivateStateStore {
            override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> = error("Codec must not read storage")
            override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> = error("Codec must not write storage")
            override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("Codec must not erase identities")
        }, SessionBoundary(), Dispatchers.Unconfined, EpochClock { START }, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = error("Codec must not use network")
        })
        private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun objectOf(bytes: PrivateBytes) = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
        private fun objectOf(document: WireDocument) = document.json().jsonObject
        private fun changed(root: JsonObject, key: String, value: JsonElement) = bytes(JsonObject(root + (key to value)).toString())
        private fun uuid(value: Int) = "123e4567-e89b-12d3-a456-${value.toString().padStart(12, '0')}"
        private fun header(pending: List<String> = emptyList()) = CookHeader(ID, PLAN, ORIGIN, HASH, HASH2, HASH,
            "\"7\"", START, pending)
        private fun action() = CookActionHeader(ACTION, ID, "updateCookSession", HASH, START, false, null)
        private fun timer() = TimerStateWire.from(WireDocument.parse("""{"timerId":"$TIMER","stepId":"private-step","status":"running","endAt":"2026-09-13T12:02:00Z","durationSeconds":1e2}"""))
        private fun progress(status: CookingStatus = CookingStatus.ACTIVE, notes: List<WireDocument>? = null, sequence: String = "3") =
            CookingProgress(status, "private-step", listOf("step-two", "step-one"), listOf(timer()), notes, sequence)
        private fun serverDocument(status: String = "active", sequence: String = "3", personalNotes: String? = null): WireDocument {
            val notes = personalNotes?.let { ",\"personalNotes\":$it" } ?: ""
            val raw = """{"id":"$ID","version":7,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","planId":"$PLAN","status":"$status","currentStepId":"private-step","completedStepIds":["step-two","step-one"],"deviceSequence":$sequence,"timers":[${timer().document.json()}]$notes}"""
            return context.document("CookSession", bytes(raw))
        }
        private fun invalid(action: () -> Unit) {
            assertEquals(FailureReason.INVALID_DATA, assertFailsWith<KitchenFailure> { action() }.reason)
        }
    }
}
