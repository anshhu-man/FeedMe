package com.feedme.server.memory

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Six basic private Save/cookbook operations. No GET initialization, custom collection editing,
 * feedback inference, social grant, recipe reuse/adaptation, worker or default content authority.
 * First authorized Save provisions a concrete default cookbook in the same transaction. Saved
 * content is recipe-only, detached from the Plan TTL; positive copy permission is still mandatory.
 * Current identity/established-copy rights precede receipt disclosure. One recalled candidate
 * denies its page until audited per-item lifecycle/redaction integration exists.
 */
class SavedRecipeStore(val environment: String, private val transactions: PgTransactions,
    private val authority: SavedRecipeAuthority, private val cursors: SavedRecipeCursors,
    val policy: SavedRecipeServicePolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun saveRecipe(actor: VerifiedSavedRecipePrincipal, key: UUID, body: JsonObject): CommandResult {
        val input = request(body)
        if (input["markMakeAgain"]?.jsonPrimitive?.boolean == true) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
        val planId = optionalId(input, "planId"); val versionId = optionalId(input, "recipeVersionId")
        val targetCollection = optionalId(input, "collectionId")
        return command(actor, "saveRecipe", key, body = input, replay = { c, cached ->
            val marker = query(c, "SELECT saved_recipe_id,collection_id,generation FROM memory.save_commands WHERE environment=? AND actor_kind=? AND principal_id=? AND command_id=?",
                { owner(actor); setObject(4, key) }) { r -> if (!r.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                Triple(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java), r.getLong(3)) }
            requireDefaultCollection(collection(c, actor, marker.second))
            val saved = authorized(c, actor, marker.first)
            if (saved.generation != marker.third || !member(c, actor, marker.second, saved.id)) fail(SavedRecipeFailureCode.COPY_CONFLICT)
            if (cached.status != 201 || cached.etag != "\"${saved.version}\"" || cached.body != saved.body) fail(SavedRecipeFailureCode.VERSION_CONFLICT)
        }) { c ->
            // This check is not a copy grant. It prevents an adapter from substituting catalog
            // amounts or another principal's Plan for the exact immutable materialized ownPlan.
            val pinned = planId?.let { ownedPlanRecipe(c, actor, it) }
            val permit = authority.authorizeNewCopy(c, actor, planId, versionId); current()
            if (permit.recipe["id"] != JsonPrimitive(permit.recipeVersionId.toString()) ||
                (versionId != null && versionId != permit.recipeVersionId) || (pinned != null && canonical(pinned) != canonical(permit.recipe)))
                fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
            if (permit.recipe["reviewStatus"] != JsonPrimitive("published")) fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
            val hash = digest(canonical(permit.recipe)); val at = now(c)
            val previous = latest(c, actor, permit.recipeVersionId, hash)
            val existing = previous?.takeUnless { it.deleted }
            ensureHead(c, actor)
            val collection = if (targetCollection == null) defaultCollection(c, actor, at) else collection(c, actor, targetCollection)
            requireDefaultCollection(collection)
            val saved = if (existing != null) {
                authorize(c, actor, existing)
                if (input["title"] != null && input["title"] != existing.body?.get("title")) fail(SavedRecipeFailureCode.COPY_CONFLICT)
                existing
            } else {
                val id = UUID.randomUUID(); val generation = increment(previous?.generation ?: 0)
                val type = if (planId == null) "catalog" else "ownPlan"
                val snapshot = buildJsonObject {
                    put("id", id.toString()); put("version", 1); put("createdAt", at.toString()); put("updatedAt", at.toString())
                    put("title", input["title"] ?: permit.recipe.getValue("title")); put("snapshot", permit.recipe)
                    put("sourceType", type); put("recalled", false); put("contentLicense", permit.contentLicense)
                }
                // Reserve an envelope/cursor budget, so every accepted item can later be paged.
                if (bytes(snapshot).size + PAGE_RESERVE > policy.maxResponseBytes) fail(SavedRecipeFailureCode.RESPONSE_TOO_LARGE)
                reply("saveRecipe", 201, snapshot, 1)
                exec(c, "INSERT INTO memory.saved_recipes(environment,actor_kind,principal_id,id,generation,version,recipe_version_id,recipe_hash,source_type,source_id,origin_plan_id,content_license,snapshot,copy_evidence,created_at,updated_at) VALUES(?,?,?,?,?,1,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)") {
                    owner(actor); setObject(4, id); setLong(5, generation); setObject(6, permit.recipeVersionId); setString(7, hash)
                    setString(8, type); setObject(9, planId ?: permit.recipeVersionId); setObject(10, planId); setString(11, permit.contentLicense)
                    setString(12, snapshot.toString()); setString(13, permit.evidence.toString()); setObject(14, time(at)); setObject(15, time(at))
                }
                val persisted = row(c, actor, id)
                event(c, actor, key, "memory.recipe.saved.v1", "saved_recipe", id, 1, buildJsonObject {
                    put("principalId", actor.principalId.toString()); put("savedRecipeId", id.toString()); put("sourceType", type)
                })
                persisted
            }
            if (!member(c, actor, collection.id, saved.id)) {
                val position = query(c, "SELECT coalesce(max(position),0) FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=?",
                    { owner(actor); setObject(4, collection.id) }) { it.next(); increment(it.getLong(1)) }
                exec(c, "INSERT INTO memory.collection_items VALUES(?,?,?,?,?,?)") { owner(actor); setObject(4, collection.id); setObject(5, saved.id); setLong(6, position) }
                collectionChanged(c, actor, collection, key, at, "itemAdded"); advanceHead(c, actor)
            }
            exec(c, "INSERT INTO memory.save_commands VALUES(?,?,?,?,?,?,?)") {
                owner(actor); setObject(4, key); setObject(5, saved.id); setObject(6, collection.id); setLong(7, saved.generation)
            }
            reply("saveRecipe", 201, saved.body, saved.version)
        }
    }

    fun getSavedRecipe(actor: VerifiedSavedRecipePrincipal, id: UUID): StoredReply = read(actor) { c ->
        val saved = authorized(c, actor, id); reply("getSavedRecipe", 200, saved.body, saved.version)
    }

    fun deleteSavedRecipe(actor: VerifiedSavedRecipePrincipal, key: UUID, id: UUID, ifMatch: String): CommandResult {
        val expected = version(ifMatch)
        return command(actor, "deleteSavedRecipe", key, mapOf("savedRecipeId" to id.toString()), ifMatch = ifMatch,
            replay = { c, cached ->
                val saved = row(c, actor, id)
                if (!saved.deleted || saved.deletionKey != key || saved.version != increment(expected) ||
                    latest(c, actor, saved.recipeId, saved.hash)?.id != id) fail(SavedRecipeFailureCode.VERSION_CONFLICT)
                if (cached.status != 204 || cached.body != null || cached.etag != null) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            }) { c ->
            val saved = row(c, actor, id)
            if (saved.deleted) fail(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE)
            if (saved.version != expected) fail(SavedRecipeFailureCode.VERSION_CONFLICT)
            val collections = query(c, "SELECT collection_id FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND saved_recipe_id=? ORDER BY collection_id",
                { owner(actor); setObject(4, id) }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
            val at = now(c)
            for (collectionId in collections) {
                val col = collection(c, actor, collectionId)
                exec(c, "DELETE FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=? AND saved_recipe_id=?") {
                    owner(actor); setObject(4, collectionId); setObject(5, id)
                }
                collectionChanged(c, actor, col, key, at, "itemRemoved")
            }
            exec(c, "UPDATE memory.saved_recipes SET deleted=true,snapshot=NULL,copy_evidence=NULL,deletion_key=?,version=?,updated_at=? WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? AND version=? AND NOT deleted") {
                setObject(1, key); setLong(2, increment(saved.version)); setObject(3, time(at)); owner(actor, 4); setObject(7, id); setLong(8, expected)
            }
            advanceHead(c, actor)
            event(c, actor, key, "memory.recipe.deleted.v1", "saved_recipe", id, saved.version + 1, buildJsonObject {
                put("principalId", actor.principalId.toString()); put("savedRecipeId", id.toString())
            })
            reply("deleteSavedRecipe", 204, null)
        }
    }

    fun listSavedRecipes(actor: VerifiedSavedRecipePrincipal, q: String? = null, cursor: String? = null, limit: Int = 20): StoredReply {
        pageInputs(q, limit)
        return read(actor) { c ->
            val revision = head(c, actor); val at = now(c); val scope = if (q == null) "absent" else "text:$q"
            val after = cursors.decode(actor, "saved", scope, revision, cursor, at)
            val ids = query(c, "SELECT id FROM memory.saved_recipes WHERE environment=? AND actor_kind=? AND principal_id=? AND NOT deleted AND (?::uuid IS NULL OR id>?) AND (?::text IS NULL OR position(lower(?) in lower(snapshot->>'title'))>0) ORDER BY id LIMIT ?",
                { owner(actor); setObject(4, after); setObject(5, after); setString(6, q); setString(7, q); setInt(8, limit + 1) }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
            val items = mutableListOf<JsonElement>(); var next: String? = null
            for (id in ids.take(limit)) {
                val saved = authorized(c, actor, id)
                val more = ids.size > items.size + 1
                val candidateCursor = if (more) token(actor, "saved", scope, revision, id, at) else null
                val candidate = page(items + saved.body!!, candidateCursor, at)
                if (bytes(candidate).size > policy.maxResponseBytes) {
                    if (items.isEmpty()) fail(SavedRecipeFailureCode.RESPONSE_TOO_LARGE)
                    next = token(actor, "saved", scope, revision, UUID.fromString(items.last().jsonObject.text("id")), at); break
                }
                items += saved.body!!; next = candidateCursor
            }
            reply("listSavedRecipes", 200, page(items, next, at))
        }
    }

    fun listCollections(actor: VerifiedSavedRecipePrincipal, cursor: String? = null, limit: Int = 20): StoredReply {
        pageInputs(null, limit)
        return read(actor) { c ->
            val revision = head(c, actor); val at = now(c)
            val after = cursors.decode(actor, "collections", "", revision, cursor, at)
            val ids = query(c, "SELECT id FROM memory.collections WHERE environment=? AND actor_kind=? AND principal_id=? AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
                { owner(actor); setObject(4, after); setObject(5, after); setInt(6, limit + 1) }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
            val items = mutableListOf<JsonElement>(); var next: String? = null
            for (id in ids.take(limit)) {
                val document = collectionPage(c, actor, collection(c, actor, id), null, 20, at)
                val candidateCursor = if (ids.size > items.size + 1) token(actor, "collections", "", revision, id, at) else null
                if (bytes(page(items + document, candidateCursor, at)).size > policy.maxResponseBytes) {
                    if (items.isEmpty()) fail(SavedRecipeFailureCode.RESPONSE_TOO_LARGE)
                    next = token(actor, "collections", "", revision, UUID.fromString(items.last().jsonObject.text("id")), at); break
                }
                items += document; next = candidateCursor
            }
            reply("listCollections", 200, page(items, next, at))
        }
    }

    fun getCollection(actor: VerifiedSavedRecipePrincipal, id: UUID, cursor: String? = null, limit: Int = 20): StoredReply {
        pageInputs(null, limit)
        return read(actor) { c -> val col = collection(c, actor, id)
            reply("getCollection", 200, collectionPage(c, actor, col, cursor, limit, now(c)), col.version)
        }
    }

    private fun collectionPage(c: Connection, actor: VerifiedSavedRecipePrincipal, col: CollectionRow, cursor: String?, limit: Int, at: Instant): JsonObject {
        val after = cursors.decode(actor, "items", col.id.toString(), col.version, cursor, at)
        val afterPosition = if (after == null) 0L else query(c,
            "SELECT position FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=? AND saved_recipe_id=?",
            { owner(actor); setObject(4, col.id); setObject(5, after) }) { if (!it.next()) fail(SavedRecipeFailureCode.CURSOR_INVALID); it.getLong(1) }
        val ids = query(c, "SELECT saved_recipe_id FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=? AND position>? ORDER BY position,saved_recipe_id LIMIT ?",
            { owner(actor); setObject(4, col.id); setLong(5, afterPosition); setInt(6, limit + 1) }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
        val selected = mutableListOf<UUID>(); var next: String? = null
        for (id in ids.take(limit)) {
            authorized(c, actor, id)
            val candidateCursor = if (ids.size > selected.size + 1) token(actor, "items", col.id.toString(), col.version, id, at) else null
            if (bytes(collectionJson(col, selected + id, candidateCursor)).size + PAGE_RESERVE > policy.maxResponseBytes) {
                if (selected.isEmpty()) fail(SavedRecipeFailureCode.RESPONSE_TOO_LARGE)
                next = token(actor, "items", col.id.toString(), col.version, selected.last(), at); break
            }
            selected += id; next = candidateCursor
        }
        return collectionJson(col, selected, next)
    }

    private fun ownedPlanRecipe(c: Connection, actor: VerifiedSavedRecipePrincipal, id: UUID): JsonObject = query(c,
        "SELECT snapshot_text,snapshot_hash,status,recipe_version_id FROM planning.plans WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? FOR SHARE",
        { owner(actor); setObject(4, id) }) { r ->
        if (!r.next()) fail(SavedRecipeFailureCode.PLAN_UNAVAILABLE)
        val text = r.getString(1); val document = Json.parseToJsonElement(text).jsonObject
        if (digest(text) != r.getString(2) || document["id"] != JsonPrimitive(id.toString())) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        if (r.getString(3) != "ready" || document["status"] != JsonPrimitive("ready")) fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
        val recipe = document["recipeSnapshot"]?.jsonObject ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        if (recipe["id"] != JsonPrimitive(r.getObject(4, UUID::class.java).toString())) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        recipe
    }

    private fun authorized(c: Connection, actor: VerifiedSavedRecipePrincipal, id: UUID): Row = row(c, actor, id).also { authorize(c, actor, it) }
    private fun authorize(c: Connection, actor: VerifiedSavedRecipePrincipal, saved: Row) {
        if (saved.deleted) fail(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE)
        val body = saved.body ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        if (body["recalled"] == JsonPrimitive(true)) fail(SavedRecipeFailureCode.RECIPE_RECALLED)
        authority.requireExistingCopyAllowed(c, actor, SavedRecipeCopyEvidence(saved.id, saved.recipeId, saved.hash,
            body.text("sourceType"), saved.planId, body.text("contentLicense"), saved.evidence!!)); current()
    }
    private fun row(c: Connection, actor: VerifiedSavedRecipePrincipal, id: UUID): Row = query(c,
        "SELECT * FROM memory.saved_recipes WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? FOR UPDATE",
        { owner(actor); setObject(4, id) }) { if (!it.next()) fail(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE); row(it) }
    private fun latest(c: Connection, actor: VerifiedSavedRecipePrincipal, id: UUID, hash: String): Row? = query(c,
        "SELECT * FROM memory.saved_recipes WHERE environment=? AND actor_kind=? AND principal_id=? AND recipe_version_id=? AND recipe_hash=? ORDER BY generation DESC LIMIT 1 FOR UPDATE",
        { owner(actor); setObject(4, id); setString(5, hash) }) { if (it.next()) row(it) else null }
    private fun row(r: ResultSet): Row {
        val id = r.getObject("id", UUID::class.java); val version = r.getLong("version"); val recipeId = r.getObject("recipe_version_id", UUID::class.java)
        val hash = r.getString("recipe_hash"); val body = r.getString("snapshot")?.let { Json.parseToJsonElement(it).jsonObject }
        val evidence = r.getString("copy_evidence")?.let { Json.parseToJsonElement(it).jsonObject }
        if (!r.getBoolean("deleted")) {
            if (body == null || evidence.isNullOrEmpty() || body["id"] != JsonPrimitive(id.toString()) || body["version"]?.jsonPrimitive?.long != version ||
                body["snapshot"]?.jsonObject?.get("id") != JsonPrimitive(recipeId.toString()) || digest(canonical(body.getValue("snapshot"))) != hash ||
                body.text("sourceType") != r.getString("source_type") || body.text("contentLicense") != r.getString("content_license") ||
                body.text("createdAt") != instant(r, "created_at").toString() || body.text("updatedAt") != instant(r, "updated_at").toString()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            reply("getSavedRecipe", 200, body, version)
        }
        return Row(id, r.getLong("generation"), version, recipeId, hash, body, evidence, r.getObject("origin_plan_id", UUID::class.java), r.getBoolean("deleted"), r.getObject("deletion_key", UUID::class.java))
    }
    private fun collection(c: Connection, actor: VerifiedSavedRecipePrincipal, id: UUID): CollectionRow = query(c,
        "SELECT * FROM memory.collections WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? FOR UPDATE",
        { owner(actor); setObject(4, id) }) { if (!it.next()) fail(SavedRecipeFailureCode.COLLECTION_UNAVAILABLE); collectionRow(it) }
    private fun collectionRow(r: ResultSet) = CollectionRow(r.getObject("id", UUID::class.java), r.getLong("version"), r.getString("name"), r.getString("description"), instant(r, "created_at"), instant(r, "updated_at"), r.getBoolean("is_default"))
    private fun requireDefaultCollection(collection: CollectionRow) {
        if (!collection.isDefault) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
    }
    private fun defaultCollection(c: Connection, actor: VerifiedSavedRecipePrincipal, at: Instant): CollectionRow {
        val existing = query(c, "SELECT * FROM memory.collections WHERE environment=? AND actor_kind=? AND principal_id=? AND is_default FOR UPDATE", { owner(actor) }) { if (it.next()) collectionRow(it) else null }
        if (existing != null) return existing
        val id = UUID.randomUUID()
        exec(c, "INSERT INTO memory.collections(environment,actor_kind,principal_id,id,version,is_default,name,created_at,updated_at) VALUES(?,?,?,?,1,true,?,?,?)") {
            owner(actor); setObject(4, id); setString(5, policy.defaultCollectionName); setObject(6, time(at)); setObject(7, time(at))
        }
        return collection(c, actor, id)
    }
    private fun member(c: Connection, actor: VerifiedSavedRecipePrincipal, collectionId: UUID, savedId: UUID) = query(c,
        "SELECT 1 FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=? AND saved_recipe_id=?",
        { owner(actor); setObject(4, collectionId); setObject(5, savedId) }) { it.next() }
    private fun collectionChanged(c: Connection, actor: VerifiedSavedRecipePrincipal, col: CollectionRow, key: UUID, at: Instant, action: String) {
        val version = increment(col.version)
        exec(c, "UPDATE memory.collections SET version=?,updated_at=? WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? AND version=?") {
            setLong(1, version); setObject(2, time(at)); owner(actor, 3); setObject(6, col.id); setLong(7, col.version)
        }
        event(c, actor, key, "memory.collection.changed.v1", "collection", col.id, version, buildJsonObject {
            put("principalId", actor.principalId.toString()); put("collectionId", col.id.toString()); put("action", action)
        })
    }
    private fun head(c: Connection, actor: VerifiedSavedRecipePrincipal): Long = query(c,
        "SELECT revision FROM memory.library_heads WHERE environment=? AND actor_kind=? AND principal_id=? FOR UPDATE", { owner(actor) }) { if (it.next()) it.getLong(1) else 0 }
    private fun ensureHead(c: Connection, actor: VerifiedSavedRecipePrincipal) {
        if (head(c, actor) == 0L) exec(c, "INSERT INTO memory.library_heads VALUES(?,?,?,1)") { owner(actor) }
    }
    private fun advanceHead(c: Connection, actor: VerifiedSavedRecipePrincipal) {
        val next = increment(head(c, actor)); exec(c, "UPDATE memory.library_heads SET revision=? WHERE environment=? AND actor_kind=? AND principal_id=?") { setLong(1, next); owner(actor, 2) }
    }
    private fun event(c: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID, type: String, aggregate: String, id: UUID, version: Long, data: JsonObject) {
        current(); outbox.append(c, EventDraft(UUID.randomUUID(), type, 1, aggregate, id, version, "memory", UUID.randomUUID().toString(), key, data))
    }
    private fun command(actor: VerifiedSavedRecipePrincipal, op: String, key: UUID, paths: Map<String, String> = emptyMap(), body: JsonObject? = null,
        ifMatch: String? = null, replay: (Connection, StoredReply) -> Unit, mutate: (Connection) -> StoredReply): CommandResult = safe {
        checkActor(actor); current()
        val identity = CommandIdentity(PrincipalScope(environment, actor.kind, actor.principalId), op, key, paths, body = body, ifMatch = ifMatch)
        commands.execute(identity, { authority.lockPrincipal(it, actor); current() }, {}, { c, cached -> replay(c, cached); current() }, { c -> mutate(c).also { current() } })
    }
    private fun <T> read(actor: VerifiedSavedRecipePrincipal, action: (Connection) -> T): T = safe {
        checkActor(actor); current(); transactions.run { c -> authority.lockPrincipal(c, actor); current(); action(c).also { current() } }
    }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: SavedRecipeFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }
    private fun request(body: JsonObject): JsonObject {
        val bytes = bytes(body, SavedRecipeFailureCode.INPUT_INVALID)
        if (validator.validateRequest("saveRecipe", bytes, "application/json") != BodyValidationResult.Valid) fail(SavedRecipeFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun reply(op: String, status: Int, body: JsonObject?, version: Long? = null): StoredReply {
        val bytes = body?.let { bytes(it) }
        if (bytes != null && bytes.size > policy.maxResponseBytes) fail(SavedRecipeFailureCode.RESPONSE_TOO_LARGE)
        if (validator.validateResponse(op, status, bytes, if (body == null) null else "application/json") != BodyValidationResult.Valid) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(status, body, version?.let { "\"$it\"" })
    }
    private fun pageInputs(q: String?, limit: Int) {
        if (limit !in 1..50) fail(SavedRecipeFailureCode.INPUT_INVALID)
        if (q != null) {
            try { q.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: Exception) { fail(SavedRecipeFailureCode.INPUT_INVALID) }
            if (q.codePointCount(0, q.length) > 100 || q.any { Character.isISOControl(it) }) fail(SavedRecipeFailureCode.INPUT_INVALID)
        }
    }
    private fun checkActor(actor: VerifiedSavedRecipePrincipal) { if (actor.environment != environment) fail(SavedRecipeFailureCode.UNAUTHENTICATED) }
    private fun current() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Saved recipe operation interrupted") }
    private fun bytes(body: JsonObject, code: SavedRecipeFailureCode = SavedRecipeFailureCode.STORAGE_UNAVAILABLE): ByteArray = try { body.toString().encodeToByteArray(throwOnInvalidSequence = true) }
        catch (_: IllegalArgumentException) { fail(code) } catch (_: CharacterCodingException) { fail(code) }
    private fun version(etag: String): Long {
        if (!etag.matches(Regex("\"[0-9]{1,64}\""))) fail(SavedRecipeFailureCode.INPUT_INVALID)
        return etag.drop(1).dropLast(1).trimStart('0').ifEmpty { "0" }.toLongOrNull()?.takeIf { it > 0 } ?: fail(SavedRecipeFailureCode.INPUT_INVALID)
    }
    private fun optionalId(body: JsonObject, name: String) = body[name]?.jsonPrimitive?.content?.let(UUID::fromString)
    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) else value + 1
    private fun fail(code: SavedRecipeFailureCode): Nothing = throw SavedRecipeFailure(code)
    private fun token(actor: VerifiedSavedRecipePrincipal, purpose: String, scope: String, revision: Long, after: UUID, at: Instant) = cursors.encode(actor, purpose, scope, revision, after, at.plusSeconds(policy.cursorLifetimeSeconds.toLong()))
    private fun page(items: List<JsonElement>, cursor: String?, at: Instant) = buildJsonObject { put("items", JsonArray(items)); put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", at.toString()) }
    private fun collectionJson(row: CollectionRow, ids: List<UUID>, cursor: String?) = buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("createdAt", row.created.toString()); put("updatedAt", row.updated.toString())
        put("name", row.name); row.description?.let { put("description", it) }; put("savedRecipeIds", JsonArray(ids.map { JsonPrimitive(it.toString()) }))
        put("nextItemCursor", cursor?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun PreparedStatement.owner(actor: VerifiedSavedRecipePrincipal, start: Int = 1) { setString(start, environment); setString(start + 1, actor.kind.name.lowercase()); setObject(start + 2, actor.principalId) }
    private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { it.next(); it.getObject(1, OffsetDateTime::class.java).toInstant() } }
    private fun instant(r: ResultSet, name: String) = r.getObject(name, OffsetDateTime::class.java).toInstant()
    private fun time(at: Instant) = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) { current(); c.prepareStatement(sql).use { it.bind(); if (it.executeUpdate() != 1) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) } }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T = c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private data class Row(val id: UUID, val generation: Long, val version: Long, val recipeId: UUID, val hash: String, val body: JsonObject?, val evidence: JsonObject?, val planId: UUID?, val deleted: Boolean, val deletionKey: UUID?)
    private data class CollectionRow(val id: UUID, val version: Long, val name: String, val description: String?, val created: Instant, val updated: Instant, val isDefault: Boolean)
    private companion object {
        const val PAGE_RESERVE = 1024
        val validator by lazy { ContractBodyValidator.bundled() }
        fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, v) -> "${JsonPrimitive(key)}:${canonical(v)}" }
            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
            is JsonPrimitive -> if (value.isString || value == JsonNull || value.booleanOrNull != null) value.toString() else BigDecimal(value.content).stripTrailingZeros().toPlainString()
        }
    }
}
