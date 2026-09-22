package com.feedme.server.social.reports

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.catalog.RecipeCatalogJournal
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Report-only access to actual stored targets, never a feed, media or recipe grant.
 * AccountReportStore must first lock the authenticated reporter and retain this transaction.
 * Reports deliberately do not require a positive food/moderation decision, current Terms,
 * completed onboarding or an unblocked pair: those gates must not silence a complaint.
 * Privacy still requires ownership or CURRENT circle membership and the original author's
 * generation. No old UUID, removed membership or expired Today placement grants access.
 * Foreign roots use NOWAIT; contention retries the entire owning transaction. All returned
 * evidence stays in the restricted report store, not in a user response or event payload.
 * Direct messages retain their real participant/source authority; shortcuts remain closed.
 */
internal class PostgresReportTargets(private val environment: String,
    private val recipeMessageCatalog: RecipeCatalogJournal? = null) : ReportTargetAuthority {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(recipeMessageCatalog == null || recipeMessageCatalog.environment == environment) }

    override fun capture(connection: Connection, reporterId: UUID, targetType: String, targetId: UUID): ReportTargetEvidence = nowait {
        require(!connection.isClosed && !connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Report target read interrupted")
        when (targetType) {
            "post" -> post(connection, reporterId, targetId)
            "user" -> user(connection, reporterId, targetId)
            "message" -> message(connection, reporterId, targetId)
            "shortcut" -> fail(ReportFailureCode.NOT_CONFIGURED)
            else -> fail(ReportFailureCode.INPUT_INVALID)
        }
    }

    private data class Message(val id: UUID, val thread: UUID, val sender: UUID, val client: UUID,
        val sequence: Long, val kind: String, val text: String, val created: Instant,
        val request: UUID?, val recipe: UUID?)
    private data class DirectThread(val id: UUID, val low: UUID, val high: UUID, val version: Long,
        val sequence: Long, val last: Instant?, val created: Instant, val updated: Instant)
    private data class RecipeRequest(val id: UUID, val thread: UUID, val requester: UUID, val author: UUID,
        val version: Long, val status: String, val recipe: UUID?, val created: Instant, val updated: Instant,
        val expires: Instant)

    private fun message(c: Connection, reporter: UUID, target: UUID): ReportTargetEvidence {
        // Discover without foreign locks. Account/contact roots precede request/thread/message
        // locks; NOWAIT retries the entire report transaction rather than reversing a writer.
        val candidate = storedMessage(c, target, false) ?: unavailable()
        val initialThread = directThread(c, candidate.thread, reporter, false) ?: unavailable()
        if (candidate.sender == reporter) fail(ReportFailureCode.INPUT_INVALID)
        validateMessage(candidate, initialThread)
        profile(c, candidate.sender)
        val request = if (candidate.kind == "text") null else {
            val initial = recipeRequest(c, candidate.request ?: corrupt(), false) ?: corrupt()
            validateRequest(candidate, initialThread, initial)
            recipeContact(c, initial.requester, initial.author)
            val locked = recipeRequest(c, initial.id, true) ?: retry()
            if (locked != initial) retry()
            locked
        }
        if (candidate.recipe != null) requireMessageRecipe(c, candidate.recipe)
        val thread = directThread(c, candidate.thread, reporter, true) ?: retry()
        if (thread != initialThread) retry()
        val current = storedMessage(c, target, true) ?: retry()
        if (current != candidate) retry()
        validateMessage(current, thread)
        val at = now(c)
        if (at < current.created || at < thread.updated) corrupt()
        val deadline = request?.let {
            validateRequest(current, thread, it)
            if (it.status == "unavailable") unavailable()
            if (it.status == "pending") it.expires.also { expires -> if (at >= expires) unavailable() } else null
        }
        val material = buildJsonObject {
            put("id", current.id.toString()); put("threadId", current.thread.toString())
            put("ownerId", current.sender.toString()); put("version", 1)
            put("kind", current.kind); put("text", current.text); put("createdAt", current.created.toString())
            request?.let { put("recipeRequestId", it.id.toString()); put("recipeRequestVersion", it.version)
                put("recipeRequestStatus", it.status) }
            current.recipe?.let { put("recipeVersionId", it.toString()) }
        }
        // No sender profile, clientMessageId, recipe body, audience, derivative or provider
        // identity is copied. The authored message evidence remains restricted to safety.
        val evidence = JsonObject(material + ("contentSha256" to JsonPrimitive(hash(material))))
        return ReportTargetEvidence("message", target, current.sender, 1, evidence, deadline)
    }

    private fun validateMessage(message: Message, thread: DirectThread) {
        if (message.thread != thread.id || message.sender !in setOf(thread.low, thread.high) || thread.low == thread.high ||
            thread.low.toString() >= thread.high.toString() || thread.version <= 0 || message.sequence <= 0 ||
            thread.sequence < message.sequence || thread.last == null || thread.updated < thread.created ||
            thread.last < thread.created || thread.last > thread.updated || message.created < thread.created ||
            message.created > thread.last || message.sequence == thread.sequence && message.created != thread.last ||
            message.kind !in setOf("text", "recipeRequest", "recipeCard", "system") ||
            message.text.codePointCount(0, message.text.length) > 2000 ||
            message.text.any { it.isISOControl() && it !in "\n\r\t" }) corrupt()
        if (message.kind == "text") {
            if (message.text.isBlank() || message.request != null || message.recipe != null) corrupt()
        } else if (message.request == null || (message.kind == "recipeCard") != (message.recipe != null)) corrupt()
    }

    private fun validateRequest(message: Message, thread: DirectThread, request: RecipeRequest) {
        if (message.request != request.id || request.thread != thread.id || request.requester == request.author ||
            setOf(request.requester, request.author) != setOf(thread.low, thread.high) || request.version <= 0 ||
            request.status !in setOf("pending", "fulfilled", "declined", "unavailable") ||
            (request.status == "fulfilled") != (request.recipe != null) || request.updated < request.created ||
            request.expires <= request.created || message.created < request.created ||
            (message.kind == "recipeRequest" && message.sender != request.requester) ||
            (message.kind in setOf("recipeCard", "system") && message.sender != request.author) ||
            (message.kind == "recipeCard" && (request.status != "fulfilled" || message.recipe != request.recipe)) ||
            (message.kind == "system" && request.status != "declined")) corrupt()
    }

    /** Same source-visibility gates as typed conversation reads, excluding reciprocal blocks
     * and account onboarding/Terms gates that must not prevent an actual complaint. */
    private fun recipeContact(c: Connection, requester: UUID, author: UUID) {
        val candidates = query(c, "SELECT a.circle_id FROM social.circle_members a JOIN social.circle_members b " +
            "ON b.environment=a.environment AND b.circle_id=a.circle_id WHERE a.environment=? AND a.user_id=? " +
            "AND b.user_id=? AND a.status='active' AND b.status='active' ORDER BY a.circle_id LIMIT 100", {
            setString(1, environment); setObject(2, requester); setObject(3, author)
        }) { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        if (candidates.none { shared(c, it, requester, author, null) }) unavailable()
        val consent = query(c, "SELECT version,allow_recipe_requests FROM social.account_privacy " +
            "WHERE environment=? AND user_id=? FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, author)
        }) { rows ->
            if (!rows.next() || rows.getLong(1) <= 0) corrupt()
            rows.getBoolean(2).also { if (rows.next()) corrupt() }
        }
        if (!consent) unavailable()
    }

    private fun requireMessageRecipe(c: Connection, version: UUID) {
        val catalog = recipeMessageCatalog ?: fail(ReportFailureCode.NOT_CONFIGURED)
        // The journal shares this head later; acquire it without waiting on a publisher while
        // holding private roots. Immutable release/history reads remain the journal's owner.
        query(c, "SELECT revision FROM catalog.recipe_heads WHERE environment=? FOR SHARE NOWAIT", {
            setString(1, environment)
        }) { if (!it.next()) fail(ReportFailureCode.NOT_CONFIGURED) }
        val view = catalog.openView(c)
        val actual = view.lookupCurrent(version)?.entry ?: unavailable()
        if (actual.recipe["reviewStatus"] != JsonPrimitive("published") ||
            actual.recipe["contentLicense"] != JsonPrimitive("catalogRedistributable") ||
            actual.review["freeCatalogEligible"] != JsonPrimitive(true) || actual.recall != null || actual.rightsReference.isBlank()) unavailable()
        view.checkCurrent()
    }

    private fun storedMessage(c: Connection, id: UUID, lock: Boolean): Message? = query(c,
        "SELECT id,thread_id,sender_user_id,client_message_id,sequence,kind,text,created_at,recipe_request_id,recipe_version_id " +
            "FROM social.thread_messages WHERE environment=? AND id=?" + if (lock) " FOR SHARE NOWAIT" else "", {
            setString(1, environment); setObject(2, id)
        }) { r -> if (!r.next()) null else Message(r.getObject("id", UUID::class.java), r.getObject("thread_id", UUID::class.java),
            r.getObject("sender_user_id", UUID::class.java), r.getObject("client_message_id", UUID::class.java), r.getLong("sequence"),
            r.getString("kind"), r.getString("text"), r.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            r.getObject("recipe_request_id", UUID::class.java), r.getObject("recipe_version_id", UUID::class.java)).also { if (r.next()) corrupt() } }

    private fun directThread(c: Connection, id: UUID, reporter: UUID, lock: Boolean): DirectThread? = query(c,
        "SELECT id,user_low,user_high,version,last_sequence,last_message_at,created_at,updated_at FROM social.direct_threads " +
            "WHERE environment=? AND id=? AND (user_low=? OR user_high=?)" + if (lock) " FOR SHARE NOWAIT" else "", {
            setString(1, environment); setObject(2, id); setObject(3, reporter); setObject(4, reporter)
        }) { r -> if (!r.next()) null else DirectThread(r.getObject("id", UUID::class.java), r.getObject("user_low", UUID::class.java),
            r.getObject("user_high", UUID::class.java), r.getLong("version"), r.getLong("last_sequence"),
            r.getObject("last_message_at", OffsetDateTime::class.java)?.toInstant(),
            r.getObject("created_at", OffsetDateTime::class.java).toInstant(), r.getObject("updated_at", OffsetDateTime::class.java).toInstant())
            .also { if (r.next()) corrupt() } }

    private fun recipeRequest(c: Connection, id: UUID, lock: Boolean): RecipeRequest? = query(c,
        "SELECT id,thread_id,requester_user_id,author_user_id,version,status,recipe_version_id,created_at,updated_at,expires_at " +
            "FROM social.recipe_requests WHERE environment=? AND id=?" + if (lock) " FOR SHARE NOWAIT" else "", {
            setString(1, environment); setObject(2, id)
        }) { r -> if (!r.next()) null else RecipeRequest(r.getObject("id", UUID::class.java), r.getObject("thread_id", UUID::class.java),
            r.getObject("requester_user_id", UUID::class.java), r.getObject("author_user_id", UUID::class.java), r.getLong("version"),
            r.getString("status"), r.getObject("recipe_version_id", UUID::class.java),
            r.getObject("created_at", OffsetDateTime::class.java).toInstant(), r.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
            r.getObject("expires_at", OffsetDateTime::class.java).toInstant()).also { if (r.next()) corrupt() } }

    private fun corrupt(): Nothing = fail(ReportFailureCode.STORAGE_UNAVAILABLE)

    private fun user(c: Connection, reporter: UUID, target: UUID): ReportTargetEvidence {
        if (reporter == target) fail(ReportFailureCode.INPUT_INVALID)
        val candidates = query(c, "SELECT a.circle_id FROM social.circle_members a JOIN social.circle_members b " +
            "ON b.environment=a.environment AND b.circle_id=a.circle_id " +
            "WHERE a.environment=? AND a.user_id=? AND b.user_id=? AND a.status='active' AND b.status='active' ORDER BY a.circle_id", {
            setString(1, environment); setObject(2, reporter); setObject(3, target)
        }) { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        if (candidates.none { shared(c, it, reporter, target, null) }) unavailable()
        val profile = profile(c, target)
        return ReportTargetEvidence("user", target, target, profile.getValue("version").jsonPrimitive.long, profile, null)
    }

    private data class Post(val owner: UUID, val id: UUID, val version: Long, val status: String,
        val content: JsonObject?, val published: Instant, val expires: Instant, val updated: Instant)

    private fun post(c: Connection, reporter: UUID, target: UUID): ReportTargetEvidence {
        val candidate = uniquePost(c, target, false) ?: unavailable()
        val content = candidate.content ?: unavailable()
        if (candidate.status != "published") unavailable()
        if (validator.validateSchema("Post", content.toString().encodeToByteArray()) != BodyValidationResult.Valid ||
            uuid(content.getValue("id")) != target || content.getValue("version").jsonPrimitive.long != candidate.version ||
            content["status"] != JsonPrimitive(candidate.status) ||
            uuid(content.getValue("author").jsonObject.getValue("userId")) != candidate.owner ||
            instant(content, "createdAt") != candidate.published || instant(content, "publishedAt") != candidate.published ||
            instant(content, "expiresAt") != candidate.expires || instant(content, "updatedAt") != candidate.updated ||
            candidate.expires != candidate.published.plusSeconds(86400)) fail(ReportFailureCode.STORAGE_UNAVAILABLE)
        val audience = content.getValue("audience").jsonObject
        val kind = audience.getValue("kind").jsonPrimitive.content
        val bindings = audience.getValue("bindings").jsonArray.map { element ->
            val value = element.jsonObject
            uuid(value.getValue("circleId")) to value.getValue("authorMembershipGeneration").jsonPrimitive.long
        }
        val circles = audience.getValue("circleIds").jsonArray.map(::uuid)
        if (bindings.map { it.first }.toSet().size != bindings.size || circles.toSet().size != circles.size ||
            circles.toSet() != bindings.map { it.first }.toSet() ||
            kind !in setOf("self", "circles") || (kind == "self") != bindings.isEmpty())
            fail(ReportFailureCode.STORAGE_UNAVAILABLE)
        // Discover without post locks; circle roots always precede post/material locks.
        if (reporter != candidate.owner && (kind == "self" || bindings.sortedBy { it.first.toString() }.none {
                shared(c, it.first, reporter, candidate.owner, it.second)
            })) unavailable()
        val current = uniquePost(c, target, true) ?: retry()
        if (current != candidate) retry()
        profile(c, current.owner)
        val actual = query(c, "SELECT circle_id,author_membership_generation FROM social.post_audiences " +
            "WHERE environment=? AND owner_user_id=? AND post_id=? ORDER BY circle_id FOR SHARE NOWAIT", { key(current) }) {
            rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getLong(2)) }
        }
        if (actual != bindings.toMap()) fail(ReportFailureCode.STORAGE_UNAVAILABLE)
        query(c, "SELECT published_at,response_sha256 FROM social.post_publications " +
            "WHERE environment=? AND owner_user_id=? AND post_id=? FOR SHARE NOWAIT", { key(current) }) { rows ->
            if (!rows.next() || rows.getObject(1, OffsetDateTime::class.java).toInstant() != current.published ||
                current.version == 1L && rows.getString(2) != hash(content)) fail(ReportFailureCode.STORAGE_UNAVAILABLE)
            if (rows.next()) fail(ReportFailureCode.STORAGE_UNAVAILABLE)
        }
        val deadline = if (content["keepOnPlate"] == JsonPrimitive(true)) null else current.expires
        val at = now(c)
        if (at.isBefore(current.published) || deadline != null && !at.isBefore(deadline)) unavailable()
        val evidence = buildJsonObject {
            put("id", target.toString()); put("ownerId", current.owner.toString()); put("version", current.version)
            put("contentSha256", hash(content)); put("status", current.status)
            // No derivative coordinates, private recipe pin, provider subject or other-circle IDs.
            for (field in listOf("caption", "mediaIds", "publishedAt", "expiresAt", "keepOnPlate"))
                content[field]?.let { put(field, it) }
        }
        return ReportTargetEvidence("post", target, current.owner, current.version, evidence, deadline)
    }

    private fun shared(c: Connection, circle: UUID, reporter: UUID, author: UUID, generation: Long?): Boolean {
        val active = query(c, "SELECT status FROM social.circles WHERE environment=? AND id=? FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, circle)
        }) { it.next() && it.getString(1) == "active" }
        if (!active) return false
        val members = query(c, "SELECT user_id,status,generation FROM social.circle_members WHERE environment=? AND circle_id=? " +
            "AND user_id IN (?,?) ORDER BY user_id FOR SHARE NOWAIT", {
            setString(1, environment); setObject(2, circle); setObject(3, reporter); setObject(4, author)
        }) { rows -> buildMap { while (rows.next()) put(rows.getObject(1, UUID::class.java), rows.getString(2) to rows.getLong(3)) } }
        val writer = members[author]
        return members[reporter]?.first == "active" && writer?.first == "active" && (generation == null || writer.second == generation)
    }

    private fun profile(c: Connection, target: UUID): JsonObject = query(c,
        "SELECT u.status account_status,p.status principal_status,f.live,f.version,f.display_name,f.normalized_handle,f.bio " +
            "FROM identity.users u JOIN identity.principals p ON p.environment=u.environment AND p.user_id=u.id " +
            "JOIN profile.profiles f ON f.environment=u.environment AND f.user_id=u.id " +
            "WHERE u.environment=? AND u.id=? FOR SHARE OF u,p,f NOWAIT", {
            setString(1, environment); setObject(2, target)
        }) { rows ->
        if (!rows.next() || rows.getString("account_status") != "active" || rows.getString("principal_status") != "active" ||
            !rows.getBoolean("live")) unavailable()
        val result = buildJsonObject {
            put("id", target.toString()); put("version", rows.getLong("version"))
            for ((column, field) in listOf("display_name" to "displayName", "normalized_handle" to "handle", "bio" to "bio"))
                rows.getString(column)?.let { put(field, it) }
        }
        if (rows.next()) fail(ReportFailureCode.STORAGE_UNAVAILABLE)
        result
    }

    private fun uniquePost(c: Connection, id: UUID, lock: Boolean): Post? = query(c,
        "SELECT * FROM social.posts WHERE environment=? AND id=? ORDER BY owner_user_id" + if (lock) " FOR SHARE NOWAIT" else "", {
            setString(1, environment); setObject(2, id)
        }) { rows ->
        if (!rows.next()) null else {
            val post = Post(rows.getObject("owner_user_id", UUID::class.java), id, rows.getLong("version"), rows.getString("status"),
                rows.getString("content")?.let { Json.parseToJsonElement(it).jsonObject },
                rows.getObject("published_at", OffsetDateTime::class.java).toInstant(), rows.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                rows.getObject("updated_at", OffsetDateTime::class.java).toInstant())
            if (rows.next()) unavailable() // Canonical path has no owner discriminator.
            post
        }
    }
    private fun PreparedStatement.key(post: Post) { setString(1, environment); setObject(2, post.owner); setObject(3, post.id) }
    private fun now(c: Connection) = query(c, "SELECT clock_timestamp()", {}) { check(it.next()); it.getObject(1, OffsetDateTime::class.java).toInstant() }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T =
        c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
    private fun <T> nowait(action: () -> T): T = try { action() } catch (failure: SQLException) {
        if (failure.sqlState != "55P03") throw failure
        throw SQLException("Report target is contended", "40001").also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
    }
    private fun uuid(value: JsonElement) = UUID.fromString(value.jsonPrimitive.content)
    private fun instant(value: JsonObject, field: String) = Instant.parse(value.getValue(field).jsonPrimitive.content)
    private fun hash(value: JsonElement) = MessageDigest.getInstance("SHA-256").digest(canonical(value).encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, item) -> "${JsonPrimitive(key)}:${canonical(item)}" }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString() else BigDecimal(value.content).stripTrailingZeros().toString()
    }
    private fun unavailable(): Nothing = fail(ReportFailureCode.TARGET_UNAVAILABLE)
    private fun fail(code: ReportFailureCode): Nothing = throw ReportFailure(code)
    private fun retry(): Nothing = throw SQLException("Report target changed during authorization", "40001")
    override fun toString() = "PostgresReportTargets(<redacted>)"
    private companion object { val validator by lazy { ContractBodyValidator.bundled() } }
}
