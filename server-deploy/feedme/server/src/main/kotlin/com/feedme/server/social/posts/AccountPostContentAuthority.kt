package com.feedme.server.social.posts

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.identity.*
import com.feedme.server.media.processing.*
import com.feedme.server.planning.*
import com.feedme.server.social.*
import com.feedme.server.social.drafts.*
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit deployment admission. None of these values constitute editorial approval,
 * image safety, a saved-copy redistribution grant, or consent to publish. */
class AccountPostAdmissionPolicy(val eligibilityPolicyVersion: String, val requiredTermsVersion: String,
    val draftMutationsEnabled: Boolean, val publishingEnabled: Boolean, val saveDisclosureVersion: String,
    val maximumDraftsPerAccount: Int, val maximumPublicationsPer24Hours: Int,
    val mediaSafety: PostReadMediaSafetyPolicy?) {
    init {
        require(listOf(eligibilityPolicyVersion, requiredTermsVersion, saveDisclosureVersion).all {
            it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)
        })
        require(maximumDraftsPerAccount in 1..1000 && maximumPublicationsPer24Hours in 1..1000)
    }
    override fun toString() = "AccountPostAdmissionPolicy(<redacted>)"
}

/** Real transaction-bound authoring authority. Provider/account/device locks precede the
 * caller's command/head/root locks; all circle parents precede block-pair locks; media comes
 * last. No network, nested transaction, accepting fixture, client-selected private principal,
 * or implicit publication occurs. Retained attachment reads are a distinct, non-impersonating
 * purpose: a private Plan proves provenance, while CURRENT catalog publication proves rights.
 * Personal recipes and post-to-post remixes remain unsupported until their real provenance
 * and redistribution authorities are composed. Supabase private-held media stays private. */
