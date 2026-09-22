package com.feedme.server.kitchen

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import java.nio.charset.CharacterCodingException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * Private, versioned PostgreSQL inputs; no HTTP, identity, catalog or provisioning defaults.
 * Each command atomically commits its domain row, original-key receipt and canonical outbox fact.
 * Current authority precedes cached disclosure. Replays require the exact current representation
 * or exact deletion marker; a later write/delete/recreation never revives an old receipt.
 * GET never initializes rows. Pantry absence remains absence, not a reported quantity or safety fact.
 */
class KitchenStore(private val environment: String, private val transactions: PgTransactions,
    private val authority: KitchenAuthority, private val cursors: KitchenCursorCodec, val policy: KitchenServicePolicy) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    /** Trusted lifecycle integration ONLY. Initial fields must be explicit and complete; no public route calls this. */
    fun provisionPreferences(actor: VerifiedKitchenPrincipal, initial: JsonObject): StoredReply {
        val fields = request("updatePreferences", initial)
        if (!fields.keys.containsAll(setOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds", "equipmentIds")))
            fail(KitchenFailureCode.INPUT_INVALID)
        return read(actor) { c ->
            authority.requireProvisioningAllowed(c, actor)
            KitchenPreferenceProvisioning.provision(c, environment, actor.kind, actor.principalId,
                fields, UUID.randomUUID(), policy.maxResponseBytes, outbox) { proposed ->
                authority.validatePreferences(c, actor, proposed)
            }
        }
    }

    fun getPreferences(actor: VerifiedKitchenPrincipal): StoredReply = getPreferencesIn(null, actor)

    /** Internal lifecycle composition only: the caller owns this already-open transaction,
     * its commit/rollback, and final current-identity checks. These overloads retain every
     * normal kitchen authority, receipt, version, response and completion check; they never
     * open a nested transaction or treat the supplied principal as authentication. */
    internal fun getPreferences(connection: Connection, actor: VerifiedKitchenPrincipal): StoredReply =
        getPreferencesIn(connection, actor)

    private fun getPreferencesIn(connection: Connection?, actor: VerifiedKitchenPrincipal): StoredReply = read(connection, actor) { c ->
        val row = preference(c, actor) ?: fail(KitchenFailureCode.PREFERENCES_UNAVAILABLE)
        val body = preferenceJson(row)
        reply("getPreferences", 200, body, row.version)
    }

    fun updatePreferences(actor: VerifiedKitchenPrincipal, key: UUID, ifMatch: String, body: JsonObject): CommandResult =
        updatePreferencesIn(null, actor, key, ifMatch, body)

    internal fun updatePreferences(connection: Connection, actor: VerifiedKitchenPrincipal, key: UUID,
        ifMatch: String, body: JsonObject): CommandResult = updatePreferencesIn(connection, actor, key, ifMatch, body)

    private fun updatePreferencesIn(connection: Connection?, actor: VerifiedKitchenPrincipal, key: UUID,
        ifMatch: String, body: JsonObject): CommandResult {
        val patch = request("updatePreferences", body); val expected = version(ifMatch)
        return command(connection, actor, "updatePreferences", key, body = patch, ifMatch = ifMatch, replay = { c, cached ->
            val row = preference(c, actor) ?: fail(KitchenFailureCode.PREFERENCES_UNAVAILABLE)
            val current = preferenceJson(row)
            currentReply(cached, reply("updatePreferences", 200, current, row.version))
        }) { c ->
            val previous = preference(c, actor) ?: fail(KitchenFailureCode.PREFERENCES_UNAVAILABLE)
            expect(previous.version, expected)
            val changed = previous.copy(version = increment(previous.version), updated = now(c), fields = JsonObject(previous.fields + patch))
            val result = preferenceJson(changed); reply("updatePreferences", 200, result, changed.version)
            authority.validatePreferences(c, actor, result)
            exec(c, "UPDATE profile.preferences SET fields=?::jsonb,version=?,updated_at=? WHERE environment=? AND actor_kind=? AND principal_id=?") {
                setString(1, changed.fields.toString()); setLong(2, changed.version); setObject(3, time(changed.updated)); owner(actor, 4)
            }
            preferenceEvent(c, actor, key, changed, patch.keys.filter { previous.fields[it] != patch[it] }.toSet())
            reply("updatePreferences", 200, preferenceJson(preference(c, actor)!!), changed.version)
        }
    }

    fun listPantry(actor: VerifiedKitchenPrincipal, cursor: String? = null, limit: Int = 20): StoredReply =
        listPantryIn(null, actor, cursor, limit)

    internal fun listPantry(connection: Connection, actor: VerifiedKitchenPrincipal,
        cursor: String? = null, limit: Int = 20): StoredReply = listPantryIn(connection, actor, cursor, limit)

    private fun listPantryIn(connection: Connection?, actor: VerifiedKitchenPrincipal, cursor: String?, limit: Int): StoredReply {
        limit(limit)
        return read(connection, actor) { c ->
            val at = now(c); val after = cursors.decode(actor, cursor, at)
            val rows = query(c, "SELECT * FROM pantry.pantry_items WHERE environment=? AND actor_kind=? AND principal_id=? AND NOT deleted " +
                "AND (?::uuid IS NULL OR ingredient_id>?::uuid) ORDER BY ingredient_id LIMIT ? FOR UPDATE", {
                owner(actor); setObject(4, after); setObject(5, after); setInt(6, limit + 1)
            }) { r -> buildList { while (r.next()) add(pantryRow(r)) } }
            val items = rows.take(limit).map(::pantryJson)
            reply("listPantry", 200, buildJsonObject {
                put("items", JsonArray(items))
                put("nextCursor", if (rows.size > limit) JsonPrimitive(cursors.encode(actor, rows[limit - 1].ingredientId!!,
                    at.plusSeconds(policy.cursorLifetimeSeconds.toLong()))) else JsonNull)
                put("serverTime", at.toString())
            })
        }
    }

    fun upsertPantryItem(actor: VerifiedKitchenPrincipal, key: UUID, body: JsonObject): CommandResult =
        upsertPantryItemIn(null, actor, key, body)

    internal fun upsertPantryItem(connection: Connection, actor: VerifiedKitchenPrincipal, key: UUID,
        body: JsonObject): CommandResult = upsertPantryItemIn(connection, actor, key, body)

    private fun upsertPantryItemIn(connection: Connection?, actor: VerifiedKitchenPrincipal, key: UUID,
        body: JsonObject): CommandResult {
        val input = request("upsertPantryItem", body); val ingredient = uuid(input, "ingredientId")
        val expected = input["expectedVersion"]?.jsonPrimitive?.content?.toBigDecimalOrNull()?.let {
            try { it.longValueExact().also { v -> if (v < 1) fail(KitchenFailureCode.INPUT_INVALID) } }
            catch (_: ArithmeticException) { fail(KitchenFailureCode.INPUT_INVALID) }
        }
        if (input.containsKey("expectedVersion") && expected == null) fail(KitchenFailureCode.INPUT_INVALID)
        return command(connection, actor, "upsertPantryItem", key, body = input, replay = { c, cached ->
            val row = pantry(c, actor, ingredient)?.takeUnless { it.deleted } ?: fail(KitchenFailureCode.PANTRY_ITEM_UNAVAILABLE)
            val current = pantryJson(row)
            currentReply(cached, reply("upsertPantryItem", 200, current, row.version))
        }) { c ->
            val previous = pantry(c, actor, ingredient); val active = previous?.takeUnless { it.deleted }
            if (active == null) { if (expected != null) fail(KitchenFailureCode.VERSION_CONFLICT) }
            else { if (expected == null) fail(KitchenFailureCode.VERSION_CONFLICT); expect(active.version, expected) }
            // Upsert updates explicitly supplied fields. Missing optional fields preserve an existing
            // report; only a NEW incarnation defaults confirmedAt=null/staple=false, never confirmation.
            val fields = JsonObject((active?.fields ?: buildJsonObject { put("confirmedAt", JsonNull); put("staple", false) }) +
                (input - setOf("ingredientId", "expectedVersion")))
            val at = now(c)
            val changed = Row(active?.id ?: UUID.randomUUID(), previous?.version?.let(::increment) ?: 1,
                active?.created ?: at, at, fields, ingredient)
            val result = pantryJson(changed); reply("upsertPantryItem", 200, result, changed.version)
            authority.validatePantryItem(c, actor, result)
            exec(c, "INSERT INTO pantry.pantry_items(environment,actor_kind,principal_id,ingredient_id,id,version,created_at,updated_at,fields) " +
                "VALUES(?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(environment,actor_kind,principal_id,ingredient_id) DO UPDATE SET " +
                "id=EXCLUDED.id,version=EXCLUDED.version,created_at=EXCLUDED.created_at,updated_at=EXCLUDED.updated_at,fields=EXCLUDED.fields,deleted=false,deletion_key=NULL") {
                owner(actor); setObject(4, ingredient); setObject(5, changed.id); setLong(6, changed.version)
                setObject(7, time(changed.created)); setObject(8, time(changed.updated)); setString(9, fields.toString())
            }
            pantryEvent(c, actor, key, changed, "upserted")
            reply("upsertPantryItem", 200, pantryJson(pantry(c, actor, ingredient)!!), changed.version)
        }
    }

    fun removePantryItem(actor: VerifiedKitchenPrincipal, key: UUID, ingredientId: UUID, ifMatch: String): CommandResult =
        removePantryItemIn(null, actor, key, ingredientId, ifMatch)

    internal fun removePantryItem(connection: Connection, actor: VerifiedKitchenPrincipal, key: UUID,
        ingredientId: UUID, ifMatch: String): CommandResult = removePantryItemIn(connection, actor, key, ingredientId, ifMatch)

    private fun removePantryItemIn(connection: Connection?, actor: VerifiedKitchenPrincipal, key: UUID,
        ingredientId: UUID, ifMatch: String): CommandResult {
        val expected = version(ifMatch)
        return command(connection, actor, "removePantryItem", key, paths = mapOf("ingredientId" to ingredientId.toString()), ifMatch = ifMatch, replay = { c, cached ->
            val row = pantry(c, actor, ingredientId) ?: fail(KitchenFailureCode.PANTRY_ITEM_UNAVAILABLE)
            if (!row.deleted || row.deletionKey != key || row.version != increment(expected)) fail(KitchenFailureCode.VERSION_CONFLICT)
            currentReply(cached, reply("removePantryItem", 204, null))
        }) { c ->
            val previous = pantry(c, actor, ingredientId)?.takeUnless { it.deleted } ?: fail(KitchenFailureCode.PANTRY_ITEM_UNAVAILABLE)
            // Removing an owned rough report does not require the ingredient to remain published.
            expect(previous.version, expected)
            val changed = previous.copy(version = increment(previous.version), updated = now(c), fields = JsonObject(emptyMap()), deleted = true, deletionKey = key)
            val response = reply("removePantryItem", 204, null)
            exec(c, "UPDATE pantry.pantry_items SET deleted=true,fields=NULL,deletion_key=?,version=?,updated_at=? WHERE environment=? AND actor_kind=? AND principal_id=? AND ingredient_id=?") {
                setObject(1, key); setLong(2, changed.version); setObject(3, time(changed.updated)); owner(actor, 4); setObject(7, ingredientId)
            }
            pantryEvent(c, actor, key, changed, "removed")
            response
        }
    }

    fun searchIngredients(actor: VerifiedKitchenPrincipal, q: String?, cursor: String?, limit: Int,
        search: KitchenIngredientSearch): StoredReply {
        limit(limit)
        if ((q != null && (q.codePointCount(0, q.length) > 100 || !validText(q))) ||
            (cursor != null && (cursor.codePointCount(0, cursor.length) > 2048 || !validText(cursor))))
            fail(KitchenFailureCode.INPUT_INVALID)
        return read(actor) { c -> reply("searchIngredients", 200, search.search(c, actor, q, cursor, limit)) }
    }

    fun lookupIngredients(actor: VerifiedKitchenPrincipal, ids: List<UUID>, search: KitchenIngredientSearch): StoredReply {
        val selected = checkedIngredientIds(ids)
        return read(actor) { c -> reply("searchIngredients", 200, search.lookup(c, actor, selected)) }
    }

    private fun command(connection: Connection?, actor: VerifiedKitchenPrincipal, op: String, key: UUID, paths: Map<String, String> = emptyMap(),
        body: JsonObject? = null, ifMatch: String? = null, replay: (Connection, StoredReply) -> Unit,
        mutate: (Connection) -> StoredReply): CommandResult = safe {
        checkActor(actor)
        val command = CommandIdentity(PrincipalScope(environment, actor.kind, actor.principalId), op, key, paths, body = body, ifMatch = ifMatch)
        inTransaction(connection) { c ->
            val result = commands.executeInTransaction(c, command, { authority.lockPrincipal(it, actor) }, {}, replay, mutate)
            (authority as? KitchenCompletionAuthority)?.revalidatePrincipal(c, actor)
            result
        }
    }
    private fun <T> read(actor: VerifiedKitchenPrincipal, action: (Connection) -> T): T = read(null, actor, action)

    private fun <T> read(connection: Connection?, actor: VerifiedKitchenPrincipal, action: (Connection) -> T): T = safe {
        checkActor(actor)
        inTransaction(connection) { c ->
            authority.lockPrincipal(c, actor)
            val result = action(c)
            (authority as? KitchenCompletionAuthority)?.revalidatePrincipal(c, actor)
            result
        }
    }
    private fun <T> inTransaction(connection: Connection?, action: (Connection) -> T): T {
        if (connection == null) return transactions.run(action)
        require(!connection.autoCommit) { "Use the caller's owned kitchen transaction" }
        val result = action(connection)
        check(!connection.autoCommit) { "Kitchen callback changed connection ownership" }
        return result
    }
    private fun checkActor(actor: VerifiedKitchenPrincipal) { if (actor.environment != environment) fail(KitchenFailureCode.UNAUTHENTICATED) }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: KitchenFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (_: Exception) { fail(KitchenFailureCode.STORAGE_UNAVAILABLE) }
    private fun request(op: String, body: JsonObject): JsonObject {
        val bytes = utf8(body, KitchenFailureCode.INPUT_INVALID)
        if (validator.validateRequest(op, bytes, "application/json") != BodyValidationResult.Valid) fail(KitchenFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    private fun reply(op: String, status: Int, body: JsonObject?, version: Long? = null): StoredReply {
        val bytes = body?.let { utf8(it, KitchenFailureCode.STORAGE_UNAVAILABLE) }
        if (bytes != null && bytes.size > policy.maxResponseBytes) fail(KitchenFailureCode.RESPONSE_TOO_LARGE)
        if (validator.validateResponse(op, status, bytes, if (body == null) null else "application/json") != BodyValidationResult.Valid)
            fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(status, body, version?.let { "\"$it\"" })
    }
    private fun currentReply(cached: StoredReply, current: StoredReply) {
        if (cached.status != current.status || cached.etag != current.etag || cached.body != current.body) fail(KitchenFailureCode.VERSION_CONFLICT)
    }
    private fun utf8(body: JsonObject, failure: KitchenFailureCode) = try { body.toString().encodeToByteArray(throwOnInvalidSequence = true) }
        catch (_: IllegalArgumentException) { fail(failure) } catch (_: CharacterCodingException) { fail(failure) }
    private fun validText(value: String): Boolean = try {
        value.encodeToByteArray(throwOnInvalidSequence = true); value.none { Character.isISOControl(it) }
    } catch (_: Exception) { false }
    private fun version(etag: String): Long {
        if (!etag.matches(Regex("\"[0-9]{1,64}\""))) fail(KitchenFailureCode.INPUT_INVALID)
        return etag.substring(1, etag.length - 1).trimStart('0').ifEmpty { "0" }.toLongOrNull()?.takeIf { it > 0 }
            ?: fail(KitchenFailureCode.INPUT_INVALID)
    }
    private fun limit(limit: Int) { if (limit !in 1..50) fail(KitchenFailureCode.INPUT_INVALID) }
    private fun expect(actual: Long, expected: Long) { if (actual != expected) fail(KitchenFailureCode.VERSION_CONFLICT) }
    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) fail(KitchenFailureCode.STORAGE_UNAVAILABLE) else value + 1
    private fun uuid(body: JsonObject, key: String) = UUID.fromString(body.getValue(key).jsonPrimitive.content)
    private fun fail(code: KitchenFailureCode): Nothing = throw KitchenFailure(code)

    private data class Row(val id: UUID, val version: Long, val created: Instant, val updated: Instant, val fields: JsonObject,
        val ingredientId: UUID? = null, val deleted: Boolean = false, val deletionKey: UUID? = null)
    private fun preference(c: Connection, actor: VerifiedKitchenPrincipal): Row? = query(c,
        "SELECT * FROM profile.preferences WHERE environment=? AND actor_kind=? AND principal_id=? FOR UPDATE", { owner(actor) }) {
        if (it.next()) row(it) else null
    }
    private fun pantry(c: Connection, actor: VerifiedKitchenPrincipal, id: UUID): Row? = query(c,
        "SELECT * FROM pantry.pantry_items WHERE environment=? AND actor_kind=? AND principal_id=? AND ingredient_id=? FOR UPDATE",
        { owner(actor); setObject(4, id) }) { if (it.next()) pantryRow(it) else null }
    private fun row(r: ResultSet) = Row(r.getObject("id", UUID::class.java), r.getLong("version"),
        r.getObject("created_at", OffsetDateTime::class.java).toInstant(), r.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        r.getString("fields")?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap()))
    private fun pantryRow(r: ResultSet) = row(r).copy(ingredientId = r.getObject("ingredient_id", UUID::class.java),
        deleted = r.getBoolean("deleted"), deletionKey = r.getObject("deletion_key", UUID::class.java))
    private fun preferenceJson(row: Row): JsonObject {
        if (!preferenceFields.containsAll(row.fields.keys)) fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
        return JsonObject(row.fields + metadata(row))
    }
    private fun pantryJson(row: Row): JsonObject {
        if (row.deleted) fail(KitchenFailureCode.PANTRY_ITEM_UNAVAILABLE)
        if (!pantryFields.containsAll(row.fields.keys)) fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
        return JsonObject(row.fields + metadata(row) + ("ingredientId" to JsonPrimitive(row.ingredientId.toString())))
    }
    private fun metadata(row: Row) = buildJsonObject {
        put("id", row.id.toString()); put("version", row.version); put("createdAt", row.created.toString()); put("updatedAt", row.updated.toString())
    }
    private fun preferenceEvent(c: Connection, actor: VerifiedKitchenPrincipal, key: UUID, row: Row, fields: Set<String>) = event(c, actor, key,
        "profile.preferences.changed.v1", "preference", row, "profile", buildJsonObject {
            put("principalId", actor.principalId.toString()); put("preferenceVersion", row.version)
            put("changedFieldKinds", JsonArray(fields.sorted().map(::JsonPrimitive)))
        })
    private fun pantryEvent(c: Connection, actor: VerifiedKitchenPrincipal, key: UUID, row: Row, action: String) = event(c, actor, key,
        "pantry.item.changed.v1", "pantryItem", row, "pantry", buildJsonObject {
            put("principalId", actor.principalId.toString()); put("ingredientId", row.ingredientId.toString()); put("action", action)
        })
    private fun event(c: Connection, actor: VerifiedKitchenPrincipal, key: UUID, type: String, aggregate: String, row: Row, producer: String, data: JsonObject) {
        outbox.append(c, EventDraft(UUID.randomUUID(), type, 1, aggregate, row.id, row.version, producer, UUID.randomUUID().toString(), key, data,
            owner = EventOwner.principal(environment, actor.kind, actor.principalId)))
    }
    private fun PreparedStatement.owner(actor: VerifiedKitchenPrincipal, start: Int = 1) {
        setString(start, environment); setString(start + 1, actor.kind.name.lowercase()); setObject(start + 2, actor.principalId)
    }
    private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use {
        it.next(); it.getObject(1, OffsetDateTime::class.java).toInstant()
    } }
    private fun time(value: Instant) = OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC)
    private fun exec(c: Connection, sql: String, bind: PreparedStatement.() -> Unit) = c.prepareStatement(sql).use {
        it.bind(); if (it.executeUpdate() != 1) fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T =
        c.prepareStatement(sql).use { it.bind(); it.executeQuery().use(read) }
    private companion object {
        val validator by lazy { ContractBodyValidator.bundled() }
        val preferenceFields = setOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds", "equipmentIds",
            "preferredTasteTags", "defaultEnergy", "consentVersion", "defaultServings", "personalizationEnabled")
        val pantryFields = setOf("presence", "quantity", "unit", "confirmedAt", "staple", "confirmationStatus")
    }
}
