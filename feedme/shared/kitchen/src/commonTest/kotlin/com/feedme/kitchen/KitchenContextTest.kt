package com.feedme.kitchen

import com.feedme.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Common adapter contracts only; these fakes do not establish native storage or authentication. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KitchenContextTest {
    @Test fun currentLeaseAllowsAccountAndGuestWithoutActivatingNewIdentity() = runTest {
        for (kind in listOf(ActorKind.ACCOUNT, ActorKind.GUEST)) {
            val f = Fixture(StandardTestDispatcher(testScheduler), kind)
            assertEquals("private-local-result", value(f.context.guarded(f.lease) { "private-local-result" }))
            assertSame(f.lease, f.boundary.current())
            assertEquals(0, f.store.reads)
            assertEquals(0, f.store.commits.size)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun clearedAndSupersededLeaseFailBeforeReadCommitOrFetch() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.boundary.clear()
        failure(FailureReason.STALE_SESSION, f.context.guarded(f.lease) { f.context.read(f.lease, KEY) })
        failure(FailureReason.STALE_SESSION, f.context.guarded(f.lease) { f.context.commit(f.lease, listOf(kitchenPut(KEY, null, bytes("x")))) })
        failure(FailureReason.STALE_SESSION, f.fetch())
        f.boundary.activate(f.scope)
        failure(FailureReason.STALE_SESSION, f.fetch())
        assertEquals(0, f.store.reads)
        assertTrue(f.store.commits.isEmpty())
        assertTrue(f.calls.isEmpty())
    }

    @Test fun scopeMismatchCannotUseCurrentLeaseForAnotherOwnerOrEnvironment() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (scope in listOf(StorageScope("test", ActorKind.ACCOUNT, "other-owner"),
            StorageScope("production", ActorKind.ACCOUNT, "private-owner"),
            StorageScope("test", ActorKind.GUEST, "private-owner"))) {
            val other = f.boundary.activate(scope)
            failure(FailureReason.STALE_SESSION, f.context.guarded(other) { f.context.read(other, KEY) })
        }
        assertEquals(0, f.store.reads)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun demoCannotAccessPrivateRepositoriesDespiteCurrentLease() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler), ActorKind.DEMO)
        failure(FailureReason.UNAUTHENTICATED, f.fetch())
        failure(FailureReason.UNAUTHENTICATED, f.context.guarded(f.lease) { f.context.read(f.lease, KEY) })
        assertEquals(0, f.store.reads)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun readPassesExactOwnerAndKeyAndPreservesAbsence() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        assertNull(value(f.context.guarded(f.lease) { f.context.read(f.lease, KEY) }))
        val record = PrivateRecord(4, 1, bytes("private-record"))
        f.store.readResult = PortResult.Value(record)
        val read = assertNotNull(value(f.context.guarded(f.lease) { f.context.read(f.lease, KEY) }))
        assertEquals(4L, read.revision)
        assertContentEquals(record.payload.copyForCodec(), read.payload.copyForCodec())
        assertEquals(listOf(f.scope to KEY, f.scope to KEY), f.store.readArguments)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun sessionChangeDuringSuspendedStoreReadFencesReturnedPrivateData() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store.beforeReadReturn = { entered.complete(Unit); release.await() }
        f.store.readResult = PortResult.Value(PrivateRecord(1, 1, bytes("private-other-incarnation")))
        val pending = async { f.context.guarded(f.lease) { f.context.read(f.lease, KEY) } }
        entered.await()
        f.boundary.activate(f.scope)
        release.complete(Unit)
        failure(FailureReason.STALE_SESSION, pending.await())
        assertEquals(1, f.store.reads)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun sessionChangeDuringSuspendedCommitDoesNotClaimCommittedDomainSuccess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store.beforeCommitReturn = { entered.complete(Unit); release.await() }
        f.store.commitResult = PortResult.Value(mapOf(KEY to 3L))
        val pending = async { f.context.guarded(f.lease) { f.context.commit(f.lease, listOf(kitchenPut(KEY, 2, bytes("new")))) } }
        entered.await()
        f.boundary.clear()
        release.complete(Unit)
        failure(FailureReason.STALE_SESSION, pending.await())
        assertEquals(1, f.store.commits.size)
        assertEquals(2L, f.store.commits.single().single().expectedRevision)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun sessionChangeDuringSuspendedTransportFencesSuccessfulServerReply() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.exchange = { _, _ -> entered.complete(Unit); release.await(); PortResult.Value(cookReply()) }
        val pending = async { f.fetch() }
        entered.await()
        f.boundary.clear()
        release.complete(Unit)
        failure(FailureReason.STALE_SESSION, pending.await())
        assertEquals(1, f.calls.size)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun finalGuardFenceRejectsDataAfterActionChangesSession() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        failure(FailureReason.STALE_SESSION, f.context.guarded(f.lease) {
            f.boundary.clear()
            "private-result"
        })
    }

    @Test fun canonicalCookGetReturnsOriginalReplyWithoutSynthesizingOrWritingCache() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val reply = cookReply()
        f.exchange = { lease, call ->
            assertSame(f.lease, lease)
            assertEquals("getCookSession", call.operationId)
            assertEquals(mapOf("sessionId" to ID), call.pathParameters)
            assertTrue(call.queryParameters.isEmpty())
            assertNull(call.body)
            assertNull(call.idempotencyKey)
            assertNull(call.ifMatch)
            PortResult.Value(reply)
        }
        val result = value(f.fetch())
        assertSame(reply, result)
        assertEquals(200, result.status)
        assertContentEquals(reply.body!!.copyForCodec(), result.body!!.copyForCodec())
        assertEquals("\"7\"", result.etag)
        assertEquals(0, f.store.reads)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun missingGetEtagRemainsMissingInsteadOfBecomingWritable() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.exchange = { _, _ -> PortResult.Value(cookReply().copy(etag = null)) }
        assertNull(value(f.fetch()).etag)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun allOwnedGetOperationsPassThroughCanonicalErrorBindingWithoutWrites() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.exchange = { _, _ -> PortResult.Value(problem(404)) }
        for ((operation, key) in listOf("getCookSession" to "sessionId", "getPlan" to "planId", "getSavedRecipe" to "savedRecipeId")) {
            failure(FailureReason.NOT_FOUND, f.context.guarded(f.lease) { f.context.fetch(f.lease, operation, mapOf(key to ID)) })
        }
        assertEquals(listOf("getCookSession", "getPlan", "getSavedRecipe"), f.calls.map { it.operationId })
        assertEquals(0, f.store.reads)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun unknownReadsMutationsAndArbitraryUrlsAreRejectedBeforeTransport() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (operation in listOf("getMe", "listRecipes", "getServiceHealth", "updateCookSession", "saveRecipe", "deleteSavedRecipe",
            "adminGetHealth", "unknownOperation", "https://other.example/private")) {
            failure(FailureReason.INVALID_DATA, f.context.guarded(f.lease) { f.context.fetch(f.lease, operation, emptyMap()) })
        }
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.store.reads)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun canonicalHttpFailuresMapToExplicitLocalFailureAndRetainRetryHeader() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val expected = mapOf(400 to FailureReason.INVALID_DATA, 401 to FailureReason.UNAUTHENTICATED,
            403 to FailureReason.FORBIDDEN, 404 to FailureReason.NOT_FOUND, 409 to FailureReason.CONFLICT,
            410 to FailureReason.NOT_FOUND, 412 to FailureReason.CONFLICT, 422 to FailureReason.INVALID_DATA,
            428 to FailureReason.INVALID_DATA, 429 to FailureReason.RATE_LIMITED,
            500 to FailureReason.UNAVAILABLE, 503 to FailureReason.UNAVAILABLE)
        for ((status, reason) in expected) {
            f.exchange = { _, _ -> PortResult.Value(problem(status, 91)) }
            val failure = failure(reason, f.fetch())
            assertEquals(91L, failure.retryAfterSeconds, "HTTP $status")
        }
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun malformedAndMismatchedErrorBodiesCannotBecomeTrustedFailures() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (reply in listOf(ApiReply(401, bytes("{}"), contentType = "application/problem+json"),
            ApiReply(401, problem(403).body, contentType = "application/problem+json"),
            problem(401).copy(traceId = "different-trace"),
            problem(503).copy(contentType = "text/plain"),
            ApiReply(404, null),
            ApiReply(302, null))) {
            f.exchange = { _, _ -> PortResult.Value(reply) }
            failure(FailureReason.INVALID_DATA, f.fetch())
        }
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun unexpectedSuccessStatusMissingBodyAndMalformedSchemaNeverBecomeSynthetic200() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (reply in listOf(ApiReply(204, null), ApiReply(201, cookReply().body, contentType = "application/json"),
            ApiReply(200, null), ApiReply(200, bytes("{}"), contentType = "application/json"),
            ApiReply(200, cookReply().body, contentType = "text/plain"),
            ApiReply(200, PrivateBytes(byteArrayOf(0x80.toByte())), contentType = "application/json"))) {
            f.exchange = { _, _ -> PortResult.Value(reply) }
            failure(FailureReason.INVALID_DATA, f.fetch())
        }
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun validLookingSuccessStillRequiresNestedCompleteCanonicalSchema() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val original = cookReply().body!!.copyForCodec().decodeToString()
        for (body in listOf(original.replace("\"timers\":[]", "\"timers\":[{}]"),
            original.replace("\"deviceSequence\":3", "\"deviceSequence\":-1"),
            original.dropLast(1) + ",\"unexpected\":true}",
            original.dropLast(1) + ",\"version\":8}")) {
            f.exchange = { _, _ -> PortResult.Value(ApiReply(200, bytes(body), contentType = "application/json")) }
            failure(FailureReason.INVALID_DATA, f.fetch())
        }
    }

    @Test fun transportFailuresPreserveOriginalCategoryAndRetryMetadataWithoutRetries() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        for (reason in FailureReason.entries) {
            f.exchange = { _, _ -> PortResult.Failure(reason, 67) }
            val result = failure(reason, f.fetch())
            assertEquals(67L, result.retryAfterSeconds)
        }
        assertEquals(FailureReason.entries.size, f.calls.size)
        assertTrue(f.store.commits.isEmpty())
    }

    @Test fun readAndBatchCommitFailuresPropagateMetadataWithoutRetryOrPartialLocalApplication() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.store.readResult = PortResult.Failure(FailureReason.STORAGE_FAILURE, 11)
        assertEquals(11L, failure(FailureReason.STORAGE_FAILURE,
            f.context.guarded(f.lease) { f.context.read(f.lease, KEY) }).retryAfterSeconds)
        val second = RecordKey("kitchen.plan", OTHER_ID)
        val changes = listOf(kitchenPut(KEY, 4, bytes("replacement")), StoreMutation.Delete(second, 9))
        for (reason in listOf(FailureReason.CONFLICT, FailureReason.STORAGE_FAILURE, FailureReason.OUTCOME_UNKNOWN)) {
            f.store.commitResult = PortResult.Failure(reason, 22)
            val failure = failure(reason, f.context.guarded(f.lease) { f.context.commit(f.lease, changes) })
            assertEquals(22L, failure.retryAfterSeconds)
        }
        assertEquals(3, f.store.commits.size)
        f.store.commits.forEach { batch ->
            assertEquals(2, batch.size)
            assertEquals(listOf(KEY, second), batch.map { it.key })
            assertEquals(listOf(4L, 9L), batch.map { it.expectedRevision })
        }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun successfulBatchReturnsStoreOwnedRevisionsAndDoesNotFabricateValues() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val second = RecordKey("kitchen.plan", OTHER_ID)
        val revisions = mapOf(KEY to 100L, second to null)
        f.store.commitResult = PortResult.Value(revisions)
        f.store.readByKey = { key -> PortResult.Value(if (key == KEY) PrivateRecord(100, 1, bytes("next")) else null) }
        assertEquals(revisions, value(f.context.guarded(f.lease) {
            f.context.commit(f.lease, listOf(kitchenPut(KEY, 99, bytes("next")), StoreMutation.Delete(second, 3)))
        }))
        assertEquals(listOf(f.scope), f.store.commitScopes)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun unexpectedAdapterExceptionsAreSanitizedNotLeakedThroughResults() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.exchange = { _, _ -> error("private access token and recipe") }
        val result = failure(FailureReason.STORAGE_FAILURE, f.fetch())
        assertFalse(result.toString().contains("private access token"))
        assertNull(result.retryAfterSeconds)
        assertEquals(1, f.calls.size)
        val typed = assertFailsWith<KitchenFailure> { kitchenFail(FailureReason.INVALID_DATA, 7) }
        assertEquals("Kitchen operation failed", typed.message)
        assertNull(typed.cause)
    }

    @Test fun cancellationWhileTransportSuspendsIsPropagatedAndNeverConvertedToSuccess() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.exchange = { _, _ -> awaitCancellation() }
        val pending = async { f.fetch() }
        runCurrent()
        assertEquals(1, f.calls.size)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(f.store.commits.isEmpty())
        assertSame(f.lease, f.boundary.current())
    }

    @Test fun cancellationWhileStoreSuspendsDoesNotBecomeStorageFailure() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.store.beforeReadReturn = { awaitCancellation() }
        val pending = async { f.context.guarded(f.lease) { f.context.read(f.lease, KEY) } }
        runCurrent()
        assertEquals(1, f.store.reads)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(f.store.commits.isEmpty())
        assertTrue(f.calls.isEmpty())
    }

    @Test fun negativeClockFailsLocallyWhileZeroAndLongMaximumAreExact() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.now = -1
        failure(FailureReason.INVALID_DATA, f.context.guarded(f.lease) { f.context.now() })
        f.now = 0
        assertEquals(0L, value(f.context.guarded(f.lease) { f.context.now() }))
        f.now = Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, value(f.context.guarded(f.lease) { f.context.now() }))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun documentValidationRetainsOriginalCanonicalBytesAndRejectsUnknownSchemas() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val raw = " \n" + cookReply().body!!.copyForCodec().decodeToString() + "\n "
        val document = f.context.document("CookSession", bytes(raw))
        assertContentEquals(raw.encodeToByteArray(), document.encodeUtf8())
        invalid { f.context.document("UnknownSchema", bytes(raw)) }
        invalid { f.context.document("CookSession", bytes("{}")) }
        invalid { f.context.document("CookSession", bytes(raw.replace("\"version\":7", "\"version\":0"))) }
        assertEquals(0, f.store.reads)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun contentDigestUsesExactBytesAndKnownSha256Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", privateDigest(bytes("")))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", privateDigest(bytes("abc")))
        assertNotEquals(privateDigest(bytes("{\"a\":1}")), privateDigest(bytes(" {\"a\":1}")))
        assertNotEquals(privateDigest(bytes("private-A")), privateDigest(bytes("private-B")))
        assertTrue(privateDigest(bytes("private-content")).matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun verifiedRecordRequiresExactDigestSchemaAndPresence() {
        val payload = bytes("private-owned-document")
        val record = PrivateRecord(5, 1, payload)
        val digest = privateDigest(payload)
        assertContentEquals(payload.copyForCodec(), readVerified(record, digest).copyForCodec())
        invalid { readVerified(null, digest) }
        invalid { readVerified(record.copy(schemaVersion = 2), digest) }
        invalid { readVerified(record.copy(payload = bytes("tampered-document")), digest) }
        invalid { readVerified(record, digest.uppercase()) }
        invalid { readVerified(record, "0".repeat(64)) }
        invalid { readVerified(record, "invalid") }
    }

    @Test fun digestAndVerifiedPayloadDoNotAliasCallerMutableBuffers() {
        val source = "private-original".encodeToByteArray()
        val payload = PrivateBytes(source)
        val digest = privateDigest(payload)
        source.fill(0)
        val read = readVerified(PrivateRecord(1, 1, payload), digest)
        val projection = read.copyForCodec()
        projection.fill(0)
        assertEquals("private-original", read.copyForCodec().decodeToString())
        assertEquals(digest, privateDigest(read))
    }

    @Test fun identifierNormalizationIsStrictButCaseInsensitiveAtExternalBoundary() {
        assertEquals(ID, normalizedId(ID))
        assertEquals(ID, normalizedId(ID.uppercase()))
        for (id in listOf("", "../$ID", "$ID/next", "%2e%2e", "https://other.example/$ID", " $ID", "$ID ",
            "$ID\n", ID.replace("-", ""), "123e4567-e89b-12d3-a456-42661417400g")) invalid { normalizedId(id) }
    }

    @Test fun kitchenPutAlwaysUsesExplicitCasAndSchemaOneWithoutChangingPayload() {
        val payload = bytes("private-json")
        val create = kitchenPut(KEY, null, payload)
        assertEquals(KEY, create.key)
        assertNull(create.expectedRevision)
        assertEquals(1, create.schemaVersion)
        assertContentEquals(payload.copyForCodec(), create.payload.copyForCodec())
        assertEquals(17L, kitchenPut(KEY, 17, payload).expectedRevision)
    }

    @Test fun typedFailureUnwrappingPreservesZeroRetryWithoutRawProviderDetails() {
        assertEquals("value", kitchenValue(PortResult.Value("value")))
        val failure = assertFailsWith<KitchenFailure> { kitchenValue(PortResult.Failure(FailureReason.RATE_LIMITED, 0)) }
        assertEquals(FailureReason.RATE_LIMITED, failure.reason)
        assertEquals(0L, failure.retryAfterSeconds)
        assertEquals("Kitchen operation failed", failure.message)
        assertNull(failure.cause)
    }

    private class Fixture(dispatcher: CoroutineDispatcher, kind: ActorKind = ActorKind.ACCOUNT) {
        val scope = StorageScope("test", kind, "private-owner")
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        val store = TestStore()
        var now = 1_800_000_000_000L
        val calls = mutableListOf<ApiCall>()
        var exchange: suspend (SessionLease, ApiCall) -> PortResult<ApiReply> = { _, _ -> PortResult.Value(cookReply()) }
        val context = KitchenContext(scope, store, boundary, dispatcher, EpochClock { now }, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call
                return exchange(lease, call)
            }
        })
        suspend fun fetch() = context.guarded(lease) { context.fetch(lease, "getCookSession", mapOf("sessionId" to ID)) }
    }

    private class TestStore : PrivateStateStore {
        var reads = 0
        val readArguments = mutableListOf<Pair<StorageScope, RecordKey>>()
        val commits = mutableListOf<List<StoreMutation>>()
        val commitScopes = mutableListOf<StorageScope>()
        var readResult: PortResult<PrivateRecord?> = PortResult.Value(null)
        var readByKey: ((RecordKey) -> PortResult<PrivateRecord?>)? = null
        var commitResult: PortResult<Map<RecordKey, Long?>> = PortResult.Value(emptyMap())
        var beforeReadReturn: suspend () -> Unit = {}
        var beforeCommitReturn: suspend () -> Unit = {}
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            reads++
            readArguments += scope to key
            beforeReadReturn()
            return readByKey?.invoke(key) ?: readResult
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            commits += mutations.toList()
            commitScopes += scope
            beforeCommitReturn()
            return commitResult
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("Context must not erase or activate identity")
    }

    companion object {
        private const val ID = "123e4567-e89b-12d3-a456-426614174001"
        private const val OTHER_ID = "123e4567-e89b-12d3-a456-426614174002"
        private val KEY = RecordKey("kitchen.cook", ID)
        private fun bytes(text: String) = PrivateBytes(text.encodeToByteArray())
        private fun cookReply() = ApiReply(200, bytes("""{"id":"$ID","version":7,"createdAt":"2026-09-13T07:00:00Z","updatedAt":"2026-09-13T08:00:00Z","planId":"$OTHER_ID","status":"active","currentStepId":"step-one","completedStepIds":[],"deviceSequence":3,"timers":[]}"""),
            etag = "\"7\"", contentType = "application/json")
        private fun problem(status: Int, retryAfter: Long? = null) = ApiReply(status,
            bytes("""{"type":"https://example.test/problems/unavailable","title":"Unavailable","status":$status,"code":"TEST_UNAVAILABLE","traceId":"test-trace"}"""),
            retryAfterSeconds = retryAfter, contentType = "application/problem+json")
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
        private fun failure(reason: FailureReason, result: PortResult<*>): PortResult.Failure =
            assertIs<PortResult.Failure>(result).also { assertEquals(reason, it.reason) }
        private fun invalid(action: () -> Unit) {
            assertEquals(FailureReason.INVALID_DATA, assertFailsWith<KitchenFailure> { action() }.reason)
        }
    }
}
