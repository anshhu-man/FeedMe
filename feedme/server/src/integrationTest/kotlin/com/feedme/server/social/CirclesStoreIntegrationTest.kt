package com.feedme.server.social

import com.feedme.server.db.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Real PostgreSQL; identity/profile/block tables below are explicit test-only adapter fixtures. */
class CirclesStoreIntegrationTest {
    @Test fun creationMembershipCommandReceiptAndOutboxCommitOnceAndReplayExactResponse() {
        val f = Fixture(); val key = UUID.randomUUID(); val first = f.store.createCircle(f.owner, key, circleBody())
        val replay = f.store.createCircle(f.owner, key, circleBody())
        assertIs<CommandResult.Applied>(first); assertIs<CommandResult.Replayed>(replay); assertEquals(reply(first).body, reply(replay).body)
        assertEquals(1, f.count("social.circles")); assertEquals(1, f.count("social.circle_members"))
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
        val circle = body(first); assertEquals("owner", circle["role"]!!.jsonPrimitive.content); assertEquals(1, circle["memberCount"]!!.jsonPrimitive.int)
        assertFalse(circle.containsKey("ownerId")); assertEquals("\"1\"", reply(first).etag)
        assertIs<CommandResult.Mismatch>(f.store.createCircle(f.owner, key, circleBody("changed"))); assertEquals(1, f.count("social.circles"))
    }

