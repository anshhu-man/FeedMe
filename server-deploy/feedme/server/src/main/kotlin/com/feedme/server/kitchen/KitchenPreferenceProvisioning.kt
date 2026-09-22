package com.feedme.server.kitchen

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandActor
import com.feedme.server.db.EventDraft
import com.feedme.server.db.EventOwner
import com.feedme.server.db.OutboxStore
import com.feedme.server.db.StoredReply
import java.nio.charset.CharacterCodingException
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** DB-only provisioning inside the caller's already-authorized, principal-serialized transaction.
 * No nested transaction, receipt, provider call or reusable authority is created here. The caller
 * owns current-policy checks through commit. Existing rows are never repaired or overwritten.
 */
internal object KitchenPreferenceProvisioning {
    /** Called ONLY from the first-account bootstrap mutation, with its actual private principal
     * and original command key. Empty lists mean nothing recorded, not answered questions, an
     * allergy declaration, a skip, equipment ownership, consent or private-ready eligibility.
     * There are no catalog references or optional defaults to authorize in this exact shape.
     */
    fun provisionUnansweredAccount(c: Connection, environment: String, principalId: UUID,
        bootstrapKey: UUID, outbox: OutboxStore): StoredReply {
        val unanswered = buildJsonObject { required.forEach { put(it, JsonArray(emptyList())) } }
        return provision(c, environment, CommandActor.ACCOUNT, principalId, unanswered,
            bootstrapKey, 262144, outbox) { proposed ->
            if (proposed.keys != required + metadata || required.any { proposed[it] != JsonArray(emptyList()) })
                fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
        }
    }

    /** Caller supplies full explicit initial fields and a mandatory validator for new rows only.
     * Same-field existing rows preserve their exact identity/version and emit no further event.
     */
    fun provision(c: Connection, environment: String, kind: CommandActor, principalId: UUID,
        fields: JsonObject, causationKey: UUID, maxResponseBytes: Int, outbox: OutboxStore,
        validateNew: (JsonObject) -> Unit): StoredReply {
        require(!c.autoCommit) { "Provision in the caller's domain transaction" }
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(kind == CommandActor.ACCOUNT || kind == CommandActor.GUEST)
        require(maxResponseBytes in 1..262144)
        if (!fields.keys.containsAll(required) || validator.validateRequest("updatePreferences",
                bytes(fields, KitchenFailureCode.INPUT_INVALID), "application/json") != BodyValidationResult.Valid)
            fail(KitchenFailureCode.INPUT_INVALID)
        val existing = read(c, environment, kind, principalId)
        if (existing != null) {
            if (JsonObject(existing - metadata) != fields) fail(KitchenFailureCode.VERSION_CONFLICT)
            return reply(existing, maxResponseBytes)
        }
        val id = UUID.randomUUID()
        val at = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
            check(r.next()); r.getObject(1, OffsetDateTime::class.java)
        } }
        val proposed = JsonObject(fields + buildJsonObject {
            put("id", id.toString()); put("version", 1)
            put("createdAt", at.toInstant().toString()); put("updatedAt", at.toInstant().toString())
        })
        reply(proposed, maxResponseBytes) // Schema and response bounds before any domain write.
        validateNew(proposed)
        c.prepareStatement("INSERT INTO profile.preferences(environment,actor_kind,principal_id,id,version,created_at,updated_at,fields) VALUES(?,?,?,?,1,?,?,?::jsonb)").use {
            it.owner(environment, kind, principalId); it.setObject(4, id)
            it.setObject(5, at); it.setObject(6, at); it.setString(7, fields.toString())
            if (it.executeUpdate() != 1) fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
        }
        outbox.append(c, EventDraft(UUID.randomUUID(), "profile.preferences.changed.v1", 1,
            "preference", id, 1, "profile", UUID.randomUUID().toString(), causationKey, buildJsonObject {
                put("principalId", principalId.toString()); put("preferenceVersion", 1)
                put("changedFieldKinds", JsonArray(fields.keys.sorted().map(::JsonPrimitive)))
            }, owner = EventOwner.principal(environment, kind, principalId)))
        return reply(read(c, environment, kind, principalId) ?: fail(KitchenFailureCode.STORAGE_UNAVAILABLE), maxResponseBytes)
    }

    private fun read(c: Connection, environment: String, kind: CommandActor, principalId: UUID): JsonObject? =
        c.prepareStatement("SELECT * FROM profile.preferences WHERE environment=? AND actor_kind=? AND principal_id=? FOR UPDATE").use { s ->
            s.owner(environment, kind, principalId)
            s.executeQuery().use { r ->
                if (!r.next()) null else {
                    val fields = r.getString("fields")?.let { Json.parseToJsonElement(it).jsonObject }
                        ?: fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
                    if (!allowed.containsAll(fields.keys)) fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
                    JsonObject(fields + buildJsonObject {
                        put("id", r.getObject("id", UUID::class.java).toString()); put("version", r.getLong("version"))
                        put("createdAt", r.getObject("created_at", OffsetDateTime::class.java).toInstant().toString())
                        put("updatedAt", r.getObject("updated_at", OffsetDateTime::class.java).toInstant().toString())
                    })
                }
            }
        }
    private fun reply(body: JsonObject, maxBytes: Int): StoredReply {
        val bytes = bytes(body, KitchenFailureCode.STORAGE_UNAVAILABLE)
        if (bytes.size > maxBytes) fail(KitchenFailureCode.RESPONSE_TOO_LARGE)
        if (validator.validateResponse("getPreferences", 200, bytes, "application/json") != BodyValidationResult.Valid)
            fail(KitchenFailureCode.STORAGE_UNAVAILABLE)
        return StoredReply(200, body, "\"${body.getValue("version").jsonPrimitive.long}\"")
    }
    private fun bytes(body: JsonObject, failure: KitchenFailureCode) = try {
        body.toString().encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: IllegalArgumentException) { fail(failure) }
      catch (_: CharacterCodingException) { fail(failure) }
    private fun PreparedStatement.owner(environment: String, kind: CommandActor, principalId: UUID) {
        setString(1, environment); setString(2, kind.name.lowercase()); setObject(3, principalId)
    }
    private fun fail(code: KitchenFailureCode): Nothing = throw KitchenFailure(code)
    private val validator by lazy { ContractBodyValidator.bundled() }
    private val required = setOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds", "equipmentIds")
    private val metadata = setOf("id", "version", "createdAt", "updatedAt")
    private val allowed = required + setOf("preferredTasteTags", "defaultEnergy", "consentVersion", "defaultServings", "personalizationEnabled")
}
