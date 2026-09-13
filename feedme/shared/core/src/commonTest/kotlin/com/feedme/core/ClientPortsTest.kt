package com.feedme.core

import com.feedme.core.ports.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*

class ClientPortsTest {
    private val owner = StorageScope("test", ActorKind.ACCOUNT, "account-a")

    @Test fun scopeSeparatesActorKindAndEnvironment() {
        assertNotEquals(owner, owner.copy(actorKind = ActorKind.GUEST))
        assertNotEquals(owner, owner.copy(actorKind = ActorKind.DEMO))
        assertNotEquals(owner, owner.copy(environment = "production"))
        assertNotEquals(owner, owner.copy(actorId = "account-b"))
    }

    @Test fun invalidOwnershipAndCredentialInputsAreRejected() {
        assertFailsWith<IllegalArgumentException> { owner.copy(actorId = "") }
        assertFailsWith<IllegalArgumentException> { owner.copy(environment = "test\nproduction") }
        assertFailsWith<IllegalArgumentException> { SecretText("token\r\nInjected: value") }
        assertFailsWith<IllegalArgumentException> { IdentitySession(owner.copy(actorKind = ActorKind.DEMO), 100) }
        assertFailsWith<IllegalArgumentException> {
            StoredCredentials.Account(owner.copy(actorKind = ActorKind.DEMO), SecretText("access"), null, 100)
        }
    }

    @Test fun defaultDebugStringsDoNotExposePrivateMaterial() {
        val credentials = StoredCredentials.Account(owner, SecretText("access-sensitive"), SecretText("refresh-sensitive"), 100, SecretText("device-sensitive"))
        val output = credentials.toString()
        for (secret in listOf("account-a", "device-sensitive", "access-sensitive", "refresh-sensitive")) assertFalse(output.contains(secret))
        assertEquals("access-sensitive", credentials.accessToken.use { it })
        assertFalse(PrivateRecord(1, 1, PrivateBytes("dietary-note".encodeToByteArray())).toString().contains("dietary-note"))
    }

    @Test fun guestAndPreBootstrapCredentialsNeedNoFabricatedDeviceSession() {
        val guest = StoredCredentials.Guest(owner.copy(actorKind = ActorKind.GUEST), SecretText("guest-id"), SecretText("guest-token"), 100)
        assertEquals(ActorKind.GUEST, guest.scope.actorKind)
        assertFalse(guest.toString().contains("guest-token"))
        val bootstrap = StoredCredentials.Account(owner, SecretText("provider-token"), null, 100)
        assertNull(bootstrap.deviceSessionId)
        val ready = bootstrap.copy(deviceSessionId = SecretText("registered-session"))
        assertNotNull(ready.deviceSessionId)
        assertFailsWith<IllegalArgumentException> { guest.copy(scope = owner) }
        assertFailsWith<IllegalArgumentException> { bootstrap.copy(scope = guest.scope) }
    }

    @Test fun serializedPrivateBuffersAreDetachedAtBothBoundaries() {
        val source = byteArrayOf(1, 2, 3)
        val stored = PrivateBytes(source)
        source[0] = 9
        val returned = stored.copyForCodec()
        returned[1] = 8
        assertContentEquals(byteArrayOf(1, 2, 3), stored.copyForCodec())
    }

    @Test fun storeMutationValidationDoesNotPermitUnconditionalOverwrite() {
        val key = RecordKey("cookSessions", "id")
        assertNull(StoreMutation.Put(key, null, 1, PrivateBytes(byteArrayOf())).expectedRevision)
        assertEquals(2, StoreMutation.Put(key, 2, 1, PrivateBytes(byteArrayOf())).expectedRevision)
        assertFailsWith<IllegalArgumentException> { StoreMutation.Put(key, 0, 1, PrivateBytes(byteArrayOf())) }
        assertFailsWith<IllegalArgumentException> { StoreMutation.Delete(key, 0) }
        assertFailsWith<IllegalArgumentException> { PrivateRecord(1, 0, PrivateBytes(byteArrayOf())) }
    }