    @Test fun membershipReadsArePrivateAndMissingAndForeignCirclesAreNonEnumerating() {
        val f = Fixture(); val circle = f.circle(); val other = f.account()
        assertTrue(f.store.listCircles(other).body!!.jsonObject["items"]!!.jsonArray.isEmpty())
        for (id in listOf(circle, UUID.randomUUID())) {
            denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.getCircle(other, id) }
            denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.listMembers(other, id) }
        }
        assertEquals(1, f.store.listMembers(f.owner, circle).body!!.jsonObject["items"]!!.jsonArray.size)
        assertEquals(0, f.policy.foreignProfileReads)
    }

    @Test fun principalDeviceEnvironmentAndCurrentAccountStateAreCheckedBeforeNewAndCachedCommands() {
        val f = Fixture(); val key = UUID.randomUUID(); f.store.createCircle(f.owner, key, circleBody())
        for (actor in listOf(VerifiedSocialAccount("other", f.owner.accountId, f.owner.deviceSessionId),
            VerifiedSocialAccount("test", f.owner.accountId, UUID.randomUUID()), VerifiedSocialAccount("test", UUID.randomUUID(), UUID.randomUUID())))
            denied(SocialFailureCode.UNAUTHENTICATED) { f.store.createCircle(actor, key, circleBody()) }
        f.sql("UPDATE social_test.accounts SET active=false WHERE id='${f.owner.accountId}'")
        denied(SocialFailureCode.UNAUTHENTICATED) { f.store.createCircle(f.owner, key, circleBody()) }
        denied(SocialFailureCode.UNAUTHENTICATED) { f.store.listCircles(f.owner) }
        assertEquals(1, f.count("social.circles")); assertEquals(1, f.count("platform.idempotency"))
    }

    @Test fun invitationBearerIsReproducibleButAbsentFromRowsReceiptsEventsAndDiagnostics() {
        val f = Fixture(); val circle = f.circle(); val key = UUID.randomUUID(); val input = inviteBody(circle)
        val first = f.store.createInvitation(f.owner, key, input); val second = f.store.createInvitation(f.owner, key, input)
        assertEquals(body(first), body(second)); val token = token(first)
        assertEquals(1, f.count("social.circle_invitations"))
        assertEquals(sha(token), f.value("SELECT token_hash FROM social.circle_invitations"))
        val retained = f.value("SELECT string_agg(row_to_json(i)::text,'') FROM platform.idempotency i") +
            f.value("SELECT string_agg(row_to_json(i)::text,'') FROM platform.outbox i") +
            f.value("SELECT string_agg(row_to_json(i)::text,'') FROM social.circle_invitations i")
        assertFalse(retained.contains(token)); assertFalse(retained.contains("inviteUrl")); assertFalse(retained.contains("example.invalid"))
        assertFalse(first.toString().contains(token)); assertFalse(reply(first).toString().contains(token))
    }

    @Test fun publicPreviewContainsOnlyMinimalTokenBoundFieldsAndUniformUnavailableStates() {
        val f = Fixture(); val circle = f.circle(); val invite = f.invite(circle); val token = token(invite)
        val preview = f.store.previewInvitation(token).body!!.jsonObject
        assertEquals(setOf("status", "targetType", "targetName", "inviterLabel", "expiresAt"), preview.keys)
        assertFalse(preview.toString().contains(f.owner.accountId.toString())); assertFalse(preview.toString().contains(circle.toString()))
        val unavailable = buildJsonObject { put("status", "unavailable") }
        assertEquals(unavailable, f.store.previewInvitation("x".repeat(64)).body)
        f.expire(uuid(body(invite), "id")); assertEquals(unavailable, f.store.previewInvitation(token).body)
        val another = f.invite(circle); f.store.revokeInvitation(f.owner, UUID.randomUUID(), uuid(body(another), "id"), "\"1\"")
        assertEquals(unavailable, f.store.previewInvitation(token(another)).body)
    }

    @Test fun acceptingAValidInvitationCreatesOneMembershipAndReplaysOnlyForItsCurrentGeneration() {
        val f = Fixture(); val circle = f.circle(); val invite = f.invite(circle); val guest = f.account(); val key = UUID.randomUUID()
        val first = f.store.acceptInvitation(guest, key, acceptBody(token(invite))); val replay = f.store.acceptInvitation(guest, key, acceptBody(token(invite)))
        assertIs<CommandResult.Replayed>(replay); assertEquals(body(first), body(replay))
        assertEquals("member", body(first)["role"]!!.jsonPrimitive.content); assertEquals("active", body(first)["status"]!!.jsonPrimitive.content)
        assertEquals(2, f.count("social.circle_members")); assertEquals(4, f.count("platform.outbox"))
        assertEquals("1", f.value("SELECT consumed_generation FROM social.circle_invitations"))
        val anotherKey = f.store.acceptInvitation(guest, UUID.randomUUID(), acceptBody(token(invite)))
        assertEquals(body(first), body(anotherKey)); assertEquals(4, f.count("platform.outbox"))
        assertEquals("2", f.value("SELECT version FROM social.circle_invitations"))
        denied(SocialFailureCode.INVITATION_USED) { f.store.acceptInvitation(f.account(), UUID.randomUUID(), acceptBody(token(invite))) }
        f.store.leaveCircle(guest, UUID.randomUUID(), circle)
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.acceptInvitation(guest, key, acceptBody(token(invite))) }
    }

    @Test fun concurrentSingleUseRedemptionHasOneWinnerWithoutDoubleCapacityOrReceipts() {
        val f = Fixture(); val circle = f.circle(); val invitation = f.invite(circle); val one = f.account(); val two = f.account()
        val result = racing(listOf({ f.store.acceptInvitation(one, UUID.randomUUID(), acceptBody(token(invitation))) },
            { f.store.acceptInvitation(two, UUID.randomUUID(), acceptBody(token(invitation))) }))
        assertEquals(1, result.count { it is CommandResult.Applied }); assertEquals(1, result.count { it is SocialFailure && it.code == SocialFailureCode.INVITATION_USED })
        assertEquals(2, f.count("social.circle_members")); assertEquals(3, f.count("platform.idempotency")); assertEquals(4, f.count("platform.outbox"))
    }

    @Test fun concurrentDistinctInvitationsRespectTheConfiguredCircleCapacity() {
        val f = Fixture(memberLimit = 2); val circle = f.circle(); val first = f.invite(circle); val second = f.invite(circle)
        val one = f.account(); val two = f.account()
        val result = racing(listOf({ f.store.acceptInvitation(one, UUID.randomUUID(), acceptBody(token(first))) },
            { f.store.acceptInvitation(two, UUID.randomUUID(), acceptBody(token(second))) }))
        assertEquals(1, result.count { it is CommandResult.Applied }); assertEquals(1, result.count { it is SocialFailure && it.code == SocialFailureCode.CIRCLE_FULL })
        assertEquals("2", f.value("SELECT count(*) FROM social.circle_members WHERE status='active'"))
        assertEquals("1", f.value("SELECT count(*) FROM social.circle_invitations WHERE status='active'"))
    }

    @Test fun concurrentSameKeyRetriesReturnOneOriginalMembershipAndOnePairOfEvents() {
        val f = Fixture(); val circle = f.circle(); val invitation = f.invite(circle); val member = f.account(); val key = UUID.randomUUID()
        val results = racing(List(4) { { f.store.acceptInvitation(member, key, acceptBody(token(invitation))) } })
        assertEquals(1, results.count { it is CommandResult.Applied }); assertEquals(3, results.count { it is CommandResult.Replayed })
        assertEquals(1, results.map { body(it as CommandResult) }.toSet().size); assertEquals(4, f.count("platform.outbox"))
    }

    @Test fun leaveRejoinDoesNotRestoreOldAuthorGrantsOrReplayAnOldLeaveAgainstNewMembership() {
        val f = Fixture(); val circle = f.circle(); val author = f.account(); f.join(circle, author)
        assertTrue(f.store.hasCurrentAudienceMembership(f.owner, circle, author.accountId, 1))
        val leaving = UUID.randomUUID(); f.store.leaveCircle(author, leaving, circle)
        assertIs<CommandResult.Replayed>(f.store.leaveCircle(author, leaving, circle))
        assertFalse(f.store.hasCurrentAudienceMembership(f.owner, circle, author.accountId, 1))
        f.join(circle, author)
        assertEquals("2", f.value("SELECT generation FROM social.circle_members WHERE user_id='${author.accountId}'"))
        assertFalse(f.store.hasCurrentAudienceMembership(f.owner, circle, author.accountId, 1))
        assertTrue(f.store.hasCurrentAudienceMembership(f.owner, circle, author.accountId, 2))
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.leaveCircle(author, leaving, circle) }
        assertEquals("active", f.value("SELECT status FROM social.circle_members WHERE user_id='${author.accountId}'"))
        f.store.leaveCircle(author, UUID.randomUUID(), circle)
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.leaveCircle(author, leaving, circle) }
    }

    @Test fun adminCanInviteAndRemoveNonownersButCannotAssignRolesTransferOrDissolve() {
        val f = Fixture(); val circle = f.circle(); val admin = f.account(); val other = f.account()
        f.join(circle, admin); f.join(circle, other)
        f.store.updateMember(f.owner, UUID.randomUUID(), circle, admin.accountId, "\"1\"", roleBody("admin"))
        f.store.createInvitation(admin, UUID.randomUUID(), inviteBody(circle))
        denied(SocialFailureCode.NOT_OWNER) { f.store.updateMember(admin, UUID.randomUUID(), circle, other.accountId, "\"1\"", roleBody("admin")) }
        denied(SocialFailureCode.NOT_OWNER) { f.store.deleteCircle(admin, UUID.randomUUID(), circle, f.store.getCircle(admin, circle).etag!!) }
        denied(SocialFailureCode.NOT_OWNER) { f.store.transferOwnership(admin, UUID.randomUUID(), circle, "\"1\"", transferBody(other)) }
        f.store.removeMember(admin, UUID.randomUUID(), circle, other.accountId, "\"1\"")
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.getCircle(other, circle) }
    }

    @Test fun explicitOwnershipTransferChangesBothRolesAtomicallyAndRequiresCurrentCircleVersion() {
        val f = Fixture(); val circle = f.circle(); val successor = f.account(); f.join(circle, successor)
        val version = f.store.getCircle(f.owner, circle).etag!!; val key = UUID.randomUUID()
        denied(SocialFailureCode.INPUT_INVALID) { f.store.transferOwnership(f.owner, UUID.randomUUID(), circle, version,
            buildJsonObject { put("newOwnerUserId", successor.accountId.toString()); put("confirmed", false) }) }
        denied(SocialFailureCode.VERSION_CONFLICT) { f.store.transferOwnership(f.owner, UUID.randomUUID(), circle, "\"1\"", transferBody(successor)) }
        val changed = f.store.transferOwnership(f.owner, key, circle, version, transferBody(successor))
        assertEquals("member", body(changed)["role"]!!.jsonPrimitive.content)
        assertIs<CommandResult.Replayed>(f.store.transferOwnership(f.owner, key, circle, version, transferBody(successor)))
        assertEquals("1", f.value("SELECT count(*) FROM social.circle_members WHERE role='owner' AND status='active'"))
        assertEquals(successor.accountId.toString(), f.value("SELECT owner_id FROM social.circles"))
        f.store.leaveCircle(f.owner, UUID.randomUUID(), circle)
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.transferOwnership(f.owner, key, circle, version, transferBody(successor)) }
    }

    @Test fun ownerCannotLeaveUntilTransferAndDissolutionRevokesMembersAndOutstandingInvitations() {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); f.join(circle, member); val unused = f.invite(circle)
        denied(SocialFailureCode.OWNER_TRANSFER_REQUIRED) { f.store.leaveCircle(f.owner, UUID.randomUUID(), circle) }
        denied(SocialFailureCode.OWNER_TRANSFER_REQUIRED) { f.store.removeMember(f.owner, UUID.randomUUID(), circle, f.owner.accountId, "\"1\"") }
        val key = UUID.randomUUID(); val version = f.store.getCircle(f.owner, circle).etag!!
        assertEquals(204, reply(f.store.deleteCircle(f.owner, key, circle, version)).status)
        assertIs<CommandResult.Replayed>(f.store.deleteCircle(f.owner, key, circle, version))
        assertEquals("0", f.value("SELECT count(*) FROM social.circle_members WHERE status='active'"))
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.getCircle(member, circle) }
        assertEquals("unavailable", f.store.previewInvitation(token(unused)).body!!.jsonObject["status"]!!.jsonPrimitive.content)
        denied(SocialFailureCode.INVITATION_UNAVAILABLE) { f.store.acceptInvitation(f.account(), UUID.randomUUID(), acceptBody(token(unused))) }
    }

    @Test fun currentBlockInEitherDirectionRejectsAcceptanceAndCachedSuccessAndAudienceAccess() {
        for (reverse in listOf(false, true)) {
            val f = Fixture(); val circle = f.circle(); val member = f.account(); val invitation = f.invite(circle); val key = UUID.randomUUID()
            f.store.acceptInvitation(member, key, acceptBody(token(invitation)))
            f.block(if (reverse) member else f.owner, if (reverse) f.owner else member)
            denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.acceptInvitation(member, key, acceptBody(token(invitation))) }
            assertFalse(f.store.hasCurrentAudienceMembership(member, circle, f.owner.accountId, 1))
            val second = f.invite(circle)
            denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.acceptInvitation(member, UUID.randomUUID(), acceptBody(token(second))) }
            assertEquals("active", f.value("SELECT status FROM social.circle_invitations WHERE id='${uuid(body(second), "id")}'"))
        }
        // Membership rows alone cannot preserve the authority of an inactive owner/issuer.
        for (inactiveOwner in listOf(false, true)) {
            val f = Fixture(); val circle = f.circle(); val admin = f.account(); val guest = f.account()
            f.join(circle, admin); f.store.updateMember(f.owner, UUID.randomUUID(), circle, admin.accountId, "\"1\"", roleBody("admin"))
            val invitation = f.store.createInvitation(admin, UUID.randomUUID(), inviteBody(circle))
            val disabled = if (inactiveOwner) f.owner else admin
            f.sql("UPDATE social_test.accounts SET active=false WHERE id='${disabled.accountId}'")
            denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.acceptInvitation(guest, UUID.randomUUID(), acceptBody(token(invitation))) }
            assertEquals("active", f.value("SELECT status FROM social.circle_invitations WHERE id='${uuid(body(invitation), "id")}'"))
            assertFalse(f.store.hasCurrentAudienceMembership(if (inactiveOwner) admin else f.owner, circle, disabled.accountId, 1))
            assertEquals(2, f.count("social.circle_members"))
        }
    }

    @Test fun expiryIsRecheckedAfterBlockingPolicyWaitBeforeAnyMembershipOrTokenConsumption() {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); val invitation = f.invite(circle)
        f.sql("UPDATE social.circle_invitations SET expires_at=clock_timestamp()+interval '1 second'")
        var waited = false; f.policy.afterPair = { if (!waited) { waited = true; Thread.sleep(1200) } }
        denied(SocialFailureCode.INVITATION_EXPIRED) { f.store.acceptInvitation(member, UUID.randomUUID(), acceptBody(token(invitation))) }
        assertTrue(waited); assertEquals(1, f.count("social.circle_members")); assertEquals("active", f.value("SELECT status FROM social.circle_invitations"))
    }

    @Test fun removedOrDemotedInviterCannotGrantAccessOrReissueCachedCapability() {
        val f = Fixture(); val circle = f.circle(); val admin = f.account(); f.join(circle, admin)
        f.store.updateMember(f.owner, UUID.randomUUID(), circle, admin.accountId, "\"1\"", roleBody("admin"))
        val key = UUID.randomUUID(); val invitation = f.store.createInvitation(admin, key, inviteBody(circle))
        f.store.updateMember(f.owner, UUID.randomUUID(), circle, admin.accountId, "\"2\"", roleBody("member"))
        denied(SocialFailureCode.NOT_OWNER) { f.store.createInvitation(admin, key, inviteBody(circle)) }
        assertEquals("unavailable", f.store.previewInvitation(token(invitation)).body!!.jsonObject["status"]!!.jsonPrimitive.content)
        denied(SocialFailureCode.INVITATION_UNAVAILABLE) { f.store.acceptInvitation(f.account(), UUID.randomUUID(), acceptBody(token(invitation))) }
        f.store.updateMember(f.owner, UUID.randomUUID(), circle, admin.accountId, "\"3\"", roleBody("admin"))
        denied(SocialFailureCode.INVITATION_UNAVAILABLE) { f.store.createInvitation(admin, key, inviteBody(circle)) }
        val secondKey = UUID.randomUUID(); val second = f.store.createInvitation(admin, secondKey, inviteBody(circle))
        f.store.removeMember(f.owner, UUID.randomUUID(), circle, admin.accountId, "\"4\"")
        f.join(circle, admin)
        f.store.updateMember(f.owner, UUID.randomUUID(), circle, admin.accountId, "\"6\"", roleBody("admin"))
        for ((oldKey, oldInvite) in listOf(key to invitation, secondKey to second)) {
            denied(SocialFailureCode.INVITATION_UNAVAILABLE) { f.store.createInvitation(admin, oldKey, inviteBody(circle)) }
            denied(SocialFailureCode.INVITATION_UNAVAILABLE) { f.store.acceptInvitation(f.account(), UUID.randomUUID(), acceptBody(token(oldInvite))) }
        }
    }

    @Test fun revocationChecksOwnershipVersionAndUnusedStateAndDoesNotLeakToOtherMembers() {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); f.join(circle, member); val invitation = f.invite(circle)
        val id = uuid(body(invitation), "id")
        val outsider = f.account()
        for (target in listOf(id, UUID.randomUUID())) denied(SocialFailureCode.INVITATION_UNAVAILABLE) {
            f.store.revokeInvitation(outsider, UUID.randomUUID(), target, "\"1\"")
        }
        denied(SocialFailureCode.NOT_OWNER) { f.store.revokeInvitation(member, UUID.randomUUID(), id, "\"1\"") }
        denied(SocialFailureCode.VERSION_CONFLICT) { f.store.revokeInvitation(f.owner, UUID.randomUUID(), id, "\"2\"") }
        val key = UUID.randomUUID(); f.store.revokeInvitation(f.owner, key, id, "\"1\"")
        assertIs<CommandResult.Replayed>(f.store.revokeInvitation(f.owner, key, id, "\"1\""))
        denied(SocialFailureCode.INVITATION_UNAVAILABLE) { f.store.acceptInvitation(f.account(), UUID.randomUUID(), acceptBody(token(invitation))) }
        assertEquals("revoked", f.value("SELECT status FROM social.circle_invitations WHERE id='$id'"))
    }

    @Test fun pagesHaveExactCoverageAndPrincipalAndCollectionBoundCursors() {
        val f = Fixture(); val circles = List(3) { f.circle("Circle $it") }.toSet()
        val first = f.store.listCircles(f.owner, limit = 2).body!!.jsonObject
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        val second = f.store.listCircles(f.owner, cursor, 2).body!!.jsonObject
        assertEquals(circles, (first["items"]!!.jsonArray + second["items"]!!.jsonArray).map { uuid(it.jsonObject, "id") }.toSet())
        assertEquals(JsonNull, second["nextCursor"])
        denied(SocialFailureCode.INPUT_INVALID) { f.store.listCircles(f.account(), cursor) }
        denied(SocialFailureCode.INPUT_INVALID) { f.store.listMembers(f.owner, circles.first(), cursor) }
        for (limit in listOf(0, 51)) denied(SocialFailureCode.INPUT_INVALID) { f.store.listCircles(f.owner, limit = limit) }
    }

    @Test fun canonicalValidationAndLaunchPolicyRejectUnsupportedFieldsTargetsAndUnboundedUseCounts() {
        val f = Fixture()
        denied(SocialFailureCode.INPUT_INVALID) { f.store.createCircle(f.owner, UUID.randomUUID(), buildJsonObject { put("name", "x"); put("ownerId", f.owner.accountId.toString()) }) }
        denied(SocialFailureCode.INPUT_INVALID) { f.store.createCircle(f.owner, UUID.randomUUID(), circleBody("x".repeat(61))) }
        for (field in listOf("name", "description")) denied(SocialFailureCode.INPUT_INVALID) {
            f.store.createCircle(f.owner, UUID.randomUUID(), JsonObject(circleBody() + (field to JsonPrimitive("\uD800"))))
        }
        val circle = f.circle()
        for (kind in listOf("household", "pact", "potluck")) denied(SocialFailureCode.NOT_CONFIGURED) {
            f.store.createInvitation(f.owner, UUID.randomUUID(), buildJsonObject { put("targetType", kind); put("targetId", circle.toString()) })
        }
        for ((field, value) in listOf("maxUses" to "2", "expiresInHours" to "169", "expiresInHours" to "1e1000"))
            denied(SocialFailureCode.INPUT_INVALID) { f.store.createInvitation(f.owner, UUID.randomUUID(), JsonObject(inviteBody(circle) + (field to Json.parseToJsonElement(value)))) }
        val exactInteger = JsonObject(inviteBody(circle) + mapOf("expiresInHours" to Json.parseToJsonElement("1e0"), "maxUses" to Json.parseToJsonElement("1.0")))
        f.store.createInvitation(f.owner, UUID.randomUUID(), exactInteger)
        assertEquals(1, f.count("social.circle_invitations"))
    }

    @Test fun disabledCreationNeverDisablesLeaveRemovalAndOtherPrivacyNarrowing() {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); f.join(circle, member)
        f.policy.creationEnabled = false
        denied(SocialFailureCode.NOT_CONFIGURED) { f.store.createCircle(f.owner, UUID.randomUUID(), circleBody()) }
        denied(SocialFailureCode.NOT_CONFIGURED) { f.store.createInvitation(f.owner, UUID.randomUUID(), inviteBody(circle)) }
        f.store.leaveCircle(member, UUID.randomUUID(), circle)
        f.store.deleteCircle(f.owner, UUID.randomUUID(), circle, f.store.getCircle(f.owner, circle).etag!!)
        assertEquals("archived", f.value("SELECT status FROM social.circles"))
    }

    @Test fun roleReplayRejectsChangedTargetVersionProfileAndBlockedRosterDoesNotExposeProfiles() {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); f.join(circle, member)
        val key = UUID.randomUUID(); f.store.updateMember(f.owner, key, circle, member.accountId, "\"1\"", roleBody("admin"))
        assertIs<CommandResult.Replayed>(f.store.updateMember(f.owner, key, circle, member.accountId, "\"1\"", roleBody("admin")))
        f.sql("UPDATE social_test.accounts SET display_name='Changed profile' WHERE id='${member.accountId}'")
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.updateMember(f.owner, key, circle, member.accountId, "\"1\"", roleBody("admin")) }
        f.store.updateMember(f.owner, UUID.randomUUID(), circle, member.accountId, "\"2\"", roleBody("member"))
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.updateMember(f.owner, key, circle, member.accountId, "\"1\"", roleBody("admin")) }
        f.block(f.owner, member)
        val roster = f.store.listMembers(f.owner, circle, limit = 1).body!!.jsonObject
        assertEquals(1, roster.getValue("items").jsonArray.size); assertEquals(JsonNull, roster["nextCursor"])
        assertFalse(roster.toString().contains(member.accountId.toString())); assertFalse(roster.toString().contains("Changed profile"))
    }

    @Test fun removalReplayCannotAcknowledgeAnotherRemovedMembershipIncarnation() {
        val f = Fixture(); val circle = f.circle(); val member = f.account(); f.join(circle, member)
        val key = UUID.randomUUID(); f.store.removeMember(f.owner, key, circle, member.accountId, "\"1\"")
        assertIs<CommandResult.Replayed>(f.store.removeMember(f.owner, key, circle, member.accountId, "\"1\""))
        f.join(circle, member); f.store.removeMember(f.owner, UUID.randomUUID(), circle, member.accountId, "\"3\"")
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.removeMember(f.owner, key, circle, member.accountId, "\"1\"") }
        assertEquals("2", f.value("SELECT removed_generation FROM social.circle_members WHERE user_id='${member.accountId}'"))
        val other = f.account(); f.join(circle, other); val sharedKey = UUID.randomUUID()
        f.store.removeMember(other, sharedKey, circle, other.accountId, "\"1\"")
        f.join(circle, other); f.store.leaveCircle(other, sharedKey, circle)
        assertIs<CommandResult.Replayed>(f.store.leaveCircle(other, sharedKey, circle))
        denied(SocialFailureCode.CIRCLE_UNAVAILABLE) { f.store.removeMember(other, sharedKey, circle, other.accountId, "\"1\"") }
    }

    @Test fun circleMetadataEventIsMinimalVersionedAndAtomicWithoutPrivateContent() {
        val f = Fixture(); val circle = f.circle(); val key = UUID.randomUUID()
        val input = buildJsonObject { put("name", "Private kitchen name"); put("description", "Private group purpose") }
        f.store.updateCircle(f.owner, key, circle, "\"1\"", input)
        assertIs<CommandResult.Replayed>(f.store.updateCircle(f.owner, key, circle, "\"1\"", input))
        val event = Json.parseToJsonElement(f.value("SELECT row_to_json(o)::text FROM platform.outbox o WHERE event_type='circles.circle.changed.v1'")).jsonObject
        assertEquals("circles", event.getValue("producer").jsonPrimitive.content)
        assertEquals("circle", event.getValue("aggregate_type").jsonPrimitive.content)
        assertEquals(circle.toString(), event.getValue("aggregate_id").jsonPrimitive.content)
        assertEquals(2, event.getValue("aggregate_version").jsonPrimitive.int)
        assertEquals(buildJsonObject { put("circleId", circle.toString()); put("action", "updated") }, event.getValue("payload"))
        assertFalse(event.toString().contains("Private")); assertEquals(2, f.count("platform.outbox"))
    }

    @Test fun outboxFailureRollsBackDomainAndReceiptThenSameKeyCanRetry() {
        val f = Fixture(); val key = UUID.randomUUID(); f.faults.failOutbox = true
        denied(SocialFailureCode.STORAGE_UNAVAILABLE) { f.store.createCircle(f.owner, key, circleBody()) }
        for (table in listOf("social.circles", "social.circle_members", "platform.idempotency", "platform.outbox")) assertEquals(0, f.count(table))
        assertIs<CommandResult.Applied>(f.store.createCircle(f.owner, key, circleBody()))
        assertEquals(1, f.count("social.circles")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun lostCommitResponseRetriesOriginalReceiptWithoutSecondCircleMembershipOrEvent() {
        val f = Fixture(); val key = UUID.randomUUID(); f.faults.loseCommit = true
        assertFailsWith<CommitOutcomeUnknown> { f.store.createCircle(f.owner, key, circleBody()) }
        val replay = f.store.createCircle(f.owner, key, circleBody()); assertIs<CommandResult.Replayed>(replay)
        assertEquals(1, f.count("social.circles")); assertEquals(1, f.count("social.circle_members")); assertEquals(1, f.count("platform.outbox"))
    }

    @Test fun concurrentTransfersHaveOneWinnerAndDatabaseKeepsExactlyOneOwner() {
        val f = Fixture(); val circle = f.circle(); val a = f.account(); val b = f.account(); f.join(circle, a); f.join(circle, b)
        val version = f.store.getCircle(f.owner, circle).etag!!
        val results = racing(listOf({ f.store.transferOwnership(f.owner, UUID.randomUUID(), circle, version, transferBody(a)) },
            { f.store.transferOwnership(f.owner, UUID.randomUUID(), circle, version, transferBody(b)) }))
        assertEquals(1, results.count { it is CommandResult.Applied }); assertEquals(1, results.count { it is SocialFailure })
        assertEquals("1", f.value("SELECT count(*) FROM social.circle_members WHERE role='owner' AND status='active'"))
        assertTrue(f.value("SELECT owner_id FROM social.circles") in setOf(a.accountId.toString(), b.accountId.toString()))
    }

    @Test fun deferredDatabaseConstraintRejectsMissingOwnerAndCapacityViolationAtCommit() {
        val f = Fixture(memberLimit = 2); val circle = f.circle(); val member = f.account(); f.join(circle, member)
        assertFailsWith<SQLException> { f.dataSource.connection.use { c ->
            c.autoCommit = false; c.createStatement().use { it.executeUpdate("UPDATE social.circle_members SET role='member' WHERE role='owner'") }
            c.commit()
        } }
        assertEquals("1", f.value("SELECT count(*) FROM social.circle_members WHERE role='owner'"))
        assertFailsWith<SQLException> { f.dataSource.connection.use { c ->
            c.autoCommit = false; c.createStatement().use { it.executeUpdate("INSERT INTO social.circle_members(environment,circle_id,user_id,id,role,status,generation,version) VALUES('test','$circle','${UUID.randomUUID()}','${UUID.randomUUID()}','member','active',1,1)") }
            c.commit()
        } }
        assertEquals(2, f.count("social.circle_members"))

        // Both direct writers insert before either deferred check runs. The parent lock in
        // the trigger must make the second count see the first commit, without an FK lock upgrade.
        val direct = Fixture(memberLimit = 2); val directCircle = direct.circle(); val inserted = CountDownLatch(2)
        val outcomes = racing(List(2) { {
            try {
                direct.dataSource.connection.use { c ->
                    c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED; c.autoCommit = false
                    c.createStatement().use { it.execute("SET LOCAL statement_timeout='5s'") }
                    c.prepareStatement("INSERT INTO social.circle_members(environment,circle_id,user_id,id,role,status,generation,version) VALUES('test',?,?,?,'member','active',1,1)").use {
                        it.setObject(1, directCircle); it.setObject(2, UUID.randomUUID()); it.setObject(3, UUID.randomUUID()); assertEquals(1, it.executeUpdate())
                    }
                    inserted.countDown(); assertTrue(inserted.await(5, TimeUnit.SECONDS)); c.commit()
                }
                "committed"
            } catch (failure: SQLException) { failure }
        } })
        assertEquals(1, outcomes.count { it == "committed" })
        assertEquals(listOf("23514"), outcomes.filterIsInstance<SQLException>().map { it.sqlState })
        assertEquals(2, direct.count("social.circle_members"))
    }

    @Test fun exactV001UpgradePreservesOriginalHistoryAndAddsV002ThroughV010OnlyOnce() {
        val ds = cluster.database(); val first = resource("V001__durable_platform.sql")
        ds.connection.use { c -> c.createStatement().use { s ->
            s.execute("CREATE SCHEMA platform; CREATE TABLE platform.schema_migrations(version integer PRIMARY KEY,description varchar(200) NOT NULL,checksum char(64) NOT NULL,installed_at timestamptz NOT NULL DEFAULT clock_timestamp())")
            s.execute(first); s.executeUpdate("INSERT INTO platform.schema_migrations(version,description,checksum) VALUES(1,'durable_platform','${sha(first)}')")
        } }
        val before = value(ds, "SELECT row_to_json(m)::text FROM platform.schema_migrations m WHERE version=1")
        PlatformMigrations(ds).migrate(); PlatformMigrations(ds).migrate()
        assertEquals(before, value(ds, "SELECT row_to_json(m)::text FROM platform.schema_migrations m WHERE version=1"))
        assertEquals("10", value(ds, "SELECT count(*) FROM platform.schema_migrations"))
        assertEquals(sha(resource("V002__circle_memberships.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=2"))
        assertEquals(sha(resource("V003__private_planning.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=3"))
        assertEquals(sha(resource("V004__private_kitchen.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=4"))
        assertEquals(sha(resource("V005__private_cooking.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=5"))
        assertEquals(sha(resource("V006__private_saved_recipes.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=6"))
        assertEquals(sha(resource("V007__owned_photo_media.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=7"))
        assertEquals(sha(resource("V008__media_processing_jobs.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=8"))
        assertEquals(sha(resource("V009__private_post_drafts.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=9"))
        assertEquals(sha(resource("V010__post_publication.sql")), value(ds, "SELECT checksum FROM platform.schema_migrations WHERE version=10"))
        assertEquals(
            "circle_invitations,circle_members,circles,post_attachments,post_audiences,post_media," +
                "post_publication_discard_media,post_publications,posts,recipe_save_policies",
            value(ds, "SELECT string_agg(tablename, ',' ORDER BY tablename) FROM pg_tables WHERE schemaname='social'"),
        )
    }

    private class Fixture(memberLimit: Int = 50) {
        val dataSource = cluster.database(); val faults = DbFaults(); val policy = TestIdentityPolicy()
        val capabilities = CircleCapabilities("v1", mapOf("v1" to ByteArray(32) { (it + 1).toByte() }), URI("https://example.invalid/invite"))
        val owner: VerifiedSocialAccount
        val store: CirclesStore
        init {
            PlatformMigrations(dataSource).migrate()
            sql("CREATE SCHEMA social_test; CREATE TABLE social_test.accounts(id uuid PRIMARY KEY,device uuid NOT NULL,active boolean NOT NULL,display_name text NOT NULL,handle text NOT NULL); " +
                "CREATE TABLE social_test.blocks(first_id uuid NOT NULL,second_id uuid NOT NULL,PRIMARY KEY(first_id,second_id))")
            owner = account(); store = CirclesStore("test", PgTransactions(faults.wrap(dataSource)), policy, capabilities, CircleLaunchPolicy(memberLimit, 168))
        }
        fun account(): VerifiedSocialAccount = VerifiedSocialAccount("test", UUID.randomUUID(), UUID.randomUUID()).also { actor ->
            dataSource.connection.use { c -> c.prepareStatement("INSERT INTO social_test.accounts VALUES(?,?,true,?,?)").use {
                it.setObject(1, actor.accountId); it.setObject(2, actor.deviceSessionId); it.setString(3, "Synthetic cook"); it.setString(4, "synthetic"); it.executeUpdate()
            } }
        }
        fun circle(name: String = "Kitchen friends") = uuid(body(store.createCircle(owner, UUID.randomUUID(), circleBody(name))), "id")
        fun invite(circle: UUID) = store.createInvitation(owner, UUID.randomUUID(), inviteBody(circle))
        fun join(circle: UUID, actor: VerifiedSocialAccount) = store.acceptInvitation(actor, UUID.randomUUID(), acceptBody(token(invite(circle))))
        fun expire(id: UUID) = sql("UPDATE social.circle_invitations SET created_at=clock_timestamp()-interval '2 days',expires_at=clock_timestamp()-interval '1 second' WHERE id='$id'")
        fun block(a: VerifiedSocialAccount, b: VerifiedSocialAccount) = sql("INSERT INTO social_test.blocks VALUES('${a.accountId}','${b.accountId}')")
        fun sql(sql: String) { dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
        fun value(query: String) = value(dataSource, query)
        fun count(table: String) = value("SELECT count(*) FROM $table").toInt()
    }

    private class TestIdentityPolicy : SocialIdentityPolicy {
        var creationEnabled = true; var afterPair: (() -> Unit)? = null; var foreignProfileReads = 0
        override fun lockPrincipal(connection: Connection, principal: VerifiedSocialAccount) {
            connection.prepareStatement("SELECT active,device FROM social_test.accounts WHERE id=? FOR SHARE").use { s ->
                s.setObject(1, principal.accountId); s.executeQuery().use {
                    if (!it.next() || !it.getBoolean(1) || it.getObject(2, UUID::class.java) != principal.deviceSessionId) throw SocialFailure(SocialFailureCode.UNAUTHENTICATED)
                }
            }
        }
        override fun requireCreationEnabled(connection: Connection, principal: VerifiedSocialAccount, invitations: Boolean) {
            if (!creationEnabled) throw SocialFailure(SocialFailureCode.NOT_CONFIGURED)
        }
        override fun lockUnblockedPair(connection: Connection, environment: String, first: UUID, second: UUID) {
            connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use {
                it.setString(1, "$environment:${listOf(first, second).sortedBy(UUID::toString).joinToString(":")}"); it.execute()
            }
            connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM social_test.blocks WHERE (first_id=? AND second_id=?) OR (first_id=? AND second_id=?))").use { s ->
                s.setObject(1, first); s.setObject(2, second); s.setObject(3, second); s.setObject(4, first)
                s.executeQuery().use { it.next(); if (it.getBoolean(1)) throw SocialFailure(SocialFailureCode.CIRCLE_UNAVAILABLE) }
            }
            afterPair?.invoke()
        }
        override fun readProfile(connection: Connection, environment: String, accountId: UUID): SocialProfileSummary =
            connection.prepareStatement("SELECT display_name,handle FROM social_test.accounts WHERE id=? AND active=true FOR SHARE").use { s ->
                s.setObject(1, accountId); s.executeQuery().use { if (!it.next()) { foreignProfileReads++; throw SocialFailure(SocialFailureCode.CIRCLE_UNAVAILABLE) }
                    SocialProfileSummary(accountId, it.getString(1), it.getString(2)) }
            }
    }

    private class DbFaults {
        @Volatile var failOutbox = false
        @Volatile var loseCommit = false
        fun wrap(source: DataSource): DataSource = object : DataSource by source {
            override fun getConnection(): Connection {
                val actual = source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement" && args?.firstOrNull() is String && (args[0] as String).contains("INSERT INTO platform.outbox") && failOutbox) {
                        failOutbox = false; throw SQLException("injected-private-event-failure", "XX000")
                    }
                    try {
                        val result = method.invoke(actual, *(args ?: emptyArray()))
                        if (method.name == "commit" && loseCommit) { loseCommit = false; throw SQLException("injected application response loss", "08006") }
                        result
                    } catch (failure: InvocationTargetException) { throw failure.targetException }
                } as Connection
            }
        }
    }

    companion object {
        private lateinit var cluster: PostgresTestCluster
        @JvmField @ClassRule val timeout: Timeout = Timeout(8, TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun close() { if (::cluster.isInitialized) cluster.close() }
        private fun circleBody(name: String = "Kitchen friends") = buildJsonObject { put("name", name) }
        private fun inviteBody(circle: UUID) = buildJsonObject { put("targetType", "circle"); put("targetId", circle.toString()) }
        private fun acceptBody(token: String) = buildJsonObject { put("token", token) }
        private fun roleBody(role: String) = buildJsonObject { put("role", role) }
        private fun transferBody(actor: VerifiedSocialAccount) = buildJsonObject { put("newOwnerUserId", actor.accountId.toString()); put("confirmed", true) }
        private fun reply(result: CommandResult): StoredReply = when (result) { is CommandResult.Applied -> result.reply; is CommandResult.Replayed -> result.reply; else -> fail("Expected successful command") }
        private fun body(result: CommandResult) = reply(result).body!!.jsonObject
        private fun token(result: CommandResult) = body(result).getValue("inviteUrl").jsonPrimitive.content.substringAfter("?token=")
        private fun uuid(body: JsonObject, name: String) = UUID.fromString(body.getValue(name).jsonPrimitive.content)
        private fun denied(code: SocialFailureCode, action: () -> Any?) = assertEquals(code, assertFailsWith<SocialFailure> { action() }.code)
        private fun value(ds: DataSource, sql: String): String = ds.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { assertTrue(it.next()); it.getString(1) } } }
        private fun resource(name: String) = checkNotNull(PlatformMigrations::class.java.getResourceAsStream("/db/migration/$name")).use { it.readBytes().toString(Charsets.UTF_8) }
        private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun racing(actions: List<() -> Any>): List<Any> {
            val pool = Executors.newFixedThreadPool(actions.size); val ready = CountDownLatch(actions.size); val start = CountDownLatch(1)
            val futures = actions.map { action -> pool.submit<Any> { ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); try { action() } catch (failure: SocialFailure) { failure } } }
            return try { assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown(); futures.map { it.get(20, TimeUnit.SECONDS) } }
            finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
}
