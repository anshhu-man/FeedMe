package com.feedme.server.guest

import com.feedme.server.db.CommandActor
import com.feedme.server.db.OutboxStore
import com.feedme.server.kitchen.KitchenFailure
import com.feedme.server.kitchen.KitchenFailureCode
import com.feedme.server.kitchen.KitchenPreferenceProvisioning
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject

/** Only the fresh guest issuer calls this inside its already-authorized transaction, with
 * the actual guest principal and original bootstrap command key. Empty required lists mean
 * nothing recorded: not no allergies, answered questions, equipment ownership or consent.
 * No optional default, catalog decision, authority or nested transaction is introduced.
 */
internal object GuestUnansweredPreferenceProvisioning {
    fun provision(connection: Connection, environment: String, principalId: UUID,
        bootstrapKey: UUID, outbox: OutboxStore) {
        val unanswered = buildJsonObject { required.forEach { put(it, JsonArray(emptyList())) } }
        KitchenPreferenceProvisioning.provision(connection, environment, CommandActor.GUEST,
            principalId, unanswered, bootstrapKey, 262144, outbox) { proposed ->
            if (proposed.keys != required + metadata || required.any { proposed[it] != JsonArray(emptyList()) })
                throw KitchenFailure(KitchenFailureCode.STORAGE_UNAVAILABLE)
        }
    }

    private val required = setOf("hardExcludedIngredientIds", "dietaryPatterns", "dislikedIngredientIds", "equipmentIds")
    private val metadata = setOf("id", "version", "createdAt", "updatedAt")
}