    @Test fun noCurrentSessionExistsByDefault() {
        assertNull(SessionBoundary().current())
    }

    @Test fun clearingSessionImmediatelyRejectsOutstandingLeaseWithoutDispatch() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(owner)
        boundary.clear()
        var calls = 0
        val result = completed { boundary.execute(lease) { calls++; PortResult.Value("private") } }
        assertEquals(0, calls)
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), result)
    }

    @Test fun reloginToSameAccountDoesNotAcceptOldTicket() {
        val boundary = SessionBoundary()
        val old = boundary.activate(owner)
        val replacement = boundary.activate(owner)
        assertFalse(boundary.isCurrent(old))
        assertTrue(boundary.isCurrent(replacement))
    }

    @Test fun ticketFromAnotherBoundaryIsNeverAccepted() {
        val first = SessionBoundary().activate(owner)
        val other = SessionBoundary()
        other.activate(owner)
        assertFalse(other.isCurrent(first))
    }

    @Test fun delayedReplyIsDiscardedAfterAccountSwitch() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(owner)
        var pending: Continuation<PortResult<String>>? = null
        var outcome: Result<PortResult<String>>? = null
        val request: suspend () -> PortResult<String> = {
            boundary.execute(lease) { suspendCoroutine { pending = it } }
        }
        request.startCoroutine(object : Continuation<PortResult<String>> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<PortResult<String>>) { outcome = result }
        })
        assertNull(outcome)
        boundary.activate(owner.copy(actorId = "account-b"))
        pending!!.resume(PortResult.Value("account-a-private-reply"))
        assertEquals(PortResult.Failure(FailureReason.STALE_SESSION), outcome!!.getOrThrow())
    }

    @Test fun currentTransportPreservesStatusEtagAndOpaqueBody() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(owner)
        val call = ApiCall("getRecipeVersion", pathParameters = mapOf("recipeVersionId" to "version-id"))
        val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> =
                PortResult.Value(ApiReply(200, PrivateBytes("body".encodeToByteArray()), "\"3\""))
        }
        val result = completed { boundary.execute(lease, transport, call) }
        val reply = assertIs<PortResult.Value<ApiReply>>(result).value
        assertEquals(200, reply.status)
        assertEquals("\"3\"", reply.etag)
        assertContentEquals("body".encodeToByteArray(), reply.body!!.copyForCodec())
        assertFalse(reply.toString().contains("\"3\""))
    }

    @Test fun transportPreservesFailuresAndBodylessSuccess() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(owner)
        val pending = PortResult.Failure(FailureReason.UNAVAILABLE, 10)
        assertEquals(pending, completed { boundary.execute(lease) { pending } })
        assertNull(ApiReply(204, null).body)
        assertFailsWith<IllegalArgumentException> { PortResult.Failure(FailureReason.RATE_LIMITED, -1) }
    }

    @Test fun httpErrorAndMetadataSurviveWithoutLeakingIntoDebugString() {
        val reply = ApiReply(429, PrivateBytes("problem-json".encodeToByteArray()), traceId = "private-trace", retryAfterSeconds = 10)
        val boundary = SessionBoundary()
        val lease = boundary.activate(owner)
        val value = assertIs<PortResult.Value<ApiReply>>(completed { boundary.execute(lease) { PortResult.Value(reply) } }).value
        assertEquals(429, value.status)
        assertEquals("private-trace", value.traceId)
        assertEquals(10L, value.retryAfterSeconds)
        assertFalse(value.toString().contains("private-trace"))
        assertFalse(value.toString().contains("problem-json"))
        assertFailsWith<IllegalArgumentException> { ApiReply(429, null, retryAfterSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { ApiReply(200, null, traceId = "x\r\ny") }
        assertFailsWith<IllegalArgumentException> { ApiReply(200, null, traceId = "x".repeat(257)) }
    }

    @Test fun uncertainMutationReceiptIsDistinctFromUnsentFailure() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(owner)
        val uncertain = PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
        assertEquals(uncertain, completed { boundary.execute(lease) { uncertain } })
        assertNotEquals(PortResult.Failure(FailureReason.OFFLINE), uncertain)
    }

    @Test fun transportDetachesParametersAndDoesNotLogThem() {
        val paths = mutableMapOf("recipeVersionId" to "private-version")
        val values = mutableListOf("private-cursor")
        val call = ApiCall("listRecipes", paths, mapOf("cursor" to values), idempotencyKey = SecretText("private-command"))
        paths["recipeVersionId"] = "changed"
        values[0] = "changed"
        assertEquals("private-version", call.pathParameters["recipeVersionId"])
        assertEquals(listOf("private-cursor"), call.queryParameters["cursor"])
        for (value in listOf("private-version", "private-cursor", "private-command")) assertFalse(call.toString().contains(value))
        assertFailsWith<IllegalArgumentException> { ApiCall("https://untrusted.example") }
        assertFailsWith<IllegalArgumentException> { ApiCall("listRecipes", ifMatch = "x\r\nHeader: y") }
    }

    @Test fun cancellationOrAdapterExceptionIsNotConvertedToSuccess() {
        val boundary = SessionBoundary()
        val lease = boundary.activate(owner)
        assertFailsWith<IllegalStateException> {
            completed { boundary.execute<String>(lease) { throw IllegalStateException("adapter failed") } }
        }
    }

    @Test fun apiCallStoresIndependentSnapshotsOfInitialMapsAndNestedLists() {
        val paths = mutableMapOf("recipeVersionId" to "version-one", "planId" to "plan-one")
        val cursor = mutableListOf("cursor-one", "cursor-two")
        val query = mutableMapOf("cursor" to cursor, "q" to mutableListOf("rice", "beans"))
        val call = ApiCall("listRecipes", paths, query)

        paths["recipeVersionId"] = "changed\r\n"
        paths.clear()
        cursor[0] = "changed\r\n"
        query.getValue("q").clear()
        query["injected"] = mutableListOf("unexpected")
        query.remove("cursor")

        assertEquals(mapOf("recipeVersionId" to "version-one", "planId" to "plan-one"), call.pathParameters)
        assertEquals(mapOf("cursor" to listOf("cursor-one", "cursor-two"), "q" to listOf("rice", "beans")), call.queryParameters)
    }

    @Test fun apiCallReturnedMapsAndQueryListsCannotMutateStoredIntent() {
        val expectedPaths = mapOf("recipeVersionId" to "version-one", "planId" to "plan-one")
        val expectedQuery = mapOf("cursor" to listOf("cursor-one", "cursor-two"), "q" to listOf("rice", "beans"))
        val call = ApiCall("listRecipes", expectedPaths, expectedQuery)

        // Multiple entries exercise mutable implementations behind the read-only interfaces.
        val returnedPaths = call.pathParameters as MutableMap<String, String>
        val returnedQuery = call.queryParameters as MutableMap<String, List<String>>
        val returnedCursor = returnedQuery.getValue("cursor") as MutableList<String>
        returnedPaths["recipeVersionId"] = "changed\r\n"
        returnedPaths.remove("planId")
        returnedPaths["injected"] = "unexpected"
        returnedCursor[0] = "changed\r\n"
        returnedCursor.add("extra")
        returnedQuery["q"] = listOf("changed")
        returnedQuery["injected"] = listOf("unexpected")

        assertEquals(expectedPaths, call.pathParameters)
        assertEquals(expectedQuery, call.queryParameters)
        val snapshot = ApiCall(call.operationId, call.pathParameters, call.queryParameters)
        assertEquals(expectedPaths, snapshot.pathParameters)
        assertEquals(expectedQuery, snapshot.queryParameters)
    }

    private fun <T> completed(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "Test adapter must complete synchronously in this helper." }.getOrThrow()
    }
}
