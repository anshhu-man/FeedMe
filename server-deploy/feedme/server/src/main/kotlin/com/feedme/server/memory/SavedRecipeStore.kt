package com.feedme.server.memory

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.guest.GuestSessionFailure
import com.feedme.server.planning.PlanningServiceFailure
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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

    fun saveRecipe(actor: VerifiedSavedRecipePrincipal, key: UUID, body: JsonObject): CommandResult =
        saveRecipeIn(null, actor, key, body).result
    internal fun saveRecipe(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
        body: JsonObject): Pending<CommandResult> = saveRecipeIn(connection, actor, key, body)
    /** Explicit caller-owned composition only; the existing/public paths have no effect. */
    internal fun saveRecipe(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
        body: JsonObject, effect: SavedRecipeMakeAgainEffect): Pending<CommandResult> =
        saveRecipeIn(connection, actor, key, body, effect)
    private fun saveRecipeIn(connection: Connection?, actor: VerifiedSavedRecipePrincipal, key: UUID,
        body: JsonObject, effect: SavedRecipeMakeAgainEffect? = null): Pending<CommandResult> {
        val input = request(body)
        val makeAgain = input["markMakeAgain"]?.jsonPrimitive?.boolean == true
        if (makeAgain && effect == null) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
        val planId = optionalId(input, "planId"); val versionId = optionalId(input, "recipeVersionId")
        val targetCollection = optionalId(input, "collectionId")
        return command(connection, actor, "saveRecipe", key, body = input, replay = { c, cached, trace ->
            val marker = query(c, "SELECT saved_recipe_id,collection_id,generation FROM memory.save_commands WHERE environment=? AND actor_kind=? AND principal_id=? AND command_id=?",
                { owner(actor); setObject(4, key) }) { r -> if (!r.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                Triple(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java), r.getLong(3)) }
            requireDefaultCollection(collection(c, actor, marker.second))
            val saved = authorized(c, actor, marker.first, trace)
            if (saved.generation != marker.third || !member(c, actor, marker.second, saved.id)) fail(SavedRecipeFailureCode.COPY_CONFLICT)
            if (cached.status != 201 || cached.etag != "\"${saved.version}\"" || cached.body != saved.body) fail(SavedRecipeFailureCode.VERSION_CONFLICT)
            if (makeAgain) { current(); effect!!.replayed(c, actor, key, input, saved.body!!, saved.generation); current() }
        }) { c, trace ->
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
                trace?.saved(existing)
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
                if (persisted.body == null || canonical(persisted.body) != canonical(snapshot) || persisted.evidence == null ||
                    canonical(persisted.evidence) != canonical(permit.evidence) || persisted.generation != generation ||
                    persisted.recipeId != permit.recipeVersionId || persisted.hash != hash || persisted.planId != planId)
                    fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                trace?.saved(persisted)
                event(c, actor, key, "memory.recipe.saved.v1", "saved_recipe", id, 1, buildJsonObject {
                    put("principalId", actor.principalId.toString()); put("savedRecipeId", id.toString()); put("sourceType", type)
                }, trace)
                persisted
            }
            if (!member(c, actor, collection.id, saved.id)) {
                val position = query(c, "SELECT coalesce(max(position),0) FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=?",
                    { owner(actor); setObject(4, collection.id) }) { it.next(); increment(it.getLong(1)) }
                exec(c, "INSERT INTO memory.collection_items VALUES(?,?,?,?,?,?)") { owner(actor); setObject(4, collection.id); setObject(5, saved.id); setLong(6, position) }
                collectionChanged(c, actor, collection, key, at, "itemAdded", trace); advanceHead(c, actor)
            }
            exec(c, "INSERT INTO memory.save_commands VALUES(?,?,?,?,?,?,?)") {
                owner(actor); setObject(4, key); setObject(5, saved.id); setObject(6, collection.id); setLong(7, saved.generation)
            }
            if (makeAgain) {
                // Do not offer an intended row to child composition: read the actual copy
                // and membership after all inserts, while the same owner locks remain held.
                val actual = row(c, actor, saved.id)
                if (actual.deleted || actual.generation != saved.generation || actual.version != saved.version ||
                    canonical(actual.body!!) != canonical(saved.body!!) || !member(c, actor, collection.id, saved.id))
                    fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                current(); effect!!.applied(c, actor, key, input, actual.body!!, actual.generation, existing == null); current()
            }
            reply("saveRecipe", 201, saved.body, saved.version)
        }
    }

    fun getSavedRecipe(actor: VerifiedSavedRecipePrincipal, id: UUID): StoredReply = getSavedRecipeIn(null, actor, id).result
    internal fun getSavedRecipe(connection: Connection, actor: VerifiedSavedRecipePrincipal,
        id: UUID): Pending<StoredReply> = getSavedRecipeIn(connection, actor, id)
    private fun getSavedRecipeIn(connection: Connection?, actor: VerifiedSavedRecipePrincipal,
        id: UUID): Pending<StoredReply> = read(connection, actor) { c, trace ->
        val saved = authorized(c, actor, id, trace); reply("getSavedRecipe", 200, saved.body, saved.version)
    }

    fun deleteSavedRecipe(actor: VerifiedSavedRecipePrincipal, key: UUID, id: UUID, ifMatch: String): CommandResult =
        deleteSavedRecipeIn(null, actor, key, id, ifMatch).result
    internal fun deleteSavedRecipe(connection: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID,
        id: UUID, ifMatch: String): Pending<CommandResult> = deleteSavedRecipeIn(connection, actor, key, id, ifMatch)
    private fun deleteSavedRecipeIn(connection: Connection?, actor: VerifiedSavedRecipePrincipal, key: UUID,
        id: UUID, ifMatch: String): Pending<CommandResult> {
        val expected = version(ifMatch)
        return command(connection, actor, "deleteSavedRecipe", key, mapOf("savedRecipeId" to id.toString()), ifMatch = ifMatch,
            replay = { c, cached, _ ->
                val saved = row(c, actor, id)
                if (!saved.deleted || saved.deletionKey != key || saved.version != increment(expected) ||
                    latest(c, actor, saved.recipeId, saved.hash)?.id != id) fail(SavedRecipeFailureCode.VERSION_CONFLICT)
                if (cached.status != 204 || cached.body != null || cached.etag != null) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            }) { c, trace ->
            val saved = row(c, actor, id)
            if (saved.deleted) fail(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE)
            if (saved.version != expected) fail(SavedRecipeFailureCode.VERSION_CONFLICT)
            val collections = query(c, "SELECT collection_id FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND saved_recipe_id=? ORDER BY collection_id" +
                (if (trace == null) "" else " LIMIT 51"),
                { owner(actor); setObject(4, id) }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
            if (trace != null && collections.size > 50) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
            trace?.changedCollections?.addAll(collections)
            val at = now(c)
            for (collectionId in collections) {
                val col = collection(c, actor, collectionId)
                exec(c, "DELETE FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=? AND saved_recipe_id=?") {
                    owner(actor); setObject(4, collectionId); setObject(5, id)
                }
                collectionChanged(c, actor, col, key, at, "itemRemoved", trace)
            }
            exec(c, "UPDATE memory.saved_recipes SET deleted=true,snapshot=NULL,copy_evidence=NULL,deletion_key=?,version=?,updated_at=? WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? AND version=? AND NOT deleted") {
                setObject(1, key); setLong(2, increment(saved.version)); setObject(3, time(at)); owner(actor, 4); setObject(7, id); setLong(8, expected)
            }
            advanceHead(c, actor)
            event(c, actor, key, "memory.recipe.deleted.v1", "saved_recipe", id, saved.version + 1, buildJsonObject {
                put("principalId", actor.principalId.toString()); put("savedRecipeId", id.toString())
            }, trace)
            reply("deleteSavedRecipe", 204, null)
        }
    }

    fun listSavedRecipes(actor: VerifiedSavedRecipePrincipal, q: String? = null, cursor: String? = null,
        limit: Int = 20): StoredReply = listSavedRecipesIn(null, actor, q, cursor, limit).result
    internal fun listSavedRecipes(connection: Connection, actor: VerifiedSavedRecipePrincipal, q: String? = null,
        cursor: String? = null, limit: Int = 20): Pending<StoredReply> = listSavedRecipesIn(connection, actor, q, cursor, limit)
    private fun listSavedRecipesIn(connection: Connection?, actor: VerifiedSavedRecipePrincipal, q: String?,
        cursor: String?, limit: Int): Pending<StoredReply> {
        pageInputs(q, limit)
        return read(connection, actor) { c, trace ->
            val revision = head(c, actor); val at = now(c); val scope = if (q == null) "absent" else "text:$q"
            val after = cursors.decode(actor, "saved", scope, revision, cursor, at)
            trace?.head(revision); trace?.cursor("saved", scope, revision, cursor)
            fun selection() = query(c, "SELECT id FROM memory.saved_recipes WHERE environment=? AND actor_kind=? AND principal_id=? AND NOT deleted AND (?::uuid IS NULL OR id>?) AND (?::text IS NULL OR position(lower(?) in lower(snapshot->>'title'))>0) ORDER BY id LIMIT ?",
                { owner(actor); setObject(4, after); setObject(5, after); setString(6, q); setString(7, q); setInt(8, limit + 1) }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
            val ids = selection(); trace?.observe("saved-page", idArray(ids)) { idArray(selection()) }
            val items = mutableListOf<JsonElement>(); var next: String? = null
            for (id in ids.take(limit)) {
                val saved = authorized(c, actor, id, trace)
                val more = ids.size > items.size + 1
                val candidateCursor = if (more) token(actor, "saved", scope, revision, id, at, trace) else null
                val candidate = page(items + saved.body!!, candidateCursor, at)
                if (bytes(candidate).size > policy.maxResponseBytes) {
                    if (items.isEmpty()) fail(SavedRecipeFailureCode.RESPONSE_TOO_LARGE)
                    next = token(actor, "saved", scope, revision, UUID.fromString(items.last().jsonObject.text("id")), at, trace); break
                }
                items += saved.body!!; next = candidateCursor
            }
            reply("listSavedRecipes", 200, page(items, next, at))
        }
    }

    fun listCollections(actor: VerifiedSavedRecipePrincipal, cursor: String? = null, limit: Int = 20): StoredReply =
        listCollectionsIn(null, actor, cursor, limit).result
    internal fun listCollections(connection: Connection, actor: VerifiedSavedRecipePrincipal, cursor: String? = null,
        limit: Int = 20): Pending<StoredReply> = listCollectionsIn(connection, actor, cursor, limit)
    private fun listCollectionsIn(connection: Connection?, actor: VerifiedSavedRecipePrincipal, cursor: String?,
        limit: Int): Pending<StoredReply> {
        pageInputs(null, limit)
        return read(connection, actor) { c, trace ->
            val revision = head(c, actor); val at = now(c)
            val after = cursors.decode(actor, "collections", "", revision, cursor, at)
            trace?.head(revision); trace?.cursor("collections", "", revision, cursor)
            fun selection() = query(c, "SELECT id FROM memory.collections WHERE environment=? AND actor_kind=? AND principal_id=? AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",
                { owner(actor); setObject(4, after); setObject(5, after); setInt(6, limit + 1) }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
            val ids = selection(); trace?.observe("collection-page", idArray(ids)) { idArray(selection()) }
            val items = mutableListOf<JsonElement>(); var next: String? = null
            for (id in ids.take(limit)) {
                val document = collectionPage(c, actor, collection(c, actor, id), null, 20, at, trace)
                val candidateCursor = if (ids.size > items.size + 1) token(actor, "collections", "", revision, id, at, trace) else null
                if (bytes(page(items + document, candidateCursor, at)).size > policy.maxResponseBytes) {
                    if (items.isEmpty()) fail(SavedRecipeFailureCode.RESPONSE_TOO_LARGE)
                    next = token(actor, "collections", "", revision, UUID.fromString(items.last().jsonObject.text("id")), at, trace); break
                }
                items += document; next = candidateCursor
            }
            reply("listCollections", 200, page(items, next, at))
        }
    }

    fun getCollection(actor: VerifiedSavedRecipePrincipal, id: UUID, cursor: String? = null, limit: Int = 20): StoredReply =
        getCollectionIn(null, actor, id, cursor, limit).result
    internal fun getCollection(connection: Connection, actor: VerifiedSavedRecipePrincipal, id: UUID,
        cursor: String? = null, limit: Int = 20): Pending<StoredReply> = getCollectionIn(connection, actor, id, cursor, limit)
    private fun getCollectionIn(connection: Connection?, actor: VerifiedSavedRecipePrincipal, id: UUID,
        cursor: String?, limit: Int): Pending<StoredReply> {
        pageInputs(null, limit)
        return read(connection, actor) { c, trace -> val col = collection(c, actor, id)
            reply("getCollection", 200, collectionPage(c, actor, col, cursor, limit, now(c), trace), col.version)
        }
    }

    private fun collectionPage(c: Connection, actor: VerifiedSavedRecipePrincipal, col: CollectionRow, cursor: String?,
        limit: Int, at: Instant, trace: Trace? = null): JsonObject {
        val after = cursors.decode(actor, "items", col.id.toString(), col.version, cursor, at)
        trace?.collection(col); trace?.cursor("items", col.id.toString(), col.version, cursor)
        val afterPosition = if (after == null) 0L else query(c,
            "SELECT position FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=? AND saved_recipe_id=?",
            { owner(actor); setObject(4, col.id); setObject(5, after) }) { if (!it.next()) fail(SavedRecipeFailureCode.CURSOR_INVALID); it.getLong(1) }
        after?.let { trace?.membership(col.id, it) }
        fun selection() = query(c, "SELECT saved_recipe_id FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND collection_id=? AND position>? ORDER BY position,saved_recipe_id LIMIT ?",
            { owner(actor); setObject(4, col.id); setLong(5, afterPosition); setInt(6, limit + 1) }) { r -> buildList { while (r.next()) add(r.getObject(1, UUID::class.java)) } }
        val ids = selection(); trace?.observe("items-page:${col.id}", idArray(ids)) { idArray(selection()) }
        if (trace != null) ids.forEach { trace.membership(col.id, it) }
        val selected = mutableListOf<UUID>(); var next: String? = null
        for (id in ids.take(limit)) {
            authorized(c, actor, id, trace)
            val candidateCursor = if (ids.size > selected.size + 1) token(actor, "items", col.id.toString(), col.version, id, at, trace) else null
            if (bytes(collectionJson(col, selected + id, candidateCursor)).size + PAGE_RESERVE > policy.maxResponseBytes) {
                if (selected.isEmpty()) fail(SavedRecipeFailureCode.RESPONSE_TOO_LARGE)
                next = token(actor, "items", col.id.toString(), col.version, selected.last(), at, trace); break
            }
            selected += id; next = candidateCursor
        }
        return collectionJson(col, selected, next)
    }

    private fun ownedPlanRecipe(c: Connection, actor: VerifiedSavedRecipePrincipal, id: UUID): JsonObject = query(c,
        "SELECT snapshot_text,snapshot_hash,status,recipe_version_id,storage_format FROM planning.plans WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? FOR SHARE",
        { owner(actor); setObject(4, id) }) { r ->
        if (!r.next()) fail(SavedRecipeFailureCode.PLAN_UNAVAILABLE)
        // Existing v1/v2 owners retain their explicit copy authorities. Derived Plans
        // require provenance-aware copy authorization before they enter this old path.
        if (r.getInt(5) !in 1..2) fail(SavedRecipeFailureCode.NOT_CONFIGURED)
        val text = r.getString(1); val document = Json.parseToJsonElement(text).jsonObject
        if (digest(text) != r.getString(2) || document["id"] != JsonPrimitive(id.toString())) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        if (r.getString(3) != "ready" || document["status"] != JsonPrimitive("ready")) fail(SavedRecipeFailureCode.RECIPE_UNAVAILABLE)
        val recipe = document["recipeSnapshot"]?.jsonObject ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        if (recipe["id"] != JsonPrimitive(r.getObject(4, UUID::class.java).toString())) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        recipe
    }

    private fun authorized(c: Connection, actor: VerifiedSavedRecipePrincipal, id: UUID, trace: Trace? = null): Row =
        row(c, actor, id).also { trace?.saved(it); authorize(c, actor, it) }
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
    private fun collectionChanged(c: Connection, actor: VerifiedSavedRecipePrincipal, col: CollectionRow, key: UUID,
        at: Instant, action: String, trace: Trace? = null) {
        val version = increment(col.version)
        exec(c, "UPDATE memory.collections SET version=?,updated_at=? WHERE environment=? AND actor_kind=? AND principal_id=? AND id=? AND version=?") {
            setLong(1, version); setObject(2, time(at)); owner(actor, 3); setObject(6, col.id); setLong(7, col.version)
        }
        event(c, actor, key, "memory.collection.changed.v1", "collection", col.id, version, buildJsonObject {
            put("principalId", actor.principalId.toString()); put("collectionId", col.id.toString()); put("action", action)
        }, trace)
    }
    private fun head(c: Connection, actor: VerifiedSavedRecipePrincipal): Long = query(c,
        "SELECT revision FROM memory.library_heads WHERE environment=? AND actor_kind=? AND principal_id=? FOR UPDATE", { owner(actor) }) { if (it.next()) it.getLong(1) else 0 }
    private fun ensureHead(c: Connection, actor: VerifiedSavedRecipePrincipal) {
        if (head(c, actor) == 0L) exec(c, "INSERT INTO memory.library_heads VALUES(?,?,?,1)") { owner(actor) }
    }
    private fun advanceHead(c: Connection, actor: VerifiedSavedRecipePrincipal) {
        val next = increment(head(c, actor)); exec(c, "UPDATE memory.library_heads SET revision=? WHERE environment=? AND actor_kind=? AND principal_id=?") { setLong(1, next); owner(actor, 2) }
    }
    private fun event(c: Connection, actor: VerifiedSavedRecipePrincipal, key: UUID, type: String, aggregate: String,
        id: UUID, version: Long, data: JsonObject, trace: Trace? = null) {
        current()
        val draft = EventDraft(UUID.randomUUID(), type, 1, aggregate, id, version, "memory", UUID.randomUUID().toString(), key, data)
        outbox.append(c, draft); trace?.events?.add(draft)
    }
    private fun command(connection: Connection?, actor: VerifiedSavedRecipePrincipal, op: String, key: UUID,
        paths: Map<String, String> = emptyMap(), body: JsonObject? = null, ifMatch: String? = null,
        replay: (Connection, StoredReply, Trace?) -> Unit, mutate: (Connection, Trace?) -> StoredReply): Pending<CommandResult> = safe(connection != null) {
        checkActor(actor); current()
        val identity = CommandIdentity(PrincipalScope(environment, actor.kind, actor.principalId), op, key, paths, body = body, ifMatch = ifMatch)
        inTransaction(connection) { c ->
            val trace = connection?.let { Trace(c, actor) }
            val result = commands.executeInTransaction(c, identity, {
                authority.lockPrincipal(it, actor); current(); trace?.start(now(it))
            }, {},
                { db, cached -> replay(db, cached, trace); current() }, { db -> mutate(db, trace).also { current() } })
            if (trace != null) when (result) {
                is CommandResult.Applied -> trace.command(identity, result.reply, paths)
                is CommandResult.Replayed -> trace.command(identity, result.reply, paths)
                else -> trace.refuseDomain() // A receipt refusal is not a successful Save/delete.
            }
            // Receipt, copy and collection waits do not extend provider authority. The same
            // principal roots are already held; recheck before commit or cached disclosure.
            current(); authority.lockPrincipal(c, actor); current()
            pending(result, trace)
        }
    }
    private fun read(connection: Connection?, actor: VerifiedSavedRecipePrincipal,
        action: (Connection, Trace?) -> StoredReply): Pending<StoredReply> = safe(connection != null) {
        checkActor(actor); current(); inTransaction(connection) { c ->
            val trace = connection?.let { Trace(c, actor) }
            authority.lockPrincipal(c, actor); current()
            trace?.start(now(c))
            val result = action(c, trace)
            trace?.head(head(c, actor))
            current(); authority.lockPrincipal(c, actor); current()
            pending(result, trace)
        }
    }
    private fun <T> inTransaction(connection: Connection?, action: (Connection) -> T): T {
        if (connection == null) return transactions.run(action)
        if (connection.isClosed || connection.autoCommit) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        val result = action(connection)
        if (connection.isClosed || connection.autoCommit) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        return result
    }
    private fun <T> safe(callerOwned: Boolean = false, action: () -> T): T = try { action() }
        catch (failure: SavedRecipeFailure) { throw failure }
        catch (failure: FeedbackFailure) { throw failure }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: PlanningServiceFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: SQLException) {
            current()
            if (callerOwned && failure.sqlState in setOf("40001", "40P01")) throw failure
            fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        }
        catch (_: Exception) { current(); fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) }

    /** Provisional, not committed or authorized for disclosure. Only the actual transaction
     * owner may complete this after its final identity and copy-rights checks. A reusable
     * store retains no invocation state; a refused completion cannot later be revived. */
    internal class Pending<T> internal constructor(val result: T,
        private val completion: (Connection, VerifiedSavedRecipePrincipal) -> Unit,
        private val finalTime: (Connection, VerifiedSavedRecipePrincipal, Instant) -> Unit) {
        fun revalidate(connection: Connection, actor: VerifiedSavedRecipePrincipal) = completion(connection, actor)
        /** No SQL or I/O. The real transaction owner supplies its final accepted DB time,
         * after every authority/domain wait; this is never a caller-supplied clock grant. */
        fun checkAt(connection: Connection, actor: VerifiedSavedRecipePrincipal, at: Instant) = finalTime(connection, actor, at)
        override fun toString() = "SavedRecipePending(<redacted>)"
    }
    private fun <T> pending(result: T, trace: Trace?): Pending<T> {
        val refused = AtomicBoolean(false)
        fun checked(action: () -> Unit): Unit = safe(true) {
            if (refused.get()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            try {
                // Public methods discard this object before their owned commit. They cannot
                // be converted into an accepting caller-owned completion path afterward.
                if (trace == null) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                action()
            } catch (failure: Throwable) { refused.set(true); throw failure }
        }
        return Pending(result,
            { connection, actor -> checked { trace!!.revalidate(connection, actor) } },
            { connection, actor, at -> checked { trace!!.checkAt(connection, actor, at) } })
    }

    /** Bounded, rejecting-only observations. SQL rows are compared with canonical JSONB
     * numeric semantics, without retaining an unbounded owner's library snapshot. Lists
     * preserve their original limit+1 selection, cursor anchor and returned member images;
     * final checks never rerender a new page or mint a replacement cursor. */
    private inner class Trace(private val connection: Connection, private val actor: VerifiedSavedRecipePrincipal) {
        private val thread = Thread.currentThread()
        private val transaction = transactionId()
        private val observations = linkedMapOf<String, Pair<String, () -> JsonElement>>()
        private val cursorChecks = linkedMapOf<String, (Instant) -> Unit>()
        private var firstObservedTime: Instant? = null
        private var receiptExpires: Instant? = null
        val events = mutableListOf<EventDraft>()
        val changedCollections = linkedSetOf<UUID>()

        fun start(at: Instant) {
            if (firstObservedTime != null) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            firstObservedTime = at
        }
        fun observe(key: String, expected: JsonElement, read: () -> JsonElement) {
            current()
            val hash = digest(canonical(expected))
            val previous = observations[key]
            if (previous != null) {
                if (previous.first != hash) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            } else observations[key] = hash to read
        }
        fun saved(expected: Row) {
            fun load() = ownedImage("memory.saved_recipes", " AND id=?") { setObject(4, expected.id) }
            val actual = load()
            if (actual["id"] != JsonPrimitive(expected.id.toString()) ||
                actual["generation"] != JsonPrimitive(expected.generation) || actual["version"] != JsonPrimitive(expected.version) ||
                actual["recipe_version_id"] != JsonPrimitive(expected.recipeId.toString()) || actual["recipe_hash"] != JsonPrimitive(expected.hash) ||
                canonical(actual.getValue("snapshot")) != canonical(expected.body ?: JsonNull) ||
                canonical(actual.getValue("copy_evidence")) != canonical(expected.evidence ?: JsonNull) ||
                actual["origin_plan_id"] != nullableId(expected.planId) || actual["deleted"] != JsonPrimitive(expected.deleted) ||
                actual["deletion_key"] != nullableId(expected.deletionKey)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            observe("saved:${expected.id}", actual, ::load)
        }
        fun collection(expected: CollectionRow) {
            fun load() = ownedImage("memory.collections", " AND id=?") { setObject(4, expected.id) }
            val actual = load()
            if (actual["id"] != JsonPrimitive(expected.id.toString()) || actual["version"] != JsonPrimitive(expected.version) ||
                actual["is_default"] != JsonPrimitive(expected.isDefault) || actual["name"] != JsonPrimitive(expected.name) ||
                actual["description"] != (expected.description?.let(::JsonPrimitive) ?: JsonNull) ||
                storedInstant(actual, "created_at") != expected.created || storedInstant(actual, "updated_at") != expected.updated)
                fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            observe("collection:${expected.id}", actual, ::load)
        }
        fun membership(collectionId: UUID, savedId: UUID) {
            fun load() = ownedImage("memory.collection_items", " AND collection_id=? AND saved_recipe_id=?") {
                setObject(4, collectionId); setObject(5, savedId)
            }
            val actual = load()
            if (actual["collection_id"] != JsonPrimitive(collectionId.toString()) || actual["saved_recipe_id"] != JsonPrimitive(savedId.toString()))
                fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            observe("membership:$collectionId:$savedId", actual, ::load)
        }
        fun head(expected: Long) {
            fun load(): JsonElement = optionalOwnedImage("memory.library_heads") ?: JsonNull
            val actual = load()
            val revision = (actual as? JsonObject)?.get("revision")?.jsonPrimitive?.long ?: 0L
            if (revision != expected || (actual != JsonNull && revision <= 0)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            observe("head", actual, ::load)
        }
        fun cursor(purpose: String, scope: String, revision: Long, token: String?) {
            if (token == null) return
            val key = digest("$purpose\u0000$scope\u0000$revision\u0000$token")
            cursorChecks.putIfAbsent(key) { at -> cursors.decode(actor, purpose, scope, revision, token, at); Unit }
        }
        fun refuseDomain() {
            observations.clear(); cursorChecks.clear(); events.clear(); changedCollections.clear(); receiptExpires = null
        }
        fun command(identity: CommandIdentity, response: StoredReply, paths: Map<String, String>) {
            fun receipt() = image(connection, "platform.idempotency", "principal_scope=? AND operation_id=? AND key=?") {
                setString(1, identity.scope.storageKey); setString(2, identity.operationId); setObject(3, identity.key)
            } ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            val retained = receipt()
            if (retained["principal_scope"] != JsonPrimitive(identity.scope.storageKey) ||
                retained["operation_id"] != JsonPrimitive(identity.operationId) || retained["key"] != JsonPrimitive(identity.key.toString()) ||
                retained["request_hash"] != JsonPrimitive(identity.requestHash) || retained["state"] != JsonPrimitive("completed") ||
                retained["response_code"] != JsonPrimitive(response.status) ||
                canonical(retained.getValue("response_json")) != canonical(response.body ?: JsonNull) ||
                retained["response_etag"] != (response.etag?.let(::JsonPrimitive) ?: JsonNull)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            receiptExpires = storedInstant(retained, "expires_at")
            observe("receipt", retained, ::receipt)
            val saved = when (identity.operationId) {
                "saveRecipe" -> {
                    val id = UUID.fromString(response.body!!.jsonObject.text("id"))
                    val value = row(connection, actor, id)
                    if (value.deleted || response.status != 201 || response.etag != "\"${value.version}\"" ||
                        canonical(response.body ?: JsonNull) != canonical(value.body ?: JsonNull)) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                    fun marker() = ownedImage("memory.save_commands", " AND command_id=?") { setObject(4, identity.key) }
                    val command = marker()
                    if (command["command_id"] != JsonPrimitive(identity.key.toString()) ||
                        command["saved_recipe_id"] != JsonPrimitive(id.toString()) || command["generation"] != JsonPrimitive(value.generation))
                        fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                    val collectionId = UUID.fromString(command.text("collection_id"))
                    val col = this@SavedRecipeStore.collection(connection, actor, collectionId); requireDefaultCollection(col)
                    collection(col); membership(collectionId, id)
                    observe("save-marker", command, ::marker)
                    value
                }
                "deleteSavedRecipe" -> {
                    val id = UUID.fromString(paths.getValue("savedRecipeId"))
                    val value = row(connection, actor, id)
                    if (!value.deleted || value.deletionKey != identity.key || value.body != null || value.evidence != null ||
                        response.status != 204 || response.body != null || response.etag != null) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                    fun memberships(): JsonElement = query(connection,
                        "SELECT collection_id FROM memory.collection_items WHERE environment=? AND actor_kind=? AND principal_id=? AND saved_recipe_id=? LIMIT 1",
                        { owner(actor); setObject(4, id) }) { rows ->
                        if (rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                        JsonArray(emptyList())
                    }
                    observe("deleted-memberships", memberships(), ::memberships)
                    changedCollections.forEach { collection(this@SavedRecipeStore.collection(connection, actor, it)) }
                    // Deletion remains available without a catalog or an existing-copy grant.
                    fun default(): JsonElement = optionalOwnedImage("memory.collections", " AND is_default") ?: JsonNull
                    observe("default-collection", default(), ::default)
                    value
                }
                else -> fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            }
            this.saved(saved)
            fun newest(): JsonElement = query(connection,
                "SELECT to_jsonb(retained_row) FROM memory.saved_recipes AS retained_row WHERE environment=? AND actor_kind=? AND principal_id=? AND recipe_version_id=? AND recipe_hash=? ORDER BY generation DESC LIMIT 1 FOR SHARE",
                { owner(actor); setObject(4, saved.recipeId); setString(5, saved.hash) }) { rows ->
                if (!rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                Json.parseToJsonElement(rows.getString(1)).jsonObject.also { value ->
                    requireImageOwner(value, actor)
                    if (value["id"] != JsonPrimitive(saved.id.toString()) || value["generation"] != JsonPrimitive(saved.generation))
                        fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                    if (rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                }
            }
            observe("latest-generation", newest(), ::newest)
            head(this@SavedRecipeStore.head(connection, actor))
            events.forEach { draft ->
                fun load() = image(connection, "platform.outbox", "event_id=?") { setObject(1, draft.eventId) }
                    ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                val event = load()
                if (event["event_id"] != JsonPrimitive(draft.eventId.toString()) || event["event_type"] != JsonPrimitive(draft.eventType) ||
                    event["schema_version"] != JsonPrimitive(draft.schemaVersion) || event["aggregate_type"] != JsonPrimitive(draft.aggregateType) ||
                    event["aggregate_id"] != JsonPrimitive(draft.aggregateId.toString()) || event["aggregate_version"] != JsonPrimitive(draft.aggregateVersion) ||
                    event["producer"] != JsonPrimitive(draft.producer) || event["correlation_id"] != JsonPrimitive(draft.correlationId) ||
                    event["causation_id"] != JsonPrimitive(draft.causationId.toString()) || canonical(event.getValue("payload")) != canonical(draft.data))
                    fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                observe("event:${draft.eventId}", event, ::load)
            }
        }
        fun revalidate(c: Connection, actual: VerifiedSavedRecipePrincipal) {
            check(c, actual)
            observations.values.forEach { (hash, read) ->
                if (digest(canonical(read())) != hash) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                check(c, actual)
            }
            // No DB wait or authority callback follows this time read. Never extend a
            // retained receipt or cursor because the final principal/copy check waited.
            checkAt(c, actual, now(connection))
        }
        /** The parent supplies the final actual DB time after all domains/guest checks.
         * This method deliberately performs no transaction-ID query or other SQL. */
        fun checkAt(c: Connection, actual: VerifiedSavedRecipePrincipal, at: Instant) {
            checkLocal(c, actual)
            val first = firstObservedTime ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            if (at < first) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            if (receiptExpires?.isAfter(at) == false) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            cursorChecks.values.forEach { it(at) }
            current()
        }
        private fun checkLocal(c: Connection, actual: VerifiedSavedRecipePrincipal) {
            current()
            if (Thread.currentThread() !== thread || c !== connection || actual !== actor || actor.environment != environment ||
                c.isClosed || c.autoCommit) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
        }
        private fun check(c: Connection, actual: VerifiedSavedRecipePrincipal) {
            checkLocal(c, actual)
            if (transactionId() != transaction) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
        }
        private fun transactionId(): Long {
            current()
            if (connection.isClosed || connection.autoCommit) fail(SavedRecipeFailureCode.UNAUTHENTICATED)
            return connection.createStatement().use { statement -> statement.executeQuery("SELECT txid_current()").use { rows ->
                if (!rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                val value = rows.getLong(1)
                if (rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
                value
            } }
        }
        private fun ownedImage(table: String, suffix: String = "", bind: PreparedStatement.() -> Unit = {}): JsonObject =
            optionalOwnedImage(table, suffix, bind) ?: fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
        private fun optionalOwnedImage(table: String, suffix: String = "", bind: PreparedStatement.() -> Unit = {}): JsonObject? =
            image(connection, table, "environment=? AND actor_kind=? AND principal_id=?$suffix") { owner(actor); bind() }
                ?.also { requireImageOwner(it, actor) }
    }
    private fun requireImageOwner(value: JsonObject, actor: VerifiedSavedRecipePrincipal) {
        if (value["environment"] != JsonPrimitive(environment) || value["actor_kind"] != JsonPrimitive(actor.kind.name.lowercase()) ||
            value["principal_id"] != JsonPrimitive(actor.principalId.toString())) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
    }
    // Only private source-constant tables/predicates reach this reader, never request text.
    private fun image(c: Connection, table: String, where: String, bind: PreparedStatement.() -> Unit): JsonObject? =
        query(c, "SELECT to_jsonb(retained_row) FROM $table AS retained_row WHERE $where FOR SHARE", bind) { rows ->
            if (!rows.next()) return@query null
            val value = Json.parseToJsonElement(rows.getString(1)).jsonObject
            if (rows.next()) fail(SavedRecipeFailureCode.STORAGE_UNAVAILABLE)
            value
        }
    private fun storedInstant(value: JsonObject, name: String) = OffsetDateTime.parse(value.text(name)).toInstant()
    private fun nullableId(id: UUID?) = id?.let { JsonPrimitive(it.toString()) } ?: JsonNull
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
    private fun token(actor: VerifiedSavedRecipePrincipal, purpose: String, scope: String, revision: Long, after: UUID,
        at: Instant, trace: Trace? = null) = cursors.encode(actor, purpose, scope, revision, after,
        at.plusSeconds(policy.cursorLifetimeSeconds.toLong())).also { trace?.cursor(purpose, scope, revision, it) }
    private fun idArray(ids: List<UUID>) = JsonArray(ids.map { JsonPrimitive(it.toString()) })
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
