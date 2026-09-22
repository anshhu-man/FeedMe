package com.feedme.server.ai

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.catalog.IngredientCatalogFailure
import com.feedme.server.catalog.IngredientCatalogFailureCode
import com.feedme.server.catalog.IngredientCatalogStore
import com.feedme.server.catalog.IngredientReleaseItem
import com.feedme.server.db.PgTransactions
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.identity.AccountPrivateMapping
import com.feedme.server.identity.AccountProfileStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Actual account/device/current-policy and published ingredient reads, not audience permission.
 * Each operation owns and closes a short database transaction on the supplied dispatcher. No
 * model, pantry/preference query, external call, mutation, connection or reusable grant escapes.
 * The service still requires a separately configured audience gate before using this adapter. */
internal class PostgresAccountMealIntentSource(
    private val environment: String,
    private val transactions: PgTransactions,
    private val accounts: AccountProfileStore,
    private val catalog: IngredientCatalogStore,
    private val databaseDispatcher: CoroutineDispatcher,
    private val requireAdultConsent: Boolean = false,
) : AccountMealIntentSourceAuthority {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")) && catalog.environment == environment) {
            "Invalid meal intent source configuration"
        }
    }

    override suspend fun capture(subject: VerifiedSupabaseSubject, deviceId: UUID): AccountMealIntentCapture =
        try {
            val source = runInterruptible(databaseDispatcher) {
                transactions.run { read(it, subject, deviceId) }
            }
            AccountMealIntentCapture.Value(source)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
          catch (failure: Exception) { AccountMealIntentCapture.Denied(reason(failure)) }

    override suspend fun revalidate(subject: VerifiedSupabaseSubject, deviceId: UUID,
        source: AccountMealIntentSource): AccountMealIntentCurrent = try {
        runInterruptible(databaseDispatcher) {
            transactions.run { c ->
                val fresh = read(c, subject, deviceId)
                if (fresh.accountId != source.accountId || fresh.catalogRevision != source.catalogRevision ||
                    fresh.ingredientOptions.map { it.id to it.name } != source.ingredientOptions.map { it.id to it.name })
                    AccountMealIntentCurrent.SOURCE_CHANGED
                else AccountMealIntentCurrent.CURRENT
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
      catch (failure: Exception) { when (reason(failure)) {
          AccountMealIntentFailure.UNAUTHENTICATED -> AccountMealIntentCurrent.UNAUTHENTICATED
          AccountMealIntentFailure.FORBIDDEN, AccountMealIntentFailure.SOURCE_CHANGED -> AccountMealIntentCurrent.SOURCE_CHANGED
          AccountMealIntentFailure.NOT_CONFIGURED -> AccountMealIntentCurrent.NOT_CONFIGURED
          else -> AccountMealIntentCurrent.UNAVAILABLE
      } }

    private fun read(c: Connection, subject: VerifiedSupabaseSubject, deviceId: UUID): AccountMealIntentSource {
        val owner = lockOwner(c, subject, deviceId)
        if (owner.environment != environment) throw SourceRefusal(AccountMealIntentFailure.UNAUTHENTICATED)
        catalog.checkCompatibility(c)
        // The actual immutable release is cross-checked against its rows, under a shared head
        // lock. Publication cannot change selection while the final account check waits.
        val release = catalog.current(c)
        val options = publicMealIntentOptions(release.original.items)
            ?: throw SourceRefusal(AccountMealIntentFailure.NOT_CONFIGURED)
        val source = AccountMealIntentSource(owner.accountId, revision(owner, subject, release), options)
        val finalOwner = lockOwner(c, subject, deviceId)
        if (finalOwner.environment != owner.environment || finalOwner.accountId != owner.accountId ||
            finalOwner.principalId != owner.principalId || finalOwner.deviceSessionId != owner.deviceSessionId)
            throw SourceRefusal(AccountMealIntentFailure.UNAUTHENTICATED)
        // AccountProfileStore has already rechecked provider state after all catalog waits.
        // The final DB clock additionally rejects token expiry; there is no later authority I/O.
        val at = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
            check(r.next()); r.getObject(1, OffsetDateTime::class.java).toInstant()
        } }
        if (subject.expiresAtEpochSeconds <= at.epochSecond) throw SourceRefusal(AccountMealIntentFailure.UNAUTHENTICATED)
        return source
    }

    // Runtime opts into the same persisted adult evidence as its audience gate so the
    // final catalog check cannot replace it with a generic eligible-account admission.
    private fun lockOwner(c: Connection, subject: VerifiedSupabaseSubject, deviceId: UUID) =
        if (requireAdultConsent) accounts.lockAdultMealIntentAccount(c, subject, deviceId)
        else accounts.lockPrivateAccount(c, subject, deviceId)

    private fun revision(owner: AccountPrivateMapping, subject: VerifiedSupabaseSubject,
        snapshot: IngredientCatalogStore.Snapshot): String {
        val binding = buildJsonArray {
            add(environment); add(owner.accountId.toString()); add(owner.principalId.toString())
            add(owner.deviceSessionId.toString()); add(subject.issuer); add(subject.subject.toString())
            add(subject.providerSessionId.toString()); add(snapshot.revision)
            add(snapshot.original.releaseId.toString()); add(snapshot.original.requestSha256)
            add(snapshot.original.contentSha256)
        }
        return MessageDigest.getInstance("SHA-256").digest(
            ("feedme.account-meal-intent-source.v1\u0000" + binding).toByteArray(StandardCharsets.UTF_8)
        ).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    override fun toString() = "PostgresAccountMealIntentSource(<redacted>)"

    private class SourceRefusal(val reason: AccountMealIntentFailure) : RuntimeException("Meal intent source unavailable")
    private fun reason(failure: Exception): AccountMealIntentFailure = when (failure) {
        is SourceRefusal -> failure.reason
        is AccountFailure -> when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED, AccountFailureCode.ACCOUNT_UNAVAILABLE -> AccountMealIntentFailure.UNAUTHENTICATED
            AccountFailureCode.POLICY_BLOCKED -> AccountMealIntentFailure.FORBIDDEN
            AccountFailureCode.NOT_CONFIGURED -> AccountMealIntentFailure.NOT_CONFIGURED
            else -> AccountMealIntentFailure.UNAVAILABLE
        }
        is IngredientCatalogFailure -> if (failure.code == IngredientCatalogFailureCode.NOT_CONFIGURED)
            AccountMealIntentFailure.NOT_CONFIGURED else AccountMealIntentFailure.UNAVAILABLE
        else -> AccountMealIntentFailure.UNAVAILABLE
    }
}

/** Mechanical projection only, never authority. No aliases, private facts or inferred food
 * properties are sent. This first bounded strategy supports the complete public subset only;
 * a larger catalog needs a separately reviewed lookup strategy, not silent truncation. */
internal fun publicMealIntentOptions(items: List<IngredientReleaseItem>): List<MealIntentIngredientOption>? {
    val public = items.filter { it.reviewed && it.published && it.freeAccess }.sortedBy { it.id.toString() }
    if (public.size !in 1..64) return null
    return public.map { item ->
        val name = item.ingredient.getValue("name").jsonPrimitive.content
        if (name.isBlank() || name != name.trim() || name.codePointCount(0, name.length) > 100 ||
            !StandardCharsets.UTF_8.newEncoder().canEncode(name) || name.any(Char::isISOControl)) return null
        MealIntentIngredientOption(item.id, name)
    }
}
