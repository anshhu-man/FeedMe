package com.feedme.server.planning

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.planning.PlanningFailureCode
import com.feedme.server.planning.PlanningPrivateInputsSnapshot
import com.feedme.server.planning.PlanningServiceFailure
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Current account inputs only, never an identity verifier or transaction owner. The concrete
 * account planning store holds the actual account/device/principal locks before entering and
 * rechecks them after every input/catalog wait. Shared principal locks serialize missing rows
 * with AccountPantryStore; existing preference then all pantry rows lock in UUID order.
 * Tombstones participate in the complete fingerprint, never availability. Only explicit
 * available+confirmed+timestamp reports become CONFIRMED; out/unavailable wins contradictions.
 * No pantry freshness, quantity, usual-stock, dietary expansion, defaults or base-meal inference.
 * Historical reads may observe newer inputs: PlansStore independently decides whether exact
 * preference/input equality is required for this operation, rather than this loader guessing.
 */
internal class AccountPlanningInputs(private val environment: String) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun lock(connection: Connection, principal: VerifiedKitchenPrincipal,
        memoryRead: (() -> List<JsonObject>)? = null): PlanningPrivateInputsSnapshot = safe {
        if (principal.environment != environment || principal.kind != CommandActor.ACCOUNT || principal.deviceSessionId == null)
            fail(PlanningFailureCode.UNAUTHENTICATED)
        val guard = Guard(connection)
        checkSchema(connection); guard.check()

        val preference = connection.prepareStatement(
            "SELECT id,version,created_at,updated_at,fields FROM profile.preferences " +
                "WHERE environment=? AND actor_kind='account' AND principal_id=? FOR UPDATE").use { statement ->
            statement.setString(1, environment); statement.setObject(2, principal.principalId)
            statement.executeQuery().use { rows ->
                guard.check()
                if (!rows.next()) fail(PlanningFailureCode.PREFERENCE_CHANGED)
                val fields = fields(rows.getString("fields"))
                if (!preferenceFields.containsAll(fields.keys)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                val version = rows.getLong("version")
                val body = JsonObject(fields + metadata(rows))
                validate("Preference", body)
                if (rows.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                if (fields.getValue("dietaryPatterns").jsonArray.isNotEmpty()) fail(PlanningFailureCode.NOT_CONFIGURED)
                buildJsonObject {
                    put("revision", version.toString())
                    put("excludedIngredientIds", normalizedIds(fields.getValue("hardExcludedIngredientIds")))
                    put("dislikedIngredientIds", normalizedIds(fields.getValue("dislikedIngredientIds")))
                    put("personalizationEnabled", fields["personalizationEnabled"]?.jsonPrimitive?.boolean ?: true)
                }
            }
        }
        guard.check()
        val digest = MessageDigest.getInstance("SHA-256")
        fun frame(value: String?) {
            val material = value?.encodeToByteArray(throwOnInvalidSequence = true)
            digest.update(ByteBuffer.allocate(4).putInt(material?.size ?: -1).array())
            if (material != null) digest.update(material)
        }
        frame("feedme.account.planning.pantry.v1"); frame(environment); frame("account"); frame(principal.principalId.toString())
        val items = mutableListOf<JsonElement>()
        var previous: String? = null
        var count = 0L
        connection.prepareStatement(
            "SELECT ingredient_id,id,version,created_at,updated_at,fields,deleted,deletion_key FROM pantry.pantry_items " +
                "WHERE environment=? AND actor_kind='account' AND principal_id=? ORDER BY ingredient_id FOR UPDATE").use { statement ->
            statement.setString(1, environment); statement.setObject(2, principal.principalId)
            statement.fetchSize = 64 // Same transaction: bounded JDBC cursor, including retained tombstones.
            statement.executeQuery().use { rows ->
                guard.check()
                while (rows.next()) {
                    interrupted()
                    val ingredient = rows.getObject("ingredient_id", UUID::class.java).toString()
                    if (previous != null && ingredient <= previous!! || count == Long.MAX_VALUE)
                        fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                    previous = ingredient; count++
                    val meta = metadata(rows)
                    val raw = rows.getString("fields")
                    val deleted = rows.getBoolean("deleted")
                    val deletion = rows.getObject("deletion_key", UUID::class.java)?.toString()
                    if (deleted != (raw == null) || deleted != (deletion != null)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                    // Exact persisted JSONB text and all identity/version/deletion metadata are
                    // fingerprinted, even fields irrelevant to today's three-state projection.
                    frame("row"); frame(ingredient); frame(meta.getValue("id").jsonPrimitive.content)
                    frame(meta.getValue("version").jsonPrimitive.content); frame(meta.getValue("createdAt").jsonPrimitive.content)
                    frame(meta.getValue("updatedAt").jsonPrimitive.content); frame(if (deleted) "deleted" else "live")
                    frame(deletion); frame(raw)
                    if (!deleted) {
                        if (items.size == 256) fail(PlanningFailureCode.NOT_CONFIGURED)
                        val fields = fields(checkNotNull(raw))
                        if (!pantryFields.containsAll(fields.keys)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                        validate("PantryItem", JsonObject(fields + meta + ("ingredientId" to JsonPrimitive(ingredient))))
                        val status = fields["confirmationStatus"]?.jsonPrimitive?.content
                        val presence = fields.getValue("presence").jsonPrimitive.content
                        val confirmed = fields["confirmedAt"].takeUnless { it == JsonNull }?.jsonPrimitive?.content
                        val availability = when {
                            presence == "out" || status == "unavailable" -> "UNAVAILABLE"
                            presence == "available" && status == "confirmed" && confirmed != null -> {
                                Instant.parse(confirmed); "CONFIRMED"
                            }
                            else -> "UNCERTAIN"
                        }
                        items += buildJsonObject { put("ingredientId", ingredient); put("availability", availability) }
                    }
                }
            }
        }
        guard.check(); frame("end"); frame(count.toString())
        val pantryHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val memories = if (preference.getValue("personalizationEnabled").jsonPrimitive.boolean)
            memoryRead?.invoke().orEmpty() else emptyList()
        guard.check()
        val snapshot = buildJsonObject {
            put("version", 3); put("preferences", JsonObject(preference +
                ("memories" to PlanningMemorySnapshot.fromMemories(memories))))
            put("pantry", buildJsonObject { put("revision", pantryHash); put("items", JsonArray(items)) })
            put("baseMeal", JsonNull)
        }
        PlanningPrivateInputsSnapshot.decode(snapshot.toString().encodeToByteArray(throwOnInvalidSequence = true)).also { guard.check() }
    }

    private fun checkSchema(c: Connection) {
        val expected = AccountPlanningInputs::class.java.getResourceAsStream("/db/migration/V004__private_kitchen.sql")
            ?.use { input -> MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) } }
            ?: fail(PlanningFailureCode.NOT_CONFIGURED)
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=4").use { s ->
            s.executeQuery().use { r ->
                if (!r.next() || r.getString(1) != expected || r.next()) fail(PlanningFailureCode.NOT_CONFIGURED)
            }
        }
    }

    private fun fields(text: String): JsonObject = Json.parseToJsonElement(
        WireDocument.parse(text, WireLimits(262_144, 32)).encodeUtf8().decodeToString()).jsonObject
    private fun metadata(rows: ResultSet): JsonObject {
        val version = rows.getLong("version")
        if (version <= 0) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return buildJsonObject {
            put("id", rows.getObject("id", UUID::class.java).toString()); put("version", version)
            put("createdAt", rows.getObject("created_at", OffsetDateTime::class.java).toInstant().toString())
            put("updatedAt", rows.getObject("updated_at", OffsetDateTime::class.java).toInstant().toString())
        }
    }
    private fun normalizedIds(value: JsonElement): JsonArray {
        val ids = value.jsonArray.map { UUID.fromString(it.jsonPrimitive.content).toString() }
        if (ids.size > 256 || ids.distinct().size != ids.size) fail(PlanningFailureCode.NOT_CONFIGURED)
        return JsonArray(ids.map(::JsonPrimitive))
    }
    private fun validate(schema: String, body: JsonObject) {
        val bytes = body.toString().encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size > 262_144 || validator.validateSchema(schema, bytes) != BodyValidationResult.Valid)
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }
    private class Guard(private val connection: Connection) {
        private val thread = Thread.currentThread()
        private val transaction: Long = id()
        private fun id(): Long {
            interrupted()
            if (Thread.currentThread() !== thread || connection.isClosed || connection.autoCommit)
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            return connection.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
                if (!r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                val id = r.getLong(1)
                if (r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                id
            } }
        }
        fun check() { if (id() != transaction) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    }
    override fun toString() = "AccountPlanningInputs(<redacted>)"
    private companion object {
        val validator by lazy { ContractBodyValidator.bundled() }
        val preferenceFields = setOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds", "equipmentIds",
            "preferredTasteTags", "defaultEnergy", "consentVersion", "defaultServings", "personalizationEnabled")
        val pantryFields = setOf("presence", "quantity", "unit", "confirmedAt", "staple", "confirmationStatus")
        fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
        fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Account planning inputs interrupted") }
        fun <T> safe(action: () -> T): T = try { action() }
            catch (failure: PlanningServiceFailure) { throw failure }
            catch (failure: CommitOutcomeUnknown) { throw failure }
            catch (failure: CancellationException) { throw failure }
            catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
            catch (_: Exception) { fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    }
}
