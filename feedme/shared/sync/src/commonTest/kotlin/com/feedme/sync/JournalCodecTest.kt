package com.feedme.sync

import com.feedme.core.ports.PrivateBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalCodecTest {
    @Test fun indexRoundTripsEmptyAndMaximumBoundariesWithoutLosingLongPrecision() {
        listOf(
            QueueIndex(emptyList(), 0),
            QueueIndex((1..MAX_PENDING).map(::id), Long.MAX_VALUE),
            QueueIndex(listOf(id(1)), 9_007_199_254_740_993L),
        ).forEach { value -> assertEquals(value, JournalCodec.decodeIndex(JournalCodec.encodeIndex(value))) }
    }

    @Test fun commandRoundTripKeepsRawStringsListsOrderAndExplicitNulls() {
        val value = command().copy(
            request = RequestMetadata(
                "completeCookingStep",
                linkedMapOf("sessionId" to "private/one\\two\"three", "stepId" to " café 😀 "),
                linkedMapOf("raw" to listOf("01", " 1.2300E+009 ", "", "same", "same")),
                "\"0007\"", true,
            ),
            dependencies = listOf(id(3), id(2)),
            createdAt = Long.MAX_VALUE,
            retryAt = Long.MAX_VALUE,
        )
        val bytes = JournalCodec.encodeCommand(value)
        assertEquals(value, JournalCodec.decodeCommand(bytes))
        val json = Json.parseToJsonElement(bytes.copyForCodec().decodeToString()).jsonObject
        assertEquals(JsonPrimitive(1), json["version"])
        assertEquals(JsonNull, json["firstAttemptAt"])
        assertEquals(JsonNull, json["reply"])
        assertFalse("body" in json.getValue("request").jsonObject)
        assertTrue(json.getValue("request").jsonObject.containsKey("ifMatch"))
    }

    @Test fun receiptRoundTripPreservesTypedMetadataAndBodyPresence() {
        val value = attempted(CommandPhase.RECEIPT_READY).copy(
            reply = ReplyMetadata(299, "\"0008\"", "trace:exact", Long.MAX_VALUE, "application/json; charset=utf-8", true),
        )
        assertEquals(value, JournalCodec.decodeCommand(JournalCodec.encodeCommand(value)))
        val emptyReply = value.copy(reply = ReplyMetadata(204, null, null, null, null, false))
        val encoded = JournalCodec.encodeCommand(emptyReply)
        assertEquals(emptyReply, JournalCodec.decodeCommand(encoded))
        val reply = Json.parseToJsonElement(encoded.copyForCodec().decodeToString()).jsonObject.getValue("reply").jsonObject
        listOf("etag", "traceId", "retryAfterSeconds", "contentType").forEach { assertEquals(JsonNull, reply[it]) }
    }

    @Test fun malformedJsonUnicodeAndDuplicateDecodedKeysAreSanitized() {
        val valid = encodedCommand()
        listOf(
            "", " ", "[]", "null", "{", valid + " false", "{\"version\":NaN}",
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"),
            valid.replace("\"path\":{}", "\"path\":{\"secret\":\"one\",\"secret\":\"two\"}"),
            valid.replace("\"scopeActorId\":\"actor\"", "\"scopeActorId\":\"\\uD800\""),
        ).forEach { raw -> rejected { JournalCodec.decodeCommand(privateBytes(raw)) } }
        rejected { JournalCodec.decodeCommand(PrivateBytes(byteArrayOf(0xc3.toByte(), 0x28))) }
        rejected { JournalCodec.decodeCommand(privateBytes("[".repeat(13) + "0" + "]".repeat(13))) }
    }

    @Test fun everySchemaFieldIsRequiredAndUnknownFieldsAreRejectedAtEveryLevel() {
        val index = indexJson()
        index.keys.forEach { key -> rejected { JournalCodec.decodeIndex(privateBytes(JsonObject(index - key).toString())) } }
        rejected { JournalCodec.decodeIndex(privateBytes(JsonObject(index + ("secret" to JsonPrimitive("value"))).toString())) }

        val command = commandJson()
        command.keys.forEach { key -> decodeRejected(JsonObject(command - key)) }
        decodeRejected(JsonObject(command + ("secret" to JsonPrimitive("value"))))
        val request = command.getValue("request").jsonObject
        request.keys.forEach { key -> decodeRejected(command.with("request", JsonObject(request - key))) }
        decodeRejected(command.with("request", JsonObject(request + ("body" to JsonPrimitive("private-body")))))

        val receipt = commandJson(attempted(CommandPhase.RECEIPT_READY).copy(reply = reply()))
        val reply = receipt.getValue("reply").jsonObject
        reply.keys.forEach { key -> decodeRejected(receipt.with("reply", JsonObject(reply - key))) }
        decodeRejected(receipt.with("reply", JsonObject(reply + ("body" to JsonPrimitive("private-body")))))
    }

    @Test fun wrongJsonKindsNeverCoerceToMetadataValues() {
        val value = commandJson()
        listOf("id", "originBinding", "scopeEnvironment", "scopeActorKind", "scopeActorId", "phase", "issue").forEach { key ->
            listOf(JsonNull, JsonPrimitive(12), JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap())).forEach {
                decodeRejected(value.with(key, it))
            }
        }
        listOf("request", "dependencies").forEach { key -> decodeRejected(value.with(key, JsonPrimitive("private"))) }
        decodeRejected(value.with("dependencies", JsonArray(listOf(JsonNull))))
        val request = value.getValue("request").jsonObject
        listOf("operationId", "path", "query", "hasBody").forEach { key ->
            decodeRejected(value.with("request", request.with(key, JsonNull)))
        }
        decodeRejected(value.with("request", request.with("ifMatch", JsonPrimitive(1))))
        decodeRejected(value.with("request", request.with("hasBody", JsonPrimitive("true"))))
        decodeRejected(value.with("request", request.with("path", JsonObject(mapOf("key" to JsonNull)))))
        decodeRejected(value.with("request", request.with("query", JsonObject(mapOf("key" to JsonPrimitive("value"))))))
        decodeRejected(value.with("request", request.with("query", JsonObject(mapOf("key" to JsonArray(listOf(JsonPrimitive(1))))))))

        val receipt = commandJson(attempted(CommandPhase.RECEIPT_READY).copy(reply = reply()))
        val reply = receipt.getValue("reply").jsonObject
        listOf("etag", "traceId", "contentType").forEach { key ->
            decodeRejected(receipt.with("reply", reply.with(key, JsonPrimitive(1))))
        }
        decodeRejected(receipt.with("reply", reply.with("hasBody", JsonPrimitive("false"))))
    }

    @Test fun integerMetadataRequiresCanonicalNonnegativeExactTokensAndRange() {
        val invalidNumbers = listOf("-0", "-1", "0.0", "1e0", "1E+0", "1.5", "+1", "01", "\"1\"", "true", "null", "9223372036854775808")
        invalidNumbers.forEach { number ->
            rejected { JournalCodec.decodeIndex(privateBytes("{\"version\":1,\"ids\":[],\"lastObservedMillis\":$number}")) }
        }
        val command = encodedCommand(attempted(CommandPhase.IN_FLIGHT))
        listOf("createdAt" to "10", "firstAttemptAt" to "10", "retryAt" to "0", "attempts" to "1").forEach { (key, previous) ->
            invalidNumbers.forEach { number ->
                rejected { JournalCodec.decodeCommand(privateBytes(command.replace("\"$key\":$previous", "\"$key\":$number"))) }
            }
        }
        val receipt = encodedCommand(attempted(CommandPhase.RECEIPT_READY).copy(reply = reply().copy(retryAfterSeconds = 1)))
        listOf("status" to "200", "retryAfterSeconds" to "1").forEach { (key, previous) ->
            invalidNumbers.forEach { number ->
                if (key != "retryAfterSeconds" || number != "null") {
                    rejected { JournalCodec.decodeCommand(privateBytes(receipt.replace("\"$key\":$previous", "\"$key\":$number"))) }
                }
            }
        }
        rejected { JournalCodec.decodeCommand(privateBytes(command.replace("\"attempts\":1", "\"attempts\":2147483648"))) }
        listOf("0", "2", "1.0", "1e0", "\"1\"", "null", "true").forEach { version ->
            rejected { JournalCodec.decodeCommand(privateBytes(encodedCommand().replace("\"version\":1", "\"version\":$version"))) }
            rejected { JournalCodec.decodeIndex(privateBytes(indexJson().toString().replace("\"version\":1", "\"version\":$version"))) }
        }
    }

    @Test fun attemptPhaseAndReceiptInvariantsAreCheckedOnEncodeAndDecode() {
        val base = command()
        listOf(
            base.copy(attempts = -1), base.copy(attempts = 9, firstAttemptAt = 10),
            base.copy(firstAttemptAt = 10), base.copy(attempts = 1),
            base.copy(attempts = 1, firstAttemptAt = 9), base.copy(createdAt = -1), base.copy(retryAt = -1),
            base.copy(phase = CommandPhase.IN_FLIGHT), base.copy(phase = CommandPhase.RETRY_WAIT),
            base.copy(phase = CommandPhase.RECEIPT_READY, reply = reply()), base.copy(phase = CommandPhase.APPLIED),
            attempted(CommandPhase.DISCARDED), attempted(CommandPhase.RECEIPT_READY),
            base.copy(reply = reply()), attempted(CommandPhase.APPLIED).copy(reply = reply()),
        ).forEach { value -> rejected { JournalCodec.encodeCommand(value) } }
        val attemptedJson = commandJson(attempted(CommandPhase.IN_FLIGHT))
        decodeRejected(attemptedJson.with("firstAttemptAt", JsonNull))
        decodeRejected(attemptedJson.with("attempts", JsonPrimitive(0)))
        decodeRejected(attemptedJson.with("firstAttemptAt", JsonPrimitive(9)))
        decodeRejected(attemptedJson.with("phase", JsonPrimitive("DISCARDED")))
        decodeRejected(commandJson().with("phase", JsonPrimitive("RECEIPT_READY")))
        CommandPhase.entries.filter { it != CommandPhase.RECEIPT_READY && it != CommandPhase.DISCARDED }.forEach { phase ->
            val value = attempted(phase)
            assertEquals(value, JournalCodec.decodeCommand(JournalCodec.encodeCommand(value)))
        }
    }

    @Test fun terminalStatesRetainOperationButRequireErasedPrivateRequestMetadata() {
        listOf(attempted(CommandPhase.APPLIED), command().copy(phase = CommandPhase.DISCARDED)).forEach { terminal ->
            assertEquals(terminal, JournalCodec.decodeCommand(JournalCodec.encodeCommand(terminal)))
            listOf(
                terminal.request.copy(path = mapOf("id" to "private")),
                terminal.request.copy(query = mapOf("query" to listOf("private"))),
                terminal.request.copy(ifMatch = "\"1\""), terminal.request.copy(hasBody = true),
            ).forEach { request -> rejected { JournalCodec.encodeCommand(terminal.copy(request = request)) } }
        }
    }

    @Test fun identityDependenciesAndScopesAreBoundedAndNeverNormalized() {
        val base = command()
        val maxDependencies = (2..65).map(::id)
        assertEquals(maxDependencies, JournalCodec.decodeCommand(JournalCodec.encodeCommand(base.copy(dependencies = maxDependencies))).dependencies)
        listOf(
            base.copy(id = "BAD"), base.copy(originBinding = "ABCDEF01-2345-6789-abcd-0123456789ab"),
            base.copy(dependencies = listOf(base.id)), base.copy(dependencies = listOf(id(2), id(2))),
            base.copy(dependencies = listOf("bad")), base.copy(dependencies = (2..66).map(::id)),
            base.copy(scopeEnvironment = " "), base.copy(scopeEnvironment = "a".repeat(201)),
            base.copy(scopeActorId = "actor\nprivate"), base.copy(scopeActorId = "\uD800"),
            base.copy(scopeActorKind = "DEMO"), base.copy(scopeActorKind = "account"),
        ).forEach { rejected { JournalCodec.encodeCommand(it) } }
        val maxScope = base.copy(scopeEnvironment = "a".repeat(200), scopeActorId = "😀".repeat(100), scopeActorKind = "GUEST")
        assertEquals(maxScope, JournalCodec.decodeCommand(JournalCodec.encodeCommand(maxScope)))
        listOf(
            QueueIndex(listOf(id(1), id(1)), 0), QueueIndex((1..129).map(::id), 0),
            QueueIndex(listOf("bad"), 0), QueueIndex(emptyList(), -1),
        ).forEach { rejected { JournalCodec.encodeIndex(it) } }
    }

    @Test fun parameterCeilingsCountUtf16AndIncludeCommandKeyAndIfMatch() {
        val base = command()
        val maximum = base.copy(request = base.request.copy(
            path = linkedMapOf("a" to "😀".repeat(2048), "b" to "b".repeat(4096), "c" to "c".repeat(4096), "d" to "d".repeat(4057)),
            ifMatch = "\"1\"",
        ))
        assertEquals(maximum, JournalCodec.decodeCommand(JournalCodec.encodeCommand(maximum)))
        rejected { JournalCodec.encodeCommand(maximum.copy(request = maximum.request.copy(ifMatch = "\"12\""))) }
        listOf("a".repeat(4097), "\u0000", "\u0085", "\uD800", "\uDC00").forEach { value ->
            rejected { JournalCodec.encodeCommand(base.copy(request = base.request.copy(query = mapOf("q" to listOf(value))))) }
        }
        listOf(
            base.request.copy(operationId = "bad-operation"), base.request.copy(path = mapOf("key" to " ")),
            base.request.copy(path = mapOf("" to "value")), base.request.copy(query = mapOf("key\n" to emptyList())),
            base.request.copy(ifMatch = " "),
        ).forEach { rejected { JournalCodec.encodeCommand(base.copy(request = it)) } }
    }

    @Test fun replyMetadataUsesApiReplyBoundsAndOnlySuccessfulReceipts() {
        val base = attempted(CommandPhase.RECEIPT_READY)
        listOf(
            reply().copy(status = 199), reply().copy(status = 300), reply().copy(status = 600),
            reply().copy(etag = " "), reply().copy(etag = "x".repeat(257)),
            reply().copy(traceId = "private\nvalue"), reply().copy(traceId = "\uD800"),
            reply().copy(contentType = ""), reply().copy(contentType = "x".repeat(257)),
            reply().copy(retryAfterSeconds = 0), reply().copy(retryAfterSeconds = -1),
        ).forEach { rejected { JournalCodec.encodeCommand(base.copy(reply = it)) } }
        val bounded = base.copy(reply = reply().copy(etag = "a".repeat(256), traceId = "b".repeat(256), contentType = "c".repeat(256)))
        assertEquals(bounded, JournalCodec.decodeCommand(JournalCodec.encodeCommand(bounded)))
    }

    @Test fun metadataByteLimitIsEnforcedOnDecodeAndOnEscapedEncoderOutput() {
        val raw = indexJson().toString()
        val exact = raw + " ".repeat(128 * 1024 - raw.encodeToByteArray().size)
        assertEquals(QueueIndex(emptyList(), 0), JournalCodec.decodeIndex(privateBytes(exact)))
        rejected { JournalCodec.decodeIndex(privateBytes(exact + " ")) }
        val hugeEscapedKey = "\\".repeat(65_536)
        rejected { JournalCodec.encodeCommand(command().copy(request = command().request.copy(path = mapOf(hugeEscapedKey to "value")))) }
        val tooManyEmptyValues = List(128 * 1024 / 3 + 1) { "" }
        rejected { JournalCodec.encodeCommand(command().copy(request = command().request.copy(query = mapOf("q" to tooManyEmptyValues)))) }
    }

    @Test fun encodedPrivateBytesAndDecodedCollectionSnapshotsDoNotAliasInputs() {
        val path = mutableMapOf("key" to "original")
        val values = mutableListOf("one", "two")
        val query = mutableMapOf("q" to values)
        val dependencies = mutableListOf(id(2), id(3))
        val value = command().copy(request = command().request.copy(path = path, query = query), dependencies = dependencies)
        val encoded = JournalCodec.encodeCommand(value)
        path["key"] = "changed"
        values[0] = "changed"
        query.clear()
        dependencies.clear()
        val first = JournalCodec.decodeCommand(encoded)
        val second = JournalCodec.decodeCommand(encoded)
        assertEquals(mapOf("key" to "original"), first.request.path)
        assertEquals(listOf("one", "two"), first.request.query["q"])
        assertEquals(listOf(id(2), id(3)), first.dependencies)
        assertNotSame(first.request.path, second.request.path)
        assertNotSame(first.request.query, second.request.query)
        assertNotSame(first.request.query["q"], second.request.query["q"])
        assertNotSame(first.dependencies, second.dependencies)
        val copy = encoded.copyForCodec()
        copy.fill(0)
        assertEquals(first, JournalCodec.decodeCommand(encoded))
        val indexInput = mutableListOf(id(1), id(2))
        val index = JournalCodec.encodeIndex(QueueIndex(indexInput, 1))
        indexInput.clear()
        assertEquals(listOf(id(1), id(2)), JournalCodec.decodeIndex(index).ids)
    }

    private fun command() = JournalCommand(
        id = id(1), originBinding = id(99), scopeEnvironment = "test", scopeActorKind = "ACCOUNT", scopeActorId = "actor",
        request = RequestMetadata("completeCookingStep", emptyMap(), emptyMap(), null, false),
        dependencies = emptyList(), createdAt = 10, firstAttemptAt = null, retryAt = 0,
        phase = CommandPhase.READY, attempts = 0, issue = CommandIssue.NONE,
    )

    private fun attempted(phase: CommandPhase) = command().copy(phase = phase, attempts = 1, firstAttemptAt = 10)
    private fun reply() = ReplyMetadata(200, null, null, null, null, false)
    private fun id(number: Int) = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')
    private fun privateBytes(raw: String) = PrivateBytes(raw.encodeToByteArray())
    private fun encodedCommand(value: JournalCommand = command()) = JournalCodec.encodeCommand(value).copyForCodec().decodeToString()
    private fun commandJson(value: JournalCommand = command()) = Json.parseToJsonElement(encodedCommand(value)).jsonObject
    private fun indexJson() = Json.parseToJsonElement(JournalCodec.encodeIndex(QueueIndex(emptyList(), 0)).copyForCodec().decodeToString()).jsonObject
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun decodeRejected(json: JsonElement) = rejected { JournalCodec.decodeCommand(privateBytes(json.toString())) }
    private fun rejected(block: () -> Any?) {
        val failure = assertFailsWith<JournalDecodingException> { block() }
        assertEquals("Invalid command journal", failure.message)
        assertNull(failure.cause)
    }
}
