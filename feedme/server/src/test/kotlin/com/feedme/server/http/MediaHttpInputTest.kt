package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.media.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class MediaHttpInputTest {
    @Test fun exactFourOperationsAndCanonicalHeaderPlacement() {
        assertEquals(setOf("prepareMediaUpload", "completeMediaUpload", "getMediaStatus", "deleteDraftMedia"), mediaHttpOperations)
        for (operation in mediaHttpOperations) {
            val parsed = input(operation)
            assertEquals(operation in setOf("prepareMediaUpload", "completeMediaUpload"), parsed.hasBody)
            assertEquals(if (operation == "getMediaStatus") null else uuid, parsed.key)
            assertEquals(if (operation == "prepareMediaUpload") null else uuid, parsed.mediaId)
            assertEquals(if (operation == "deleteDraftMedia") "\"1\"" else null, parsed.ifMatch)
        }
        denied(400) { input("getMediaAccess") }
    }

    @Test fun mediaRequiresDeviceAndBearerWithoutGuestInference() {
        for (name in listOf("Authorization", "X-Device-Session"))
            denied(401) { input(headers = defaults().filterNot { it.first == name }) }
        for (value in listOf("Basic abc", "Bearer ", "Bearer a=b", "Bearer a b", "Bearer " + "a".repeat(16385)))
            denied(401) { input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to value)) }
        denied(400) { input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to "Bearer a,Bearer b")) }
        assertNotNull(input(headers = defaults().filterNot { it.first == "Authorization" } + ("Authorization" to "bEaReR  abc+/==")))
    }

    @Test fun repeatedControlHeadersAreRejected() {
        for (name in listOf("Authorization", "X-Device-Session", "Idempotency-Key", "Content-Type"))
            denied(400) { input(extra = listOf(name to defaults().first { it.first == name }.second)) }
        for (name in listOf("Content-Length", "Content-Encoding", "Transfer-Encoding", "If-Match", "If-None-Match"))
            denied(400) { input(extra = listOf(name to "0", name to "0")) }
    }

    @Test fun identitiesMustBeCanonicalUuidsNotOwnerAssertions() {
        for (name in listOf("X-Device-Session", "Idempotency-Key")) for (value in listOf("1-1-1-1-1", "guest", " $ID", "$ID,$ID"))
            denied(400) { input(headers = defaults().filterNot { it.first == name } + (name to value)) }
        for (operation in mediaHttpOperations) for (name in listOf("ownerId", "clientDraftId", "mediaId", "cursor", "surface"))
            denied(400) { input(operation, query = parametersOf(name, ID)) }
        denied(400) { input("getMediaStatus", paths = Parameters.Empty) }
        denied(400) { input("getMediaStatus", paths = parametersOf("mediaId", "1-1-1-1-1")) }
        denied(400) { input("getMediaStatus", paths = Parameters.build { append("mediaId", ID); append("mediaId", ID) }) }
        denied(400) { input("getMediaStatus", paths = Parameters.build { append("mediaId", ID); append("ownerId", ID) }) }
        denied(400) { input(paths = parametersOf("mediaId", ID)) }
    }

    @Test fun mutationsKeepOriginalKeyAndOnlyDeleteUsesIfMatch() {
        for (operation in mediaHttpOperations - "getMediaStatus")
            denied(400) { input(operation, headers = defaults(operation).filterNot { it.first == "Idempotency-Key" }) }
        denied(400) { input("getMediaStatus", extra = listOf("Idempotency-Key" to ID)) }
        denied(428) { input("deleteDraftMedia", headers = defaults("deleteDraftMedia").filterNot { it.first == "If-Match" }) }
        for (operation in mediaHttpOperations - "deleteDraftMedia")
            denied(400) { input(operation, extra = listOf("If-Match" to "\"1\"")) }
        for (operation in mediaHttpOperations)
            denied(400) { input(operation, extra = listOf("If-None-Match" to "\"1\"")) }
        for (value in listOf("*", "1", "W/\"1\"", "\"-1\"", "\"1\",\"2\"", "\"" + "1".repeat(65) + "\""))
            denied(400) { input("deleteDraftMedia", headers = defaults("deleteDraftMedia").filterNot { it.first == "If-Match" } + ("If-Match" to value)) }
    }

    @Test fun framingRejectsAmbiguityCompressionAndUnsupportedMedia() {
        for (value in listOf("-1", "+1", "65537", "18446744073709551615", "1,1"))
            denied(400) { input(extra = listOf("Content-Length" to value)) }
        denied(400) { input(extra = listOf("Content-Length" to "1", "Transfer-Encoding" to "chunked")) }
        for (value in listOf("gzip", "identity", "chunked,chunked"))
            denied(400) { input(extra = listOf("Transfer-Encoding" to value)) }
        assertNotNull(input(extra = listOf("Transfer-Encoding" to "chunked")))
        for (value in listOf("gzip", "br", "identity,identity")) denied(400) { input(extra = listOf("Content-Encoding" to value)) }
        for (value in listOf("multipart/form-data", "image/jpeg", "application/json;charset=utf-16", "application/json;charset=utf-8;charset=utf-8"))
            denied(400) { input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to value)) }
        denied(400) { input(headers = defaults().filterNot { it.first == "Content-Type" }) }
        assertNotNull(input(headers = defaults().filterNot { it.first == "Content-Type" } + ("Content-Type" to "Application/JSON; charset=\"UTF-8\"")))
        for (operation in listOf("getMediaStatus", "deleteDraftMedia")) {
            for (header in listOf("Content-Type" to "application/json", "Content-Length" to "1", "Transfer-Encoding" to "chunked"))
                denied(400) { input(operation, extra = listOf(header)) }
            denied(400) { input(operation).body("{}".encodeToByteArray(), validator) }
        }
    }

    @Test fun originalWireRejectsDuplicateKeysUnicodeDepthAndTrailingBytes() {
        for (body in listOf("{", "", "{} trailing", "{\"bytes\":1,\"bytes\":2}", "{\"clientDraftId\":\"\\uD800\"}", "[".repeat(33) + "0" + "]".repeat(33)))
            denied(400) { input().body(body.encodeToByteArray(), validator) }
        denied(400) { input().body(byteArrayOf(0xc3.toByte(), 0x28), validator) }
    }

    @Test fun prepareKeepsExactIntegerSpellingAndNeverAddsClientStorageKeys() {
        for (number in listOf("1", "1e3", "9007199254740993")) {
            val body = prepare.replace("\"bytes\":1", "\"bytes\":$number")
            assertEquals(number, input().body(body.encodeToByteArray(), validator)!!.getValue("bytes").jsonPrimitive.content)
        }
        for (body in listOf(prepare.replace("\"bytes\":1", "\"bytes\":1.5"), prepare.replace("\"bytes\":1", "\"bytes\":0"),
            prepare.dropLast(1) + ",\"objectKey\":\"caller-key\"}", prepare.replace(ID, "bad"), "{}"))
            denied(422) { input().body(body.encodeToByteArray(), validator) }
        // A canonical declared kind is not a configured feature/format grant; the store rejects
        // deferred clip/avatar and byte limits separately without changing the shared schema.
        assertNotNull(input().body(prepare.replace("photo", "clip").encodeToByteArray(), validator))
    }

    @Test fun completeRequiresImmutableVersionAndChecksumNotAnArbitraryUrl() {
        val parsed = input("completeMediaUpload")
        assertEquals("version-1", parsed.body(complete.encodeToByteArray(), validator)!!.getValue("objectVersionId").jsonPrimitive.content)
        for (body in listOf("{}", "{\"objectVersionId\":\"v1\"}", complete.replace(HASH, "bad"), complete.dropLast(1) + ",\"objectUrl\":\"https://untrusted.example\"}"))
            denied(422) { parsed.body(body.encodeToByteArray(), validator) }
    }

    @Test fun streamingEnforcesActualAndDeclaredByteLimits() = runBlocking<Unit> {
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(65537) { 32 }), validator) }
        deniedSuspend(400) { input().readBody(ByteReadChannel(ByteArray(0)), validator) }
        deniedSuspend(400) { input(extra = listOf("Content-Length" to "1")).readBody(ByteReadChannel(prepare.encodeToByteArray()), validator) }
        assertNotNull(input(extra = listOf("Content-Length" to "65536")).readBody(ByteReadChannel(prepare.padEnd(65536, ' ').encodeToByteArray()), validator))
        assertNull(input("getMediaStatus").readBody(ByteReadChannel(ByteArray(0)), validator))
        deniedSuspend(400) { input("deleteDraftMedia").readBody(ByteReadChannel(byteArrayOf(32)), validator) }
    }

    @Test fun repliesHaveExactStatusSchemaVersionAndCapabilityPlacement() {
        val core = Json.parseToJsonElement(upload).jsonObject
        val prepared = JsonObject(core + mapOf("uploadUrl" to JsonPrimitive("https://upload.example.test/private"),
            "uploadMethod" to JsonPrimitive("POST"), "uploadFields" to buildJsonObject { put("policy", "secret-post-capability") },
            "uploadExpiresAt" to JsonPrimitive("2026-09-14T01:00:00Z")))
        assertEquals(prepared.toString(), validateMediaReply("prepareMediaUpload", StoredReply(201, prepared, "\"1\""), validator, 65536))
        for (operation in listOf("getMediaStatus", "completeMediaUpload")) {
            val observed = if (operation == "completeMediaUpload") JsonObject(core + ("status" to JsonPrimitive("processing"))) else core
            assertEquals(observed.toString(), validateMediaReply(operation, StoredReply(200, observed, "\"1\""), validator, 65536))
            assertFailsWith<IllegalStateException> { validateMediaReply(operation, StoredReply(200, prepared, "\"1\""), validator, 65536) }
            for (bad in listOf(StoredReply(201, core, "\"1\""), StoredReply(200, core, "\"2\""), StoredReply(200, core), StoredReply(204)))
                assertFailsWith<IllegalStateException> { validateMediaReply(operation, bad, validator, 65536) }
        }
        assertFailsWith<IllegalStateException> { validateMediaReply("completeMediaUpload", StoredReply(200, core, "\"1\""), validator, 65536) }
        for (field in listOf("uploadUrl", "uploadMethod", "uploadFields", "uploadExpiresAt"))
            assertFailsWith<IllegalStateException> { validateMediaReply("prepareMediaUpload", StoredReply(201, JsonObject(prepared - field), "\"1\""), validator, 65536) }
        for (url in listOf("http://upload.example.test", "https://name:password@upload.example.test", "https://upload.example.test/#secret"))
            assertFailsWith<IllegalStateException> { validateMediaReply("prepareMediaUpload", StoredReply(201, JsonObject(prepared + ("uploadUrl" to JsonPrimitive(url))), "\"1\""), validator, 65536) }
        assertFailsWith<IllegalStateException> { validateMediaReply("getMediaStatus", StoredReply(200, core, "\"1\""), validator, 1) }
        assertEquals("", validateMediaReply("deleteDraftMedia", StoredReply(204), validator, 65536))
        // The stored-reply boundary rejects a body for204 before HTTP projection is possible.
        assertFailsWith<IllegalArgumentException> { StoredReply(204, core) }
        assertFailsWith<IllegalStateException> { validateMediaReply("deleteDraftMedia", StoredReply(204, etag = "\"1\""), validator, 65536) }
    }

    @Test fun verifiedAccountMustMatchConfiguredEnvironmentAndSuppliedDevice() = runBlocking<Unit> {
        val actor = account()
        assertSame(actor, configuration { PortResult.Value(actor) }.authenticate(input()))
        for (wrong in listOf(VerifiedMediaAccount("other", uuid, uuid), VerifiedMediaAccount("test", uuid, UUID.randomUUID())))
            deniedSuspend(401) { configuration { PortResult.Value(wrong) }.authenticate(input()) }
    }

    @Test fun verificationFailuresAreSanitizedAndRetryMetadataIsPurposeBounded() = runBlocking<Unit> {
        for ((reason, status) in listOf(FailureReason.UNAUTHENTICATED to 401, FailureReason.STALE_SESSION to 401,
            FailureReason.INVALID_DATA to 401, FailureReason.NOT_FOUND to 401, FailureReason.FORBIDDEN to 403,
            FailureReason.RATE_LIMITED to 429, FailureReason.UNAVAILABLE to 503)) {
            val failure = deniedSuspend(status) { configuration { PortResult.Failure(reason, 7) }.authenticate(input()) }
            assertEquals(if (status in setOf(429, 503)) 7L else null, failure.retryAfterSeconds)
        }
        val error = deniedSuspend(503) { configuration { error("PRIVATE-PROVIDER-CANARY") }.authenticate(input()) }
        assertFalse(error.toString().contains("PRIVATE-PROVIDER-CANARY"))
    }

    @Test fun cancellationCannotBeSwallowedIntoAnAuthenticatedReturn() = runBlocking<Unit> {
        val job = Job()
        val config = configuration { job.cancel(); withContext(NonCancellable) { PortResult.Value(account()) } }
        assertFailsWith<CancellationException> { withContext(job) { config.authenticate(input()) } }
    }

    @Test fun diagnosticsNeverExposeBearerOrDeviceIdentity() {
        assertEquals("MediaHttpInput(<redacted>)", input().toString())
        assertEquals("MediaHttpBearer(<redacted>)", input().bearer.toString())
        assertEquals("MediaHttpConfiguration(<redacted>)", configuration { error("unused") }.toString())
    }

    private fun input(operation: String = "prepareMediaUpload", headers: List<Pair<String, String>> = defaults(operation),
        extra: List<Pair<String, String>> = emptyList(), query: Parameters = Parameters.Empty,
        paths: Parameters = if (operation == "prepareMediaUpload") Parameters.Empty else parametersOf("mediaId", ID)) =
        MediaHttpInput.parse(operation, Headers.build { (headers + extra).forEach { append(it.first, it.second) } }, query, paths)
    private fun defaults(operation: String = "prepareMediaUpload") = buildList {
        add("Authorization" to "Bearer synthetic-secret"); add("X-Device-Session" to ID)
        if (operation != "getMediaStatus") add("Idempotency-Key" to ID)
        if (operation in setOf("prepareMediaUpload", "completeMediaUpload")) add("Content-Type" to "application/json")
        if (operation == "deleteDraftMedia") add("If-Match" to "\"1\"")
    }
    private fun denied(status: Int, action: () -> Any?) = assertFailsWith<MediaHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private suspend fun deniedSuspend(status: Int, action: suspend () -> Any?) = assertFailsWith<MediaHttpFailure> { action() }.also { assertEquals(status, it.status) }
    private fun account() = VerifiedMediaAccount("test", uuid, uuid)
    private fun configuration(verifier: suspend (MediaHttpBearer) -> PortResult<VerifiedMediaAccount>): MediaHttpConfiguration {
        val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ -> error("Unexpected database access") } as DataSource
        val authority = object : MediaAuthority {
            override fun lockPrincipal(connection: Connection, actor: VerifiedMediaAccount) = error("Unexpected authority")
            override fun requireUploadEnabled(connection: Connection, actor: VerifiedMediaAccount, newReservation: Boolean) = error("Unexpected quota")
            override fun lockDraftLifecycle(connection: Connection, actor: VerifiedMediaAccount, clientDraftId: UUID, forUpload: Boolean): Long = error("Unexpected draft")
            override fun requireUnattached(connection: Connection, actor: VerifiedMediaAccount, mediaId: UUID) = error("Unexpected attachment")
        }
        val policy = MediaServicePolicy(1_000_000, setOf("image/jpeg"), 3600, 60, 4096, 65536, 256, setOf("https://upload.example.test"))
        val store = MediaStore("test", PgTransactions(source), authority, MediaUploadCapabilities { error("Unexpected signer") }, MediaObjectVerifier { error("Unexpected object read") }, policy)
        return MediaHttpConfiguration("test", store, MediaHttpVerifier(verifier), Dispatchers.IO)
    }
    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000011"
        private const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private val uuid = UUID.fromString(ID)
        private val validator = ContractBodyValidator.bundled()
        private const val prepare = """{"kind":"photo","contentType":"image/jpeg","bytes":1,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","clientDraftId":"00000000-0000-4000-8000-000000000011"}"""
        private const val complete = """{"objectVersionId":"version-1","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
        private const val upload = """{"id":"00000000-0000-4000-8000-000000000011","version":1,"createdAt":"2026-09-14T00:00:00Z","updatedAt":"2026-09-14T00:00:00Z","status":"awaitingUpload"}"""
    }
}
