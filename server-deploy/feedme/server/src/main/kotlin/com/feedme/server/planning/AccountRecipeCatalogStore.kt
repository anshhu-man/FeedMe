package com.feedme.server.planning

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.*
import com.feedme.server.db.*
import com.feedme.server.identity.AccountProfileStore
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.*

/** Account-authorized published free catalog browse. No personal/Saved/social resolution,
 * recipe publication, private input reads, Plan creation, copy permission or quota charge.
 * A bounded scan can return an empty page WITH continuation, never false exhaustion. */
internal class AccountRecipeCatalogStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val accounts: AccountProfileStore,
    private val journal: RecipeCatalogJournal,
    private val cursors: PlanningCursors,
    private val cursorLifetimeSeconds: Int,
) {
    init { require(journal.environment == environment && cursorLifetimeSeconds in 1..600) }

    fun list(subject: VerifiedSupabaseSubject, device: UUID, query: String = "", cursor: String? = null,
        limit: Int = 20): StoredReply = accountPlanningSafe {
        if (query.codePointCount(0, query.length) > 100 || query.any(Char::isISOControl) || limit !in 1..50)
            fail(PlanningFailureCode.INPUT_INVALID)
        transactions.run { c ->
            val owner = accounts.lockPrivateAccount(c, subject, device)
            if (owner.environment != environment) fail(PlanningFailureCode.UNAUTHENTICATED)
            val view = journal.openView(c)
            val binding = buildJsonArray {
                add(environment); add(owner.principalId.toString()); add(device.toString())
                add(view.revision); add(view.releaseId.toString()); add(view.requestSha256)
                add(query); add(limit)
            }.toString()
            val now = time(c).epochSecond
            val position = cursor?.let { cursors.recipePosition(binding, it, now) }
            val expires = position?.second ?: (now + cursorLifetimeSeconds)
            var after = position?.first
            var inspected = 0
            var more = false
            var bytes = 4096
            val items = mutableListOf<JsonElement>()
            val needle = query.lowercase(Locale.ROOT)
            scan@ while (inspected < MAX_INSPECTED) {
                val page = view.page(after, minOf(16, MAX_INSPECTED - inspected))
                for ((index, value) in page.entries.withIndex()) {
                    val entry = value.entry
                    val recipe = entry.recipe
                    val matches = visible(entry) && recipe.getValue("title").jsonPrimitive.content.lowercase(Locale.ROOT).contains(needle)
                    val size = if (matches) recipe.toString().encodeToByteArray().size + 1 else 0
                    if (matches && bytes + size > MAX_RESPONSE_BYTES) {
                        if (items.isEmpty() || after == null) fail(PlanningFailureCode.NOT_CONFIGURED)
                        more = true; break@scan // This entry remains AFTER the emitted cursor.
                    }
                    inspected++; after = entry.recipeVersionId
                    if (matches) { items += recipe; bytes += size }
                    more = index < page.entries.lastIndex || page.nextAfter != null
                    if (items.size == limit || inspected == MAX_INSPECTED) break@scan
                }
                if (page.nextAfter == null) { more = false; break }
            }
            view.checkCurrent()
            val current = accounts.lockPrivateAccount(c, subject, device)
            if (current.accountId != owner.accountId || current.principalId != owner.principalId)
                fail(PlanningFailureCode.UNAUTHENTICATED)
            val at = time(c)
            if (subject.expiresAtEpochSeconds <= at.epochSecond) fail(PlanningFailureCode.UNAUTHENTICATED)
            if (at.epochSecond >= expires) fail(PlanningFailureCode.CURSOR_EXPIRED)
            StoredReply(200, buildJsonObject {
                put("items", JsonArray(items)); put("serverTime", at.toString())
                put("nextCursor", if (more) JsonPrimitive(cursors.recipe(binding, checkNotNull(after), expires)) else JsonNull)
            })
        }
    }

    fun get(subject: VerifiedSupabaseSubject, device: UUID, recipeId: UUID, versionId: UUID): StoredReply = accountPlanningSafe {
        transactions.run { c ->
            val owner = accounts.lockPrivateAccount(c, subject, device)
            if (owner.environment != environment) fail(PlanningFailureCode.UNAUTHENTICATED)
            val view = journal.openView(c)
            val entry = view.lookupCurrent(versionId)?.entry ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            if (entry.recipe["recipeId"] != JsonPrimitive(recipeId.toString()) || !visible(entry))
                fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
            view.checkCurrent()
            val current = accounts.lockPrivateAccount(c, subject, device)
            if (current.accountId != owner.accountId || current.principalId != owner.principalId)
                fail(PlanningFailureCode.UNAUTHENTICATED)
            StoredReply(200, entry.recipe, "\"${entry.recipe.getValue("version").jsonPrimitive.content.toBigDecimal().longValueExact()}\"")
        }
    }

    override fun toString() = "AccountRecipeCatalogStore(<redacted>)"
    private companion object {
        const val MAX_INSPECTED = 128
        const val MAX_RESPONSE_BYTES = 1_048_576
        fun visible(entry: RecipeCatalogEntry) = entry.recipe["reviewStatus"] == JsonPrimitive("published") &&
            entry.review["freeCatalogEligible"] == JsonPrimitive(true) &&
            entry.recipe["contentLicense"] == JsonPrimitive("catalogRedistributable")
        fun time(c: Connection) = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
            check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant()
        } }
        fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
    }
}
