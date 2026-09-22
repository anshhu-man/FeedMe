package com.feedme.server.social.posts

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.PgTransactions
import com.feedme.server.db.StoredReply
import com.feedme.server.social.*
import java.security.MessageDigest
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Actual viewer-owned reads of persisted posts. No publication receipt/cursor is a grant.
 * Circle roots precede post/material locks. Unlocked candidate discovery is followed by
 * locked exact-state comparison; a changed candidate retries the ENTIRE transaction.
 * PostgreSQL time and principal/content deadlines are rechecked after all waits. This
 * supplies metadata only, never an original object, signed media URL or private recipe pin. */
class AccountPostReadStore(val environment: String, private val transactions: PgTransactions,
    private val identity: AccountSocialIdentityPolicy, private val circles: CirclesStore,
    private val cursors: PostFeedCursors, private val contentAuthority: PostReadContentAuthority,
    val policy: PostReadPolicy) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun getToday(subject: VerifiedSupabaseSubject, device: UUID, cursor: String? = null,
        limit: Int = 20, circleId: UUID? = null): StoredReply =
        page(subject, device, "today", null, circleId, cursor, limit)

    fun getProfilePlate(subject: VerifiedSupabaseSubject, device: UUID, userId: UUID,
        cursor: String? = null, limit: Int = 20): StoredReply =
        page(subject, device, "plate", userId, null, cursor, limit)

    fun getPost(subject: VerifiedSupabaseSubject, device: UUID, postId: UUID, surface: String = "detail"): StoredReply = safe {
        if (surface !in setOf("today", "plate", "detail")) fail(PostReadFailureCode.INPUT_INVALID)
        transactions.run { c ->
            val actor = identity.resolvePrincipal(c, subject, device)
            sameActor(actor)
            val candidate = unique(c, postId) ?: unavailable()
            val state = prepare(c, actor, listOf(candidate))
            val rendered = render(c, actor, candidate, surface, null, state, singlePost = true) ?: unavailable()
            identity.lockPrincipal(c, actor)
            val at = now(c)
            if (!validAt(rendered, at)) unavailable()
            response("getPost", bodyAt(rendered, at), candidate.version)
        }
    }

    /** Remix metadata uses exactly the current ordinary post authority, not the original
     * publication's old audience/capabilities. Caller owns this transaction and final TTL check. */
    internal fun observeRemixPosts(c: Connection, actor: VerifiedSocialAccount, ids: List<UUID>): List<PostRemixObservation> {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        identity.lockPrincipal(c, actor); sameActor(actor)
        val candidates = ids.distinct().mapNotNull { unique(c, it) }
        val prepared = prepare(c, actor, candidates)
        return candidates.mapNotNull { row ->
            render(c, actor, row, "detail", null, prepared, includeSource = false)?.let {
                val at = now(c)
                if (!validAt(it, at)) null else PostRemixObservation(row.id, bodyAt(it, at), it.deadline)
            }
        }
    }

    /** Read ONLY the edge from the hash-pinned original publication. Current versions do
     * not rewrite provenance. Receipt compaction is never replaced by guessing a new edge. */
    internal fun originalRemixParent(c: Connection, postId: UUID): UUID? {
        val row = unique(c, postId) ?: return null
        return query(c, "SELECT p.command_key,p.request_sha256,p.response_sha256,p.published_at," +
            "r.state,r.request_hash,r.response_code,r.response_etag,r.response_json FROM social.post_publications p " +
            "LEFT JOIN platform.idempotency r ON r.principal_scope=? AND r.operation_id='publishPost' AND r.key=p.command_key " +
            "WHERE p.environment=? AND p.owner_user_id=? AND p.post_id=? FOR SHARE OF p NOWAIT", {
                setString(1, "$environment:account:${row.owner}"); setString(2, environment)
                setObject(3, row.owner); setObject(4, row.id)
            }) { r ->
                if (!r.next() || instant(r, "published_at") != row.publishedAt) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
                val pinned = r.getString("response_sha256")
                val receiptText = r.getString("response_json")
                val original = if (receiptText != null) {
                    if (r.getString("state") != "completed" || r.getString("request_hash") != r.getString("request_sha256") ||
                        r.getInt("response_code") != 201 || r.getString("response_etag") != "\"1\"") fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
                    Json.parseToJsonElement(receiptText).jsonObject
                } else if (row.version == 1L && row.content != null) row.content
                else fail(PostReadFailureCode.NOT_CONFIGURED)
                if (r.next() || hash(original) != pinned || validator.validateSchema("Post", original.toString().encodeToByteArray()) != BodyValidationResult.Valid ||
                    uuid(original.getValue("id")) != row.id || uuid(original.getValue("author").jsonObject.getValue("userId")) != row.owner ||
                    original.getValue("version") != JsonPrimitive(1) || original.getValue("status") != JsonPrimitive("published") ||
                    Instant.parse(original.getValue("publishedAt").jsonPrimitive.content) != row.publishedAt)
                    fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
                original["sourcePostId"]?.let(::uuid)
            }
    }

    /** The request writer owns the transaction. This is the SAME viewer/post/material
     * authorization as getPost, with explicit source-only content authority and no action
     * discovery. Retain its deadline and recheck before the request commit. */
    internal fun requireRecipeRequestSource(c: Connection, actor: VerifiedSocialAccount, postId: UUID): PostRecipeRequestSource = safe {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        identity.lockPrincipal(c, actor); sameActor(actor)
        val candidate = unique(c, postId) ?: unavailable()
        val prepared = prepare(c, actor, listOf(candidate))
        val rendered = render(c, actor, candidate, "detail", null, prepared, includeSource = false, sourceOnly = true) ?: unavailable()
        val at = now(c); if (!validAt(rendered, at)) unavailable()
        PostRecipeRequestSource(candidate.owner, candidate.id, candidate.version, bodyAt(rendered, at), rendered.deadline)
    }

    /** Exact authorized material, without recursive action discovery. The caller owns
     * this transaction and must check the returned deadline after its final work. */
    internal fun requireRecipeMaterial(c: Connection, actor: VerifiedSocialAccount, postId: UUID): Pair<PostReadMaterial, Instant> = safe {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        identity.lockPrincipal(c, actor); sameActor(actor)
        val candidate = unique(c, postId) ?: unavailable()
        val prepared = prepare(c, actor, listOf(candidate))
        val rendered = render(c, actor, candidate, "detail", null, prepared, includeSource = false, sourceOnly = true) ?: unavailable()
        val retained = material(c, prepared.rows[candidate.key()] ?: unavailable())
        identity.lockPrincipal(c, actor)
        if (!validAt(rendered, now(c))) unavailable()
        PostReadMaterial(retained.ownerId, retained.postId, retained.version, bodyAt(rendered, now(c)),
            retained.attachment, retained.recipeSnapshot, retained.recipeSha256, retained.media, retained.savePolicy) to rendered.deadline
    }

    /** Explicit DB-only notification source proof, not an authenticated API read. The
     * worker separately proves both real provider accounts. Only a deadline escapes;
     * no post body, user session, URL or action capability is synthesized. */
    internal fun reactionNotificationDeadline(c: Connection, recipient: UUID, viewer: UUID, postId: UUID): Instant? {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        if (recipient == viewer) return null
        val candidate = unique(c, postId) ?: return null
        if (candidate.owner != recipient) return null
        profile(c, viewer) ?: return null
        profile(c, recipient) ?: return null
        try { identity.lockUnblockedPair(c, environment, viewer, recipient) }
        catch (failure: SocialFailure) { if (hidden(failure)) return null else throw failure }
        val (kind, bindings) = audience(candidate)
        var allowed = false
        for ((circle, generation) in bindings.entries.sortedBy { it.key.toString() }) {
            if (circles.reactionNotificationMembership(c, viewer, circle, recipient, generation)) allowed = true
        }
        if (kind != "circles" || !allowed) return null
        val row = unique(c, postId, lock = true) ?: retry()
        if (!sameRow(candidate, row)) retry()
        if (row.status != "published" || row.content == null || !placement(row, "detail", now(c))) return null
        val deadline = try { contentAuthority.authorizeReactionNotification(c, material(c, row)) }
        catch (failure: PostReadFailure) {
            if (failure.code == PostReadFailureCode.POST_UNAVAILABLE && failure.suppressed.isEmpty()) return null else throw failure
        }
        val bounded = if (row.content["keepOnPlate"] == JsonPrimitive(true)) deadline else minOf(deadline, row.expiresAt)
        return bounded.takeIf { now(c).isBefore(it) }
    }

    /** Owner placement commands use the ordinary locked material/moderation/safety proof.
     * Only an exact retained command replay may read its own receipt after removal ended
     * the last placement. That exception is not exposed by any normal reader or capability. */
    internal fun requirePlacementMaterial(c: Connection, actor: VerifiedSocialAccount, postId: UUID,
        requirePlacement: Boolean): PostPlacementObservation = safe {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        identity.lockPrincipal(c, actor); sameActor(actor)
        val candidate = unique(c, postId) ?: unavailable()
        if (candidate.owner != actor.accountId) unavailable()
        val prepared = prepare(c, actor, listOf(candidate))
        val row = prepared.rows[candidate.key()] ?: unavailable()
        if (row.status != "published" || row.content == null ||
            requirePlacement && !placement(row, "detail", now(c))) unavailable()
        val author = profile(c, row.owner) ?: unavailable()
        val material = material(c, row)
        val grant = contentAuthority.authorizeRecipeRequestSource(c, actor, material, "detail")
        var deadline = grant.validUntil
        if (requirePlacement && row.content["keepOnPlate"] != JsonPrimitive(true)) deadline = minOf(deadline, row.expiresAt)
        val fields = row.content.toMutableMap()
        fields["author"] = buildJsonObject {
            put("userId", author.userId.toString()); put("displayName", author.displayName); put("handle", author.handle)
            author.avatarMediaId?.let { put("avatarMediaId", it.toString()) }
        }
        fields["savePolicy"] = material.savePolicy
        fields["reactionCounts"] = grant.reactionCounts
        fields["capabilities"] = JsonArray(emptyList())
        fields.remove("myReaction"); fields.remove("reactionActors"); fields.remove("reactionActorsHasMore")
        // An old source attribution is not a fresh source-read grant.
        fields.remove("sourcePostId")
        identity.lockPrincipal(c, actor)
        if (!now(c).isBefore(deadline)) unavailable()
        PostPlacementObservation(material, JsonObject(fields), deadline)
    }

    /** Media issuance keeps the ordinary viewer/audience/placement/safety path. The caller
     * owns this DB-only transaction and must repeat this observation after external I/O. */
    internal fun requireMediaAccess(c: Connection, subject: VerifiedSupabaseSubject, device: UUID,
        postId: UUID, mediaId: UUID, surface: String, variant: String): com.feedme.server.media.access.PostMediaAccessObservation = safe {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        if (surface !in setOf("today", "plate", "detail") || variant !in setOf("display", "thumbnail"))
            fail(PostReadFailureCode.INPUT_INVALID)
        val actor = identity.resolvePrincipal(c, subject, device); sameActor(actor)
        val candidate = unique(c, postId) ?: unavailable()
        val prepared = prepare(c, actor, listOf(candidate))
        val rendered = render(c, actor, candidate, surface, null, prepared,
            includeSource = false, mediaAccess = true) ?: unavailable()
        val material = material(c, prepared.rows[candidate.key()] ?: unavailable())
        val media = material.media.singleOrNull { it.id == mediaId } ?: unavailable()
        val verified = com.feedme.server.media.processing.PostMediaReadVerifier(environment)
            .verify(c, material.ownerId, mediaId, media.version, media.derivatives)
        if (verified.source.storageProtocol != com.feedme.server.media.SUPABASE_MEDIA_PROTOCOL)
            fail(PostReadFailureCode.NOT_CONFIGURED)
        val output = verified.outputs.singleOrNull { it.variant.wire == variant } ?: unavailable()
        val aclVersion = material.post["aclVersion"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it > 0 } ?: fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        identity.lockPrincipal(c, actor)
        val at = now(c); if (!validAt(rendered, at)) unavailable()
        com.feedme.server.media.access.PostMediaAccessObservation(actor.accountId, material.ownerId,
            postId, material.version, aclVersion, media.version, surface, material.post, output,
            minOf(rendered.deadline, Instant.ofEpochSecond(subject.expiresAtEpochSeconds)), at)
    }

    private fun page(subject: VerifiedSupabaseSubject, device: UUID, surface: String, profile: UUID?,
        circle: UUID?, cursor: String?, limit: Int): StoredReply = safe {
        if (limit !in 1..50) fail(PostReadFailureCode.INPUT_INVALID)
        transactions.run { c ->
            val actor = identity.resolvePrincipal(c, subject, device); sameActor(actor)
            val firstTime = now(c)
            val position = cursor?.let { cursors.decode(it, environment, actor.accountId, surface, circle, profile, limit, firstTime) }
            val upper = position?.upperTime ?: firstTime
            val expiry = position?.expiresAt ?: firstTime.plusSeconds(policy.cursorLifetimeSeconds.toLong())
            if (profile != null) {
                identity.lockUnblockedPair(c, environment, actor.accountId, profile)
                profile(c, profile) ?: unavailable()
            }
            val found = candidates(c, surface, profile, circle, upper, position, policy.maxCandidates + 1)
            val scanned = found.take(policy.maxCandidates)
            val state = prepare(c, actor, scanned)
            val selected = mutableListOf<Rendered>()
            var last: Row? = null
            var consumed = 0
            for (row in scanned) {
                if (selected.size == limit) break
                last = row; consumed++
                render(c, actor, row, surface, circle, state)?.let(selected::add)
            }
            identity.lockPrincipal(c, actor)
            val finalTime = now(c)
            if (!finalTime.isBefore(expiry)) fail(PostReadFailureCode.CURSOR_EXPIRED)
            // A grant/Today placement can expire while later rows are being authorized.
            // Remove only genuine deadline expirations, not dependency/storage failures.
            val items = selected.filter { validAt(it, finalTime) }.map { bodyAt(it, finalTime) }
            val next = last?.takeIf { consumed < found.size }?.let {
                cursors.encode(environment, actor.accountId, surface, circle, profile, limit,
                    it.publishedAt, it.id, it.owner, upper, expiry)
            }
            response(if (surface == "today") "getToday" else "getProfilePlate", buildJsonObject {
                put("items", JsonArray(items)); put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull)
                put("serverTime", finalTime.toString())
            })
        }
    }

    private class Row(val owner: UUID, val id: UUID, val version: Long, val status: String,
        val content: JsonObject?, val publishedAt: Instant, val expiresAt: Instant, val updatedAt: Instant)
    private data class Key(val owner: UUID, val id: UUID)
    private fun Row.key() = Key(owner, id)
    private class Prepared(val rows: Map<Key, Row>, val sources: Map<UUID, Row>,
        val visibleCircles: Map<Key, Set<UUID>>)
    private class Rendered(val body: JsonObject, val deadline: Instant, val sourceDeadline: Instant? = null)
    private fun validAt(post: Rendered, at: Instant) = at.isBefore(post.deadline)
    private fun bodyAt(post: Rendered, at: Instant) =
        if (post.sourceDeadline != null && !at.isBefore(post.sourceDeadline)) JsonObject(post.body - "sourcePostId") else post.body

    private fun prepare(c: Connection, actor: VerifiedSocialAccount, input: List<Row>): Prepared {
        val sources = input.mapNotNull { it.content?.get("sourcePostId")?.let(::uuid) }.distinct()
            .mapNotNull { id -> unique(c, id)?.let { id to it } }.toMap()
        // The permalink contract has no owner field: ambiguous IDs must never pick a first
        // matching owner. Publication generates IDs, but legacy storage's PK is owner-scoped.
        val roots = (input + sources.values).distinctBy { it.key() }.filter { row ->
            unique(c, row.id)?.let { current ->
                if (!sameRow(row, current)) retry()
                true
            } == true
        }
        val bindings = roots.associate { it.key() to audience(it).second }
        val visible = roots.associate { it.key() to mutableSetOf<UUID>() }
        val checks = roots.flatMap { row -> bindings.getValue(row.key()).map { (circle, generation) -> Triple(circle, row, generation) } }
            .sortedWith(compareBy({ it.first.toString() }, { it.second.owner.toString() }, { it.second.id.toString() }))
        for ((circle, row, generation) in checks) {
            if (circles.hasCurrentAudienceMembership(c, actor, circle, row.owner, generation))
                visible.getValue(row.key()).add(circle)
        }
        // NOWAIT avoids acquiring a post/material lock by waiting behind a writer that
        // needs one of our circle/account roots. PostgreSQL retry discards the whole page.
        val locked = roots.sortedWith(compareBy({ it.owner.toString() }, { it.id.toString() })).associate { old ->
            val current = unique(c, old.id, lock = true) ?: retry()
            if (!sameRow(old, current)) retry()
            current.key() to current
        }
        return Prepared(locked, sources, visible.mapValues { it.value.toSet() })
    }

    private fun render(c: Connection, actor: VerifiedSocialAccount, row: Row, surface: String,
        filterCircle: UUID?, state: Prepared, includeSource: Boolean = true, sourceOnly: Boolean = false,
        mediaAccess: Boolean = false, singlePost: Boolean = false): Rendered? {
        val current = state.rows[row.key()] ?: return null
        if (current.status != "published" || current.content == null || !placement(current, surface, now(c))) return null
        val (kind, bindings) = audience(current)
        val allowed = state.visibleCircles[current.key()].orEmpty()
        if (actor.accountId != current.owner && (kind == "self" || allowed.isEmpty())) return null
        if (filterCircle != null && filterCircle !in allowed) return null
        try { identity.lockUnblockedPair(c, environment, actor.accountId, current.owner) }
        catch (failure: SocialFailure) { if (hidden(failure)) return null else throw failure }
        val author = profile(c, current.owner) ?: return null
        val material = material(c, current)
        val grant = try { if (mediaAccess) contentAuthority.authorizeMediaAccess(c, actor, material, surface)
            else if (sourceOnly) contentAuthority.authorizeRecipeRequestSource(c, actor, material, surface)
            else if (singlePost) contentAuthority.authorizeSinglePost(c, actor, material, surface)
            else contentAuthority.authorize(c, actor, material, surface) }
        catch (failure: PostReadFailure) { if (failure.code == PostReadFailureCode.POST_UNAVAILABLE && failure.suppressed.isEmpty()) return null else throw failure }
        val capabilities = grant.capabilities
        if ((actor.accountId != row.owner && capabilities.any { it in setOf("edit", "delete") }) ||
            (material.attachment == null && capabilities.any { it in setOf("makeMine", "saveRecipe", "remix") }) ||
            ("saveRecipe" in capabilities && material.savePolicy["allowFutureSaves"] != JsonPrimitive(true)))
            fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        if (grant.reactionCounts.map { it.jsonObject.getValue("kind").jsonPrimitive.content }.distinct().size != grant.reactionCounts.size)
            fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        var deadline = grant.validUntil
        if (surface == "today" || surface == "detail" && current.content["keepOnPlate"] != JsonPrimitive(true))
            deadline = minOf(deadline, current.expiresAt)
        val fields = current.content.toMutableMap()
        fields["author"] = buildJsonObject {
            put("userId", author.userId.toString()); put("displayName", author.displayName); put("handle", author.handle)
            author.avatarMediaId?.let { put("avatarMediaId", it.toString()) }
        }
        val disclosed = if (actor.accountId == current.owner) bindings.keys else allowed
        fields["audience"] = buildJsonObject {
            put("kind", kind); put("circleIds", JsonArray(disclosed.sortedBy(UUID::toString).map { JsonPrimitive(it.toString()) }))
            put("bindings", JsonArray(disclosed.sortedBy(UUID::toString).map { circle -> buildJsonObject {
                put("circleId", circle.toString()); put("authorMembershipGeneration", bindings.getValue(circle))
            } }))
        }
        fields["capabilities"] = JsonArray(capabilities.sorted().map(::JsonPrimitive))
        fields["reactionCounts"] = grant.reactionCounts
        fields.remove("myReaction"); fields.remove("reactionActors"); fields.remove("reactionActorsHasMore")
        grant.myReaction?.let { fields["myReaction"] = it }
        grant.reactionActors?.let { fields["reactionActors"] = it }
        grant.reactionActorsHasMore?.let { fields["reactionActorsHasMore"] = JsonPrimitive(it) }
        fields["savePolicy"] = material.savePolicy
        fields.remove("sourcePostId")
        var sourceDeadline: Instant? = null
        if (includeSource) current.content["sourcePostId"]?.let { source ->
            val id = uuid(source)
            if (id != current.id) state.sources[id]?.let { raw ->
                render(c, actor, raw, "detail", null, state, includeSource = false)?.let { visible ->
                    if (validAt(visible, now(c))) { fields["sourcePostId"] = source; sourceDeadline = visible.deadline }
                }
            }
        }
        return Rendered(JsonObject(fields), deadline, sourceDeadline)
    }

    private fun material(c: Connection, row: Row): PostReadMaterial {
        val post = row.content ?: unavailable()
        if (validator.validateSchema("Post", post.toString().encodeToByteArray()) != BodyValidationResult.Valid ||
            uuid(post.getValue("id")) != row.id || post.getValue("version").jsonPrimitive.long != row.version ||
            uuid(post.getValue("author").jsonObject.getValue("userId")) != row.owner ||
            post.getValue("status") != JsonPrimitive(row.status) ||
            Instant.parse(post.getValue("publishedAt").jsonPrimitive.content) != row.publishedAt ||
            Instant.parse(post.getValue("createdAt").jsonPrimitive.content) != row.publishedAt ||
            Instant.parse(post.getValue("expiresAt").jsonPrimitive.content) != row.expiresAt ||
            Instant.parse(post.getValue("updatedAt").jsonPrimitive.content) != row.updatedAt ||
            row.expiresAt != row.publishedAt.plusSeconds(86400)) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        val originalRecipeHash = query(c, "SELECT published_at,response_sha256,recipe_sha256 FROM social.post_publications " +
            "WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE NOWAIT", { key(row) }) {
            if (!it.next() || instant(it, "published_at") != row.publishedAt ||
                row.version == 1L && it.getString("response_sha256") != hash(post))
                fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
            it.getString("recipe_sha256").also { _ -> if (it.next()) fail(PostReadFailureCode.STORAGE_UNAVAILABLE) }
        }
        val actualAudience = query(c, "SELECT circle_id,author_membership_generation FROM social.post_audiences " +
            "WHERE environment=? AND owner_user_id=? AND post_id=? ORDER BY circle_id FOR SHARE NOWAIT", { key(row) }) { r ->
            buildMap { while (r.next()) put(r.getObject(1, UUID::class.java), r.getLong(2)) }
        }
        if (actualAudience != audience(row).second) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        val media = query(c, "SELECT media_id,media_version,derivative_set,position FROM social.post_media " +
            "WHERE environment=? AND owner_user_id=? AND post_id=? ORDER BY position FOR SHARE NOWAIT", { key(row) }) { r ->
            buildList { while (r.next()) {
                if (r.getInt("position") != size) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
                add(PostReadMedia(r.getObject("media_id", UUID::class.java), r.getLong("media_version"),
                    Json.parseToJsonElement(r.getString("derivative_set")).jsonObject))
            } }
        }
        if (media.map { it.id } != post.getValue("mediaIds").jsonArray.map(::uuid)) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        var attachment: JsonObject? = null; var recipe: JsonObject? = null; var recipeHash: String? = null
        query(c, "SELECT attachment,recipe_snapshot,recipe_sha256 FROM social.post_attachments " +
            "WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE NOWAIT", { key(row) }) { r ->
            if (r.next()) {
                attachment = Json.parseToJsonElement(r.getString(1)).jsonObject
                recipe = Json.parseToJsonElement(r.getString(2)).jsonObject; recipeHash = r.getString(3)
                if (hash(checkNotNull(recipe)) != recipeHash || r.next()) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        if (attachment != post["attachment"] || recipeHash != originalRecipeHash) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        val save = query(c, "SELECT version,allow_future_saves,disclosure_version,recipe_sha256 FROM social.recipe_save_policies " +
            "WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE NOWAIT", { key(row) }) { r ->
            if (!r.next() || r.getString("recipe_sha256") != recipeHash) fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
            buildJsonObject {
                put("policyVersion", r.getLong("version")); put("allowFutureSaves", r.getBoolean("allow_future_saves"))
                put("disclosureVersion", r.getString("disclosure_version"))
            }.also { if (r.next()) fail(PostReadFailureCode.STORAGE_UNAVAILABLE) }
        }
        return PostReadMaterial(row.owner, row.id, row.version, post, attachment, recipe, recipeHash, media, save)
    }

    private fun audience(row: Row): Pair<String, Map<UUID, Long>> {
        val post = row.content ?: return "self" to emptyMap()
        val value = post.getValue("audience").jsonObject
        val ids = value.getValue("circleIds").jsonArray.map(::uuid)
        val bindings = value["bindings"]?.jsonArray?.map {
            val b = it.jsonObject; uuid(b.getValue("circleId")) to b.getValue("authorMembershipGeneration").jsonPrimitive.long
        }.orEmpty()
        val kind = value.getValue("kind").jsonPrimitive.content
        if (kind !in setOf("self", "circles") || ids.size != ids.distinct().size ||
            bindings.size != bindings.map { it.first }.distinct().size || bindings.any { it.second <= 0 } ||
            ids.toSet() != bindings.map { it.first }.toSet() || (kind == "self") != ids.isEmpty())
            fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        return kind to bindings.toMap()
    }
    private fun placement(row: Row, surface: String, at: Instant): Boolean {
        if (row.publishedAt > at) return false
        val retained = row.content?.get("keepOnPlate") == JsonPrimitive(true)
        return when (surface) { "today" -> at < row.expiresAt; "plate" -> retained; "detail" -> retained || at < row.expiresAt; else -> false }
    }
    private fun profile(c: Connection, owner: UUID): SocialProfileSummary? = try { identity.readProfile(c, environment, owner) }
        catch (failure: SocialFailure) { if (hidden(failure)) null else throw failure }
    private fun hidden(failure: SocialFailure) = failure.code == SocialFailureCode.CIRCLE_UNAVAILABLE && failure.suppressed.isEmpty()
    private fun candidates(c: Connection, surface: String, profile: UUID?, circle: UUID?, upper: Instant,
        position: PostFeedCursorPosition?, count: Int): List<Row> {
        val sql = "SELECT p.* FROM social.posts p WHERE p.environment=? AND p.status='published' AND p.published_at<=? " +
            (if (surface == "plate") "AND p.owner_user_id=? AND p.content->'keepOnPlate'='true'::jsonb " else "AND p.expires_at>clock_timestamp() ") +
            (if (circle != null) "AND EXISTS(SELECT 1 FROM social.post_audiences a WHERE a.environment=p.environment AND a.owner_user_id=p.owner_user_id AND a.post_id=p.id AND a.circle_id=?) " else "") +
            (if (position != null) "AND (p.published_at,p.id,p.owner_user_id)<(?,?,?) " else "") +
            "ORDER BY p.published_at DESC,p.id DESC,p.owner_user_id DESC LIMIT ?"
        return query(c, sql, {
            var n = 1; setString(n++, environment); setObject(n++, OffsetDateTime.ofInstant(upper, java.time.ZoneOffset.UTC))
            if (surface == "plate") setObject(n++, profile)
            if (circle != null) setObject(n++, circle)
            if (position != null) { setObject(n++, OffsetDateTime.ofInstant(position.publishedAt, java.time.ZoneOffset.UTC)); setObject(n++, position.postId); setObject(n++, position.ownerId) }
            setInt(n, count)
        }) { r -> buildList { while (r.next()) add(row(r)) } }
    }
    private fun unique(c: Connection, id: UUID, lock: Boolean = false): Row? = query(c,
        "SELECT * FROM social.posts WHERE environment=? AND id=? ORDER BY owner_user_id LIMIT 2" + if (lock) " FOR SHARE NOWAIT" else "",
        { setString(1, environment); setObject(2, id) }) { r -> if (!r.next()) null else row(r).takeUnless { r.next() } }
    private fun row(r: ResultSet) = Row(r.getObject("owner_user_id", UUID::class.java), r.getObject("id", UUID::class.java),
        r.getLong("version"), r.getString("status"), r.getString("content")?.let { Json.parseToJsonElement(it).jsonObject },
        instant(r, "published_at"), instant(r, "expires_at"), instant(r, "updated_at"))
    private fun sameRow(a: Row, b: Row) = a.owner == b.owner && a.id == b.id && a.version == b.version && a.status == b.status &&
        a.content == b.content && a.publishedAt == b.publishedAt && a.expiresAt == b.expiresAt && a.updatedAt == b.updatedAt
    private fun PreparedStatement.key(row: Row) { setString(1, environment); setObject(2, row.owner); setObject(3, row.id) }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T = try {
        c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
    } catch (failure: SQLException) {
        if (failure.sqlState != "55P03") throw failure
        throw SQLException("Post read dependency contended", "40001").also { replacement -> failure.suppressed.forEach(replacement::addSuppressed) }
    }
    private fun response(operation: String, body: JsonObject, version: Long? = null): StoredReply {
        val bytes = body.toString().encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size > policy.maxResponseBytes || validator.validateResponse(operation, 200, bytes, "application/json") != BodyValidationResult.Valid)
            fail(PostReadFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(200, body, version?.let { "\"$it\"" })
    }
    private fun sameActor(actor: VerifiedSocialAccount) { if (actor.environment != environment) fail(PostReadFailureCode.UNAUTHENTICATED) }
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    private fun instant(r: ResultSet, name: String) = r.getObject(name, OffsetDateTime::class.java).toInstant()
    private fun uuid(value: JsonElement) = UUID.fromString(value.jsonPrimitive.content)
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonical(value.getValue(it)) }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString()
            else BigDecimal(value.content).stripTrailingZeros().toString()
    }
    private fun hash(value: JsonElement) = MessageDigest.getInstance("SHA-256").digest(canonical(value).encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun retry(): Nothing = throw SQLException("Post candidate changed", "40001")
    private fun unavailable(): Nothing = fail(PostReadFailureCode.POST_UNAVAILABLE)
    private fun fail(code: PostReadFailureCode): Nothing = throw PostReadFailure(code)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: PostReadFailure) { throw failure }
        catch (failure: PostFeedCursorFailure) { fail(if (failure.reason == PostFeedCursorFailureReason.EXPIRED) PostReadFailureCode.CURSOR_EXPIRED else PostReadFailureCode.INPUT_INVALID) }
        catch (failure: SocialFailure) { fail(when (failure.code) {
            SocialFailureCode.UNAUTHENTICATED -> PostReadFailureCode.UNAUTHENTICATED
            SocialFailureCode.CIRCLE_UNAVAILABLE -> PostReadFailureCode.POST_UNAVAILABLE
            SocialFailureCode.NOT_CONFIGURED -> PostReadFailureCode.NOT_CONFIGURED
            else -> PostReadFailureCode.STORAGE_UNAVAILABLE
        }) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(PostReadFailureCode.STORAGE_UNAVAILABLE) }
    override fun toString() = "AccountPostReadStore(<redacted>)"
    companion object { private val validator by lazy { ContractBodyValidator.bundled() } }
}
