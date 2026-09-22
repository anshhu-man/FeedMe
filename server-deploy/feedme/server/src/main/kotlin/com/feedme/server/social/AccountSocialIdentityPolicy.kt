package com.feedme.server.social

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountFailureCode
import com.feedme.server.identity.AccountProfileStore
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Real account/provider/device admission. Explicit deployment rules are required; constructing
 * this adapter does not enable routes, establish eligibility, accept Terms or grant media access.
 * The caller owns every transaction, including retry and final post-receipt revalidation. */
class AccountSocialIdentityPolicy(
    private val environment: String,
    private val accounts: AccountProfileStore,
    private val relationships: SocialBlockRelationships,
    private val eligibilityPolicyVersion: String,
    private val requiredTermsVersion: String,
    private val circleCreationEnabled: Boolean,
    private val invitationCreationEnabled: Boolean,
) : SocialIdentityPolicy {
    init {
        require(environment == accounts.environment && environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(listOf(eligibilityPolicyVersion, requiredTermsVersion).all {
            it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)
        })
    }

    /** No caller-selected app account UUID and no hidden transaction or retained connection. */
    fun resolvePrincipal(connection: Connection, subject: VerifiedSupabaseSubject, deviceSessionId: UUID): VerifiedSocialAccount =
        accountFailure {
            transaction(connection)
            VerifiedSocialAccount.resolve(connection, accounts, subject, deviceSessionId).also { lockPrincipal(connection, it) }
        }

    override fun lockPrincipal(connection: Connection, principal: VerifiedSocialAccount) = accountFailure {
        transaction(connection)
        val subject = principal.providerSubject ?: fail(SocialFailureCode.UNAUTHENTICATED)
        if (principal.environment != environment ||
            accounts.lockSocialEligibility(connection, subject, principal.deviceSessionId) != principal.accountId)
            fail(SocialFailureCode.UNAUTHENTICATED)
        // The explicit social descriptor must agree with real locked app facts too; this
        // never turns the bootstrap policy or its accepted-Terms check into a default grant.
        readProfile(connection, environment, principal.accountId)
        if (accounts.lockSocialEligibility(connection, subject, principal.deviceSessionId) != principal.accountId)
            fail(SocialFailureCode.UNAUTHENTICATED)
        Unit
    }

    override fun requireCreationEnabled(connection: Connection, principal: VerifiedSocialAccount, invitations: Boolean) {
        lockPrincipal(connection, principal)
        if (if (invitations) !invitationCreationEnabled else !circleCreationEnabled) fail(SocialFailureCode.NOT_CONFIGURED)
    }

    override fun lockUnblockedPair(connection: Connection, environment: String, first: UUID, second: UUID) {
        transaction(connection); sameEnvironment(environment)
        relationships.lockUnblockedPair(connection, environment, first, second)
    }

    override fun requireInvitationPair(connection: Connection, environment: String, first: UUID, second: UUID, issuedOrder: Long) {
        transaction(connection); sameEnvironment(environment)
        if (issuedOrder <= 0) fail(SocialFailureCode.STORAGE_UNAVAILABLE)
        relationships.requireInvitationPair(connection, environment, first, second, issuedOrder)
    }

    override fun readProfile(connection: Connection, environment: String, accountId: UUID): SocialProfileSummary {
        transaction(connection); sameEnvironment(environment)
        return nowait {
            // Foreign roots are acquired only without waiting: actor/circle roots may
            // already be held by this transaction. Contention aborts the WHOLE attempt,
            // rather than deadlocking against a peer actor or hiding a visible member.
            connection.prepareStatement("SELECT u.status,p.status,u.eligibility_state,u.eligibility_policy_version,u.terms_version " +
                "FROM identity.users u JOIN identity.principals p ON p.environment=u.environment AND p.user_id=u.id " +
                "WHERE u.environment=? AND u.id=? FOR SHARE OF u,p NOWAIT").use { statement ->
                statement.setString(1, environment); statement.setObject(2, accountId)
                statement.executeQuery().use { rows ->
                    if (!rows.next() || rows.getString(1) != "active" || rows.getString(2) != "active" ||
                        rows.getString(3) != "eligible" || rows.getString(4) != eligibilityPolicyVersion ||
                        rows.getString(5) != requiredTermsVersion) fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
                    if (rows.next()) fail(SocialFailureCode.STORAGE_UNAVAILABLE)
                }
            }
            connection.prepareStatement("SELECT display_name,normalized_handle,avatar_media_id,onboarding_step FROM profile.profiles " +
                "WHERE environment=? AND user_id=? AND live FOR SHARE NOWAIT").use { statement ->
                statement.setString(1, environment); statement.setObject(2, accountId)
                statement.executeQuery().use { rows ->
                    if (!rows.next() || rows.getString(4) != "ready") fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
                    val name = rows.getString(1) ?: fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
                    val handle = rows.getString(2) ?: fail(SocialFailureCode.CIRCLE_UNAVAILABLE)
                    val result = SocialProfileSummary(accountId, name, handle, rows.getObject(3, UUID::class.java))
                    if (rows.next()) fail(SocialFailureCode.STORAGE_UNAVAILABLE)
                    result
                }
            }
        }
    }

    private fun transaction(connection: Connection) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Social account operation interrupted")
        require(!connection.isClosed && !connection.autoCommit && connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED)
    }
    private fun sameEnvironment(value: String) { if (value != environment) fail(SocialFailureCode.CIRCLE_UNAVAILABLE) }
    private fun <T> nowait(action: () -> T): T = try { action() } catch (failure: SQLException) {
        if (failure.sqlState != "55P03") throw failure
        // Preserve close/control-flow failures: PgTransactions must not retry those.
        throw SQLException("Social profile root is contended", "40001").also { retry ->
            failure.suppressed.forEach(retry::addSuppressed)
        }
    }
    private fun <T> accountFailure(action: () -> T): T = try { action() } catch (failure: AccountFailure) {
        throw SocialFailure(when (failure.code) {
            AccountFailureCode.UNAUTHENTICATED -> SocialFailureCode.UNAUTHENTICATED
            AccountFailureCode.NOT_CONFIGURED -> SocialFailureCode.NOT_CONFIGURED
            AccountFailureCode.STORAGE_UNAVAILABLE -> SocialFailureCode.STORAGE_UNAVAILABLE
            else -> SocialFailureCode.CIRCLE_UNAVAILABLE
        }).also { mapped -> failure.suppressed.forEach(mapped::addSuppressed) }
    }
    private fun fail(code: SocialFailureCode): Nothing = throw SocialFailure(code)
    override fun toString() = "AccountSocialIdentityPolicy(<redacted>)"
}
