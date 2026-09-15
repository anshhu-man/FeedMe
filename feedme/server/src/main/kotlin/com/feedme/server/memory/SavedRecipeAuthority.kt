package com.feedme.server.memory

import com.feedme.server.db.CommandActor
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Actual verifier output only. Guest identity is internal, never a caller-selected HTTP origin. */
class VerifiedSavedRecipePrincipal(val environment: String, val kind: CommandActor, val principalId: UUID,
    val deviceSessionId: UUID?, val guestSessionId: UUID? = null) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(kind == CommandActor.ACCOUNT || kind == CommandActor.GUEST)
        require(if (kind == CommandActor.ACCOUNT) deviceSessionId != null && guestSessionId == null
            else deviceSessionId == null && guestSessionId != null)
    }
    override fun toString() = "VerifiedSavedRecipePrincipal(<redacted>)"
}

/** A positive, current copy decision, not borrowed cooking/read access or a caught expiry failure. */
class AuthorizedRecipeCopy(val recipeVersionId: UUID, recipe: JsonObject, val contentLicense: String,
    evidence: JsonObject) {
    val recipe = Json.parseToJsonElement(recipe.toString()).jsonObject
    val evidence = Json.parseToJsonElement(evidence.toString()).jsonObject
    init {
        require(contentLicense in setOf("catalogRedistributable", "privateCopyOnly"))
        require(this.evidence.isNotEmpty() && this.evidence.toString().toByteArray().size <= 32768)
    }
    override fun toString() = "AuthorizedRecipeCopy(<redacted>)"
}

/** Persisted permission provenance; contains no token, account credential or whole original Plan. */
class SavedRecipeCopyEvidence(val savedRecipeId: UUID, val recipeVersionId: UUID, val recipeHash: String,
    val sourceType: String, val originPlanId: UUID?, val contentLicense: String, evidence: JsonObject) {
    val evidence = Json.parseToJsonElement(evidence.toString()).jsonObject
    override fun toString() = "SavedRecipeCopyEvidence(<redacted>)"
}

/**
 * REQUIRED trusted integration, with no accepting/default provider or catalog implementation.
 * All callbacks operate on the supplied transaction, without network I/O, commit or input mutation.
 * lockPrincipal takes the exclusive CURRENT account/device or bounded guest lifecycle lock shared
 * by planning/kitchen/cooking and merge/revocation writers. Order: principal, receipt, owned Plan,
 * source/review/rights, saved lineage, collections. Source/lifecycle writers must honor these locks.
 * authorizeNewCopy must positively authorize the exact materialized recipe and independent reviewed
 * evidence, free-catalog eligibility for guests, source/license/recall and any allowed scaling. A
 * Plan TTL exception or historical cooking pin is NOT a copy grant. Both supplied IDs must agree.
 * requireExistingCopyAllowed rechecks retained copy rights and recall, not fresh publication or
 * continued Plan existence. Ordinary retirement must not revoke an established permitted copy.
 * Throw RECIPE_RECALLED for a denied recalled copy; no silent per-item filtering or fake metadata.
 * Owned deletion deliberately needs only current principal and exact copy/version, not catalog access.
 */
interface SavedRecipeAuthority {
    fun lockPrincipal(connection: Connection, principal: VerifiedSavedRecipePrincipal)
    fun authorizeNewCopy(connection: Connection, principal: VerifiedSavedRecipePrincipal,
        planId: UUID?, recipeVersionId: UUID?): AuthorizedRecipeCopy
    fun requireExistingCopyAllowed(connection: Connection, principal: VerifiedSavedRecipePrincipal,
        copy: SavedRecipeCopyEvidence)
}

class SavedRecipeServicePolicy(val maxResponseBytes: Int, val cursorLifetimeSeconds: Int, val defaultCollectionName: String) {
    init {
        require(maxResponseBytes in 1..262144 && cursorLifetimeSeconds in 1..86400)
        require(defaultCollectionName.isNotBlank() && defaultCollectionName.codePointCount(0, defaultCollectionName.length) <= 60)
        require(defaultCollectionName.none { Character.isISOControl(it) })
        defaultCollectionName.encodeToByteArray(throwOnInvalidSequence = true)
    }
    override fun toString() = "SavedRecipeServicePolicy(<redacted>)"
}

enum class SavedRecipeFailureCode(val status: Int) {
    INPUT_INVALID(422), UNAUTHENTICATED(401), FORBIDDEN(403), SAVED_RECIPE_UNAVAILABLE(404),
    COLLECTION_UNAVAILABLE(404), PLAN_UNAVAILABLE(404), RECIPE_UNAVAILABLE(404), RECIPE_RECALLED(409),
    VERSION_CONFLICT(412), COPY_CONFLICT(409), CURSOR_INVALID(409), CURSOR_EXPIRED(410),
    NOT_CONFIGURED(503), STORAGE_UNAVAILABLE(503), RESPONSE_TOO_LARGE(422),
}
class SavedRecipeFailure(val code: SavedRecipeFailureCode) : RuntimeException("Saved recipe operation unavailable: ${code.name}")
