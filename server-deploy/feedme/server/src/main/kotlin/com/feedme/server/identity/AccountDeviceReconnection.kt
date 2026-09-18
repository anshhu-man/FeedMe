package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import java.security.MessageDigest
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** Explicit operator policy; no notice/version/age defaults and no eligibility grant. */
class AccountDeviceReconnectionRules(val revision: String, val consentVersion: String,
    val maximumAuthenticationAgeSeconds: Long, val newReconnectionsEnabled: Boolean) {
    init {
        require(validText(revision, 128) && validText(consentVersion, 256) && maximumAuthenticationAgeSeconds in 1..900) {
            "Invalid account reconnection configuration"
        }
    }
    override fun toString() = "AccountDeviceReconnectionRules(<redacted>)"
}

internal class AccountDeviceReconnectionIntent(val previousDeviceId: UUID, val consentVersion: String) {
    override fun toString() = "AccountDeviceReconnectionIntent(<redacted>)"
    companion object {
        fun from(input: JsonObject): AccountDeviceReconnectionIntent? {
            if ("replacesDeviceSessionId" !in input && "replacementConsentVersion" !in input) return null
            try {
                val previous = input.getValue("replacesDeviceSessionId").jsonPrimitive.let { require(it.isString); UUID.fromString(it.content) }
                val consent = input.getValue("replacementConsentVersion").jsonPrimitive.let { require(it.isString); it.content }
                require(validText(consent, 256))
                return AccountDeviceReconnectionIntent(previous, consent)
            } catch (_: Exception) { throw AccountFailure(AccountFailureCode.INPUT_INVALID) }
        }
    }
}

/** Actual provider facts plus separately persisted explicit consent. This authority cannot
 * change accounts, bypass account/installation/predecessor checks, initialize a missing
 * account, accept terms or grant eligibility. The caller owns provider -> account -> device
 * locks and the original durable command. No network or nested transaction is performed.
 */
