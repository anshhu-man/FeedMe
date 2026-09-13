package com.feedme.transport

import com.feedme.contracts.ContractCatalog
import com.feedme.core.ports.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HttpExchangeTest {
    private val catalog = ContractCatalog.bundled()
    private suspend fun response(bytes: ByteArray, status: Int = 200,
        headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json")),
        operation: String = "getServiceHealth"): PortResult<ApiReply> {
        val engine = MockEngine { respond(ByteReadChannel(bytes), HttpStatusCode.fromValue(status), headersOf(*headers.entries.map { it.key to it.value }.toTypedArray())) }
        val client = HttpClient(engine) { followRedirects = false; expectSuccess = false }
        return try {
            HttpExchange(ApiEndpoint.loopback("test", 8789), client).execute(PreparedCall(catalog.operation(operation)!!,
                listOf("v1", "test"), emptyMap(), null, emptyMap()), null)
        } finally { client.close(); engine.close() }
    }

    @Test fun bodyless204RemainsAbsentNotJsonNull() = runTest {
        val reply = assertIs<PortResult.Value<ApiReply>>(response(byteArrayOf(), 204,
            mapOf("Content-Length" to listOf("0")), "deletePost")).value
        assertNull(reply.body); assertNull(reply.contentType)
    }

    @Test fun bytesOnBodylessOperationAreNotFabricatedReceipt() = runTest {
        assertEquals(FailureReason.OUTCOME_UNKNOWN, assertIs<PortResult.Failure>(response("null".encodeToByteArray(), 204, emptyMap(), "deletePost")).reason)
    }

    @Test fun responseLimitRejectsDeclaredAndChunkedOversize() = runTest {
        assertIs<PortResult.Failure>(response("{}".encodeToByteArray(), headers = mapOf(
            "Content-Type" to listOf("application/json"), "Content-Length" to listOf("1048577"))))
        assertIs<PortResult.Failure>(response(("\"" + "a".repeat(1048575) + "\"").encodeToByteArray()))
    }

    @Test fun contentLengthMismatchAndBadLengthsReject() = runTest {
        for (length in listOf("1", "3", "-1", "abc", "99999999999999999999")) {
            assertIs<PortResult.Failure>(response("{}".encodeToByteArray(), headers = mapOf(
                "Content-Type" to listOf("application/json"), "Content-Length" to listOf(length))))
        }
    }

    @Test fun invalidUtf8DuplicateKeysAndEmptyJsonAreRejected() = runTest {
        for (body in listOf(byteArrayOf(0xff.toByte()), byteArrayOf(), "{\"a\":1,\"a\":2}".encodeToByteArray(),
            "{}true".encodeToByteArray(), "{\"a\":NaN}".encodeToByteArray()))
            assertIs<PortResult.Failure>(response(body))
    }

    @Test fun undeclaredStatusIsNotInterpretedAsSuccessfulOperation() = runTest {
        assertIs<PortResult.Failure>(response("{}".encodeToByteArray(), 202))
        assertIs<PortResult.Failure>(response("{}".encodeToByteArray(), 302))
    }

    @Test fun mediaTypeMustMatchCanonicalResponseAndUtf8Policy() = runTest {
        for (media in listOf("text/html", "application/problem+json", "application/json; charset=latin1", "application/json; garbage",
            "application/json; charset=UTF-8; charset=UTF-8", "application/jsonsuffix"))
            assertIs<PortResult.Failure>(response("{}".encodeToByteArray(), headers = mapOf("Content-Type" to listOf(media))))
        assertIs<PortResult.Failure>(response("{}".encodeToByteArray(), headers = emptyMap()))
        assertIs<PortResult.Value<ApiReply>>(response("{}".encodeToByteArray(), headers = mapOf("Content-Type" to listOf("APPLICATION/JSON; charset=\"utf-8\""))))
    }

    @Test fun duplicateMetadataAndUnsupportedCompressionReject() = runTest {
        for ((name, values) in mapOf("Content-Type" to listOf("application/json", "application/json"),
            "X-Trace-Id" to listOf("a", "b"), "Content-Encoding" to listOf("gzip"), "ETag" to listOf("a".repeat(257))))
            assertIs<PortResult.Failure>(response("{}".encodeToByteArray(), headers = mapOf("Content-Type" to listOf("application/json")) + (name to values)))
    }

    @Test fun retryAfterRequiresCanonicalPositiveIntegerNotDateOrOverflow() = runTest {
        for (retry in listOf("0", "-1", "1.0", "Sun, 13 Sep 2026 12:00:00 GMT", "999999999999999999999"))
            assertIs<PortResult.Failure>(response("{}".encodeToByteArray(), headers = mapOf("Content-Type" to listOf("application/json"), "Retry-After" to listOf(retry))))
    }

    @Test fun rawNumbersAndExplicitNullStayUnchangedButAreNotSchemaCertification() = runTest {
        val raw = "{\"large\":9007199254740993,\"null\":null,\"exact\":1.2300}"
        val reply = assertIs<PortResult.Value<ApiReply>>(response(raw.encodeToByteArray())).value
        assertEquals(raw, reply.body!!.copyForCodec().decodeToString())
        // This isn't a valid Health object. Transport does not certify schema/domain validity;
        // response binding must run before anything reaches domain state or durable storage.
    }

    @Test fun percentEncodedPathAndQueryCannotChangeOriginOrParameterCardinality() = runTest {
        val engine = MockEngine { request ->
            assertEquals("127.0.0.1", request.url.host)
            assertEquals("/v1/a%2Fb%3Fc%23d%25", request.url.encodedPath)
            assertEquals(listOf("a&x=y +?#"), request.url.parameters.getAll("q"))
            assertNull(request.url.parameters["x"])
            respond("{}", headers = headersOf("Content-Type", "application/json"))
        }
        val client = HttpClient(engine)
        try {
            assertIs<PortResult.Value<ApiReply>>(HttpExchange(ApiEndpoint.loopback("test", 8789), client).execute(
                PreparedCall(catalog.operation("getServiceHealth")!!, listOf("v1", "a/b?c#d%"),
                    mapOf("q" to listOf("a&x=y +?#")), null, emptyMap()), null))
        } finally { client.close(); engine.close() }
    }
}
