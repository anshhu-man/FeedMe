package com.feedme.server.identity

import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.kitchen.KitchenPreferenceProvisioning
import java.nio.ByteBuffer
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.*

/** PostgreSQL identity/profile persistence only; no HTTP/provider ingress or social admission.
 * Exact issuer+subject mapping, account/private-principal/profile/device and bootstrap receipt
 * are committed in one transaction. Profile.id is the genuine random app account UUID, not a
 * provider UUID. JWT session_id only binds the distinct random FeedMe device record.
 * AccountBootstrapPolicy is mandatory; signature validity never supplies its authority. */
class AccountProfileStore(private val environment: String, private val transactions: PgTransactions,
    private val policy: AccountBootstrapPolicy, private val reconnection: SupabaseAccountDeviceReconnection? = null) {
    private val commands = DurableCommands(transactions)
    private val outbox = OutboxStore(transactions)
    init { require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}"))) }

    fun bootstrapAccount(subject: VerifiedSupabaseSubject, key: UUID, body: JsonObject): CommandResult = safe {
        val input=request("bootstrapAccount",body)
        val reconnect=AccountDeviceReconnectionIntent.from(input)
        transactions.run { c ->
            provider(c,subject); subjectLock(c,subject)
            val existing=account(c,subject)
            if(reconnect!=null && existing==null)fail(AccountFailureCode.POLICY_BLOCKED)
            existing?.let { requireActive(it) }
            val decision=policy.bootstrap(c,subject,input,existing?.facts)
            validatePolicy(decision.policy)
            if(existing!=null)requirePolicy(existing,decision.policy)
            val submitted=input["termsVersion"]?.jsonPrimitive?.content
            if(decision.acceptSubmittedTerms && submitted!=decision.policy.requiredTermsVersion)fail(AccountFailureCode.POLICY_BLOCKED)
            val actor=existing ?: createAccount(c,subject,decision.policy)
            val identity=CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,actor.facts.accountId),"bootstrapAccount",key,body=input)
            var reconnectProof: SupabasePasswordReauthenticationEvidence? = null
            val result=commands.executeInTransaction(c,identity,{ provider(it,subject); requireActive(actor) },{},
                { db,cached -> authorizeBootstrapReplay(db,subject,actor,input,cached,key,identity.requestHash) }) { db ->
                val currentPolicy=policy.current(db,subject,actor.facts);validatePolicy(currentPolicy);requirePolicy(actor,currentPolicy)
                if(currentPolicy.requiredTermsVersion!=decision.policy.requiredTermsVersion || currentPolicy.flags!=decision.policy.flags ||
                    currentPolicy.entitlements!=decision.policy.entitlements)fail(AccountFailureCode.POLICY_BLOCKED)
                val accepted=if(decision.acceptSubmittedTerms)submitted else actor.facts.acceptedTermsVersion
                exec(db,"UPDATE identity.users SET terms_version=?,terms_accepted_at=CASE WHEN ?::text IS NULL THEN NULL WHEN terms_version IS DISTINCT FROM ? THEN clock_timestamp() ELSE terms_accepted_at END,"+
                    "version=version+1,updated_at=clock_timestamp() WHERE environment=? AND id=?") {
                    setString(1,accepted);setString(2,accepted);setString(3,accepted);setString(4,environment);setObject(5,actor.facts.accountId)
                }
                val current=account(db,subject) ?: fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                val device=registerDevice(db,subject,current,input,key,reconnect,identity.requestHash) { reconnectProof=it }
                exec(db,"INSERT INTO identity.bootstrap_consents(environment,user_id,command_key,submitted_terms_version,accepted_terms_version,eligibility_declaration,eligibility_state,eligibility_policy_version) VALUES(?,?,?,?,?,?,?,?)") {
                    setString(1,environment);setObject(2,current.facts.accountId);setObject(3,key);setString(4,submitted)
                    setString(5,if(decision.acceptSubmittedTerms)submitted else null);setString(6,input["eligibilityDeclaration"]?.jsonPrimitive?.content)
                    setString(7,current.facts.eligibility.wire);setString(8,current.facts.eligibilityPolicyVersion)
                }
                val profile=profile(db,current.facts.accountId)
                // Only the newly created account gets a blank recorded-input resource. This
                // neither answers onboarding gates nor grants ordinary kitchen authority.
                if(existing==null)KitchenPreferenceProvisioning.provisionUnansweredAccount(
                    db,environment,current.facts.principalId,key,outbox)
                val result=buildJsonObject {
                    put("profile",profileJson(current,profile));put("sessionId",device.id.toString())
                    put("requiredGates",gates(current,profile,currentPolicy));put("flags",currentPolicy.flags)
                    put("entitlements",currentPolicy.entitlements);put("serverTime",time(db).toString())
                }
                if(existing==null)outbox.append(db,EventDraft(UUID.randomUUID(),"identity.account.bootstrapped.v1",1,"account",current.facts.accountId,current.version,
                    "identity",key.toString(),key,buildJsonObject{put("userId",current.facts.accountId.toString());put("deviceSessionId",device.id.toString())}))
                provider(db,subject);reply("bootstrapAccount",result)
            }
            // DurableCommands completes the receipt after mutate. Fresh-auth validity must
            // survive that final write/wait too; expiry rolls back consent, both devices,
            // events, terms changes and the receipt together. Known replay needs no new
            // password ceremony; its current provider/device/original evidence was checked.
            provider(c,subject)
            reconnectProof?.revalidate(c)
            result
        }
    }

    /** Revoke only this registered FeedMe device, not the upstream provider session.
     * Provider -> subject -> account -> device -> receipt locks are held through commit.
     * Voluntary logout does not require completed onboarding, terms or social eligibility.
     * The device's durable key binding authorizes ONLY this original after its own revoke;
     * an empty canonical response cannot itself identify the revoked device. */
    fun logoutSession(subject: VerifiedSupabaseSubject, deviceSessionId: UUID, key: UUID,
        body: JsonObject): CommandResult = safe {
        val input=request("logoutSession",body)
        transactions.run { c ->
            provider(c,subject);subjectLock(c,subject)
            val actor=account(c,subject) ?: fail(AccountFailureCode.UNAUTHENTICATED)
            requireActive(actor)
            requireLogoutBinding(c,subject,actor,deviceSessionId,key)
            val identity=CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,actor.facts.accountId),"logoutSession",key,body=input)
            commands.executeInTransaction(c,identity,
                { db -> provider(db,subject);requireActive(actor);requireLogoutBinding(db,subject,actor,deviceSessionId,key) },
                { db -> if(requireLogoutBinding(db,subject,actor,deviceSessionId,key).revoked)fail(AccountFailureCode.UNAUTHENTICATED) },
                { db,cached ->
                    val actual=requireLogoutBinding(db,subject,actor,deviceSessionId,key)
                    if(!actual.revoked || actual.logoutKey!=key)fail(AccountFailureCode.UNAUTHENTICATED)
                    validateStored("logoutSession",cached)
                    if(cached.etag!=null)fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                    provider(db,subject)
                }) { db ->
                val old=requireLogoutBinding(db,subject,actor,deviceSessionId,key)
                if(old.revoked)fail(AccountFailureCode.UNAUTHENTICATED)
                if(old.version==Long.MAX_VALUE)fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                exec(db,"UPDATE identity.device_sessions SET revoked_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1,logout_command_key=? "+
                    "WHERE environment=? AND user_id=? AND id=? AND version=? AND revoked_at IS NULL AND logout_command_key IS NULL") {
                    setObject(1,key);setString(2,environment);setObject(3,actor.facts.accountId);setObject(4,old.id);setLong(5,old.version)
                }
                outbox.append(db,EventDraft(UUID.randomUUID(),"identity.session.revoked.v1",1,"device_session",old.id,old.version+1,
                    "identity",key.toString(),key,buildJsonObject{put("userId",actor.facts.accountId.toString());put("sessionId",old.id.toString())}))
                provider(db,subject)
                reply("logoutSession",JsonObject(emptyMap()))
            }
        }
    }

    private fun requireLogoutBinding(c: Connection,subject: VerifiedSupabaseSubject,actor: AccountRow,id: UUID,key: UUID): DeviceRow {
        val actual=device(c,id,actor.facts.accountId)
        if(actual.providerSession!=subject.providerSessionId || actual.id==subject.providerSessionId ||
            (actual.revoked && actual.logoutKey!=key))fail(AccountFailureCode.UNAUTHENTICATED)
        // Account root serializes all device writers. Check this BEFORE receipt lookup as
        // X-Device-Session is not part of the generic canonical command fingerprint.
        val owner=query(c,"SELECT id FROM identity.device_sessions WHERE environment=? AND user_id=? AND logout_command_key=? FOR UPDATE",{
            setString(1,environment);setObject(2,actor.facts.accountId);setObject(3,key)
        }) { r->if(r.next())r.getObject(1,UUID::class.java)else null }
        if(owner!=null && owner!=id)fail(AccountFailureCode.UNAUTHENTICATED)
        return actual
    }

    fun getMe(subject: VerifiedSupabaseSubject, deviceSessionId: UUID): StoredReply = safe {
        transactions.run { c ->
            val actor=authorized(c,subject,deviceSessionId); val p=profile(c,actor.facts.accountId)
            currentPolicy(c,subject,actor);provider(c,subject)
            reply("getMe",profileJson(actor,p),p.version)
        }
    }

    /** Current read-only recovery observation, independent of any bootstrap receipt lifetime.
     * A current bearer and its exact registered device are required on every read. Locks are
     * retained through serialization; the profile, policy and provider are checked after all
     * waits. This neither accepts terms nor provisions/replaces a device, and never emits a
     * command receipt or renews a previous grant. Nonempty gates are observations, not access. */
    fun getAccountReady(subject: VerifiedSupabaseSubject, deviceSessionId: UUID): StoredReply = safe {
        transactions.run { c ->
            val actor = authorized(c, subject, deviceSessionId)
            val p = profile(c, actor.facts.accountId)
            val rules = currentPolicy(c, subject, actor)
            val result = buildJsonObject {
                put("profile", profileJson(actor, p))
                put("sessionId", deviceSessionId.toString())
                put("requiredGates", gates(actor, p, rules))
                put("flags", rules.flags)
                put("entitlements", rules.entitlements)
                put("serverTime", time(c).toString())
            }
            provider(c, subject)
            reply("getAccountReady", result)
        }
    }

    /** Baseline for a future configured social adapter, not an object/role/block grant.
     * Its caller must retain this same transaction and perform all domain-specific checks. */
    internal fun lockSocialEligibility(c: Connection,subject: VerifiedSupabaseSubject,deviceSessionId: UUID) {
        require(!c.autoCommit)
        val actor=authorized(c,subject,deviceSessionId);val p=profile(c,actor.facts.accountId)
        val rules=currentPolicy(c,subject,actor)
        if(p.step!="ready" || p.name==null || p.handle==null || actor.facts.eligibility!=AccountEligibility.ELIGIBLE ||
            actor.facts.acceptedTermsVersion!=rules.requiredTermsVersion)fail(AccountFailureCode.POLICY_BLOCKED)
        provider(c,subject)
    }

    /** Resolve a ready account's actual private owner inside the domain caller's transaction.
     * This is a mapping, NOT a reusable capability or an object/catalog/feature grant. Retain
     * this transaction and repeat admission after domain/receipt waits before disclosure or
     * commit. Provider -> subject -> account/principal -> device -> profile is the same order
     * as account and lifecycle writers. Never derive these IDs from JWT or request UUIDs.
     * No eligibility, onboarding step, accepted terms or device binding is changed here. */
    internal fun lockPrivateAccount(c: Connection, subject: VerifiedSupabaseSubject,
        deviceSessionId: UUID): AccountPrivateMapping {
        require(!c.autoCommit && c.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED) {
            "Private account mapping requires the caller's read-committed transaction"
        }
        val actor = authorized(c, subject, deviceSessionId)
        val p = profile(c, actor.facts.accountId)
        val rules = currentPolicy(c, subject, actor)
        if (actor.facts.eligibility != AccountEligibility.ELIGIBLE || p.step != "ready" ||
            p.name == null || p.handle == null || actor.facts.acceptedTermsVersion != rules.requiredTermsVersion)
            fail(AccountFailureCode.POLICY_BLOCKED)
        provider(c, subject)
        return AccountPrivateMapping(environment, actor.facts.accountId, actor.facts.principalId, deviceSessionId)
    }

    /** Restricted self-preferences authority, not a private/social lease or a bootstrap grant.
     * The caller retains this transaction through its preference/receipt/catalog operation.
     * Reacquire on EVERY operation, including reads and original-request replay; the returned
     * UUID is only the actual private-principal mapping, never reusable authority by itself.
     * Keep provider -> subject -> account/principal -> device -> profile lock order.
     * Equipment also admits independently established eligible accounts; this accessor never
     * establishes eligibility or accepts terms. Ready/general access remains separate. */
    internal fun lockPendingPreferences(c: Connection, subject: VerifiedSupabaseSubject, deviceSessionId: UUID): UUID =
        lockPreferences(c, subject, deviceSessionId, allowReady = false).principalId

    /** Self-preferences classification only, never pantry/social access or a private lease.
     * This result is not a capability: retain this transaction and repeat the full admission
     * after domain/receipt/catalog waits, before commit or disclosure. A caller must not
     * fall back to another mode after a refusal or reuse an earlier UUID/mode as authority. */
    internal fun lockAccountPreferences(c: Connection, subject: VerifiedSupabaseSubject,
        deviceSessionId: UUID): AccountPreferencesAdmission =
        lockPreferences(c, subject, deviceSessionId, allowReady = true)

    private fun lockPreferences(c: Connection, subject: VerifiedSupabaseSubject, deviceSessionId: UUID,
        allowReady: Boolean): AccountPreferencesAdmission {
        require(!c.autoCommit) { "Account preferences require the caller's transaction" }
        val actor = authorized(c, subject, deviceSessionId)
        val p = profile(c, actor.facts.accountId)
        val rules = currentPolicy(c, subject, actor)
        if (p.name == null || p.handle == null) fail(AccountFailureCode.POLICY_BLOCKED)
        val mode = when {
            (actor.facts.eligibility == AccountEligibility.PENDING && p.step in setOf("preferences", "equipment")) ||
                (actor.facts.eligibility == AccountEligibility.ELIGIBLE && p.step == "equipment") -> AccountPreferencesMode.SETUP
            allowReady && actor.facts.eligibility == AccountEligibility.ELIGIBLE && p.step == "ready" &&
                actor.facts.acceptedTermsVersion == rules.requiredTermsVersion -> AccountPreferencesMode.READY
            else -> fail(AccountFailureCode.POLICY_BLOCKED)
        }
        provider(c, subject)
        return AccountPreferencesAdmission(actor.facts.principalId, mode)
    }

    fun updateMe(subject: VerifiedSupabaseSubject, deviceSessionId: UUID, key: UUID,
        ifMatch: String, body: JsonObject): CommandResult = safe(profileWrite=true) {
        val input=request("updateMe",body);if(input.isEmpty())fail(AccountFailureCode.INPUT_INVALID)
        val expected=version(ifMatch)
        val decision=parseDecision(input)
        // Command metadata participates in the original fingerprint, never in Profile itself.
        val fields=JsonObject(input-"onboardingDecision")
        transactions.run { c ->
            val actor=authorized(c,subject,deviceSessionId)
            val identity=CommandIdentity(PrincipalScope(environment,CommandActor.ACCOUNT,actor.facts.accountId),"updateMe",key,body=input,ifMatch=ifMatch)
            commands.executeInTransaction(c,identity,{provider(it,subject);currentPolicy(it,subject,actor)},{},
                { db,cached ->
                    validateStored("updateMe",cached)
                    val actualPolicy=currentPolicy(db,subject,actor)
                    val cachedProfile=cached.body!!.jsonObject
                    if(uuid(cachedProfile,"id")!=actor.facts.accountId || cachedProfile.getValue("eligibility").jsonPrimitive.content!=actualPolicy.eligibility.wire ||
                        cached.etag!="\"${cachedProfile.getValue("version").jsonPrimitive.long}\"")fail(AccountFailureCode.POLICY_BLOCKED)
                    if(cachedProfile.getValue("onboardingStep").jsonPrimitive.content=="ready" &&
                        (actor.facts.eligibility!=AccountEligibility.ELIGIBLE || actor.facts.acceptedTermsVersion!=actualPolicy.requiredTermsVersion))
                        fail(AccountFailureCode.POLICY_BLOCKED)
                    // An old profile reply cannot re-grant a completed gate after an authoritative reset.
                    val present=profile(db,actor.facts.accountId)
                    if(step(cachedProfile.getValue("onboardingStep").jsonPrimitive.content)>step(present.step))fail(AccountFailureCode.POLICY_BLOCKED)
                    decision?.let { verifyDecisionReplay(db,actor,deviceSessionId,identity,expected,it,cachedProfile) }
                    provider(db,subject)
                }) { db ->
                val old=profile(db,actor.facts.accountId);if(old.version!=expected)fail(AccountFailureCode.VERSION_CONFLICT)
                if(old.version==Long.MAX_VALUE)fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                val before=profileJson(actor,old)
                val proposed=JsonObject(before+fields+("version" to JsonPrimitive(old.version+1))+("updatedAt" to JsonPrimitive(time(db).toString())))
                if(validator.validateSchema("Profile",proposed.toString().encodeToByteArray())!=BodyValidationResult.Valid)fail(AccountFailureCode.INPUT_INVALID)
                val nextStep=proposed.getValue("onboardingStep").jsonPrimitive.content
                if(step(nextStep) !in step(old.step)..minOf(step(old.step)+1,3))fail(AccountFailureCode.INPUT_INVALID)
                val rules=currentPolicy(db,subject,actor)
                if(nextStep=="ready" && (actor.facts.eligibility!=AccountEligibility.ELIGIBLE || actor.facts.acceptedTermsVersion!=rules.requiredTermsVersion))fail(AccountFailureCode.POLICY_BLOCKED)
                val onward=old.step!=nextStep && old.step in setOf("preferences","equipment")
                if(onward != (decision!=null))fail(AccountFailureCode.INPUT_INVALID)
                policy.authorizeProfileUpdate(db,subject,actor.facts,before,proposed)
                decision?.let { recordDecision(db,actor,deviceSessionId,identity,old,it,nextStep) }
                exec(db,"UPDATE profile.profiles SET display_name=?,normalized_handle=?,bio=?,avatar_media_id=?,onboarding_step=?,version=version+1,updated_at=clock_timestamp() WHERE environment=? AND user_id=?") {
                    setString(1,proposed["displayName"]?.jsonPrimitive?.content);setString(2,proposed["handle"]?.jsonPrimitive?.content)
                    setString(3,proposed["bio"]?.jsonPrimitive?.content);setObject(4,proposed["avatarMediaId"]?.jsonPrimitive?.content?.let(UUID::fromString))
                    setString(5,nextStep);setString(6,environment);setObject(7,actor.facts.accountId)
                }
                val updated=profile(db,actor.facts.accountId)
                outbox.append(db,EventDraft(UUID.randomUUID(),"profile.profile.changed.v1",1,"profile",actor.facts.accountId,updated.version,
                    "profile",key.toString(),key,buildJsonObject{put("userId",actor.facts.accountId.toString());put("profileVersion",updated.version)
                        put("changedFieldKinds",JsonArray(fields.keys.filter{before[it]!=proposed[it]}.sorted().map(::JsonPrimitive)))}))
                provider(db,subject);currentPolicy(db,subject,actor)
                reply("updateMe",profileJson(actor,updated),updated.version)
            }
        }
    }

    private class PromptDecision(val kind:String,val preference:UUID?,val preferenceVersion:Long?)
    private fun parseDecision(input:JsonObject):PromptDecision? {
        val value=input["onboardingDecision"]?.jsonObject ?: return null
        val kind=value.getValue("kind").jsonPrimitive.content
        if(kind=="skipped")return PromptDecision(kind,null,null)
        val n=try { BigDecimal(value.getValue("preferenceVersion").jsonPrimitive.content).longValueExact() }
            catch(_: RuntimeException) { fail(AccountFailureCode.INPUT_INVALID) }
        if(kind!="answered" || n<=0)fail(AccountFailureCode.INPUT_INVALID)
        return PromptDecision(kind,uuid(value,"preferenceId"),n)
    }

    /** Account root serializes both profile and preference commands. This follows the
     * existing provider/account/device/receipt/profile -> preference ordering. It neither
     * invokes a second command nor rewrites preferences or grants catalog/consent authority. */
    private fun recordDecision(c:Connection,actor:AccountRow,device:UUID,command:CommandIdentity,
        old:ProfileRow,decision:PromptDecision,next:String) {
        // A lost/missing generic receipt cannot make retained provenance a new command.
        if(query(c,"SELECT 1 FROM profile.onboarding_decisions WHERE environment=? AND user_id=? AND command_key=? FOR UPDATE",{
            setString(1,environment);setObject(2,actor.facts.accountId);setObject(3,command.key)
        }) { it.next() })fail(AccountFailureCode.STORAGE_UNAVAILABLE)
        val digest=if(decision.kind=="answered")query(c,
            "SELECT * FROM profile.preferences WHERE environment=? AND actor_kind='account' AND principal_id=? FOR UPDATE",{
                setString(1,environment);setObject(2,actor.facts.principalId)
            }) { row ->
                if(!row.next() || row.getObject("id",UUID::class.java)!=decision.preference || row.getLong("version")!=decision.preferenceVersion)
                    fail(AccountFailureCode.VERSION_CONFLICT)
                val storedFields=Json.parseToJsonElement(row.getString("fields")).jsonObject
                if(storedFields.keys.any { it in setOf("id","version","createdAt","updatedAt") })fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                val snapshot=buildJsonObject {
                    put("id",row.getObject("id",UUID::class.java).toString());put("version",row.getLong("version"))
                    put("createdAt",instant(row,"created_at").toString());put("updatedAt",instant(row,"updated_at").toString())
                    storedFields.forEach { (k,v)->put(k,v) }
                }
                if(validator.validateSchema("Preference",snapshot.toString().encodeToByteArray())!=BodyValidationResult.Valid)
                    fail(AccountFailureCode.STORAGE_UNAVAILABLE)
                MessageDigest.getInstance("SHA-256").digest(canonicalDecisionSnapshot(snapshot).encodeToByteArray())
                    .joinToString(""){"%02x".format(it)}
            } else null
        exec(c,"INSERT INTO profile.onboarding_decisions(environment,user_id,command_key,request_sha256,device_session_id,prompt,disposition,next_step,profile_version_before,profile_version_after,preference_id,preference_version,preference_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)") {
            setString(1,environment);setObject(2,actor.facts.accountId);setObject(3,command.key);setString(4,command.requestHash)
            setObject(5,device);setString(6,old.step);setString(7,decision.kind);setString(8,next)
            setLong(9,old.version);setLong(10,old.version+1);setObject(11,decision.preference)
            decision.preferenceVersion?.let { setLong(12,it) } ?: setNull(12,java.sql.Types.BIGINT);setString(13,digest)
        }
    }

    private fun verifyDecisionReplay(c:Connection,actor:AccountRow,device:UUID,command:CommandIdentity,
        beforeVersion:Long,decision:PromptDecision,cached:JsonObject) {
        query(c,"SELECT * FROM profile.onboarding_decisions WHERE environment=? AND user_id=? AND command_key=? FOR UPDATE",{
            setString(1,environment);setObject(2,actor.facts.accountId);setObject(3,command.key)
        }) { row ->
            if(!row.next())fail(AccountFailureCode.STORAGE_UNAVAILABLE)
            if(row.getObject("device_session_id",UUID::class.java)!=device)fail(AccountFailureCode.UNAUTHENTICATED)
            val next=cached.getValue("onboardingStep").jsonPrimitive.content
            val prompt=when(next){"equipment"->"preferences";"ready"->"equipment";else->fail(AccountFailureCode.STORAGE_UNAVAILABLE)}
            val storedVersion=row.getLong("preference_version").let { if(row.wasNull())null else it }
            val digest=row.getString("preference_sha256")
            if(row.getString("request_sha256")!=command.requestHash || row.getString("disposition")!=decision.kind ||
                row.getString("prompt")!=prompt || row.getString("next_step")!=next ||
                row.getLong("profile_version_before")!=beforeVersion || row.getLong("profile_version_after")!=beforeVersion+1 ||
                cached.getValue("version").jsonPrimitive.long!=beforeVersion+1 ||
                row.getObject("preference_id",UUID::class.java)!=decision.preference || storedVersion!=decision.preferenceVersion ||
                (if(decision.kind=="skipped")digest!=null else digest?.matches(Regex("[0-9a-f]{64}"))!=true))
                fail(AccountFailureCode.STORAGE_UNAVAILABLE)
        }
        // Original observation is historical; later preference writes do not revoke its ACK.
    }

    private fun canonicalDecisionSnapshot(value:JsonElement):String=when(value) {
        is JsonObject->value.toSortedMap().entries.joinToString(",","{","}"){(k,v)->"${JsonPrimitive(k)}:${canonicalDecisionSnapshot(v)}"}
        is JsonArray->value.joinToString(",","[","]"){canonicalDecisionSnapshot(it)}
        is JsonPrimitive->if(value.isString || value==JsonNull || value.booleanOrNull!=null)value.toString()
            else BigDecimal(value.content).stripTrailingZeros().toString()
    }

    private fun authorizeBootstrapReplay(c: Connection, subject: VerifiedSupabaseSubject, actor: AccountRow, input: JsonObject, cached: StoredReply,
        key: UUID, requestHash: String) {
        validateStored("bootstrapAccount",cached);if(cached.etag!=null)fail(AccountFailureCode.STORAGE_UNAVAILABLE)
        val body=cached.body!!.jsonObject;val cachedProfile=body.getValue("profile").jsonObject
        if(uuid(cachedProfile,"id")!=actor.facts.accountId)fail(AccountFailureCode.STORAGE_UNAVAILABLE)
        val device=device(c,uuid(body,"sessionId"),actor.facts.accountId);requireDevice(device,subject)
        if(device.installationHash!=installationHash(input.getValue("installationId").jsonPrimitive.content) || device.platform!=input.getValue("platform").jsonPrimitive.content)
            fail(AccountFailureCode.POLICY_BLOCKED)
        AccountDeviceReconnectionIntent.from(input)?.let { intent ->
            (reconnection ?: fail(AccountFailureCode.NOT_CONFIGURED)).requireOriginal(c,subject,actor.facts,intent,key,requestHash,
                AccountRegisteredDevice(device.id,device.providerSession,device.platform,device.installationHash,device.version))
        }
        val actual=currentPolicy(c,subject,actor);val p=profile(c,actor.facts.accountId)
        if(cachedProfile.getValue("eligibility").jsonPrimitive.content!=actual.eligibility.wire ||
            body.getValue("flags")!=actual.flags || body.getValue("entitlements")!=actual.entitlements ||
            !body.getValue("requiredGates").jsonArray.containsAll(gates(actor,p,actual)))fail(AccountFailureCode.POLICY_BLOCKED)
        provider(c,subject)
    }
    private fun authorized(c: Connection, subject: VerifiedSupabaseSubject, device: UUID): AccountRow {
        provider(c,subject);subjectLock(c,subject)
        val actor=account(c,subject) ?: fail(AccountFailureCode.UNAUTHENTICATED);requireActive(actor)
        requireDevice(device(c,device,actor.facts.accountId),subject);currentPolicy(c,subject,actor);return actor
    }
    private fun provider(c: Connection, subject: VerifiedSupabaseSubject) {
        policy.lockProvider(c,subject)
        if(subject.expiresAtEpochSeconds<=time(c).epochSecond)fail(AccountFailureCode.UNAUTHENTICATED)
    }
    private fun currentPolicy(c: Connection, subject: VerifiedSupabaseSubject, actor: AccountRow): AccountPolicySnapshot =
        policy.current(c,subject,actor.facts).also { validatePolicy(it);requirePolicy(actor,it) }
    private fun requirePolicy(actor: AccountRow, p: AccountPolicySnapshot) {
        if(actor.facts.eligibility!=p.eligibility || actor.facts.eligibilityPolicyVersion!=p.eligibilityPolicyVersion)fail(AccountFailureCode.POLICY_BLOCKED)
    }
    private fun requireActive(actor: AccountRow) { if(actor.status!="active" || actor.principalStatus!="active")fail(AccountFailureCode.UNAUTHENTICATED) }
    private fun requireDevice(device: DeviceRow, subject: VerifiedSupabaseSubject) {
        if(device.revoked || device.providerSession!=subject.providerSessionId || device.id==subject.providerSessionId)fail(AccountFailureCode.UNAUTHENTICATED)
    }
    private fun subjectLock(c: Connection, subject: VerifiedSupabaseSubject) {
        val material=buildJsonArray { add(environment);add(subject.issuer);add(subject.subject.toString()) }.toString()
        val lock=ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray())).long
        query(c,"SELECT pg_advisory_xact_lock(?)",{setLong(1,lock)}) { Unit }
    }
    private fun createAccount(c: Connection, subject: VerifiedSupabaseSubject, p: AccountPolicySnapshot): AccountRow {
        val accountId=randomExcept(subject.subject,subject.providerSessionId);val principalId=randomExcept(accountId,subject.subject,subject.providerSessionId)
        exec(c,"INSERT INTO identity.users(environment,id,provider_issuer,provider_subject,status,eligibility_state,eligibility_policy_version,version) VALUES(?,?,?,?,'active',?,?,1)") {
            setString(1,environment);setObject(2,accountId);setString(3,subject.issuer);setObject(4,subject.subject);setString(5,p.eligibility.wire);setString(6,p.eligibilityPolicyVersion)
        }
        exec(c,"INSERT INTO identity.principals(environment,id,user_id,kind,status,version) VALUES(?,?,?,'user','active',1)") {setString(1,environment);setObject(2,principalId);setObject(3,accountId)}
        exec(c,"INSERT INTO profile.profiles(environment,user_id,onboarding_step,live,version) VALUES(?,?,'profile',true,1)") {setString(1,environment);setObject(2,accountId)}
        return account(c,subject) ?: fail(AccountFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun registerDevice(c: Connection, subject: VerifiedSupabaseSubject, actor: AccountRow, input: JsonObject, key: UUID,
        reconnect: AccountDeviceReconnectionIntent?, requestHash: String,
        retainProof: (SupabasePasswordReauthenticationEvidence) -> Unit): DeviceRow {
        val hash=installationHash(input.getValue("installationId").jsonPrimitive.content);val platform=input.getValue("platform").jsonPrimitive.content
        val old=query(c,"SELECT * FROM identity.device_sessions WHERE environment=? AND user_id=? AND installation_id_hash=? AND revoked_at IS NULL FOR UPDATE",{
            setString(1,environment);setObject(2,actor.facts.accountId);setString(3,hash)
        }) { if(it.next())deviceRow(it)else null }
        // An explicit reconnect may never fall through to first registration or same-session
        // refresh. A stale predecessor must not revoke a later replacement on this install.
        if(reconnect!=null && (old==null || old.id!=reconnect.previousDeviceId || old.providerSession==subject.providerSessionId))
            fail(AccountFailureCode.POLICY_BLOCKED)
        if(old!=null && old.platform!=platform)fail(AccountFailureCode.INPUT_INVALID)
        if(old!=null && old.providerSession==subject.providerSessionId) {
            exec(c,"UPDATE identity.device_sessions SET last_seen_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1,app_version=? WHERE environment=? AND id=?") {
                setString(1,input.getValue("appVersion").jsonPrimitive.content);setString(2,environment);setObject(3,old.id)
            };return device(c,old.id,actor.facts.accountId)
        }
        val id=randomExcept(subject.providerSessionId,subject.subject,actor.facts.accountId,actor.facts.principalId,
            old?.id ?: subject.providerSessionId, old?.providerSession ?: subject.providerSessionId)
        if(old!=null) {
            val previous=AccountRegisteredDevice(old.id,old.providerSession,old.platform,old.installationHash,old.version)
            if(reconnect!=null) retainProof((reconnection ?: fail(AccountFailureCode.NOT_CONFIGURED))
                .authorize(c,subject,actor.facts,previous,reconnect,key,requestHash,id))
            else if(!policy.allowDeviceReplacement(c,subject,actor.facts,previous))fail(AccountFailureCode.POLICY_BLOCKED)
            provider(c,subject)
            if(old.version==Long.MAX_VALUE)fail(AccountFailureCode.STORAGE_UNAVAILABLE)
            exec(c,"UPDATE identity.device_sessions SET revoked_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1 WHERE environment=? AND id=?") {setString(1,environment);setObject(2,old.id)}
            outbox.append(c,EventDraft(UUID.randomUUID(),"identity.session.revoked.v1",1,"device_session",old.id,old.version+1,"identity",key.toString(),key,
                buildJsonObject{put("userId",actor.facts.accountId.toString());put("sessionId",old.id.toString())}))
        }
        exec(c,"INSERT INTO identity.device_sessions(environment,id,user_id,installation_id_hash,provider_session_id,platform,device_label,app_version,version) VALUES(?,?,?,?,?,?,?,?,1)") {
            setString(1,environment);setObject(2,id);setObject(3,actor.facts.accountId);setString(4,hash);setObject(5,subject.providerSessionId)
            setString(6,platform);setString(7,if(platform=="android")"Android device" else "iOS device");setString(8,input.getValue("appVersion").jsonPrimitive.content)
        };return device(c,id,actor.facts.accountId)
    }
    private class AccountRow(val facts: AccountPolicyFacts,val status: String,val principalStatus: String,val version: Long)
    private class ProfileRow(val name: String?,val handle: String?,val bio: String?,val avatar: UUID?,val step: String,val version: Long,val created: Instant,val updated: Instant)
    private class DeviceRow(val id: UUID,val installationHash: String,val providerSession: UUID,val platform: String,val revoked: Boolean,val version: Long,val logoutKey: UUID?)
    private fun account(c: Connection,s: VerifiedSupabaseSubject): AccountRow? = query(c,
        "SELECT u.*,p.id AS principal_id,p.status AS principal_status FROM identity.users u JOIN identity.principals p ON p.environment=u.environment AND p.user_id=u.id WHERE u.environment=? AND u.provider_issuer=? AND u.provider_subject=? FOR UPDATE OF u,p",{
            setString(1,environment);setString(2,s.issuer);setObject(3,s.subject)
        }) { r->if(!r.next())null else AccountRow(AccountPolicyFacts(r.getObject("id",UUID::class.java),r.getObject("principal_id",UUID::class.java),
            AccountEligibility.entries.single{it.wire==r.getString("eligibility_state")},r.getString("eligibility_policy_version"),r.getString("terms_version")),r.getString("status"),r.getString("principal_status"),r.getLong("version")) }
    private fun profile(c: Connection,id: UUID): ProfileRow = query(c,"SELECT * FROM profile.profiles WHERE environment=? AND user_id=? AND live FOR UPDATE",{setString(1,environment);setObject(2,id)}) { r->
        if(!r.next())fail(AccountFailureCode.ACCOUNT_UNAVAILABLE)
        ProfileRow(r.getString("display_name"),r.getString("normalized_handle"),r.getString("bio"),r.getObject("avatar_media_id",UUID::class.java),r.getString("onboarding_step"),r.getLong("version"),instant(r,"created_at"),instant(r,"updated_at")) }
    private fun device(c: Connection,id: UUID,user: UUID): DeviceRow = query(c,"SELECT * FROM identity.device_sessions WHERE environment=? AND id=? AND user_id=? FOR UPDATE",{setString(1,environment);setObject(2,id);setObject(3,user)}) {r->if(r.next())deviceRow(r)else fail(AccountFailureCode.UNAUTHENTICATED)}
    private fun deviceRow(r: ResultSet)=DeviceRow(r.getObject("id",UUID::class.java),r.getString("installation_id_hash"),r.getObject("provider_session_id",UUID::class.java),r.getString("platform"),r.getObject("revoked_at")!=null,r.getLong("version"),r.getObject("logout_command_key",UUID::class.java))
    private fun profileJson(actor: AccountRow,p: ProfileRow)=buildJsonObject {
        put("id",actor.facts.accountId.toString());put("version",p.version);put("createdAt",p.created.toString());put("updatedAt",p.updated.toString())
        p.name?.let{put("displayName",it)};p.handle?.let{put("handle",it)};p.bio?.let{put("bio",it)};p.avatar?.let{put("avatarMediaId",it.toString())}
        put("onboardingStep",p.step);put("eligibility",actor.facts.eligibility.wire)
    }
    private fun gates(actor: AccountRow,p: ProfileRow,rules: AccountPolicySnapshot)=buildJsonArray {
        if(actor.facts.eligibility!=AccountEligibility.ELIGIBLE)add("eligibility")
        if(actor.facts.acceptedTermsVersion!=rules.requiredTermsVersion)add("terms")
        for(s in listOf("profile","preferences","equipment").drop(step(p.step)))add(s)
    }
    private fun validatePolicy(p: AccountPolicySnapshot) {
        for((name,items)in listOf("FeatureFlag" to p.flags,"Entitlement" to p.entitlements)) {
            if(items.any{validator.validateSchema(name,it.toString().encodeToByteArray())!=BodyValidationResult.Valid})fail(AccountFailureCode.NOT_CONFIGURED)
            if(items.map{it.jsonObject.getValue("key").jsonPrimitive.content}.distinct().size!=items.size)fail(AccountFailureCode.NOT_CONFIGURED)
        }
    }
    private fun request(op: String,body: JsonObject): JsonObject {
        val bytes=try{body.toString().encodeToByteArray(throwOnInvalidSequence=true)}catch(_: Exception){fail(AccountFailureCode.INPUT_INVALID)}
        if(bytes.size>16_384 || validator.validateRequest(op,bytes,"application/json")!=BodyValidationResult.Valid)fail(AccountFailureCode.INPUT_INVALID)
        return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }
    private fun reply(op: String,body: JsonObject,version: Long?=null): StoredReply = StoredReply(200,body,version?.let{"\"$it\""}).also{validateStored(op,it)}
    private fun validateStored(op: String,r: StoredReply) {
        if(r.status!=200 || validator.validateResponse(op,r.status,r.body?.toString()?.encodeToByteArray(),"application/json")!=BodyValidationResult.Valid)fail(AccountFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun version(s: String): Long = Regex("\"([1-9][0-9]*)\"").matchEntire(s)?.groupValues?.get(1)?.toLongOrNull() ?: fail(AccountFailureCode.INPUT_INVALID)
    private fun step(s: String)=listOf("profile","preferences","equipment","ready").indexOf(s).also{if(it<0)fail(AccountFailureCode.INPUT_INVALID)}
    private fun uuid(o: JsonObject,k: String)=UUID.fromString(o.getValue(k).jsonPrimitive.content)
    private fun installationHash(s: String)=MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray()).joinToString(""){"%02x".format(it)}
    private fun randomExcept(vararg denied: UUID): UUID { while(true){val id=UUID.randomUUID();if(id !in denied)return id} }
    private fun instant(r: ResultSet,k: String)=r.getObject(k,OffsetDateTime::class.java).toInstant()
    private fun time(c: Connection)=query(c,"SELECT clock_timestamp()",{}) {it.next();it.getObject(1,OffsetDateTime::class.java).toInstant()}
    private fun exec(c: Connection,sql: String,bind: PreparedStatement.()->Unit)=c.prepareStatement(sql).use{it.bind();check(it.executeUpdate()==1)}
    private fun<T> query(c: Connection,sql: String,bind: PreparedStatement.()->Unit,read:(ResultSet)->T): T = c.prepareStatement(sql).use{it.bind();it.executeQuery().use(read)}
    private fun<T> safe(profileWrite: Boolean=false,action:()->T): T = try{action()}
        catch(f: AccountFailure){throw f}
        catch(f: CommitOutcomeUnknown){throw f}
        catch(f: InterruptedException){Thread.currentThread().interrupt();throw f}
        catch(f: SQLException){fail(if(profileWrite&&f.sqlState=="23505")AccountFailureCode.HANDLE_UNAVAILABLE else AccountFailureCode.STORAGE_UNAVAILABLE)}
        catch(_: Exception){fail(AccountFailureCode.STORAGE_UNAVAILABLE)}
    private fun fail(code: AccountFailureCode): Nothing = throw AccountFailure(code)
    private companion object { val validator: ContractBodyValidator by lazy { ContractBodyValidator.bundled() } }
}

/** Internal result of a fresh locked self-preferences check, not reusable authority. */
internal enum class AccountPreferencesMode { SETUP, READY }
internal data class AccountPreferencesAdmission(val principalId: UUID, val mode: AccountPreferencesMode) {
    override fun toString() = "AccountPreferencesAdmission(mode=$mode, principal=<redacted>)"
}

/** Locked identity lookup result only; domain operations must reacquire current authority. */
internal class AccountPrivateMapping internal constructor(val environment: String, val accountId: UUID,
    val principalId: UUID, val deviceSessionId: UUID) {
    override fun toString() = "AccountPrivateMapping(<redacted>)"
}