class SupabaseAccountDeviceReconnection(
    private val environment: String,
    private val authority: SupabasePostgresAuthority,
    private val rules: AccountDeviceReconnectionRules,
) {
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    internal fun authorize(c: Connection, subject: VerifiedSupabaseSubject, account: AccountPolicyFacts,
        previous: AccountRegisteredDevice, intent: AccountDeviceReconnectionIntent, commandKey: UUID,
        requestHash: String, successor: UUID): SupabasePasswordReauthenticationEvidence {
        checkCompatibility(c)
        if (!rules.newReconnectionsEnabled) throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
        if (intent.previousDeviceId != previous.deviceSessionId || intent.consentVersion != rules.consentVersion ||
            previous.providerSessionId == subject.providerSessionId || successor in setOf(previous.deviceSessionId,
                previous.providerSessionId, subject.providerSessionId, subject.subject, account.accountId, account.principalId)) blocked()
        if (previous.version == Long.MAX_VALUE) throw AccountFailure(AccountFailureCode.STORAGE_UNAVAILABLE)
        val proof = authority.lockFreshPasswordSession(c, subject, rules.maximumAuthenticationAgeSeconds)
        c.prepareStatement("INSERT INTO identity.device_reconnections(environment,user_id,command_key,request_sha256," +
            "previous_device_id,new_device_id,previous_provider_session_id,new_provider_session_id,provider_issuer,provider_subject," +
            "installation_id_hash,platform,previous_device_version,policy_revision,consent_version,maximum_authentication_age_seconds," +
            "provider_session_created_at,password_authenticated_at,authorized_at,valid_until) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
            s.setString(1, environment); s.setObject(2, account.accountId); s.setObject(3, commandKey); s.setString(4, requestHash)
            s.setObject(5, previous.deviceSessionId); s.setObject(6, successor); s.setObject(7, previous.providerSessionId)
            s.setObject(8, subject.providerSessionId); s.setString(9, subject.issuer); s.setObject(10, subject.subject)
            s.setString(11, previous.installationHash); s.setString(12, previous.platform); s.setLong(13, previous.version)
            s.setString(14, rules.revision); s.setString(15, rules.consentVersion); s.setLong(16, rules.maximumAuthenticationAgeSeconds)
            listOf(proof.sessionCreatedAt, proof.passwordAuthenticatedAt, proof.acceptedAt, proof.validUntil).forEachIndexed { index, at ->
                s.setObject(17 + index, at.atOffset(java.time.ZoneOffset.UTC))
            }
            check(s.executeUpdate() == 1)
        }
        proof.revalidate(c)
        return proof
    }

    /** The original committed decision stays evidence, not a reusable grant. Replay requires
     * the exact successor and current provider/account checks but does not demand another
     * password login or consume consent again after the original freshness window ends.
     * A later replacement/revocation of that successor denies disclosure through the store.
     */
    internal fun requireOriginal(c: Connection, subject: VerifiedSupabaseSubject, account: AccountPolicyFacts,
        intent: AccountDeviceReconnectionIntent, commandKey: UUID, requestHash: String,
        successor: AccountRegisteredDevice) {
        checkCompatibility(c)
        c.prepareStatement("SELECT r.*,d.user_id AS predecessor_owner,d.revoked_at AS predecessor_revoked," +
            "d.provider_session_id AS predecessor_provider,d.installation_id_hash AS predecessor_installation,d.platform AS predecessor_platform " +
            "FROM identity.device_reconnections r JOIN identity.device_sessions d ON d.environment=r.environment AND d.id=r.previous_device_id " +
            "WHERE r.environment=? AND r.user_id=? AND r.command_key=? FOR SHARE OF r,d").use { s ->
            s.setString(1, environment); s.setObject(2, account.accountId); s.setObject(3, commandKey)
            s.executeQuery().use { r ->
                if (!r.next() || r.getString("request_sha256") != requestHash || r.getObject("previous_device_id", UUID::class.java) != intent.previousDeviceId ||
                    r.getString("consent_version") != intent.consentVersion || r.getObject("new_device_id", UUID::class.java) != successor.deviceSessionId ||
                    r.getObject("new_provider_session_id", UUID::class.java) != subject.providerSessionId ||
                    r.getObject("provider_subject", UUID::class.java) != subject.subject || r.getString("provider_issuer") != subject.issuer ||
                    r.getString("installation_id_hash") != successor.installationHash || r.getString("platform") != successor.platform ||
                    r.getObject("predecessor_owner", UUID::class.java) != account.accountId || r.getObject("predecessor_revoked") == null ||
                    r.getObject("predecessor_provider", UUID::class.java) != r.getObject("previous_provider_session_id", UUID::class.java) ||
                    r.getString("predecessor_installation") != successor.installationHash || r.getString("predecessor_platform") != successor.platform)
                    blocked()
                val created = r.getObject("provider_session_created_at", OffsetDateTime::class.java).toInstant()
                val password = r.getObject("password_authenticated_at", OffsetDateTime::class.java).toInstant()
                val accepted = r.getObject("authorized_at", OffsetDateTime::class.java).toInstant()
                val deadline = r.getObject("valid_until", OffsetDateTime::class.java).toInstant()
                val age = r.getLong("maximum_authentication_age_seconds")
                if (!validText(r.getString("policy_revision"), 128) || age !in 1..900 || created > password || password > accepted ||
                    accepted >= deadline || deadline > created.plusSeconds(age) || deadline > password.plusSeconds(age) || r.next())
                    throw AccountFailure(AccountFailureCode.STORAGE_UNAVAILABLE)
            }
        }
        authority.lockCurrent(c, subject)
    }

    fun checkCompatibility(c: Connection) {
        if (c.autoCommit || c.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
        val expected = checkNotNull(javaClass.getResourceAsStream("/db/migration/V030__account_device_reconnections.sql"))
            .use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
        c.prepareStatement("SELECT checksum FROM platform.schema_migrations WHERE version=30").use { s -> s.executeQuery().use { r ->
            if (!r.next() || r.getString(1) != expected || r.next()) throw AccountFailure(AccountFailureCode.NOT_CONFIGURED)
        } }
        c.createStatement().use { it.executeQuery("SELECT environment,user_id,command_key,request_sha256,previous_device_id,new_device_id," +
            "previous_provider_session_id,new_provider_session_id,provider_issuer,provider_subject,installation_id_hash,platform," +
            "previous_device_version,policy_revision,consent_version,maximum_authentication_age_seconds,provider_session_created_at," +
            "password_authenticated_at,authorized_at,valid_until,recorded_at FROM identity.device_reconnections WHERE false").close() }
    }
    override fun toString() = "SupabaseAccountDeviceReconnection(<redacted>)"
    private fun blocked(): Nothing = throw AccountFailure(AccountFailureCode.POLICY_BLOCKED)
}

private fun validText(value: String, maximum: Int): Boolean = try {
    value.length in 1..maximum && !value.isBlank() && value.none(Char::isISOControl) &&
        value.encodeToByteArray(throwOnInvalidSequence = true).isNotEmpty()
} catch (_: Exception) { false }
