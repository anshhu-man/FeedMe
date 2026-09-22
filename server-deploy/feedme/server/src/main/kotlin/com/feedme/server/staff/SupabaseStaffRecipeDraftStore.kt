package com.feedme.server.staff

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.db.*
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Real unpublished authoring and optional independent editorial review, separate
 * from the public recipe journal. Full-body edits and lifecycle actions create
 * immutable revision/audit rows; no copy licence or publication is manufactured.
 * Each invocation (including replay)
 * obtains the real current Supabase staff admission in its one owned transaction. */
internal class SupabaseStaffRecipeDraftStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val auth: SupabaseStaffAdmissionStore,
    private val maxDraftBytes: Int = 65536,
    private val maxResponseBytes: Int = 262144,
    private val reviews: SupabaseStaffRecipeReviewWorkflow? = null,
    private val publications: SupabaseStaffRecipePublicationWorkflow? = null,
) {
    private val commands = DurableCommands(transactions)
    init {
        require(auth.isBoundTo(environment, transactions))
        require(reviews == null || reviews.isBoundTo(environment, transactions))
        require(publications == null || publications.isBoundTo(environment, transactions))
        require(maxDraftBytes in 1024..65536 && maxResponseBytes in (maxDraftBytes + 2048)..262144)
    }

    fun adminCreateRecipe(subject: VerifiedSupabaseSubject, key: UUID, body: JsonObject): CommandResult =
        write(subject, "adminCreateRecipe", key, null, null, null, validated(body))
    fun adminUpdateRecipe(subject: VerifiedSupabaseSubject, key: UUID, recipeId: UUID, recipeVersionId: UUID,
        ifMatch: String, body: JsonObject): CommandResult =
        write(subject, "adminUpdateRecipe", key, recipeId, recipeVersionId, ifMatch, validated(body))
    fun adminSubmitRecipe(subject: VerifiedSupabaseSubject, key: UUID, recipeId: UUID, recipeVersionId: UUID,
        ifMatch: String, body: JsonObject): CommandResult =
        write(subject, SupabaseStaffRecipeReviewWorkflow.SUBMIT, key, recipeId, recipeVersionId, ifMatch,
            validatedWorkflow(SupabaseStaffRecipeReviewWorkflow.SUBMIT, body))
    fun adminReviewRecipe(subject: VerifiedSupabaseSubject, key: UUID, recipeId: UUID, recipeVersionId: UUID,
        ifMatch: String, body: JsonObject): CommandResult =
        write(subject, SupabaseStaffRecipeReviewWorkflow.REVIEW, key, recipeId, recipeVersionId, ifMatch,
            validatedWorkflow(SupabaseStaffRecipeReviewWorkflow.REVIEW, body))
    fun adminPublishRecipe(subject: VerifiedSupabaseSubject, key: UUID, recipeId: UUID, recipeVersionId: UUID,
        ifMatch: String, body: JsonObject): CommandResult =
        write(subject, SupabaseStaffRecipePublicationWorkflow.PUBLISH, key, recipeId, recipeVersionId, ifMatch,
            validatedPublication(SupabaseStaffRecipePublicationWorkflow.PUBLISH, body))
    fun adminRecallRecipe(subject: VerifiedSupabaseSubject, key: UUID, recipeId: UUID, recipeVersionId: UUID,
        ifMatch: String, body: JsonObject): CommandResult =
        write(subject, SupabaseStaffRecipePublicationWorkflow.RECALL, key, recipeId, recipeVersionId, ifMatch,
            validatedPublication(SupabaseStaffRecipePublicationWorkflow.RECALL, body))

    fun adminGetRecipe(subject: VerifiedSupabaseSubject, recipeId: UUID, recipeVersionId: UUID): JsonObject =
        adminGetRecipeObservation(subject, recipeId, recipeVersionId).body
    fun adminGetRecipeObservation(subject: VerifiedSupabaseSubject, recipeId: UUID, recipeVersionId: UUID): SupabaseStaffRecipeObservation = safe {
        auth.withCatalogAccess(subject, write = false) { c, actor ->
            bound(c, actor)
            val head = head(c, recipeId, recipeVersionId)
            val revision = revision(c, head, head.version)
            current(c, actor)
            val owner = actor.canPublish && head.author == actor.actorId
            val status = revision.snapshot.getValue("reviewStatus").jsonPrimitive.content
            val workflow = auth.policy.catalogReviewsEnabled && reviews != null
            val publishing = auth.policy.catalogPublicationEnabled && publications != null && actor.canPublish
            val review = revision.snapshot["latestReview"] as? JsonObject
            SupabaseStaffRecipeObservation(revision.snapshot, owner && status in setOf("draft", "changesRequested"),
                workflow && owner && status == "draft", workflow && actor.canReview && head.author != actor.actorId && status == "inReview",
                publishing && status == "approved" && review?.get("professionalReview") is JsonObject &&
                    review?.get("reviewerId") != JsonPrimitive(actor.actorId.toString()),
                publishing && status in setOf("published", "retired"))
        }
    }

    fun adminListRecipes(subject: VerifiedSupabaseSubject, q: String? = null, cursor: String? = null, limit: Int = 20): JsonObject = safe {
        if (limit !in 1..50 || q?.let { it.length > 100 || it.any(Char::isISOControl) } == true) invalid()
        auth.withCatalogAccess(subject, write = false) { c, actor ->
            bound(c, actor)
            val after = cursor?.let { decodeCursor(it, actor, q) }
            val heads = c.prepareStatement("SELECT h.environment,h.recipe_id,h.id,h.author_id,h.current_version,h.created_at,h.updated_at " +
                "FROM ONLY staff.recipe_drafts h JOIN ONLY staff.recipe_draft_revisions r " +
                "ON r.environment=h.environment AND r.draft_id=h.id AND r.version=h.current_version " +
                "WHERE h.environment=? AND (?::uuid IS NULL OR h.id>?::uuid) " +
                "AND (?::text IS NULL OR position(?::text in r.snapshot_text::jsonb->>'title')>0) " +
                "ORDER BY h.id LIMIT ? FOR SHARE OF h,r").use { s ->
                s.setString(1, environment); s.setObject(2, after); s.setObject(3, after)
                s.setString(4, q); s.setString(5, q); s.setInt(6, limit + 1)
                s.executeQuery().use { r -> buildList { while (r.next()) add(decodeHead(r)) } }
            }
            val items = mutableListOf<JsonObject>(); var next: String? = null
            for ((index, h) in heads.withIndex()) {
                if (index == limit) { next = encodeCursor(actor, q, heads[index - 1].id); break }
                val item = revision(c, h, h.version).snapshot
                val proposed = page(items + item, null, actor.checkedAt)
                if (proposed.toString().encodeToByteArray().size + 1024 > maxResponseBytes) {
                    if (items.isEmpty()) fail(SupabaseStaffRecipeFailureCode.RESPONSE_TOO_LARGE)
                    next = encodeCursor(actor, q, heads[index - 1].id); break
                }
                items += item
            }
            val at = current(c, actor)
            page(items, next, at).also { response("RecipeVersionPage", it) }
        }
    }

    private fun write(subject: VerifiedSupabaseSubject, operation: String, key: UUID, recipeId: UUID?, id: UUID?,
        ifMatch: String?, input: JsonObject): CommandResult = safe {
        val expected = ifMatch?.let(::version)
        if ((operation != "adminCreateRecipe") != (recipeId != null && id != null && expected != null)) invalid()
        val reviewOperation = operation == SupabaseStaffRecipeReviewWorkflow.REVIEW
        if (operation in WORKFLOW_OPERATIONS) workflow()
        if (operation in PUBLICATION_OPERATIONS) publication()
        auth.withCatalogAccess(subject, write = !reviewOperation, requireReview = reviewOperation) { c, actor ->
            bound(c, actor)
            val identity = CommandIdentity(PrincipalScope(environment, CommandActor.STAFF, actor.actorId), operation, key,
                if (id == null) emptyMap() else mapOf("recipeId" to recipeId.toString(), "recipeVersionId" to id.toString()),
                body = input, ifMatch = ifMatch)
            var accepted: Revision? = null
            val result = commands.executeInTransaction(c, identity,
                validatePrincipal = { actual -> sameConnection(c, actual); current(c, actor) },
                authorizeNew = { actual -> sameConnection(c, actual); current(c, actor) },
                authorizeReplay = { actual, reply ->
                    sameConnection(c, actual); current(c, actor)
                    val body = reply.body as? JsonObject ?: unavailable()
                    val resolvedId = if (reviewOperation) requireNotNull(id) else UUID.fromString(body.getValue("id").jsonPrimitive.content)
                    val resolvedRecipe = if (reviewOperation) requireNotNull(recipeId) else UUID.fromString(body.getValue("recipeId").jsonPrimitive.content)
                    if (id != null && (resolvedId != id || resolvedRecipe != recipeId)) unavailable()
                    val h = head(c, resolvedRecipe, resolvedId); authorize(actor, h, operation)
                    val rev = revision(c, h, body.getValue("version").jsonPrimitive.long)
                    requireOriginal(identity, input, reply, rev)
                    accepted = rev
                }, mutate = { actual ->
                    sameConnection(c, actual)
                    val h: Head
                    var before: Revision? = null
                    if (id == null) {
                        val at = current(c, actor)
                        h = Head(environment, UUID.randomUUID(), UUID.randomUUID(), actor.actorId, 1, at, at)
                        c.prepareStatement("INSERT INTO staff.recipe_drafts(environment,recipe_id,id,author_id,current_version,created_at,updated_at) " +
                            "VALUES(?,?,?,?,1,?,?)").use { s ->
                            s.setString(1, environment); s.setObject(2, h.recipeId); s.setObject(3, h.id); s.setObject(4, h.author)
                            s.setObject(5, time(h.createdAt)); s.setObject(6, time(h.updatedAt)); if (s.executeUpdate() != 1) unavailable()
                        }
                    } else {
                        val old = head(c, requireNotNull(recipeId), id, exclusive = true); authorize(actor, old, operation)
                        before = revision(c, old, old.version)
                        if (old.version != expected) fail(SupabaseStaffRecipeFailureCode.VERSION_CONFLICT)
                        if (operation == "adminUpdateRecipe" && before.snapshot.getValue("reviewStatus").jsonPrimitive.content !in setOf("draft", "changesRequested"))
                            fail(SupabaseStaffRecipeFailureCode.STATE_CONFLICT)
                        if (old.version == Long.MAX_VALUE) unavailable()
                        val at = current(c, actor)
                        if (at < old.updatedAt) unavailable()
                        h = old.copy(version = old.version + 1, updatedAt = at)
                        c.prepareStatement("UPDATE staff.recipe_drafts SET current_version=?,updated_at=? " +
                            "WHERE environment=? AND recipe_id=? AND id=? AND author_id=? AND current_version=?").use { s ->
                            s.setLong(1, h.version); s.setObject(2, time(at)); s.setString(3, environment); s.setObject(4, h.recipeId)
                            s.setObject(5, h.id); s.setObject(6, h.author); s.setLong(7, old.version); if (s.executeUpdate() != 1) unavailable()
                        }
                    }
                    val snapshot = when (operation) {
                        SupabaseStaffRecipeReviewWorkflow.SUBMIT -> workflow().submitted(c, requireNotNull(before).snapshot, h.author, input, h.updatedAt)
                        SupabaseStaffRecipeReviewWorkflow.REVIEW -> {
                            input["professionalReview"]?.jsonObject?.let { auth.validateProfessionalRecipeReview(c, actor, h.updatedAt, it) }
                            workflow().reviewed(c, requireNotNull(before).snapshot, h.author, actor.actorId, input, h.updatedAt)
                        }
                        in PUBLICATION_OPERATIONS -> publication().mutate(c, actor, operation, requireNotNull(before).snapshot, input, key, h.updatedAt)
                        else -> SupabaseStaffRecipeDraftCodec.snapshot(input, h.recipeId, h.id, h.version, h.createdAt, h.updatedAt)
                    }
                    response("RecipeVersion", snapshot)
                    val text = canonical(snapshot)
                    if (text.encodeToByteArray().size > 131072) fail(SupabaseStaffRecipeFailureCode.RESPONSE_TOO_LARGE)
                    c.prepareStatement("INSERT INTO staff.recipe_draft_revisions(environment,recipe_id,draft_id,version,author_id,editor_id," +
                        "principal_scope,operation_id,command_key,request_hash,request_text,snapshot_text,snapshot_sha256,recorded_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                        s.setString(1, environment); s.setObject(2, h.recipeId); s.setObject(3, h.id); s.setLong(4, h.version)
                        s.setObject(5, h.author); s.setObject(6, actor.actorId); s.setString(7, identity.scope.storageKey)
                        s.setString(8, operation); s.setObject(9, key); s.setString(10, identity.requestHash)
                        s.setString(11, canonical(input)); s.setString(12, text); s.setString(13, sha(text)); s.setObject(14, time(h.updatedAt))
                        if (s.executeUpdate() != 1) unavailable()
                    }
                    val rev = revision(c, head(c, h.recipeId, h.id), h.version)
                    StoredReply(200, replyBody(rev), "\"${h.version}\"").also { requireOriginal(identity, input, it, rev); accepted = rev }
                })
            // Verify durable original against immutable audit + current author ownership,
            // after receipt persistence. No cached/fixture principal substitutes for the
            // auth owner's mandatory final same-transaction registry/provider check.
            if (result is CommandResult.Applied || result is CommandResult.Replayed) {
                val rev = accepted ?: unavailable()
                val h = head(c, rev.recipeId, rev.id); authorize(actor, h, operation)
                val again = revision(c, h, rev.version)
                if (again != rev) unavailable()
                val reply = if (result is CommandResult.Applied) result.reply else (result as CommandResult.Replayed).reply
                requireOriginal(identity, input, reply, again)
                if (operation in WORKFLOW_OPERATIONS) workflow().revalidate(c, operation, again.snapshot, input)
                if (operation == SupabaseStaffRecipeReviewWorkflow.REVIEW) input["professionalReview"]?.jsonObject?.let {
                    auth.validateProfessionalRecipeReview(c, actor, Instant.parse(again.snapshot.getValue("latestReview").jsonObject.getValue("createdAt").jsonPrimitive.content), it)
                }
                if (operation in PUBLICATION_OPERATIONS) publication().revalidate(c, actor, operation, again.snapshot)
                verifyReceipt(c, actor, identity, reply)
            }
            current(c, actor)
            result
        }
    }

    private fun requireOriginal(identity: CommandIdentity, input: JsonObject, reply: StoredReply, revision: Revision) {
        if (revision.scope != identity.scope.storageKey || revision.operation != identity.operationId || revision.key != identity.key ||
            revision.requestHash != identity.requestHash || revision.requestText != canonical(input) ||
            reply.status != 200 || reply.etag != "\"${revision.version}\"" || reply.body != replyBody(revision)) unavailable()
    }
    private fun replyBody(revision: Revision): JsonObject = if (revision.operation == SupabaseStaffRecipeReviewWorkflow.REVIEW)
        revision.snapshot.getValue("latestReview").jsonObject else revision.snapshot
    private fun verifyReceipt(c: Connection, actor: SupabaseStaffCatalogActor, identity: CommandIdentity, reply: StoredReply) {
        c.prepareStatement("SELECT request_hash,state,response_code,response_json,response_etag,expires_at " +
            "FROM platform.idempotency WHERE principal_scope=? AND operation_id=? AND key=? FOR SHARE").use { s ->
            s.setString(1, identity.scope.storageKey); s.setString(2, identity.operationId); s.setObject(3, identity.key)
            s.executeQuery().use { r ->
                if (!r.next() || r.getString(1) != identity.requestHash || r.getString(2) != "completed" ||
                    r.getInt(3) != reply.status || Json.parseToJsonElement(r.getString(4)) != reply.body || r.getString(5) != reply.etag ||
                    instant(r, 6) <= now(c)) unavailable()
                actor.requireValidUntil(instant(r, 6))
                if (r.next()) unavailable()
            }
        }
    }
    private fun head(c: Connection, recipeId: UUID, id: UUID, exclusive: Boolean = false): Head = c.prepareStatement(
        "SELECT environment,recipe_id,id,author_id,current_version,created_at,updated_at FROM ONLY staff.recipe_drafts " +
            "WHERE environment=? AND recipe_id=? AND id=? FOR ${if (exclusive) "UPDATE" else "SHARE"}").use { s ->
        s.setString(1, environment); s.setObject(2, recipeId); s.setObject(3, id)
        s.executeQuery().use { r -> if (!r.next()) fail(SupabaseStaffRecipeFailureCode.UNAVAILABLE)
            decodeHead(r).also { if (r.next()) unavailable() } }
    }
    private fun decodeHead(r: ResultSet): Head = Head(r.getString(1), r.getObject(2, UUID::class.java), r.getObject(3, UUID::class.java),
        r.getObject(4, UUID::class.java), r.getLong(5), instant(r, 6), instant(r, 7)).also {
        if (it.environment != environment || it.version <= 0 || it.updatedAt < it.createdAt) unavailable()
    }
    private fun revision(c: Connection, h: Head, version: Long, depth: Int = 0): Revision {
        if (depth > 4) unavailable() // Recall -> publish -> review -> submit -> authored draft.
        return c.prepareStatement(
        "SELECT recipe_id,author_id,editor_id,principal_scope,operation_id,command_key,request_hash,request_text," +
            "snapshot_text,snapshot_sha256,recorded_at FROM ONLY staff.recipe_draft_revisions WHERE environment=? AND draft_id=? AND version=? FOR SHARE").use { s ->
        s.setString(1, environment); s.setObject(2, h.id); s.setLong(3, version)
        s.executeQuery().use { r ->
            if (!r.next()) unavailable()
            val recipeId = r.getObject(1, UUID::class.java); val author = r.getObject(2, UUID::class.java); val editor = r.getObject(3, UUID::class.java)
            val scope = r.getString(4); val operation = r.getString(5); val key = r.getObject(6, UUID::class.java)
            val requestHash = r.getString(7); val requestText = r.getString(8); val text = r.getString(9); val hash = r.getString(10); val at = instant(r, 11)
            if (r.next() || recipeId != h.recipeId || author != h.author ||
                scope != PrincipalScope(environment, CommandActor.STAFF, editor).storageKey || version !in 1..h.version ||
                (if (version == 1L) operation != "adminCreateRecipe" else operation !in setOf("adminUpdateRecipe") + WORKFLOW_OPERATIONS + PUBLICATION_OPERATIONS) ||
                (if (operation == SupabaseStaffRecipeReviewWorkflow.REVIEW) editor == author else operation !in PUBLICATION_OPERATIONS && editor != author) || at < h.createdAt || at > h.updatedAt ||
                version == h.version && at != h.updatedAt || sha(text) != hash || !requestHash.matches(Regex("[0-9a-f]{64}"))) unavailable()
            val input = Json.parseToJsonElement(requestText).jsonObject
            val body = Json.parseToJsonElement(text).jsonObject
            if (canonical(input) != requestText || canonical(body) != text) unavailable()
            val expected = if (operation in PUBLICATION_OPERATIONS) {
                val publication = publication(requireEnabled = false)
                try { publication.input(operation, input) } catch (_: Exception) { unavailable() }
                val source = revision(c, h, version - 1, depth + 1)
                publication.decode(c, operation, source.snapshot, editor, input, key, at)
            } else if (operation in WORKFLOW_OPERATIONS) {
                val workflow = workflow(requireEnabled = false)
                try { workflow.input(operation, input) } catch (_: Exception) { unavailable() }
                val source = revision(c, h, version - 1, depth + 1)
                try { workflow.retained(c, operation, source.snapshot, author, editor, input, at) }
                catch (f: SupabaseStaffRecipeFailure) { unavailable() }
            } else {
                try { SupabaseStaffRecipeDraftCodec.input(input, maxDraftBytes) } catch (_: Exception) { unavailable() }
                SupabaseStaffRecipeDraftCodec.snapshot(input, h.recipeId, h.id, version, h.createdAt, at)
            }
            if (body != expected) unavailable()
            val original = CommandIdentity(PrincipalScope(environment, CommandActor.STAFF, editor), operation, key,
                if (version == 1L) emptyMap() else mapOf("recipeId" to h.recipeId.toString(), "recipeVersionId" to h.id.toString()),
                body = input, ifMatch = if (version == 1L) null else "\"${version - 1}\"")
            if (original.requestHash != requestHash) unavailable()
            response("RecipeVersion", body)
            Revision(h.id, h.recipeId, version, scope, operation, key, requestHash, requestText, body)
        }
    }
    }
    private fun author(actor: SupabaseStaffCatalogActor, head: Head) {
        if (actor.actorId != head.author) fail(SupabaseStaffRecipeFailureCode.AUTHOR_REQUIRED)
    }
    private fun authorize(actor: SupabaseStaffCatalogActor, head: Head, operation: String) {
        if (operation in PUBLICATION_OPERATIONS) {
            if (!actor.canPublish) throw SupabaseStaffFailure(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
        } else if (operation == SupabaseStaffRecipeReviewWorkflow.REVIEW) {
            if (!actor.canReview || actor.actorId == head.author) fail(SupabaseStaffRecipeFailureCode.INDEPENDENT_REVIEW_REQUIRED)
        } else author(actor, head)
    }
    private fun bound(c: Connection, actor: SupabaseStaffCatalogActor) {
        if (c.autoCommit || actor.environment != environment) unavailable()
        SupabaseStaffRecipeServingCompatibility.check(c)
        current(c, actor)
    }
    private fun current(c: Connection, actor: SupabaseStaffCatalogActor): Instant {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Staff draft interrupted")
        val at = now(c)
        if (actor.environment != environment || at < actor.checkedAt || at >= actor.validUntil) throw SupabaseStaffFailure(SupabaseStaffFailureCode.STAFF_ACCESS_DENIED)
        return at
    }
    private fun page(items: List<JsonObject>, cursor: String?, at: Instant) = buildJsonObject {
        put("items", JsonArray(items)); put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", at.toString())
    }
    /** Opaque keyset position, not a bearer grant. Every page still requires fresh
     * authenticated reviewer/publisher access to the exact environment. Tampering can
     * only choose a position within that already authorized queue, never another owner. */
    private fun encodeCursor(actor: SupabaseStaffCatalogActor, q: String?, after: UUID): String {
        val text = canonical(buildJsonObject { put("v", 1); put("environment", environment); put("actor", actor.actorId.toString())
            put("query", sha(q?.let { "text:$it" } ?: "absent")); put("after", after.toString()) })
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.encodeToByteArray())
    }
    private fun decodeCursor(value: String, actor: SupabaseStaffCatalogActor, q: String?): UUID = try {
        if (value.length !in 1..1024 || !value.matches(Regex("[A-Za-z0-9_-]+"))) cursorInvalid()
        val bytes = Base64.getUrlDecoder().decode(value)
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != value) cursorInvalid()
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val body = Json.parseToJsonElement(text).jsonObject
        if (body.keys != setOf("v", "environment", "actor", "query", "after") || canonical(body) != text || body["v"] != JsonPrimitive(1) ||
            body["environment"] != JsonPrimitive(environment) || body["actor"] != JsonPrimitive(actor.actorId.toString()) ||
            body["query"] != JsonPrimitive(sha(q?.let { "text:$it" } ?: "absent"))) cursorInvalid()
        UUID.fromString(body.getValue("after").jsonPrimitive.content).also { if (body["after"] != JsonPrimitive(it.toString())) cursorInvalid() }
    } catch (_: Exception) { cursorInvalid() }
    private fun validated(body: JsonObject): JsonObject = try {
        SupabaseStaffRecipeDraftCodec.input(body, maxDraftBytes).also {
            if (canonical(it).encodeToByteArray().size > maxDraftBytes) invalid()
        }
    } catch (f: SupabaseStaffRecipeFailure) { throw f } catch (_: Exception) { invalid() }
    private fun validatedWorkflow(operation: String, body: JsonObject): JsonObject = try { workflow().input(operation, body) }
        catch (f: SupabaseStaffRecipeFailure) { throw f } catch (_: Exception) { invalid() }
    private fun workflow(requireEnabled: Boolean = true): SupabaseStaffRecipeReviewWorkflow {
        if (requireEnabled && !auth.policy.catalogReviewsEnabled) fail(SupabaseStaffRecipeFailureCode.NOT_CONFIGURED)
        return reviews ?: fail(SupabaseStaffRecipeFailureCode.NOT_CONFIGURED)
    }
    private fun validatedPublication(operation: String, body: JsonObject): JsonObject = try { publication().input(operation, body) }
        catch (f: SupabaseStaffRecipeFailure) { throw f } catch (_: Exception) { invalid() }
    private fun publication(requireEnabled: Boolean = true): SupabaseStaffRecipePublicationWorkflow {
        if (requireEnabled && !auth.policy.catalogPublicationEnabled) fail(SupabaseStaffRecipeFailureCode.NOT_CONFIGURED)
        return publications ?: fail(SupabaseStaffRecipeFailureCode.NOT_CONFIGURED)
    }
    private fun version(value: String): Long {
        if (!value.matches(Regex("\"[1-9][0-9]{0,18}\""))) invalid()
        return value.drop(1).dropLast(1).toLongOrNull()?.takeIf { it > 0 } ?: invalid()
    }
    private fun response(schema: String, body: JsonObject) {
        if (body.toString().encodeToByteArray().size > maxResponseBytes) fail(SupabaseStaffRecipeFailureCode.RESPONSE_TOO_LARGE)
        SupabaseStaffRecipeDraftCodec.response(schema, body)
    }
    private fun sameConnection(expected: Connection, actual: Connection) { if (actual !== expected) unavailable() }
    private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
        if (!r.next()) unavailable(); instant(r, 1).also { if (r.next()) unavailable() }
    } }
    private fun instant(r: ResultSet, i: Int) = r.getObject(i, OffsetDateTime::class.java)?.toInstant() ?: unavailable()
    private fun time(at: Instant) = OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC)
    private fun canonical(value: JsonElement) = SupabaseStaffRecipeDraftCodec.canonical(value)
    private fun sha(text: String) = SupabaseStaffRecipeDraftCodec.sha(text)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (f: SupabaseStaffRecipeFailure) { throw f }
        catch (f: SupabaseStaffFailure) { throw f }
        catch (f: CommitOutcomeUnknown) { throw f }
        catch (f: CancellationException) { throw f }
        catch (f: InterruptedException) { Thread.currentThread().interrupt(); throw f }
        catch (f: SQLException) { if (f.sqlState in setOf("40001", "40P01")) throw f; unavailable() }
        catch (_: Exception) { unavailable() }
    private fun invalid(): Nothing = fail(SupabaseStaffRecipeFailureCode.INPUT_INVALID)
    private fun cursorInvalid(): Nothing = fail(SupabaseStaffRecipeFailureCode.CURSOR_INVALID)
    private fun unavailable(): Nothing = fail(SupabaseStaffRecipeFailureCode.STORAGE_UNAVAILABLE)
    private fun fail(code: SupabaseStaffRecipeFailureCode): Nothing = throw SupabaseStaffRecipeFailure(code)
    override fun toString() = "SupabaseStaffRecipeDraftStore(<redacted>)"
    private data class Head(val environment: String, val recipeId: UUID, val id: UUID, val author: UUID,
        val version: Long, val createdAt: Instant, val updatedAt: Instant)
    private data class Revision(val id: UUID, val recipeId: UUID, val version: Long, val scope: String, val operation: String,
        val key: UUID, val requestHash: String, val requestText: String, val snapshot: JsonObject)
    companion object {
        private val WORKFLOW_OPERATIONS = setOf(SupabaseStaffRecipeReviewWorkflow.SUBMIT, SupabaseStaffRecipeReviewWorkflow.REVIEW)
        private val PUBLICATION_OPERATIONS = setOf(SupabaseStaffRecipePublicationWorkflow.PUBLISH, SupabaseStaffRecipePublicationWorkflow.RECALL)
    }
}

internal class SupabaseStaffRecipeObservation(val body: JsonObject, val editable: Boolean,
    val submittable: Boolean = false, val reviewable: Boolean = false,
    val publishable: Boolean = false, val recallable: Boolean = false) {
    override fun toString() = "SupabaseStaffRecipeObservation(<redacted>)"
}