internal class AccountPostContentAuthority(private val environment: String,
    private val accounts: AccountProfileStore, private val socialIdentity: AccountSocialIdentityPolicy,
    private val catalog: RecipeCatalogJournal, private val planning: AccountPlanningStore,
    private val policy: AccountPostAdmissionPolicy) {
    private val media = PostMediaReadVerifier(environment)
    private val validator = ContractBodyValidator.bundled()
    init { require(environment == accounts.environment && environment == catalog.environment) }

    val drafts: PostDraftAuthority = object : PostDraftAuthority {
        override fun lockPrincipal(connection: Connection, actor: VerifiedSocialAccount) = draftSafe { lockActor(connection, actor) }
        override fun requireMutationEnabled(connection: Connection, actor: VerifiedSocialAccount) = draftSafe {
            eligible(connection, actor)
            if (!policy.draftMutationsEnabled) fail(PostPublicationFailureCode.NOT_CONFIGURED)
        }
        override fun lockDraftLifecycle(connection: Connection, actor: VerifiedSocialAccount, clientDraftId: UUID,
            forEdit: Boolean): Long = draftSafe { lifecycle(connection, actor, clientDraftId, forEdit, true) }
        override fun authorizeContent(connection: Connection, actor: VerifiedSocialAccount, content: JsonObject,
            newSelection: Boolean) = draftSafe {
            eligible(connection, actor)
            if (newSelection && !policy.draftMutationsEnabled) fail(PostPublicationFailureCode.NOT_CONFIGURED)
            selection(connection, actor, content, requireDisclosure = false)
            lockActor(connection, actor)
        }
    }
    val publication: PostPublicationAuthority = object : PostPublicationAuthority {
        override fun lockPrincipal(connection: Connection, actor: VerifiedSocialAccount) = postSafe { lockActor(connection, actor) }
        override fun requirePublishEnabled(connection: Connection, actor: VerifiedSocialAccount) = postSafe {
            eligible(connection, actor)
            if (!policy.publishingEnabled) fail(PostPublicationFailureCode.NOT_CONFIGURED)
            connection.prepareStatement("SELECT count(*) FROM social.posts WHERE environment=? AND owner_user_id=? " +
                "AND published_at>clock_timestamp()-interval '24 hours'").use {
                it.setString(1, environment); it.setObject(2, actor.accountId)
                it.executeQuery().use { rows ->
                    if (!rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                    if (rows.getLong(1) >= policy.maximumPublicationsPer24Hours) fail(PostPublicationFailureCode.FORBIDDEN)
                }
            }
        }
        override fun lockDraftLifecycle(connection: Connection, actor: VerifiedSocialAccount, clientDraftId: UUID,
            forPublish: Boolean): Long = postSafe { lifecycle(connection, actor, clientDraftId, forPublish, false) }
        override fun authorizeNew(connection: Connection, actor: VerifiedSocialAccount, selection: JsonObject): PostPublicationEvidence = postSafe {
            eligible(connection, actor)
            if (!policy.publishingEnabled) fail(PostPublicationFailureCode.NOT_CONFIGURED)
            val checked = this@AccountPostContentAuthority.selection(connection, actor, selection)
            val author = socialIdentity.readProfile(connection, environment, actor.accountId).json()
            lockActor(connection, actor)
            PostPublicationEvidence(author, checked.first, selection["attachment"] as? JsonObject,
                checked.second, setOf("view"), deadline(connection, actor))
        }
        override fun authorizeReadyMedia(connection: Connection, actor: VerifiedSocialAccount, mediaId: UUID,
            version: Long, derivatives: JsonObject): Instant = postSafe {
            lockActor(connection, actor)
            val safety = policy.mediaSafety ?: fail(PostPublicationFailureCode.NOT_CONFIGURED)
            val exact = media.verify(connection, actor.accountId, mediaId, version, derivatives)
            if (exact.policyRevision != safety.processingRevision || exact.codecRevision != safety.codecRevision)
                fail(PostPublicationFailureCode.MEDIA_UNAVAILABLE)
            val grant = MediaSafetyRecords.requireCurrent(connection, exact.source, exact.jobId, safety.processingRevision,
                safety.codecRevision, safety.safetyRevision, exact.outputs, exact.stage, exact.safetyMediaVersion)
            val until = minOf(grant.validUntil, deadline(connection, actor))
            if (now(connection) >= until) fail(PostPublicationFailureCode.MEDIA_UNAVAILABLE)
            until
        }
        override fun authorizeReplay(connection: Connection, actor: VerifiedSocialAccount, originalPost: JsonObject): Instant = postSafe {
            eligible(connection, actor)
            if (originalPost.text("status") != "published" || originalPost.obj("author").id("userId") != actor.accountId)
                fail(PostPublicationFailureCode.FORBIDDEN)
            moderation(connection, actor.accountId, originalPost.id("id"))
            rejectRemix(originalPost)
            val memberships = audience(connection, actor, originalPost.obj("audience"))
            val bindings = originalPost.obj("audience")["bindings"] as? JsonArray ?: JsonArray(emptyList())
            val retained = bindings.map { value ->
                val item = value as? JsonObject ?: fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                item.id("circleId") to (item["authorMembershipGeneration"]?.jsonPrimitive?.longOrNull
                    ?: fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE))
            }
            if (retained.size != memberships.size || retained.toMap() != memberships)
                fail(PostPublicationFailureCode.FORBIDDEN)
            originalPost["attachment"]?.let {
                val attachment = it as? JsonObject ?: fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                connection.prepareStatement("SELECT attachment,recipe_snapshot FROM social.post_attachments " +
                    "WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE NOWAIT").use { statement ->
                    statement.setString(1, environment); statement.setObject(2, actor.accountId); statement.setObject(3, originalPost.id("id"))
                    statement.executeQuery().use { rows ->
                        if (!rows.next() || Json.parseToJsonElement(rows.getString(1)) != attachment)
                            fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                        verifyRetainedAttachment(connection, actor.accountId, attachment, Json.parseToJsonElement(rows.getString(2)).jsonObject)
                        if (rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                    }
                }
            }
            lockActor(connection, actor)
            deadline(connection, actor)
        }
    }

    fun resolvePrincipal(connection: Connection, subject: VerifiedSupabaseSubject, device: UUID): VerifiedSocialAccount = postSafe {
        transaction(connection)
        VerifiedSocialAccount.resolveSafety(connection, accounts, subject, device).also { lockActor(connection, it) }
    }

    /** Only for actual retained post_attachments material, not an endpoint accepting an owner
     * UUID. No expired author's bearer is synthesized and no new private Plan grant is issued. */
    fun verifyRetainedAttachment(connection: Connection, ownerAccountId: UUID, attachment: JsonObject,
        recipeSnapshot: JsonObject) = postSafe {
        transaction(connection); attachmentShape(attachment)
        val recipe = if (attachment.containsKey("recipeVersionId")) {
            if (changes(attachment).isNotEmpty()) fail(PostPublicationFailureCode.FORBIDDEN)
            publishedRecipe(connection, attachment.id("recipeVersionId"))
        } else {
            val plan = retainedPlan(connection, ownerAccountId, attachment.id("planId"))
            planRecipe(connection, attachment, plan)
        }
        if (recipe != recipeSnapshot) fail(PostPublicationFailureCode.FORBIDDEN)
    }

    private fun selection(c: Connection, actor: VerifiedSocialAccount, value: JsonObject,
        requireDisclosure: Boolean = true): Pair<Map<UUID, Long>, JsonObject?> {
        rejectRemix(value)
        // An unfinished draft can omit this optional field; publication and an explicit
        // future-save selection must carry the exact reviewed disclosure version.
        if ((requireDisclosure || value.containsKey("saveDisclosureVersion") ||
                value["allowRecipeSaves"]?.jsonPrimitive?.booleanOrNull == true) &&
            value.text("saveDisclosureVersion") != policy.saveDisclosureVersion) fail(PostPublicationFailureCode.FORBIDDEN)
        val memberships = audience(c, actor, value.obj("audience"))
        val attachment = value["attachment"] as? JsonObject
        if (value["allowRecipeSaves"]?.jsonPrimitive?.booleanOrNull == true && attachment == null)
            fail(PostPublicationFailureCode.INPUT_INVALID)
        val snapshot = attachment?.let {
            attachmentShape(it)
            if (it.containsKey("recipeVersionId")) {
                if (changes(it).isNotEmpty()) fail(PostPublicationFailureCode.FORBIDDEN)
                publishedRecipe(c, it.id("recipeVersionId"))
            } else planning.withOwnedTransaction(c, actor.providerSubject ?: fail(PostPublicationFailureCode.UNAUTHENTICATED),
                actor.deviceSessionId) { principal, plans, checkAt ->
                val result = planRecipe(c, it, plans.readOwnedPostPlan(c, principal, it.id("planId")))
                checkAt(now(c)); result
            }
        }
        return memberships to snapshot
    }

    private fun planRecipe(c: Connection, attachment: JsonObject, plan: JsonObject): JsonObject {
        if (validator.validateSchema("Plan", plan.toString().encodeToByteArray()) != BodyValidationResult.Valid ||
            plan.id("id") != attachment.id("planId") || plan.text("status") != "ready") fail(PostPublicationFailureCode.FORBIDDEN)
        rejectRemix(plan)
        val explanations = (plan["changes"] as? JsonArray ?: fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)).map {
            (it as? JsonObject ?: fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)).text("explanation")
        }
        if (changes(attachment) != explanations) fail(PostPublicationFailureCode.FORBIDDEN)
        val retained = plan.obj("recipeSnapshot")
        val current = publishedRecipe(c, plan.id("recipeVersionId"))
        // No private transformation is relabelled as the catalog's reviewed original.
        if (retained != current) fail(PostPublicationFailureCode.FORBIDDEN)
        return current
    }

    private fun retainedPlan(c: Connection, owner: UUID, planId: UUID): JsonObject {
        val principal = c.prepareStatement("SELECT p.id FROM identity.users u JOIN identity.principals p " +
            "ON p.environment=u.environment AND p.user_id=u.id WHERE u.environment=? AND u.id=? " +
            "AND u.status='active' AND p.status='active' FOR SHARE OF u,p NOWAIT").use {
            it.setString(1, environment); it.setObject(2, owner)
            it.executeQuery().use { rows ->
                if (!rows.next()) fail(PostPublicationFailureCode.FORBIDDEN)
                rows.getObject(1, UUID::class.java).also { if (rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE) }
            }
        }
        return c.prepareStatement("SELECT snapshot_text,snapshot_hash,status,recipe_version_id FROM planning.plans " +
            "WHERE environment=? AND actor_kind='account' AND principal_id=? AND id=? FOR SHARE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, principal); it.setObject(3, planId)
            it.executeQuery().use { rows ->
                if (!rows.next()) fail(PostPublicationFailureCode.FORBIDDEN)
                val text = rows.getString(1)
                if (text.encodeToByteArray().size > 262144 || digest(text) != rows.getString(2) || rows.getString(3) != "ready")
                    fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                val result = Json.parseToJsonElement(text).jsonObject
                if (result.id("id") != planId || result.id("recipeVersionId") != rows.getObject(4, UUID::class.java) || rows.next())
                    fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                result
            }
        }
    }

    private fun attachmentShape(value: JsonObject) {
        if (validator.validateSchema("Attachment", value.toString().encodeToByteArray()) != BodyValidationResult.Valid)
            fail(PostPublicationFailureCode.INPUT_INVALID)
        if (value.containsKey("personalRecipe")) fail(PostPublicationFailureCode.NOT_CONFIGURED)
        if (value.text("rightsBasis") != "catalogRedistributable" || value.text("reviewStatus") != "reviewed" ||
            listOf("recipeVersionId", "planId").count(value::containsKey) != 1) fail(PostPublicationFailureCode.FORBIDDEN)
    }
    private fun publishedRecipe(c: Connection, id: UUID): JsonObject {
        val view = catalog.openView(c)
        val entry = view.lookupCurrent(id)?.entry ?: fail(PostPublicationFailureCode.FORBIDDEN)
        if (entry.recipe.text("reviewStatus") != "published" || entry.recipe.text("contentLicense") != "catalogRedistributable" ||
            entry.review["freeCatalogEligible"]?.jsonPrimitive?.booleanOrNull != true || entry.recall != null || entry.rightsReference.isBlank())
            fail(PostPublicationFailureCode.FORBIDDEN)
        view.checkCurrent()
        return entry.recipe
    }
    private fun audience(c: Connection, actor: VerifiedSocialAccount, value: JsonObject): Map<UUID, Long> {
        val ids = (value["circleIds"] as? JsonArray ?: fail(PostPublicationFailureCode.INPUT_INVALID)).map {
            uuid((it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: fail(PostPublicationFailureCode.INPUT_INVALID))
        }
        if (ids.distinct().size != ids.size || value.text("kind") !in setOf("self", "circles") ||
            (value.text("kind") == "self") != ids.isEmpty()) fail(PostPublicationFailureCode.INPUT_INVALID)
        val owners = ids.sortedBy(UUID::toString).associateWith { circle ->
            c.prepareStatement("SELECT owner_id,status FROM social.circles WHERE environment=? AND id=? FOR SHARE NOWAIT").use {
                it.setString(1, environment); it.setObject(2, circle)
                it.executeQuery().use { rows ->
                    if (!rows.next() || rows.getString(2) != "active") fail(PostPublicationFailureCode.FORBIDDEN)
                    rows.getObject(1, UUID::class.java).also { if (rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE) }
                }
            }
        }
        val result = owners.mapValues { (circle, _) ->
            c.prepareStatement("SELECT generation,status FROM social.circle_members WHERE environment=? AND circle_id=? AND user_id=? FOR SHARE NOWAIT").use {
                it.setString(1, environment); it.setObject(2, circle); it.setObject(3, actor.accountId)
                it.executeQuery().use { rows ->
                    if (!rows.next() || rows.getString(2) != "active" || rows.getLong(1) <= 0) fail(PostPublicationFailureCode.FORBIDDEN)
                    rows.getLong(1).also { if (rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE) }
                }
            }
        }
        owners.values.distinct().sortedBy(UUID::toString).forEach {
            socialIdentity.readProfile(c, environment, it)
            socialIdentity.lockUnblockedPair(c, environment, actor.accountId, it)
        }
        return result
    }

    private fun lifecycle(c: Connection, actor: VerifiedSocialAccount, client: UUID, mutable: Boolean, draft: Boolean): Long {
        lockActor(c, actor)
        if (mutable) {
            eligible(c, actor)
            if (if (draft) !policy.draftMutationsEnabled else !policy.publishingEnabled) fail(PostPublicationFailureCode.NOT_CONFIGURED)
        }
        c.prepareStatement("SELECT revision FROM platform.post_draft_heads WHERE environment=? AND owner_user_id=? FOR UPDATE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, actor.accountId)
            it.executeQuery().use { rows -> if (rows.next() && rows.getLong(1) <= 0) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE) }
        }
        if (mutable) c.prepareStatement("INSERT INTO platform.media_draft_lifecycles(environment,owner_user_id,client_draft_id,generation,created_at) " +
            "VALUES(?,?,?,1,clock_timestamp()) ON CONFLICT(environment,owner_user_id,client_draft_id) DO NOTHING").use {
            it.setString(1, environment); it.setObject(2, actor.accountId); it.setObject(3, client); it.executeUpdate()
        }
        val generation = c.prepareStatement("SELECT generation FROM platform.media_draft_lifecycles WHERE environment=? " +
            "AND owner_user_id=? AND client_draft_id=? FOR UPDATE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, actor.accountId); it.setObject(3, client)
            it.executeQuery().use { rows ->
                if (!rows.next()) fail(PostPublicationFailureCode.DRAFT_UNAVAILABLE)
                rows.getLong(1).also { value -> if (value <= 0 || rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE) }
            }
        }
        val exists = c.prepareStatement("SELECT draft_generation,status,published_post_id,expires_at>clock_timestamp() FROM platform.post_drafts " +
            "WHERE environment=? AND owner_user_id=? AND client_draft_id=? FOR SHARE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, actor.accountId); it.setObject(3, client)
            it.executeQuery().use { rows ->
                if (!rows.next()) false else {
                    if (rows.getLong(1) != generation) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                    if (mutable && (rows.getString(2) != "draft" || rows.getObject(3) != null || !rows.getBoolean(4)))
                        fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
                    if (rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                    true
                }
            }
        }
        if (mutable && PostPublicationLifecycle.isPublished(c, environment, actor.accountId, client))
            fail(PostPublicationFailureCode.PUBLICATION_CONFLICT)
        if (mutable && draft && !exists) c.prepareStatement("SELECT count(*) FROM platform.post_drafts " +
            "WHERE environment=? AND owner_user_id=? AND status='draft' AND expires_at>clock_timestamp()").use {
            it.setString(1, environment); it.setObject(2, actor.accountId)
            it.executeQuery().use { rows ->
                if (!rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                if (rows.getLong(1) >= policy.maximumDraftsPerAccount) fail(PostPublicationFailureCode.FORBIDDEN)
            }
        }
        lockActor(c, actor)
        return generation
    }
    private fun eligible(c: Connection, actor: VerifiedSocialAccount) {
        lockActor(c, actor); socialIdentity.lockPrincipal(c, actor)
        c.prepareStatement("SELECT eligibility_policy_version,terms_version FROM identity.users WHERE environment=? AND id=? FOR SHARE NOWAIT").use {
            it.setString(1, environment); it.setObject(2, actor.accountId)
            it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString(1) != policy.eligibilityPolicyVersion || rows.getString(2) != policy.requiredTermsVersion)
                    fail(PostPublicationFailureCode.FORBIDDEN)
            }
        }
        moderation(c, actor.accountId, null)
    }
    private fun moderation(c: Connection, owner: UUID, post: UUID?) {
        PostgresPostReadContentAuthority.lockModeration(c)
        c.prepareStatement("SELECT EXISTS(SELECT 1 FROM safety.moderation_cases WHERE environment=? AND action IN('hide','remove','suspend') " +
            "AND ((target_type='user' AND target_id=?) OR (target_type='post' AND target_id=?)))").use {
            it.setString(1, environment); it.setObject(2, owner); it.setObject(3, post)
            it.executeQuery().use { rows ->
                if (!rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
                if (rows.getBoolean(1)) fail(PostPublicationFailureCode.FORBIDDEN)
            }
        }
    }
    private fun lockActor(c: Connection, actor: VerifiedSocialAccount) {
        transaction(c)
        val subject = actor.providerSubject ?: fail(PostPublicationFailureCode.UNAUTHENTICATED)
        if (actor.environment != environment || accounts.lockAccountSafety(c, subject, actor.deviceSessionId) != actor.accountId)
            fail(PostPublicationFailureCode.UNAUTHENTICATED)
    }
    private fun deadline(c: Connection, actor: VerifiedSocialAccount): Instant {
        val at = now(c)
        val expires = Instant.ofEpochSecond(actor.providerSubject?.expiresAtEpochSeconds ?: fail(PostPublicationFailureCode.UNAUTHENTICATED))
        if (at >= expires) fail(PostPublicationFailureCode.UNAUTHENTICATED)
        return minOf(at.plusSeconds(60), expires)
    }
    private fun transaction(c: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Post account operation interrupted")
        require(!c.isClosed && !c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
    }
    private fun now(c: Connection): Instant = c.createStatement().use { it.executeQuery("SELECT clock_timestamp()").use { rows ->
        if (!rows.next()) fail(PostPublicationFailureCode.STORAGE_UNAVAILABLE)
        rows.getObject(1, OffsetDateTime::class.java).toInstant()
    } }
    private fun rejectRemix(value: JsonObject) {
        if (value["sourcePostId"] != null && value["sourcePostId"] != JsonNull) fail(PostPublicationFailureCode.NOT_CONFIGURED)
    }
    private fun changes(value: JsonObject): List<String> = (value["confirmedChanges"] as? JsonArray
        ?: fail(PostPublicationFailureCode.INPUT_INVALID)).map {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: fail(PostPublicationFailureCode.INPUT_INVALID)
    }
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: fail(PostPublicationFailureCode.INPUT_INVALID)
    private fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: fail(PostPublicationFailureCode.INPUT_INVALID)
    private fun JsonObject.id(key: String): UUID = uuid(text(key))
    private fun uuid(value: String): UUID = try { UUID.fromString(value).also { if (it.toString() != value) fail(PostPublicationFailureCode.INPUT_INVALID) } }
        catch (_: IllegalArgumentException) { fail(PostPublicationFailureCode.INPUT_INVALID) }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    private fun fail(code: PostPublicationFailureCode): Nothing = throw PostPublicationFailure(code)
    private fun <T> postSafe(action: () -> T): T = try { action() }
        catch (failure: AccountFailure) { mapped(failure, when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED -> PostPublicationFailureCode.UNAUTHENTICATED
            AccountFailureCode.NOT_CONFIGURED -> PostPublicationFailureCode.NOT_CONFIGURED
            AccountFailureCode.STORAGE_UNAVAILABLE -> PostPublicationFailureCode.STORAGE_UNAVAILABLE
            else -> PostPublicationFailureCode.FORBIDDEN
        }) }
        catch (failure: SocialFailure) { mapped(failure, when (failure.code) {
            SocialFailureCode.UNAUTHENTICATED -> PostPublicationFailureCode.UNAUTHENTICATED
            SocialFailureCode.NOT_CONFIGURED -> PostPublicationFailureCode.NOT_CONFIGURED
            SocialFailureCode.STORAGE_UNAVAILABLE -> PostPublicationFailureCode.STORAGE_UNAVAILABLE
            else -> PostPublicationFailureCode.FORBIDDEN
        }) }
        catch (failure: PlanningServiceFailure) { mapped(failure, when (failure.code) {
            PlanningFailureCode.UNAUTHENTICATED -> PostPublicationFailureCode.UNAUTHENTICATED
            PlanningFailureCode.NOT_CONFIGURED -> PostPublicationFailureCode.NOT_CONFIGURED
            PlanningFailureCode.STORAGE_UNAVAILABLE -> PostPublicationFailureCode.STORAGE_UNAVAILABLE
            else -> PostPublicationFailureCode.FORBIDDEN
        }) }
        catch (failure: RecipeCatalogFailure) { mapped(failure, if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
            PostPublicationFailureCode.NOT_CONFIGURED else PostPublicationFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: MediaProcessingFailure) { mapped(failure, when (failure.code) {
            MediaProcessingFailureCode.NOT_CONFIGURED -> PostPublicationFailureCode.NOT_CONFIGURED
            MediaProcessingFailureCode.CONFLICT, MediaProcessingFailureCode.OBJECT_MISMATCH,
            MediaProcessingFailureCode.SAFETY_UNAVAILABLE -> PostPublicationFailureCode.MEDIA_UNAVAILABLE
            else -> PostPublicationFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: SQLException) {
            if (failure.sqlState != "55P03") throw failure
            throw SQLException("Post authority is contended", "40001").also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
        }
    private fun mapped(failure: Throwable, code: PostPublicationFailureCode): Nothing =
        throw PostPublicationFailure(code).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
    private fun <T> draftSafe(action: () -> T): T = try { postSafe(action) } catch (failure: PostPublicationFailure) {
        throw PostDraftFailure(when (failure.code) {
            PostPublicationFailureCode.INPUT_INVALID -> PostDraftFailureCode.INPUT_INVALID
            PostPublicationFailureCode.UNAUTHENTICATED -> PostDraftFailureCode.UNAUTHENTICATED
            PostPublicationFailureCode.FORBIDDEN -> PostDraftFailureCode.FORBIDDEN
            PostPublicationFailureCode.DRAFT_UNAVAILABLE -> PostDraftFailureCode.DRAFT_UNAVAILABLE
            PostPublicationFailureCode.DRAFT_EXPIRED -> PostDraftFailureCode.DRAFT_EXPIRED
            PostPublicationFailureCode.NOT_CONFIGURED -> PostDraftFailureCode.NOT_CONFIGURED
            PostPublicationFailureCode.STORAGE_UNAVAILABLE -> PostDraftFailureCode.STORAGE_UNAVAILABLE
            else -> PostDraftFailureCode.DRAFT_CONFLICT
        }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
    }
    override fun toString() = "AccountPostContentAuthority(<redacted>)"
}
