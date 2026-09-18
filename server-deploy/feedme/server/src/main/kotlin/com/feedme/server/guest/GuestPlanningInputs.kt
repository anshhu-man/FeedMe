package com.feedme.server.guest

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

/** Current-input projection ONLY, called inside the actual GuestSessionStore.withCurrent
 * transaction after its principal/guest and command locks. The supplied principal is metadata,
 * not authentication; this loader cannot open a transaction or authorize a planning operation.
 * The owning guest store must perform its final current/expiry checks before commit/disclosure.
 *
 * Lock order: owned preference, then ALL pantry rows in ingredient UUID order. The outer
 * principal lock serializes absent rows/phantoms with GuestKitchenStore. Tombstones participate
 * in the domain-separated pantry fingerprint, not availability. No persisted field is changed.
 * Only an explicit available+confirmed report with a valid nonnull confirmation timestamp is
 * projected CONFIRMED. This is a reported fact, not freshness, quantity sufficiency or food safety.
 * Out/unavailable wins contradictory reports; every other report remains UNCERTAIN. No TTL,
 * usual-stock inference, dietary-pattern expansion, defaults or base-meal grant is introduced.
 * Preference UUID spelling is normalized for the strict snapshot without changing order or
 * membership. Duplicate normalized IDs and over-bound lists refuse rather than deduplicate.
 */
internal class GuestPlanningInputs(private val environment: String) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun lock(connection: Connection, principal: VerifiedKitchenPrincipal,
        request: WireDocument): PlanningPrivateInputsSnapshot = safe {
        if (principal.environment != environment || principal.kind != CommandActor.GUEST || principal.deviceSessionId != null)
            fail(PlanningFailureCode.UNAUTHENTICATED)
        val guard = Guard(connection)
        val bytes = request.encodeUtf8()
        if (bytes.size > 65_536 || validator.validateRequest("createPlan", bytes, "application/json") != BodyValidationResult.Valid)
            fail(PlanningFailureCode.INPUT_INVALID)
        val original = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        checkSchema(connection); guard.check()

        val preference = connection.prepareStatement(
            "SELECT id,version,created_at,updated_at,fields FROM profile.preferences " +
                "WHERE environment=? AND actor_kind='guest' AND principal_id=? FOR UPDATE").use { statement ->
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
                val expected = original["preferenceVersion"]?.jsonPrimitive?.content?.toBigDecimalOrNull()
                    ?: fail(PlanningFailureCode.PREFERENCE_CHANGED)
                if (expected.compareTo(version.toBigDecimal()) != 0) fail(PlanningFailureCode.PREFERENCE_CHANGED)
                if (fields.getValue("dietaryPatterns").jsonArray.isNotEmpty()) fail(PlanningFailureCode.NOT_CONFIGURED)
                buildJsonObject {
                    put("revision", version.toString())
                    put("excludedIngredientIds", normalizedIds(fields.getValue("hardExcludedIngredientIds")))
                    put("dislikedIngredientIds", normalizedIds(fields.getValue("dislikedIngredientIds")))
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
        frame("feedme.guest.planning.pantry.v1"); frame(environment); frame("guest"); frame(principal.principalId.toString())
        val items = mutableListOf<JsonElement>()
        var previous: String? = null
        var count = 0L
        connection.prepareStatement(
            "SELECT ingredient_id,id,version,created_at,updated_at,fields,deleted,deletion_key FROM pantry.pantry_items " +
                "WHERE environment=? AND actor_kind='guest' AND principal_id=? ORDER BY ingredient_id FOR UPDATE").use { statement ->
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
        val snapshot = buildJsonObject {
            put("version", 2); put("preferences", preference)
            put("pantry", buildJsonObject { put("revision", pantryHash); put("items", JsonArray(items)) })
            put("baseMeal", JsonNull)
        }
        PlanningPrivateInputsSnapshot.decode(snapshot.toString().encodeToByteArray(throwOnInvalidSequence = true)).also { guard.check() }
    }

    private fun checkSchema(c: Connection) {
        val expected = GuestPlanningInputs::class.java.getResourceAsStream("/db/migration/V004__private_kitchen.sql")
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
    override fun toString() = "GuestPlanningInputs(<redacted>)"
    private companion object {
        val validator by lazy { ContractBodyValidator.bundled() }
        val preferenceFields = setOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds", "equipmentIds",
            "preferredTasteTags", "defaultEnergy", "consentVersion", "defaultServings")
        val pantryFields = setOf("presence", "quantity", "unit", "confirmedAt", "staple", "confirmationStatus")
        fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
        fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest planning inputs interrupted") }
        fun <T> safe(action: () -> T): T = try { action() }
            catch (failure: PlanningServiceFailure) { throw failure }
            catch (failure: CommitOutcomeUnknown) { throw failure }
            catch (failure: CancellationException) { throw failure }
            catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
            catch (_: Exception) { fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    }
}
