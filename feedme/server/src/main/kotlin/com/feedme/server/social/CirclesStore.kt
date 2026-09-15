package com.feedme.server.social

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * PostgreSQL circles foundation, not an HTTP/provider adapter. The mandatory identity policy
 * reauthorizes every read, command and cached reply. Lock order: identity policy, command receipt,
 * circle, invitation, then block-pair policy. All member mutations serialize on their circle.
 * Rejoin increments generation; a content grant must retain its author's generation, not just ID.
 * No scheduler, notification delivery, media grant, post or later invitation target is implemented.
 * Outbox schema circles.circle.changed.v1: {circleId: UUID, action: "updated"}, producer
 * "circles", aggregate type "circle", ID the circle UUID, aggregateVersion its incremented
 * version. No name/description is published. Projection/cache consumers remain unimplemented.
 */
class CirclesStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val identity: SocialIdentityPolicy,
    private val capabilities: CircleCapabilities,
    private val launch: CircleLaunchPolicy,
) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun createCircle(actor: VerifiedSocialAccount, key: UUID, body: JsonObject): CommandResult {
        val input = request("createCircle", body); val id = UUID.randomUUID(); val memberId = UUID.randomUUID()
        return command(actor, "createCircle", key, body = input, replay = { c, reply ->
            val circle = circle(c, uuid(reply.body!!.jsonObject, "id")); val member = member(c, circle.id, actor.accountId)
            requireActive(circle, member); requireRole(member, "owner"); requireReplyRole(reply, member)
        }) { c ->
            identity.requireCreationEnabled(c, actor, false)
            exec(c, "INSERT INTO social.circles(environment,id,owner_id,name,description,status,member_limit,version) VALUES(?,?,?,?,?,'active',?,1)") {
                setString(1, environment); setObject(2, id); setObject(3, actor.accountId)
                setString(4, input.getValue("name").jsonPrimitive.content); setString(5, input["description"]?.jsonPrimitive?.content)
                setInt(6, launch.memberLimit)
            }
            exec(c, "INSERT INTO social.circle_members(environment,circle_id,user_id,id,role,status,generation,version) VALUES(?,?,?,?,'owner','active',1,1)") {
                setString(1, environment); setObject(2, id); setObject(3, actor.accountId); setObject(4, memberId)
            }
            val current = circle(c, id); val owner = member(c, id, actor.accountId)!!
            membershipEvent(c, key, current, owner, "joined")
            reply("createCircle", 201, circleJson(c, current, owner), current.version)
        }
    }

    fun getCircle(actor: VerifiedSocialAccount, circleId: UUID): StoredReply = read(actor) { c ->
        val current = circle(c, circleId); val viewer = member(c, circleId, actor.accountId); requireActive(current, viewer)
        reply("getCircle", 200, circleJson(c, current, viewer!!), current.version)
    }

    fun listCircles(actor: VerifiedSocialAccount, cursor: String? = null, limit: Int = 20): StoredReply = read(actor) { c ->
        validateLimit(limit); val after = capabilities.parseCursor(actor, "circles", null, cursor)
        // Lock all selected parents before observing memberships; removed rows never become a cursor grant.
        val ids = query(c, "SELECT c.id FROM social.circles c JOIN social.circle_members m ON m.environment=c.environment AND m.circle_id=c.id " +
            "WHERE c.environment=? AND c.status='active' AND m.user_id=? AND m.status='active' AND (?::uuid IS NULL OR c.id>?::uuid) " +
            "ORDER BY c.id LIMIT ? FOR UPDATE OF c", {
            setString(1, environment); setObject(2, actor.accountId); setObject(3, after); setObject(4, after); setInt(5, limit + 1)
        }) { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        val visible = ids.mapNotNull { id -> val circle = circle(c, id); val member = member(c, id, actor.accountId)
            if (circle.status == "active" && member?.status == "active") circle to member else null }
        val page = visible.take(limit)
        reply("listCircles", 200, page(c, page.map { circleJson(c, it.first, it.second) },
            if (visible.size > limit) capabilities.cursor(actor, "circles", null, page.last().first.id) else null))
    }

    fun listMembers(actor: VerifiedSocialAccount, circleId: UUID, cursor: String? = null, limit: Int = 20): StoredReply = read(actor) { c ->
        validateLimit(limit); val after = capabilities.parseCursor(actor, "members", circleId, cursor)
        val current = circle(c, circleId); requireActive(current, member(c, circleId, actor.accountId))
        val members = query(c, "SELECT * FROM social.circle_members WHERE environment=? AND circle_id=? AND status='active' " +
            "AND (?::uuid IS NULL OR user_id>?::uuid) ORDER BY user_id", {
            setString(1, environment); setObject(2, circleId); setObject(3, after); setObject(4, after)
        }) { rows -> buildList { while (rows.next()) add(memberRow(rows)) } }
        // The configured circle cap bounds this scan; filter BEFORE pagination so a blocked row
        // is never exposed as a profile or used to infer a short page of otherwise visible members.
        val visible = members.mapNotNull { target ->
            try { unblocked(c, actor.accountId, target.userId); target.userId to memberJson(c, target) }
            catch (failure: SocialFailure) { if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE) null else throw failure }
        }
        reply("listCircleMembers", 200, page(c, visible.take(limit).map { it.second },
            if (visible.size > limit) capabilities.cursor(actor, "members", circleId, visible[limit - 1].first) else null))
    }

    fun updateCircle(actor: VerifiedSocialAccount, key: UUID, circleId: UUID, ifMatch: String, body: JsonObject): CommandResult {
        val input = request("updateCircle", body); val version = etag(ifMatch)
        return command(actor, "updateCircle", key, paths(circleId), input, ifMatch, replay = { c, reply ->
            val current = circle(c, circleId); val viewer = member(c, circleId, actor.accountId)
            requireActive(current, viewer); requireRole(viewer, "owner", "admin"); requireReplyRole(reply, viewer)
        }) { c ->
            val current = circle(c, circleId); requireActive(current, member(c, circleId, actor.accountId))
            requireRole(member(c, circleId, actor.accountId), "owner", "admin"); expect(current.version, version)
            exec(c, "UPDATE social.circles SET name=?,description=?,version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=?") {
                setString(1, input.getValue("name").jsonPrimitive.content); setString(2, input["description"]?.jsonPrimitive?.content)
                setString(3, environment); setObject(4, circleId)
            }
            val updated = circle(c, circleId); circleEvent(c, key, updated, "updated")
            reply("updateCircle", 200, circleJson(c, updated, member(c, circleId, actor.accountId)!!), updated.version)
        }
    }

    fun updateMember(actor: VerifiedSocialAccount, key: UUID, circleId: UUID, userId: UUID, ifMatch: String, body: JsonObject): CommandResult {
        val input = request("updateCircleMember", body); val version = etag(ifMatch)
        return command(actor, "updateCircleMember", key, paths(circleId, userId), input, ifMatch, replay = { c, cached ->
            val current = circle(c, circleId); requireActive(current, member(c, circleId, actor.accountId))
            requireRole(member(c, circleId, actor.accountId), "owner")
            requireCurrentMemberReply(c, activeTarget(c, current, actor, userId), cached)
        }) { c ->
            val current = circle(c, circleId); requireActive(current, member(c, circleId, actor.accountId))
            requireRole(member(c, circleId, actor.accountId), "owner")
            val target = activeTarget(c, current, actor, userId); if (target.role == "owner") fail(SocialFailureCode.OWNER_TRANSFER_REQUIRED)
            expect(target.version, version); updateRole(c, target, input.getValue("role").jsonPrimitive.content)
            bumpCircle(c, circleId); val changed = member(c, circleId, userId)!!
            membershipEvent(c, key, circle(c, circleId), changed, "role_changed")
            reply("updateCircleMember", 200, memberJson(c, changed), changed.version)
        }
    }

    fun leaveCircle(actor: VerifiedSocialAccount, key: UUID, circleId: UUID): CommandResult =
        remove(actor, key, circleId, actor.accountId, null, leaving = true)

    fun removeMember(actor: VerifiedSocialAccount, key: UUID, circleId: UUID, userId: UUID, ifMatch: String): CommandResult =
        remove(actor, key, circleId, userId, ifMatch, leaving = false)

    private fun remove(actor: VerifiedSocialAccount, key: UUID, circleId: UUID, userId: UUID, ifMatch: String?, leaving: Boolean): CommandResult {
        val op = if (leaving) "leaveCircle" else "removeCircleMember"; val version = ifMatch?.let(::etag)
        return command(actor, op, key, if (leaving) paths(circleId) else paths(circleId, userId),
            if (leaving) buildJsonObject {} else null, ifMatch, replay = { c, _ ->
                val current = circle(c, circleId); val target = member(c, circleId, userId)
                if (target?.status != "removed" || target.removalKey != key || target.removalOperation != op || target.removedBy != actor.accountId ||
                    target.removedGeneration != target.generation) fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
                if (!leaving && actor.accountId != userId) { requireActive(current, member(c, circleId, actor.accountId)); requireRole(member(c, circleId, actor.accountId), "owner", "admin") }
            }) { c ->
            val current = circle(c, circleId); val viewer = member(c, circleId, actor.accountId); requireActive(current, viewer)
            val target = member(c, circleId, userId); if (target?.status != "active") fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
            if (actor.accountId != userId) requireRole(viewer, "owner", "admin")
            if (target.role == "owner") fail(SocialFailureCode.OWNER_TRANSFER_REQUIRED)
            version?.let { expect(target.version, it) }; removeRow(c, target, key, actor.accountId, op); bumpCircle(c, circleId)
            membershipEvent(c, key, circle(c, circleId), member(c, circleId, userId)!!, if (actor.accountId == userId) "left" else "removed")
            reply(op, if (leaving) 200 else 204, if (leaving) buildJsonObject {} else null)
        }
    }

    fun transferOwnership(actor: VerifiedSocialAccount, key: UUID, circleId: UUID, ifMatch: String, body: JsonObject): CommandResult {
        val input = request("transferCircleOwnership", body); val version = etag(ifMatch); val userId = uuid(input, "newOwnerUserId")
        if (!input.getValue("confirmed").jsonPrimitive.boolean || userId == actor.accountId) fail(SocialFailureCode.INPUT_INVALID)
        return command(actor, "transferCircleOwnership", key, paths(circleId), input, ifMatch, replay = { c, reply ->
            val current = circle(c, circleId); val viewer = member(c, circleId, actor.accountId)
            requireActive(current, viewer); if (current.ownerId != userId) fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
            activeTarget(c, current, actor, userId); requireReplyRole(reply, viewer)
        }) { c ->
            val current = circle(c, circleId); val owner = member(c, circleId, actor.accountId)
            requireActive(current, owner); requireRole(owner, "owner"); expect(current.version, version)
            val target = activeTarget(c, current, actor, userId)
            updateRole(c, owner!!, "member"); updateRole(c, target, "owner")
            exec(c, "UPDATE social.circles SET owner_id=?,version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=?") {
                setObject(1, userId); setString(2, environment); setObject(3, circleId)
            }
            val changed = circle(c, circleId)
            event(c, key, "circles.ownership.transferred.v1", "circle", circleId, changed.version, buildJsonObject {
                put("circleId", circleId.toString()); put("fromUserId", actor.accountId.toString()); put("toUserId", userId.toString())
            })
            reply("transferCircleOwnership", 200, circleJson(c, changed, member(c, circleId, actor.accountId)!!), changed.version)
        }
    }

    fun deleteCircle(actor: VerifiedSocialAccount, key: UUID, circleId: UUID, ifMatch: String): CommandResult {
        val version = etag(ifMatch)
        return command(actor, "deleteCircle", key, paths(circleId), ifMatch = ifMatch, replay = { c, _ ->
            val current = circle(c, circleId)
            if (current.status != "archived" || current.ownerId != actor.accountId) fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
        }) { c ->
            val current = circle(c, circleId); requireActive(current, member(c, circleId, actor.accountId))
            requireRole(member(c, circleId, actor.accountId), "owner"); expect(current.version, version)
            val members = query(c, "SELECT * FROM social.circle_members WHERE environment=? AND circle_id=? AND status='active' ORDER BY user_id", {
                setString(1, environment); setObject(2, circleId)
            }) { rows -> buildList { while (rows.next()) add(memberRow(rows)) } }
            members.forEach { removeRow(c, it) }
            exec(c, "UPDATE social.circles SET status='archived',version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=?") {
                setString(1, environment); setObject(2, circleId)
            }
            val archived = circle(c, circleId)
            members.forEach { membershipEvent(c, key, archived, member(c, circleId, it.userId)!!, "circle_archived") }
            reply("deleteCircle", 204, null)
        }
    }

    fun createInvitation(actor: VerifiedSocialAccount, key: UUID, body: JsonObject): CommandResult {
        val input = request("createInvitation", body)
        if (input.getValue("targetType").jsonPrimitive.content != "circle") fail(SocialFailureCode.NOT_CONFIGURED)
        val circleId = uuid(input, "targetId")
        val hours = input["expiresInHours"]?.let(::integer) ?: launch.invitationLifetimeHours
        val uses = input["maxUses"]?.let(::integer) ?: 1
        if (hours !in 1..launch.invitationLifetimeHours || uses != 1) fail(SocialFailureCode.INPUT_INVALID)
        val id = UUID.randomUUID(); val keyId = capabilities.currentKeyId
        val tokenHash = capabilities.tokenHash(capabilities.invite(environment, id, keyId))
        val result = command(actor, "createInvitation", key, body = input, replay = { c, cached ->
            val current = circle(c, circleId); requireActive(current, member(c, circleId, actor.accountId))
            requireRole(member(c, circleId, actor.accountId), "owner", "admin")
            val invite = invitation(c, uuid(cached.body!!.jsonObject, "id")); requireUsableInvite(c, current, invite)
            if (invite.createdBy != actor.accountId) fail(SocialFailureCode.INVITATION_UNAVAILABLE)
        }) { c ->
            val current = circle(c, circleId); requireActive(current, member(c, circleId, actor.accountId))
            requireRole(member(c, circleId, actor.accountId), "owner", "admin"); identity.requireCreationEnabled(c, actor, true)
            val issuer = member(c, circleId, actor.accountId)!!
            exec(c, "INSERT INTO social.circle_invitations(environment,id,circle_id,created_by,token_hash,token_key_id,issuer_generation,issuer_version,status,version,created_at,updated_at,expires_at) " +
                "SELECT ?,?,?,?,?,?,?,?,'active',1,t,t,t+(? * interval '1 hour') FROM (SELECT clock_timestamp() AS t) clock", {
                setString(1, environment); setObject(2, id); setObject(3, circleId); setObject(4, actor.accountId)
                setString(5, tokenHash); setString(6, keyId); setLong(7, issuer.generation); setLong(8, issuer.version); setInt(9, hours)
            })
            val invite = invitation(c, id); invitationEvent(c, key, invite, "issued")
            reply("createInvitation", 201, invitationJson(invite), invite.version)
        }
        // Bearer appears only in the returned response, never in the durable receipt/outbox.
        return when (result) {
            is CommandResult.Applied -> CommandResult.Applied(invitationCapability(actor, result.reply))
            is CommandResult.Replayed -> CommandResult.Replayed(invitationCapability(actor, result.reply))
            else -> result
        }
    }

    fun previewInvitation(token: String): StoredReply = safe {
        val tokenHash = capabilities.tokenHash(token)
        transactions.run { c ->
            val candidate = findInvitation(c, tokenHash)
            if (candidate == null) return@run reply("previewInvitation", 200, unavailablePreview())
            val current = circle(c, candidate.circleId); val invite = invitation(c, candidate.id)
            try {
                requireUsableInvite(c, current, invite)
                val inviter = identity.readProfile(c, environment, invite.createdBy)
                if (inviter.userId != invite.createdBy) fail(SocialFailureCode.STORAGE_UNAVAILABLE)
                requireUsableInvite(c, current, invite)
                reply("previewInvitation", 200, buildJsonObject {
                    put("status", "active"); put("targetType", "circle"); put("targetName", current.name)
                    put("inviterLabel", inviter.displayName); put("expiresAt", invite.expiresAt.toString())
                })
            } catch (failure: SocialFailure) {
                if (failure.code in setOf(SocialFailureCode.STORAGE_UNAVAILABLE, SocialFailureCode.NOT_CONFIGURED)) throw failure
                reply("previewInvitation", 200, unavailablePreview())
            }
        }
    }

    fun acceptInvitation(actor: VerifiedSocialAccount, key: UUID, body: JsonObject): CommandResult {
        val input = request("acceptInvitation", body); val tokenHash = capabilities.tokenHash(input.getValue("token").jsonPrimitive.content)
        val newMemberId = UUID.randomUUID()
        return command(actor, "acceptInvitation", key, body = input, replay = { c, cached ->
            val candidate = findInvitation(c, tokenHash) ?: fail(SocialFailureCode.INVITATION_UNAVAILABLE)
            val current = circle(c, candidate.circleId); val invite = invitation(c, candidate.id)
            val currentMember = member(c, current.id, actor.accountId); requireActive(current, currentMember)
            if (invite.consumedBy != actor.accountId || invite.consumedGeneration != currentMember!!.generation ||
                currentMember.id != uuid(cached.body!!.jsonObject, "id")) fail(SocialFailureCode.INVITATION_UNAVAILABLE)
            joinPolicy(c, actor, current, invite)
            requireCurrentMemberReply(c, currentMember, cached)
        }) { c ->
            val candidate = findInvitation(c, tokenHash) ?: fail(SocialFailureCode.INVITATION_UNAVAILABLE)
            val current = circle(c, candidate.circleId); val invite = invitation(c, candidate.id)
            val previous = member(c, current.id, actor.accountId)
            // The acceptance itself is idempotent, not merely one particular command key.
            // An old token never grants a second generation after leave/rejoin.
            if (invite.status == "exhausted" && invite.consumedBy == actor.accountId) {
                if (current.status != "active" || previous?.status != "active" || previous.generation != invite.consumedGeneration)
                    fail(SocialFailureCode.INVITATION_UNAVAILABLE)
                joinPolicy(c, actor, current, invite)
                return@command reply("acceptInvitation", 200, memberJson(c, previous), previous.version)
            }
            requireUsableInvite(c, current, invite); joinPolicy(c, actor, current, invite)
            // Policy locks can wait. Expiry is authoritative at execution, not before that wait.
            requireUsableInvite(c, current, invite)
            if (previous?.status != "active") {
                if (memberCount(c, current.id) >= current.memberLimit) fail(SocialFailureCode.CIRCLE_FULL)
                if (previous == null) exec(c, "INSERT INTO social.circle_members(environment,circle_id,user_id,id,role,status,generation,version) VALUES(?,?,?,?,'member','active',1,1)") {
                    setString(1, environment); setObject(2, current.id); setObject(3, actor.accountId); setObject(4, newMemberId)
                } else exec(c, "UPDATE social.circle_members SET role='member',status='active',generation=generation+1,version=version+1,removal_key=NULL,removal_operation=NULL,removed_by=NULL,removed_generation=NULL,updated_at=clock_timestamp(),joined_at=clock_timestamp() " +
                    "WHERE environment=? AND circle_id=? AND user_id=?") {
                    setString(1, environment); setObject(2, current.id); setObject(3, actor.accountId)
                }
                bumpCircle(c, current.id)
            }
            val joined = member(c, current.id, actor.accountId)!!
            exec(c, "UPDATE social.circle_invitations SET status='exhausted',consumed_by=?,consumed_generation=?,version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=?") {
                setObject(1, actor.accountId); setLong(2, joined.generation); setString(3, environment); setObject(4, invite.id)
            }
            if (previous?.status != "active") membershipEvent(c, key, circle(c, current.id), joined, "joined")
            invitationEvent(c, key, invitation(c, invite.id), "accepted")
            reply("acceptInvitation", 200, memberJson(c, joined), joined.version)
        }
    }

    fun revokeInvitation(actor: VerifiedSocialAccount, key: UUID, invitationId: UUID, ifMatch: String): CommandResult {
        val version = etag(ifMatch)
        return command(actor, "revokeInvitation", key, mapOf("invitationId" to invitationId.toString()), ifMatch = ifMatch,
            replay = { c, _ ->
                val candidate = invitation(c, invitationId, locked = false); val current = circle(c, candidate.circleId)
                val invite = invitation(c, invitationId); requireInvitationAccess(current, member(c, current.id, actor.accountId))
                requireRole(member(c, current.id, actor.accountId), "owner", "admin")
                if (invite.createdBy != actor.accountId || invite.status != "revoked") fail(SocialFailureCode.INVITATION_UNAVAILABLE)
            }) { c ->
            val candidate = invitation(c, invitationId, locked = false); val current = circle(c, candidate.circleId)
            val invite = invitation(c, invitationId); requireInvitationAccess(current, member(c, current.id, actor.accountId))
            requireRole(member(c, current.id, actor.accountId), "owner", "admin")
            if (invite.createdBy != actor.accountId) fail(SocialFailureCode.INVITATION_UNAVAILABLE)
            requireUsableInvite(c, current, invite); expect(invite.version, version)
            exec(c, "UPDATE social.circle_invitations SET status='revoked',version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=?") {
                setString(1, environment); setObject(2, invitationId)
            }
            invitationEvent(c, key, invitation(c, invitationId), "revoked"); reply("revokeInvitation", 204, null)
        }
    }

    /** Foundation for F40; callers must ALSO authorize the post's status, placement and other grants. */
    fun hasCurrentAudienceMembership(actor: VerifiedSocialAccount, circleId: UUID, authorId: UUID, authorGeneration: Long): Boolean = read(actor) { c ->
        val current = circleOrNull(c, circleId) ?: return@read false
        val viewer = member(c, circleId, actor.accountId); val author = member(c, circleId, authorId)
        if (current.status != "active" || viewer?.status != "active" || author?.status != "active" || author.generation != authorGeneration) return@read false
        try { unblocked(c, actor.accountId, authorId); eligibleProfile(c, authorId); true }
        catch (failure: SocialFailure) { if (failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE) false else throw failure }
    }

    private fun invitationCapability(actor: VerifiedSocialAccount, cached: StoredReply): StoredReply = read(actor) { c ->
        val old = cached.body!!.jsonObject; val current = circle(c, uuid(old, "targetId")); val invite = invitation(c, uuid(old, "id"))
        requireActive(current, member(c, current.id, actor.accountId)); requireRole(member(c, current.id, actor.accountId), "owner", "admin")
        requireUsableInvite(c, current, invite); if (invite.createdBy != actor.accountId) fail(SocialFailureCode.INVITATION_UNAVAILABLE)
        val token = capabilities.invite(environment, invite.id, invite.keyId)
        if (capabilities.tokenHash(token) != invite.tokenHash) fail(SocialFailureCode.NOT_CONFIGURED)
        reply("createInvitation", 201, JsonObject(old + ("inviteUrl" to JsonPrimitive(capabilities.url(environment, invite.id, invite.keyId)))), old.getValue("version").jsonPrimitive.long)
    }

    private fun joinPolicy(c: Connection, actor: VerifiedSocialAccount, circle: CircleRow, invite: InvitationRow) {
        unblocked(c, actor.accountId, circle.ownerId); unblocked(c, actor.accountId, invite.createdBy)
        setOf(actor.accountId, circle.ownerId, invite.createdBy).sortedBy(UUID::toString).forEach { eligibleProfile(c, it) }
    }
    private fun eligibleProfile(c: Connection, userId: UUID): SocialProfileSummary = identity.readProfile(c, environment, userId).also {
        if (it.userId != userId) fail(SocialFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun unblocked(c: Connection, a: UUID, b: UUID) { if (a != b) identity.lockUnblockedPair(c, environment, a, b) }
    private fun requireUsableInvite(c: Connection, circle: CircleRow, invite: InvitationRow) {
        if (circle.status != "active" || circle.id != invite.circleId) fail(SocialFailureCode.INVITATION_UNAVAILABLE)
        val inviter = member(c, circle.id, invite.createdBy)
        if (inviter?.status != "active" || inviter.role !in setOf("owner", "admin") ||
            inviter.generation != invite.issuerGeneration || inviter.version != invite.issuerVersion) fail(SocialFailureCode.INVITATION_UNAVAILABLE)
        if (invite.status == "exhausted") fail(SocialFailureCode.INVITATION_USED)
        if (invite.status != "active") fail(SocialFailureCode.INVITATION_UNAVAILABLE)
        if (!invite.expiresAt.isAfter(now(c))) fail(SocialFailureCode.INVITATION_EXPIRED)
    }
    private fun activeTarget(c: Connection, circle: CircleRow, actor: VerifiedSocialAccount, userId: UUID): MemberRow {
        val target = member(c, circle.id, userId)
        if (target?.status != "active") fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
        unblocked(c, actor.accountId, userId); return target
    }
    private fun requireActive(circle: CircleRow, viewer: MemberRow?) {
        if (circle.status != "active" || viewer?.status != "active") fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
    }
    private fun requireInvitationAccess(circle: CircleRow, viewer: MemberRow?) {
        if (circle.status != "active" || viewer?.status != "active") fail(SocialFailureCode.INVITATION_UNAVAILABLE)
    }
    private fun requireCurrentMemberReply(c: Connection, current: MemberRow, cached: StoredReply) {
        if (cached.etag != "\"${current.version}\"" || cached.body != memberJson(c, current)) fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
    }
    private fun requireRole(member: MemberRow?, vararg allowed: String) { if (member?.role !in allowed) fail(SocialFailureCode.NOT_OWNER) }
    private fun requireReplyRole(reply: StoredReply, viewer: MemberRow?) {
        if (reply.body!!.jsonObject.getValue("role").jsonPrimitive.content != viewer?.role) fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
    }
    private fun expect(actual: Long, expected: Long) { if (actual != expected) fail(SocialFailureCode.VERSION_CONFLICT) }
    private fun validateLimit(limit: Int) { if (limit !in 1..50) fail(SocialFailureCode.INPUT_INVALID) }
    private fun etag(value: String): Long {
        if (!value.matches(Regex("\"[0-9]{1,64}\""))) fail(SocialFailureCode.INPUT_INVALID)
        return value.substring(1, value.length - 1).trimStart('0').ifEmpty { "0" }.toLongOrNull()?.takeIf { it > 0 }
            ?: fail(SocialFailureCode.INPUT_INVALID)
    }
    private fun paths(circleId: UUID, userId: UUID? = null) = buildMap {
        put("circleId", circleId.toString()); userId?.let { put("userId", it.toString()) }
    }
    private fun request(op: String, body: JsonObject): JsonObject {
        val bytes = try { body.toString().encodeToByteArray(throwOnInvalidSequence = true) }
            catch (_: IllegalArgumentException) { fail(SocialFailureCode.INPUT_INVALID) }
            catch (_: CharacterCodingException) { fail(SocialFailureCode.INPUT_INVALID) }
        if (validator.validateRequest(op, bytes, "application/json") != BodyValidationResult.Valid) fail(SocialFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun reply(op: String, status: Int, body: JsonObject?, version: Long? = null): StoredReply {
        val bytes = try { body?.toString()?.encodeToByteArray(throwOnInvalidSequence = true) }
            catch (_: IllegalArgumentException) { fail(SocialFailureCode.STORAGE_UNAVAILABLE) }
            catch (_: CharacterCodingException) { fail(SocialFailureCode.STORAGE_UNAVAILABLE) }
        if (validator.validateResponse(op, status, bytes, if (body == null) null else "application/json") != BodyValidationResult.Valid)
            fail(SocialFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(status, body, version?.let { "\"$it\"" })
    }
    private fun command(actor: VerifiedSocialAccount, op: String, key: UUID, paths: Map<String, String> = emptyMap(), body: JsonObject? = null,
        ifMatch: String? = null, replay: (Connection, StoredReply) -> Unit, mutate: (Connection) -> StoredReply): CommandResult = safe {
        checkActor(actor)
        val command = CommandIdentity(PrincipalScope(environment, CommandActor.ACCOUNT, actor.accountId), op, key, paths, body = body, ifMatch = ifMatch)
        commands.execute(command, { identity.lockPrincipal(it, actor) }, {}, replay, mutate)
    }
    private fun <T> read(actor: VerifiedSocialAccount, action: (Connection) -> T): T = safe {
        checkActor(actor); transactions.run { c -> identity.lockPrincipal(c, actor); action(c) }
    }
    private fun checkActor(actor: VerifiedSocialAccount) { if (actor.environment != environment) fail(SocialFailureCode.UNAUTHENTICATED) }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: SocialFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(SocialFailureCode.STORAGE_UNAVAILABLE) }

    private fun circle(c: Connection, id: UUID) = circleOrNull(c, id) ?: fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
    private fun circleOrNull(c: Connection, id: UUID): CircleRow? = query(c, "SELECT * FROM social.circles WHERE environment=? AND id=? FOR UPDATE", {
        setString(1, environment); setObject(2, id)
    }) { if (it.next()) CircleRow(it.getObject("id", UUID::class.java), it.getObject("owner_id", UUID::class.java), it.getString("name"),
        it.getString("description"), it.getString("status"), it.getInt("member_limit"), it.getLong("version"), instant(it, "created_at"), instant(it, "updated_at")) else null }
    private fun member(c: Connection, circleId: UUID, userId: UUID): MemberRow? = query(c,
        "SELECT * FROM social.circle_members WHERE environment=? AND circle_id=? AND user_id=?", {
            setString(1, environment); setObject(2, circleId); setObject(3, userId)
        }) { if (it.next()) memberRow(it) else null }
    private fun memberRow(r: ResultSet) = MemberRow(r.getObject("id", UUID::class.java), r.getObject("circle_id", UUID::class.java),
        r.getObject("user_id", UUID::class.java), r.getString("role"), r.getString("status"), r.getLong("generation"), r.getLong("version"), instant(r, "created_at"), instant(r, "updated_at"),
        r.getObject("removal_key", UUID::class.java), r.getString("removal_operation"), r.getObject("removed_by", UUID::class.java), r.getLong("removed_generation").takeUnless { r.wasNull() })
    private fun memberCount(c: Connection, id: UUID) = query(c, "SELECT count(*) FROM social.circle_members WHERE environment=? AND circle_id=? AND status='active'", {
        setString(1, environment); setObject(2, id)
    }) { it.next(); it.getInt(1) }
    private fun invitation(c: Connection, id: UUID, locked: Boolean = true): InvitationRow = query(c,
        "SELECT * FROM social.circle_invitations WHERE environment=? AND id=?${if (locked) " FOR UPDATE" else ""}", {
            setString(1, environment); setObject(2, id)
        }) { if (it.next()) invitationRow(it) else fail(SocialFailureCode.INVITATION_UNAVAILABLE) }
    private fun findInvitation(c: Connection, tokenHash: String): InvitationRow? = query(c,
        "SELECT * FROM social.circle_invitations WHERE environment=? AND token_hash=?", {
            setString(1, environment); setString(2, tokenHash)
        }) { if (it.next()) invitationRow(it) else null }
    private fun invitationRow(r: ResultSet) = InvitationRow(r.getObject("id", UUID::class.java), r.getObject("circle_id", UUID::class.java),
        r.getObject("created_by", UUID::class.java), r.getString("token_hash"), r.getString("token_key_id"), r.getString("status"),
        r.getObject("consumed_by", UUID::class.java), r.getLong("consumed_generation").takeUnless { r.wasNull() }, r.getLong("version"),
        instant(r, "created_at"), instant(r, "updated_at"), instant(r, "expires_at"), r.getLong("issuer_generation"), r.getLong("issuer_version"))
    private fun bumpCircle(c: Connection, id: UUID) = exec(c, "UPDATE social.circles SET version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=?") {
        setString(1, environment); setObject(2, id)
    }
    private fun updateRole(c: Connection, m: MemberRow, role: String) = exec(c,
        "UPDATE social.circle_members SET role=?,version=version+1,updated_at=clock_timestamp() WHERE environment=? AND circle_id=? AND user_id=?") {
        setString(1, role); setString(2, environment); setObject(3, m.circleId); setObject(4, m.userId)
    }
    private fun removeRow(c: Connection, m: MemberRow, key: UUID? = null, actor: UUID? = null, operation: String? = null) = exec(c,
        "UPDATE social.circle_members SET status='removed',version=version+1,removal_key=?,removed_by=?,removed_generation=?,removal_operation=?,updated_at=clock_timestamp() WHERE environment=? AND circle_id=? AND user_id=?") {
        setObject(1, key); setObject(2, actor); if (key == null) setNull(3, java.sql.Types.BIGINT) else setLong(3, m.generation)
        setString(4, operation); setString(5, environment); setObject(6, m.circleId); setObject(7, m.userId)
    }
    private fun circleJson(c: Connection, row: CircleRow, viewer: MemberRow) = buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("createdAt", row.createdAt.toString()); put("updatedAt", row.updatedAt.toString())
        put("name", row.name); row.description?.let { put("description", it) }; put("role", viewer.role)
        put("memberCount", memberCount(c, row.id)); put("status", row.status)
    }
    private fun memberJson(c: Connection, row: MemberRow): JsonObject {
        val profile = eligibleProfile(c, row.userId)
        return buildJsonObject {
            put("id", row.id.toString()); put("version", row.version); put("createdAt", row.createdAt.toString()); put("updatedAt", row.updatedAt.toString())
            put("user", profile.json()); put("role", row.role); put("status", row.status)
        }
    }
    private fun invitationJson(row: InvitationRow) = buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("createdAt", row.createdAt.toString()); put("updatedAt", row.updatedAt.toString())
        put("targetType", "circle"); put("targetId", row.circleId.toString()); put("expiresAt", row.expiresAt.toString())
        put("remainingUses", if (row.status == "active") 1 else 0); put("status", row.status)
    }
    private fun page(c: Connection, items: List<JsonObject>, cursor: String?) = buildJsonObject {
        put("items", JsonArray(items)); put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", now(c).toString())
    }
    private fun membershipEvent(c: Connection, key: UUID, circle: CircleRow, member: MemberRow, action: String) =
        event(c, key, "circles.membership.changed.v1", "circle", circle.id, circle.version, buildJsonObject {
            put("circleId", circle.id.toString()); put("userId", member.userId.toString()); put("action", action); put("membershipVersion", member.version)
        })
    private fun invitationEvent(c: Connection, key: UUID, row: InvitationRow, action: String) =
        event(c, key, "circles.invitation.changed.v1", "invitation", row.id, row.version, buildJsonObject {
            put("invitationId", row.id.toString()); put("targetType", "circle"); put("targetId", row.circleId.toString()); put("action", action)
        })
    private fun circleEvent(c: Connection, key: UUID, row: CircleRow, action: String) =
        event(c, key, "circles.circle.changed.v1", "circle", row.id, row.version, buildJsonObject { put("circleId", row.id.toString()); put("action", action) })
    private fun event(c: Connection, key: UUID, type: String, aggregate: String, id: UUID, version: Long, body: JsonObject) =
        outbox.append(c, EventDraft(UUID.randomUUID(), type, 1, aggregate, id, version, "circles", key.toString(), key, body))
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { it.next(); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    private fun instant(r: ResultSet, column: String) = r.getObject(column, OffsetDateTime::class.java).toInstant()
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit = {}) { c.prepareStatement(sql).use { it.bind(); check(it.executeUpdate() == 1) } }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, result: (ResultSet) -> T): T =
        c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(result) }
    private class CircleRow(val id: UUID, val ownerId: UUID, val name: String, val description: String?, val status: String,
        val memberLimit: Int, val version: Long, val createdAt: Instant, val updatedAt: Instant)
    private class MemberRow(val id: UUID, val circleId: UUID, val userId: UUID, val role: String, val status: String,
        val generation: Long, val version: Long, val createdAt: Instant, val updatedAt: Instant,
        val removalKey: UUID?, val removalOperation: String?, val removedBy: UUID?, val removedGeneration: Long?)
    private class InvitationRow(val id: UUID, val circleId: UUID, val createdBy: UUID, val tokenHash: String, val keyId: String,
        val status: String, val consumedBy: UUID?, val consumedGeneration: Long?, val version: Long, val createdAt: Instant, val updatedAt: Instant, val expiresAt: Instant,
        val issuerGeneration: Long, val issuerVersion: Long)
    companion object {
        private val validator by lazy { ContractBodyValidator.bundled() }
        private fun uuid(body: JsonObject, name: String) = UUID.fromString(body.getValue(name).jsonPrimitive.content)
        private fun integer(value: JsonElement): Int = try { value.jsonPrimitive.content.toBigDecimal().intValueExact() }
            catch (_: Exception) { fail(SocialFailureCode.INPUT_INVALID) }
        private fun fail(code: SocialFailureCode): Nothing = throw SocialFailure(code)
        private fun unavailablePreview() = buildJsonObject { put("status", "unavailable") }
    }
}
