package com.feedme.server.http

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.planning.*
import com.feedme.server.social.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.Socket
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Actual Ktor/CIO and PostgreSQL. Identity/profile/block/provider facts below are TEST ONLY. */
class SocialHttpIntegrationTest {
    @Test fun configuredCreateReadListAndUpdateReturnCanonicalPrivateRepresentations() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }
        val first = client.command(f, "POST", "/v1/circles", BODY); success(first, 201, "createCircle")
        val circle = objectBody(first); val id = circle.string("id")
        assertEquals("owner", circle.string("role")); assertEquals("\"1\"", first.headers[HttpHeaders.ETag])
        val read = client.get("/v1/circles/$id") { account(f) }; success(read, 200, "getCircle"); assertEquals(circle, objectBody(read))
        val listed = client.get("/v1/circles?limit=1") { account(f) }; success(listed, 200, "listCircles")
        assertEquals(circle, objectBody(listed).getValue("items").jsonArray.single())
        val changed = client.command(f, "PATCH", "/v1/circles/$id", """{"name":"Updated circle","description":"Reviewed description"}""", etag = "\"1\"")
        success(changed, 200, "updateCircle"); assertEquals("\"2\"", changed.headers[HttpHeaders.ETag])
        assertEquals("Updated circle", objectBody(changed).string("name"))
        assertEquals(1, f.count("social.circles")); assertEquals(2, f.count("platform.outbox"))
        problem(client.get("/v1/plans/${UUID.randomUUID()}") { account(f) }, 503, "OPERATION_NOT_IMPLEMENTED")
    }

    @Test fun invitationAcceptanceMemberRosterRoleUpdateAndRemovalUseActualStore() = testApplication {
        val f = Fixture(); val member = f.account(); application { feedMeLocalService(local, social = f.configuration()) }
        val circle = f.circle(); val issued = client.command(f, "POST", "/v1/invitations", invitation(circle)); success(issued, 201, "createInvitation")
        val joined = client.command(f, "POST", "/v1/invitations/accept", accept(token(objectBody(issued))), actor = member)
        success(joined, 200, "acceptInvitation"); assertEquals("member", objectBody(joined).string("role"))
        val roster = client.get("/v1/circles/$circle/members") { account(f) }; success(roster, 200, "listCircleMembers")
        assertEquals(setOf(f.owner.accountId.toString(), member.accountId.toString()), objectBody(roster).getValue("items").jsonArray
            .map { it.jsonObject.getValue("user").jsonObject.string("userId") }.toSet())
        val role = client.command(f, "PATCH", "/v1/circles/$circle/members/${member.accountId}", """{"role":"admin"}""", etag = "\"1\"")
        success(role, 200, "updateCircleMember"); assertEquals("\"2\"", role.headers[HttpHeaders.ETag])
        success(client.command(f, "DELETE", "/v1/circles/$circle/members/${member.accountId}", etag = "\"2\""), 204, "removeCircleMember")
        problem(client.get("/v1/circles/$circle") { account(f, member) }, 404, "CIRCLE_UNAVAILABLE")
    }

    @Test fun explicitTransferThenLeaveAndDissolveNeverLeavesAnOwnerlessActiveCircle() = testApplication {
        val f = Fixture(); val target = f.account(); val circle = f.circle(); f.join(circle, target)
        application { feedMeLocalService(local, social = f.configuration()) }
        problem(client.command(f, "POST", "/v1/circles/$circle/leave", "{}"), 409, "OWNER_TRANSFER_REQUIRED")
        val transfer = client.command(f, "POST", "/v1/circles/$circle/ownership",
            """{"newOwnerUserId":"${target.accountId}","confirmed":true}""", etag = f.store.getCircle(f.owner, circle).etag!!)
        success(transfer, 200, "transferCircleOwnership"); assertEquals("member", objectBody(transfer).string("role"))
        val leaveKey = UUID.randomUUID()
        repeat(2) { success(client.command(f, "POST", "/v1/circles/$circle/leave", "{}", key = leaveKey), 200, "leaveCircle") }
        success(client.command(f, "DELETE", "/v1/circles/$circle", actor = target, etag = f.store.getCircle(target, circle).etag!!), 204, "deleteCircle")
        assertEquals("archived", f.value("SELECT status FROM social.circles"))
        assertEquals("0", f.value("SELECT count(*) FROM social.circle_members WHERE status='active'"))
    }

    @Test fun publicPreviewIsMinimalCapabilityReadAndIgnoresOptionalAuthWithoutVerification() = testApplication {
        val f = Fixture(); val circle = f.circle(); val bearer = token(f.invite(circle)); val counts = f.counts()
        application { feedMeLocalService(local, social = f.configuration()) }; f.verification = { error("Preview must not acquire a provider") }
        val anonymous = client.get("/v1/invitations/preview") { parameter("token", bearer) }; success(anonymous, 200, "previewInvitation")
        val body = objectBody(anonymous); assertEquals(setOf("status","targetType","targetName","inviterLabel","expiresAt"), body.keys)
        for (secret in listOf(circle.toString(), f.owner.accountId.toString(), bearer)) assertFalse(anonymous.bodyAsText().contains(secret))
        val optional = client.get("/v1/invitations/preview") {
            parameter("token", bearer); header(HttpHeaders.Authorization, "Bearer synthetic-guest"); header("X-Device-Session", f.owner.deviceSessionId)
        }
        success(optional, 200, "previewInvitation"); assertEquals(body, objectBody(optional)); assertEquals(0, f.verifications)
        assertEquals(counts, f.counts()); assertEquals("active", f.value("SELECT status FROM social.circle_invitations"))
    }

    @Test fun expiredRevokedAndUnknownPreviewCapabilitiesAreUniformAndNeverRedeem() = testApplication {
        val f = Fixture(); val circle = f.circle(); val expired = f.invite(circle); f.expire(expired.id())
        val revoked = f.invite(circle); f.store.revokeInvitation(f.owner, UUID.randomUUID(), revoked.id(), "\"1\"")
        application { feedMeLocalService(local, social = f.configuration()) }; val before = f.counts()
        for (bearer in listOf(token(expired), token(revoked), "x".repeat(64))) {
            val response = client.get("/v1/invitations/preview") { parameter("token", bearer) }
            success(response, 200, "previewInvitation"); assertEquals("""{"status":"unavailable"}""", response.bodyAsText())
        }
        assertEquals(before, f.counts()); assertEquals(0, f.verifications)
    }

    @Test fun invitationIssueAndRevokeReplayExactResponseWithoutRetainingBearer() = testApplication {
        val f = Fixture(); val circle = f.circle(); application { feedMeLocalService(local, social = f.configuration()) }
        val key = UUID.randomUUID(); val first = client.command(f, "POST", "/v1/invitations", invitation(circle), key = key)
        val repeated = client.command(f, "POST", "/v1/invitations", invitation(circle), key = key)
        success(first, 201, "createInvitation"); success(repeated, 201, "createInvitation"); sameRepresentation(first, repeated)
        val issued = objectBody(first); val bearer = token(issued)
        val retained = f.value("SELECT string_agg(row_to_json(i)::text,'') FROM platform.idempotency i") +
            f.value("SELECT string_agg(row_to_json(i)::text,'') FROM platform.outbox i") +
            f.value("SELECT string_agg(row_to_json(i)::text,'') FROM social.circle_invitations i")
        assertFalse(retained.contains(bearer)); assertFalse(retained.contains("inviteUrl"))
        val revokeKey = UUID.randomUUID()
        repeat(2) { success(client.command(f, "DELETE", "/v1/invitations/${issued.id()}", key = revokeKey, etag = "\"1\""), 204, "revokeInvitation") }
        assertEquals("revoked", f.value("SELECT status FROM social.circle_invitations"))
        problem(client.command(f, "POST", "/v1/invitations", invitation(circle), key = key), 404, "INVITATION_UNAVAILABLE")
    }

    @Test fun sameAndNewKeyAcceptReturnOneCurrentMembershipButNotAnotherActorOrGeneration() = testApplication {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); val other = f.account(); val bearer = token(f.invite(circle))
        application { feedMeLocalService(local, social = f.configuration()) }; val key = UUID.randomUUID()
        val first = client.command(f, "POST", "/v1/invitations/accept", accept(bearer), actor = member, key = key); success(first, 200, "acceptInvitation")
        for (k in listOf(key, UUID.randomUUID())) {
            val replay = client.command(f, "POST", "/v1/invitations/accept", accept(bearer), actor = member, key = k)
            success(replay, 200, "acceptInvitation"); sameRepresentation(first, replay)
        }
        assertEquals(2, f.count("social.circle_members")); assertEquals(4, f.count("platform.outbox"))
        problem(client.command(f, "POST", "/v1/invitations/accept", accept(bearer), actor = other), 409, "INVITATION_USED")
        success(client.command(f, "POST", "/v1/circles/$circle/leave", "{}", actor = member), 200, "leaveCircle")
        problem(client.command(f, "POST", "/v1/invitations/accept", accept(bearer), actor = member, key = key), 404, "CIRCLE_UNAVAILABLE")
    }

    @Test fun currentRoleAndVersionAreEnforcedForFreshAndReplayedWrites() = testApplication {
        val f = Fixture(); val member = f.account(); val circle = f.circle(); f.join(circle, member)
        application { feedMeLocalService(local, social = f.configuration()) }
        problem(client.command(f, "PATCH", "/v1/circles/$circle", BODY, actor = member, etag = "\"2\""), 403, "NOT_OWNER")
        problem(client.command(f, "PATCH", "/v1/circles/$circle", BODY), 428, "PRECONDITION_REQUIRED")
        problem(client.command(f, "PATCH", "/v1/circles/$circle", BODY, etag = "\"1\""), 412, "VERSION_CONFLICT")
        val key = UUID.randomUUID(); val body = """{"name":"Changed once"}"""
        val good = client.command(f, "PATCH", "/v1/circles/$circle", body, key = key, etag = "\"2\""); success(good, 200, "updateCircle")
        val replay = client.command(f, "PATCH", "/v1/circles/$circle", body, key = key, etag = "\"2\"")
        success(replay, 200, "updateCircle"); sameRepresentation(good, replay)
        problem(client.command(f, "PATCH", "/v1/circles/$circle", """{"name":"Changed twice"}""", key = key, etag = "\"2\""), 409, "IDEMPOTENCY_MISMATCH")
        assertEquals("3", f.value("SELECT version FROM social.circles"))
    }

    @Test fun guestRefreshAndInvitationTokensCannotBecomeAccountPrincipals() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }
        for (token in listOf("synthetic-guest", "synthetic-refresh", "synthetic-invitation", "unknown")) problem(client.post("/v1/circles") {
            header(HttpHeaders.Authorization, "Bearer $token"); header("X-Device-Session", f.owner.deviceSessionId)
            header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(BODY)
        }, 401, "UNAUTHENTICATED")
        problem(client.post("/v1/circles") { header(HttpHeaders.Authorization, "Bearer ${f.token(f.owner)}"); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(BODY) }, 401, "UNAUTHENTICATED")
        assertEquals(0, f.count("social.circles")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.policy.principalReads)
    }

    @Test fun suppliedDeviceAndVerifiedEnvironmentMustAgreeBeforeStoreAcquisition() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }
        problem(client.post("/v1/circles") {
            account(f); headers.remove("X-Device-Session"); header("X-Device-Session", UUID.randomUUID())
            header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(BODY)
        }, 401, "UNAUTHENTICATED")
        f.verification = { PortResult.Value(VerifiedSocialAccount("other", f.owner.accountId, f.owner.deviceSessionId)) }
        problem(client.command(f, "POST", "/v1/circles", BODY), 401, "UNAUTHENTICATED")
        assertEquals(0, f.policy.principalReads); assertEquals(0, f.count("social.circles"))
    }

    @Test fun verifierUnavailableForbiddenAndRateLimitProblemsAreSanitizedAndBounded() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }
        f.verification = { error("PRIVATE-VERIFIER-CANARY") }; problem(client.command(f, "POST", "/v1/circles", BODY), 503, "AUTHENTICATION_UNAVAILABLE")
        f.verification = { PortResult.Failure(FailureReason.FORBIDDEN) }; problem(client.command(f, "POST", "/v1/circles", BODY), 403, "FORBIDDEN")
        for (delay in listOf(0L, 7L, Long.MAX_VALUE)) {
            f.verification = { PortResult.Failure(FailureReason.RATE_LIMITED, retryAfterSeconds = delay) }
            val response = client.command(f, "POST", "/v1/circles", BODY); problem(response, 429, "RATE_LIMITED")
            assertEquals(delay, objectBody(response).getValue("retryAfterSeconds").jsonPrimitive.long)
            assertEquals(delay.takeIf { it > 0 }?.toString(), response.headers[HttpHeaders.RetryAfter])
        }
        assertEquals(0, f.policy.principalReads); assertEquals(0, f.count("platform.idempotency"))
    }

    @Test fun currentAccountRevocationStillDeniesVerifiedCachedCommandReplay() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }; val key = UUID.randomUUID()
        success(client.command(f, "POST", "/v1/circles", BODY, key = key), 201, "createCircle")
        f.sql("UPDATE social_http_test.accounts SET active=false WHERE id='${f.owner.accountId}'")
        problem(client.command(f, "POST", "/v1/circles", BODY, key = key), 401, "UNAUTHENTICATED")
        problem(client.get("/v1/circles") { account(f) }, 401, "UNAUTHENTICATED")
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun foreignAndMissingCirclesAndInvitationsHaveIndistinguishableProblems() = testApplication {
        val f = Fixture(); val circle = f.circle(); val issued = f.invite(circle); val other = f.account()
        application { feedMeLocalService(local, social = f.configuration()) }
        for (id in listOf(circle, UUID.randomUUID())) {
            problem(client.get("/v1/circles/$id") { account(f, other) }, 404, "CIRCLE_UNAVAILABLE")
            problem(client.get("/v1/circles/$id/members") { account(f, other) }, 404, "CIRCLE_UNAVAILABLE")
        }
        for (id in listOf(issued.id(), UUID.randomUUID()))
            problem(client.command(f, "DELETE", "/v1/invitations/$id", actor = other, etag = "\"1\""), 404, "INVITATION_UNAVAILABLE")
        assertEquals(0, f.policy.hiddenProfileReads)
        problem(client.get("/unregistered-private-route"), 404, "ROUTE_NOT_FOUND")
    }

    @Test fun blockedRedemptionIsDeniedAndBlockedProfilesAreFilteredBeforeRosterPagination() = testApplication {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); f.join(circle, member)
        val newcomer = f.account(); val issued = f.invite(circle); f.block(f.owner, newcomer); f.block(f.owner, member)
        application { feedMeLocalService(local, social = f.configuration()) }; val before = f.counts()
        problem(client.command(f, "POST", "/v1/invitations/accept", accept(token(issued)), actor = newcomer), 404, "CIRCLE_UNAVAILABLE")
        val roster = client.get("/v1/circles/$circle/members?limit=1") { account(f) }; success(roster, 200, "listCircleMembers")
        val items = objectBody(roster).getValue("items").jsonArray
        assertEquals(1, items.size); assertEquals(f.owner.accountId.toString(), items.single().jsonObject.getValue("user").jsonObject.string("userId"))
        assertEquals(before, f.counts())
    }

    @Test fun expiredAndRevokedAcceptsNeverConsumeMembershipAndDeferredTargetsStayClosed() = testApplication {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); val expired = f.invite(circle); f.expire(expired.id())
        val revoked = f.invite(circle); f.store.revokeInvitation(f.owner, UUID.randomUUID(), revoked.id(), "\"1\"")
        application { feedMeLocalService(local, social = f.configuration()) }; val before = f.counts()
        problem(client.command(f, "POST", "/v1/invitations/accept", accept(token(expired)), actor = member), 410, "INVITATION_EXPIRED")
        problem(client.command(f, "POST", "/v1/invitations/accept", accept(token(revoked)), actor = member), 404, "INVITATION_UNAVAILABLE")
        for (kind in listOf("household","pact","potluck"))
            problem(client.command(f, "POST", "/v1/invitations", """{"targetType":"$kind","targetId":"$circle"}"""), 503, "NOT_CONFIGURED")
        assertEquals(before, f.counts())
    }

    @Test fun opaquePaginationIsCurrentActorBoundAndDuplicateQueryCannotWidenIt() = testApplication {
        val f = Fixture(); f.circle(); f.circle(); val other = f.account()
        application { feedMeLocalService(local, social = f.configuration()) }
        val first = client.get("/v1/circles?limit=1") { account(f) }; success(first, 200, "listCircles")
        val cursor = objectBody(first).getValue("nextCursor").jsonPrimitive.content
        val next = client.get("/v1/circles") { account(f); parameter("limit", "1"); parameter("cursor", cursor) }
        success(next, 200, "listCircles"); assertNotEquals(objectBody(first)["items"], objectBody(next)["items"])
        problem(client.get("/v1/circles") { account(f, other); parameter("cursor", cursor) }, 422, "INPUT_INVALID")
        problem(client.get("/v1/circles?limit=1&limit=1") { account(f) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/circles?userId=${f.owner.accountId}") { account(f) }, 400, "INVALID_REQUEST")
    }

    @Test fun invalidJsonFramingEncodingAndUnexpectedDeleteBodyHaveNoDomainEffects() = testApplication {
        val f = Fixture(); val circle = f.circle(); application { feedMeLocalService(local, social = f.configuration()) }; val before = f.counts()
        for (body in listOf("{", """{"name":"one","name":"two"}""", """{"name":"\uD800"}""", BODY + " ".repeat(65536)))
            problem(client.command(f, "POST", "/v1/circles", body), 400, "INVALID_REQUEST")
        problem(client.command(f, "POST", "/v1/circles", """{"name":"x","ownerId":"${f.owner.accountId}"}"""), 422, "INPUT_INVALID")
        problem(client.post("/v1/circles") { account(f); header("Idempotency-Key", UUID.randomUUID()); header("Content-Encoding", "gzip"); contentType(ContentType.Application.Json); setBody(BODY) }, 400, "INVALID_REQUEST")
        problem(client.command(f, "DELETE", "/v1/circles/$circle", "{}", etag = "\"1\""), 400, "INVALID_REQUEST")
        assertEquals(before, f.counts()); assertEquals("1", f.value("SELECT version FROM social.circles"))
    }

    @Test fun duplicateControlsAndUndeclaredConditionalReadsNeverReturn304OrMutate() = testApplication {
        val f = Fixture(); val circle = f.circle(); application { feedMeLocalService(local, social = f.configuration()) }; val before = f.counts()
        problem(client.post("/v1/circles") {
            account(f); header(HttpHeaders.Authorization, "Bearer ${f.token(f.owner)}"); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json); setBody(BODY)
        }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/circles/$circle") { account(f); header(HttpHeaders.IfNoneMatch, "\"1\"") }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/invitations/preview?token=${"x".repeat(64)}&token=${"x".repeat(64)}"), 400, "INVALID_REQUEST")
        assertEquals(before, f.counts())
    }

    @Test fun decodedCanonicalUuidSelectsOnlyExactResourceAndExtraSegmentsStayUnknown() = testApplication {
        val f = Fixture(); val circle = f.circle(); application { feedMeLocalService(local, social = f.configuration()) }
        val escaped = circle.toString().replace("-", "%2D")
        success(client.get("/v1/circles/$escaped") { account(f) }, 200, "getCircle")
        problem(client.get("/v1/circles/1-1-1-1-1") { account(f) }, 400, "INVALID_REQUEST")
        problem(client.get("/v1/circles/$circle/unregistered") { account(f) }, 404, "ROUTE_NOT_FOUND")
        assertEquals(1, f.count("social.circles"))
    }

    @Test fun simulatedLostCommitReceiptKeepsOriginalCommandForExactReconciliation() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }; val key = UUID.randomUUID(); f.faults.loseCommit = true
        problem(client.command(f, "POST", "/v1/circles", BODY, key = key), 503, "OUTCOME_UNKNOWN")
        assertEquals(1, f.count("social.circles")); assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
        success(client.command(f, "POST", "/v1/circles", BODY, key = key), 201, "createCircle")
        assertEquals(1, f.count("social.circles")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun preCommitOutboxFailureRollsBackCircleMembershipAndReceiptWithoutPrivateDiagnostic() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }; f.faults.failOutbox = true
        problem(client.command(f, "POST", "/v1/circles", BODY), 503, "STORAGE_UNAVAILABLE"); assertEquals(listOf(0,0,0,0,0), f.counts())
    }

    @Test fun cancelledCallerAfterActualCommitCanReconcileButCannotCreateASecondCircle() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }; val key = UUID.randomUUID()
        val committed = CountDownLatch(1); val release = CountDownLatch(1)
        f.faults.afterCommit = { committed.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        coroutineScope {
            val request = async(Dispatchers.Default) { client.command(f, "POST", "/v1/circles", BODY, key = key) }
            try {
                assertTrue(withContext(Dispatchers.IO) { committed.await(5, TimeUnit.SECONDS) })
                request.cancel(); release.countDown(); assertFailsWith<CancellationException> { request.await() }
            } finally { release.countDown(); request.cancelAndJoin() }
        }
        assertEquals(1, f.count("social.circles"))
        success(client.command(f, "POST", "/v1/circles", BODY, key = key), 201, "createCircle")
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun concurrentSameKeyHttpCommandsSerializeOneCircleAndOneOutboxFact() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration()) }; val key = UUID.randomUUID()
        val responses = coroutineScope { List(4) { async(Dispatchers.Default) { client.command(f, "POST", "/v1/circles", BODY, key = key) } }.awaitAll() }
        responses.forEach { success(it, 201, "createCircle") }
        responses.drop(1).forEach { sameRepresentation(responses.first(), it) }
        assertEquals(1, f.count("social.circles")); assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun independentlyConfiguredPlanningAndSocialRoutesCoexistWithoutEnablingOtherProducts() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local, social = f.configuration(), planning = f.planningConfiguration()) }
        success(client.command(f, "POST", "/v1/circles", BODY), 201, "createCircle")
        val plan = client.post("/v1/plans") {
            account(f); header("Idempotency-Key", UUID.randomUUID()); contentType(ContentType.Application.Json)
            setBody("""{"mode":"assemble","preferenceVersion":1,"constraints":{"ingredientIds":["$INGREDIENT"],"energy":"assemble","equipmentIds":["bowl"],"servings":1,"hardExcludedIngredientIds":[]}}""")
        }
        success(plan, 201, "createPlan"); assertEquals("noMatch", objectBody(plan).string("status"))
        assertEquals(1, f.count("planning.plans")); assertEquals(1, f.count("social.circles"))
        problem(client.get("/v1/preferences") { account(f) }, 503, "OPERATION_NOT_IMPLEMENTED")
        problem(client.get("/v1/posts/today") { account(f) }, 503, "OPERATION_NOT_IMPLEMENTED")
    }

    @Test fun unconfiguredSocialOperationsStay503AndDoNotAcquireAnIdentity() = testApplication {
        val f = Fixture(); application { feedMeLocalService(local) }
        for ((method, route) in routes) problem(client.request(route.replace("{circleId}", UUID.randomUUID().toString())
            .replace("{userId}", UUID.randomUUID().toString()).replace("{invitationId}", UUID.randomUUID().toString())) { this.method = HttpMethod.parse(method) },
            503, "OPERATION_NOT_IMPLEMENTED")
        val health = client.get("/v1/health"); assertEquals(200, health.status.value); assertEquals("degraded", objectBody(health).string("status"))
        assertEquals(0, f.count("social.circles")); assertEquals(0, f.verifications)
    }

    @Test fun actualCioSocketRejectsDuplicateAuthorizationAndAmbiguousLengthWithoutExtraEffects() = runBlocking<Unit> {
        val f = Fixture(); val server = embeddedServer(CIO, port = 0, host = local.host) { feedMeLocalService(local, social = f.configuration()) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            fun send(extra: String = "", chunked: Boolean = false): String = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 10000
                val payload = if (chunked) "0\r\n\r\n" else BODY
                val framing = if (chunked) "Content-Length: 0\r\nTransfer-Encoding: chunked\r\n" else "Content-Length: ${BODY.encodeToByteArray().size}\r\n"
                val request = "POST /v1/circles HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\nAuthorization: Bearer ${f.token(f.owner)}\r\nX-Device-Session: ${f.owner.deviceSessionId}\r\nIdempotency-Key: ${UUID.randomUUID()}\r\nContent-Type: application/json\r\n$extra$framing\r\n$payload"
                socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8)); socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readText()
            }
            val good = withContext(Dispatchers.IO) { send() }; assertTrue(good.startsWith("HTTP/1.1 201"), good)
            val duplicate = withContext(Dispatchers.IO) { send("Authorization: Bearer ${f.token(f.owner)}\r\n") }; assertTrue(duplicate.startsWith("HTTP/1.1 400"), duplicate)
            val ambiguous = withContext(Dispatchers.IO) { send(chunked = true) }; assertTrue(ambiguous.startsWith("HTTP/1.1 400"), ambiguous)
            assertEquals(1, f.count("social.circles")); assertEquals(1, f.count("platform.outbox"))
        } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
    }

    private class Fixture {
        val source = cluster.database(); val faults = Faults(); val policy = SyntheticIdentityPolicy()
        private val tokens = linkedMapOf<String, VerifiedSocialAccount>()
        val owner: VerifiedSocialAccount
        val store: CirclesStore
        @Volatile var verifications = 0
        var verification: (suspend (SocialHttpBearer) -> PortResult<VerifiedSocialAccount>)? = null
        init {
            PlatformMigrations(source).migrate()
            sql("CREATE SCHEMA social_http_test; CREATE TABLE social_http_test.accounts(id uuid PRIMARY KEY,device uuid NOT NULL,active boolean NOT NULL,display_name text NOT NULL,handle text NOT NULL); " +
                "CREATE TABLE social_http_test.blocks(first_id uuid NOT NULL,second_id uuid NOT NULL,PRIMARY KEY(first_id,second_id))")
            owner = account()
            store = CirclesStore("test", PgTransactions(faults.wrap(source)), policy,
                CircleCapabilities("test", mapOf("test" to ByteArray(32) { (it + 1).toByte() }), URI("https://example.invalid/invite")), CircleLaunchPolicy(50,168))
        }
        fun account() = VerifiedSocialAccount("test", UUID.randomUUID(), UUID.randomUUID()).also { actor ->
            source.connection.use { c -> c.prepareStatement("INSERT INTO social_http_test.accounts VALUES(?,?,true,?,?)").use {
                it.setObject(1, actor.accountId); it.setObject(2, actor.deviceSessionId); it.setString(3, "Synthetic cook"); it.setString(4, "synthetic"); it.executeUpdate()
            } }
            tokens["synthetic-account-${tokens.size}"] = actor
        }
        fun token(actor: VerifiedSocialAccount) = tokens.entries.single { it.value === actor }.key
        fun configuration() = SocialHttpConfiguration("test", store, SocialHttpVerifier { bearer ->
            verifications++; verification?.invoke(bearer) ?: bearer.token.use { tokens[it]?.let { account -> PortResult.Value(account) } ?: PortResult.Failure(FailureReason.UNAUTHENTICATED) }
        }, Dispatchers.IO)
        fun circle() = commandReply(store.createCircle(owner, UUID.randomUUID(), parse(BODY))).body!!.jsonObject.id()
        fun invite(circle: UUID) = commandReply(store.createInvitation(owner, UUID.randomUUID(), parse(invitation(circle)))).body!!.jsonObject
        fun join(circle: UUID, actor: VerifiedSocialAccount) = store.acceptInvitation(actor, UUID.randomUUID(), parse(accept(SocialHttpIntegrationTest.token(invite(circle)))))
        fun block(a: VerifiedSocialAccount, b: VerifiedSocialAccount) = sql("INSERT INTO social_http_test.blocks VALUES('${a.accountId}','${b.accountId}')")
        fun expire(id: UUID) = sql("UPDATE social.circle_invitations SET created_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second' WHERE id='$id'")
        fun sql(sql: String) { source.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
        fun value(sql: String): String = source.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } } }
        fun count(table: String) = value("SELECT count(*) FROM $table").toInt()
        fun counts() = listOf("social.circles", "social.circle_members", "social.circle_invitations", "platform.idempotency", "platform.outbox").map(::count)
        fun planningConfiguration(): PlanningHttpConfiguration {
            val authority = object : PlanningAuthority {
                override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal) =
                    policy.lockPrincipal(connection, VerifiedSocialAccount(principal.environment, principal.principalId, principal.deviceSessionId!!))
                override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal) = Unit
                override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument) =
                    PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse("""{"version":1,"preferences":{"revision":"1","excludedIngredientIds":[],"dislikedIngredientIds":[]},"pantry":{"revision":"pantry-1","items":[]},"baseMeal":null,"catalog":{"revision":"catalog-1","taxonomyRevision":"taxonomy-1","candidates":[],"ingredients":[{"ingredientId":"$INGREDIENT","componentIds":[]}]}}"""))
            }
            return PlanningHttpConfiguration("test", PlansStore("test", PgTransactions(source), authority,
                PlanningServicePolicy("test-rank-1", false, true, 86400, 600), PlanningCursors("test", mapOf("test" to ByteArray(32) { 7 }))),
                PlanningHttpVerifier { b -> b.token.use { t -> tokens[t]?.let { PortResult.Value(VerifiedPlanningPrincipal("test", CommandActor.ACCOUNT, it.accountId, it.deviceSessionId)) } ?: PortResult.Failure(FailureReason.UNAUTHENTICATED) } }, Dispatchers.IO)
        }
    }

    /** Explicit test-only transactional identity/profile/block adapter; no production provider. */
    private class SyntheticIdentityPolicy : SocialIdentityPolicy {
        @Volatile var principalReads = 0
        var hiddenProfileReads = 0
        override fun lockPrincipal(connection: Connection, principal: VerifiedSocialAccount) {
            principalReads++
            connection.prepareStatement("SELECT active,device FROM social_http_test.accounts WHERE id=? FOR SHARE").use { s ->
                s.setObject(1, principal.accountId); s.executeQuery().use {
                    if (!it.next() || !it.getBoolean(1) || it.getObject(2, UUID::class.java) != principal.deviceSessionId) throw SocialFailure(SocialFailureCode.UNAUTHENTICATED)
                }
            }
        }
        override fun requireCreationEnabled(connection: Connection, principal: VerifiedSocialAccount, invitations: Boolean) = Unit
        override fun lockUnblockedPair(connection: Connection, environment: String, first: UUID, second: UUID) {
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use {
                it.setString(1, "$environment:${listOf(first, second).sortedBy(UUID::toString).joinToString(":")}"); it.execute()
            }
            connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM social_http_test.blocks WHERE (first_id=? AND second_id=?) OR (first_id=? AND second_id=?))").use { s ->
                s.setObject(1, first); s.setObject(2, second); s.setObject(3, second); s.setObject(4, first)
                s.executeQuery().use { it.next(); if (it.getBoolean(1)) throw SocialFailure(SocialFailureCode.CIRCLE_UNAVAILABLE) }
            }
        }
        override fun readProfile(connection: Connection, environment: String, accountId: UUID): SocialProfileSummary =
            connection.prepareStatement("SELECT display_name,handle FROM social_http_test.accounts WHERE id=? AND active=true FOR SHARE").use { s ->
                s.setObject(1, accountId); s.executeQuery().use {
                    if (!it.next()) { hiddenProfileReads++; throw SocialFailure(SocialFailureCode.CIRCLE_UNAVAILABLE) }
                    SocialProfileSummary(accountId, it.getString(1), it.getString(2))
                }
            }
    }

    /** Exceptions after a real COMMIT simulate lost application receipts, not PostgreSQL/OS sync faults. */
    private class Faults {
        @Volatile var loseCommit = false
        @Volatile var failOutbox = false
        @Volatile var afterCommit: (() -> Unit)? = null
        fun wrap(source: DataSource): DataSource = object : DataSource by source {
            override fun getConnection(): Connection {
                val actual = source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.contains("INSERT INTO platform.outbox") == true && failOutbox) {
                        failOutbox = false; throw SQLException("PRIVATE-SQL-CANARY", "XX000")
                    }
                    try {
                        val result = method.invoke(actual, *(args ?: emptyArray()))
                        if (method.name == "commit") { val hook = afterCommit; afterCommit = null; hook?.invoke() }
                        if (method.name == "commit" && loseCommit) { loseCommit = false; throw SQLException("PRIVATE-COMMIT-CANARY", "08006") }
                        result
                    } catch (failure: InvocationTargetException) { throw failure.targetException }
                } as Connection
            }
        }
    }

    companion object {
        private const val BODY = """{"name":"Synthetic circle"}"""
        private const val INGREDIENT = "00000000-0000-4000-8000-000000000011"
        private val local = LocalServerConfig.fromEnvironment(emptyMap())
        private val validator = ContractBodyValidator.bundled()
        private lateinit var cluster: PostgresTestCluster
        @JvmField @ClassRule val timeout = Timeout(10, TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { if (::cluster.isInitialized) cluster.close() }
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
        private fun JsonObject.id() = UUID.fromString(string("id"))
        private fun token(invitation: JsonObject) = invitation.string("inviteUrl").substringAfter("?token=")
        private fun invitation(circle: UUID) = """{"targetType":"circle","targetId":"$circle"}"""
        private fun accept(token: String) = buildJsonObject { put("token", token) }.toString()
        private suspend fun objectBody(response: HttpResponse) = parse(response.bodyAsText())
        private suspend fun sameRepresentation(first: HttpResponse, repeated: HttpResponse) {
            // JSONB persists complete JSON values, not object field insertion order. Request
            // bytes/idempotency are separate invariants; response identity includes every field.
            assertEquals(first.status, repeated.status)
            assertEquals(first.headers[HttpHeaders.ETag], repeated.headers[HttpHeaders.ETag])
            assertEquals(objectBody(first), objectBody(repeated))
        }
        private fun commandReply(result: CommandResult): StoredReply = when (result) {
            is CommandResult.Applied -> result.reply; is CommandResult.Replayed -> result.reply; else -> fail("Expected success")
        }
        private fun HttpRequestBuilder.account(f: Fixture, actor: VerifiedSocialAccount = f.owner) {
            header(HttpHeaders.Authorization, "Bearer ${f.token(actor)}"); header("X-Device-Session", actor.deviceSessionId)
        }
        private suspend fun HttpClient.command(f: Fixture, method: String, route: String, body: String? = null,
            actor: VerifiedSocialAccount = f.owner, key: UUID = UUID.randomUUID(), etag: String? = null): HttpResponse = request(route) {
            this.method = HttpMethod.parse(method); account(f, actor); header("Idempotency-Key", key)
            etag?.let { header(HttpHeaders.IfMatch, it) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        private suspend fun success(response: HttpResponse, status: Int, operation: String) {
            val text = response.bodyAsText(); assertEquals(status, response.status.value, text); safeHeaders(response)
            if (status == 204) {
                assertEquals("", text); assertNull(response.headers[HttpHeaders.ContentType]); assertNull(response.headers[HttpHeaders.ETag])
                assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation, status, null, null))
            } else {
                assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertEquals(BodyValidationResult.Valid, validator.validateResponse(operation, status, text.encodeToByteArray(), "application/json"))
            }
        }
        private suspend fun problem(response: HttpResponse, status: Int, code: String) {
            val text = response.bodyAsText(); assertEquals(status, response.status.value, text); safeHeaders(response)
            val body = parse(text); assertEquals(code, body.string("code")); assertEquals(status, body.getValue("status").jsonPrimitive.int)
            assertEquals(response.headers["X-Trace-Id"], body.string("traceId")); assertNull(response.headers[HttpHeaders.ETag])
            assertEquals("application/problem+json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertEquals(BodyValidationResult.Valid, validator.validateSchema("Problem", text.encodeToByteArray()))
            for (secret in listOf("synthetic-account", "synthetic-guest", "PRIVATE-", "Synthetic circle", "example.invalid")) assertFalse(text.contains(secret))
        }
        private fun safeHeaders(response: HttpResponse) {
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl]); assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertNotNull(UUID.fromString(response.headers["X-Trace-Id"])); assertNull(response.headers[HttpHeaders.SetCookie]); assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
        private val routes = listOf("GET" to "/v1/circles", "POST" to "/v1/circles", "GET" to "/v1/circles/{circleId}",
            "PATCH" to "/v1/circles/{circleId}", "DELETE" to "/v1/circles/{circleId}", "GET" to "/v1/circles/{circleId}/members",
            "PATCH" to "/v1/circles/{circleId}/members/{userId}", "DELETE" to "/v1/circles/{circleId}/members/{userId}",
            "POST" to "/v1/circles/{circleId}/leave", "POST" to "/v1/circles/{circleId}/ownership",
            "POST" to "/v1/invitations", "GET" to "/v1/invitations/preview", "POST" to "/v1/invitations/accept", "DELETE" to "/v1/invitations/{invitationId}")
    }
}
