package com.feedme.server.guest

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.server.catalog.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.planning.*
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Internal retained identity ONLY. It contains no decision, recipe, Plan, cursor or grant.
 * A replay names the original preparation but MUST pass the full D5 checked reader
 * before any content disclosure; it never rescans changed inputs into a replacement. */
internal class GuestPlanningPreparation internal constructor(val manifestId: UUID, val replayed: Boolean) {
    override fun toString() = "GuestPlanningPreparation(<redacted>)"
}

/** Actual guest-owned D4 writer. The opaque token is the sole identity input. All current
 * private inputs, the full journal view, streamed ranks, seal, command mapping and allowance
 * are owned by ONE GuestSessionStore transaction. No supplied context/header/currentness
 * callback can certify a scan, and no v1 128-candidate representation is used.
 *
 * This is deliberately NOT an HTTP createPlan implementation. D4/D5 database acceptance, D6
 * Plan/continuation lineage, outbox publication and guarded erasure are still required. Do not
 * advertise capabilities or wire a route from this preparation result. The future materializer
 * must keep the original command key and reuse its charged preparation, including after a
 * commit-response loss. No accepting deployment policy or catalog content is provided here. */
internal class GuestPlanningStore(
    private val environment: String,
    private val transactions: PgTransactions,
    private val sessions: GuestSessionStore,
    private val catalog: RecipeCatalogJournal,
    private val policy: GuestPlanningPolicy,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(sessions.isBoundTo(environment, transactions)) { "Guest planning requires its actual session owner" }
        require(catalog.environment == environment) { "Guest planning catalog environment differs" }
    }
    private val inputs = GuestPlanningInputs(environment)
    internal fun isBoundTo(expectedEnvironment: String, expectedTransactions: PgTransactions): Boolean =
        environment == expectedEnvironment && transactions === expectedTransactions
    internal val policyBindingSha256: String get() = policy.bindingSha256

    /** Checked D5 domain data after the actual guest owner's final admission checks. This
     * is not a canonical Plan/HTTP reply, alternative cursor, cooking/copy grant or lease.
     * The exact original create key/body resolves its only manifest; there is no ID-only
     * private read route. A retry never rescans NEW content into the original lineage. */
    fun readPrepared(token: String, commandKey: UUID, body: JsonObject): GuestVerifiedManifest =
        withOriginalSelection(token, commandKey, body, { _, _, verified -> verified }, { _, _, _, _ -> })

    /** Internal transaction composition, not an authority adapter or an arbitrary-ID read.
     * The actual guest owner still authenticates the original token and owns every check,
     * wait, retry and commit. Consumers may perform DB work only, never open transactions,
     * retain handles, commit, or disclose provisional content. Completion is rejecting-only.
     * D6 canonical materialization uses this path so content cannot escape between D5 and
     * the Plan/receipt/outbox write; the separately committed preparation is charged once. */
    internal fun <T> withOriginalSelection(token: String, commandKey: UUID, body: JsonObject,
        consume: (Connection, VerifiedKitchenPrincipal, GuestVerifiedManifest) -> T,
        complete: (Connection, VerifiedKitchenPrincipal, GuestVerifiedManifest, T) -> Unit): T = safe {
        request(body)
        sessions.withCurrentCompletion(token, "createPlan", action = { c, actor -> safe {
            // PgTransactions may retry the complete callback after a serialization or
            // deadlock failure. Each attempt owns its own one-shot verifier and binding.
            val verifier = GuestPlanningManifestVerifier(environment, catalog, policy)
            val guard = Guard(c, actor)
            checkMigration(c, 17, "planning_manifests"); checkMigration(c, 19, "guest_planning_preparations")
            val identity = CommandIdentity(PrincipalScope(environment, CommandActor.GUEST, actor.principalId),
                "createPlan", commandKey, body = body)
            val original = query(c, "SELECT p.request_sha256,p.policy_sha256,p.manifest_id,p.guest_session_id,g.daily_plan_limit,w.used_count " +
                "FROM planning.guest_preparations p JOIN identity.principals a ON a.environment=p.environment AND a.id=p.principal_id " +
                "AND a.kind='guest' AND a.guest_session_id=p.guest_session_id " +
                "JOIN identity.guest_sessions g ON g.environment=p.environment AND g.id=p.guest_session_id " +
                "JOIN identity.guest_planning_policies b ON b.environment=p.environment AND b.guest_session_id=p.guest_session_id AND b.policy_sha256=p.policy_sha256 " +
                "JOIN identity.guest_plan_windows w ON w.environment=p.environment AND w.guest_session_id=p.guest_session_id " +
                "AND w.window_date=p.window_date AND w.policy_sha256=p.policy_sha256 " +
                "WHERE p.environment=? AND p.actor_kind='guest' AND p.principal_id=? AND p.command_key=? FOR SHARE OF p,b,w", {
                owner(actor); setObject(3, commandKey)
            }) { r ->
                if (r.getInt(5) !in 1..10000 || r.getInt(6) !in 1..r.getInt(5)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                Original(r.getString(1), r.getObject(4, UUID::class.java), r.getString(2), r.getObject(3, UUID::class.java))
            } ?: fail(PlanningFailureCode.PLAN_UNAVAILABLE)
            guard.check()
            if (original.requestHash != identity.requestHash) throw GuestSessionFailure(GuestSessionFailureCode.ORIGINAL_MISMATCH)
            if (original.policyHash != policy.bindingSha256) fail(PlanningFailureCode.NOT_CONFIGURED)
            val verified = verifier.verify(c, actor, original.manifest, commandKey, identity.requestHash)
            val result = consume(c, actor, verified)
            CheckedRead(verifier, verified, result).also { guard.check() }
        } }, complete = { c, actor, checked -> safe {
            val verified = checked.verified
            val guard = Guard(c, actor)
            if (verified.header.environment != environment || verified.header.actorKind != "guest" ||
                verified.header.principalId != actor.principalId) fail(PlanningFailureCode.UNAUTHENTICATED)
            // Held locks stop concurrent writers, but a policy check on this same connection
            // could have changed domain state. Recheck actual inputs/content as well as time.
            checked.verifier.revalidate(c, actor, verified)
            complete(c, actor, verified, checked.result)
            // A rejecting consumer check may wait or encounter a same-connection change.
            checked.verifier.revalidate(c, actor, verified)
            guard.check()
            val at = now(c)
            if (!verified.header.expiresAt.isAfter(at)) fail(PlanningFailureCode.PLAN_EXPIRED)
            if (!verified.header.cursorExpiresAt.isAfter(at)) fail(PlanningFailureCode.CURSOR_EXPIRED)
        } }).result
    }

    private class CheckedRead<T>(val verifier: GuestPlanningManifestVerifier, val verified: GuestVerifiedManifest, val result: T)

    /** Actual-token historical first-Plan read, separate from new selection/replay. The
     * verifier resolves the owned Plan and its original preparation inside this transaction;
     * no caller-supplied manifest, expiry exception or currentness assertion is accepted.
     * Historical lifetime/content checks (including any exact existing pin) are rechecked
     * after the real guest owner's final authority checks. Consumers are DB-only and must
     * not disclose provisional content or retain transaction handles. */
    internal fun <T> withHistoricalPlan(token: String, operation: String, planId: UUID,
        consume: (Connection, VerifiedKitchenPrincipal, GuestVerifiedManifest) -> T,
        complete: (Connection, VerifiedKitchenPrincipal, GuestVerifiedManifest, T) -> Unit): T = safe {
        if (operation !in setOf("getPlan", "getPlanExplanation")) fail(PlanningFailureCode.NOT_CONFIGURED)
        sessions.withCurrentCompletion(token, operation, action = { c, actor -> safe {
            val guard = Guard(c, actor)
            // The verifier is one-shot and belongs to this exact retry attempt.
            val verifier = GuestPlanningManifestVerifier(environment, catalog, policy)
            for ((version, name) in listOf(1 to "durable_platform", 3 to "private_planning",
                17 to "planning_manifests", 19 to "guest_planning_preparations", 20 to "manifest_plan_lineage"))
                checkMigration(c, version, name)
            guard.check()
            val verified = verifier.verifyStoredFirstPlan(c, actor, planId)
            CheckedRead(verifier, verified, consume(c, actor, verified)).also { guard.check() }
        } }, complete = { c, actor, checked -> safe {
            val guard = Guard(c, actor)
            val verified = checked.verified
            if (verified.selectedPlanId != planId || verified.header.environment != environment ||
                verified.header.actorKind != "guest" || verified.header.principalId != actor.principalId)
                fail(PlanningFailureCode.UNAUTHENTICATED)
            checked.verifier.revalidate(c, actor, verified)
            complete(c, actor, verified, checked.result)
            checked.verifier.revalidate(c, actor, verified)
            guard.check()
            // Do not impose selection's current-input/taxonomy/cursor deadlines here.
            // The bound historical verifier owns Plan lifetime and exact pin checks.
        } }).result
    }

    /** Actual guest-owned cooking composition. This does not reuse create selection or
     * historical GET as cooking authority. The bound Plan bridge selects the distinct new
     * cook or exact existing-session verifier inside the same receipt/domain transaction.
     * Consumers are internal, DB-only and may not publish provisional results. */
    internal fun <T> withCooking(token: String, operation: String,
        consume: (Connection, GuestCookingPlanAccess) -> T,
        complete: (Connection, GuestCookingPlanAccess, T) -> Unit): T = safe {
        if (operation !in setOf("createCookSession", "getCookSession", "updateCookSession", "completeCookSession"))
            fail(PlanningFailureCode.NOT_CONFIGURED)
        sessions.withCurrentCompletion(token, operation, action = { c, actor -> safe {
            val guard = Guard(c, actor)
            for ((version, name) in listOf(1 to "durable_platform", 3 to "private_planning", 5 to "private_cooking",
                17 to "planning_manifests", 19 to "guest_planning_preparations", 20 to "manifest_plan_lineage"))
                checkMigration(c, version, name)
            guard.check()
            // Each retry creates a new bridge and new one-shot verifier, never a portable
            // actor/snapshot or an earlier transaction's current-access assertion.
            val access = GuestCookingPlanAccess(environment, c, actor, catalog, policy)
            CookRead(access, consume(c, access)).also { guard.check(); access.revalidate() }
        } }, complete = { c, actor, checked -> safe {
            val guard = Guard(c, actor)
            checked.access.revalidate()
            complete(c, checked.access, checked.result)
            checked.access.revalidate()
            guard.check()
        } }).result
    }

    private class CookRead<T>(val access: GuestCookingPlanAccess, val result: T)

    /** Six actual guest Saved operations, with independent positive copy rights. Existing
     * copies and deletion never borrow a Plan/cooking lifetime or require its continued
     * existence. New own-Plan copies resolve genuine historical material inside this same
     * transaction; rights and effects are rechecked after actual final guest authority. */
    internal fun <T> withSavedRecipes(token: String, operation: String, rights: RecipeCopyRightsStore,
        consume: (Connection, GuestSavedRecipeAccess) -> T,
        complete: (Connection, GuestSavedRecipeAccess, T) -> Unit,
        completeAt: (Connection, GuestSavedRecipeAccess, T, Instant) -> Unit): T = safe {
        if (operation !in setOf("saveRecipe", "getSavedRecipe", "deleteSavedRecipe", "listSavedRecipes", "listCollections", "getCollection"))
            fail(PlanningFailureCode.NOT_CONFIGURED)
        require(rights.isBoundTo(environment, transactions, catalog)) { "Guest saving requires its actual rights/catalog owner" }
        sessions.withCurrentCompletion(token, operation, action = { c, actor -> safe {
            val guard = Guard(c, actor)
            for ((version, name) in listOf(1 to "durable_platform", 6 to "private_saved_recipes"))
                checkMigration(c, version, name)
            if (operation != "deleteSavedRecipe") checkMigration(c, 21, "recipe_copy_rights")
            if (operation == "saveRecipe") for ((version, name) in listOf(3 to "private_planning",
                17 to "planning_manifests", 19 to "guest_planning_preparations", 20 to "manifest_plan_lineage"))
                checkMigration(c, version, name)
            val access = GuestSavedRecipeAccess(environment, c, actor, catalog, policy, rights, operation)
            SavedRead(access, consume(c, access)).also { access.revalidate(); guard.check() }
        } }, complete = { c, actor, checked -> safe {
            val guard = Guard(c, actor)
            checked.access.revalidate()
            complete(c, checked.access, checked.result)
            checked.access.revalidate()
            guard.check()
        } }, completeAt = { c, _, checked, at -> safe {
            // Pure local rejection only, after the real guest owner's final database
            // time. No source/identity SQL can extend any bound grant/receipt/cursor.
            checked.access.checkAt(c, at)
            completeAt(c, checked.access, checked.result, at)
        } }).result
    }

    internal fun hasSavingRightsOwner(rights: RecipeCopyRightsStore): Boolean = rights.isBoundTo(environment, transactions, catalog)
    private class SavedRead<T>(val access: GuestSavedRecipeAccess, val result: T)

    /** Two explicit compound commands. Every proof is constructed from the SAME actual
     * guest admission, connection and retry attempt. Recipe copy rights remain independent
     * of a cooking/feedback proof. This does not activate an HTTP or mobile capability. */
    internal fun <T> withMakeAgain(token: String, operation: String, rights: RecipeCopyRightsStore,
        ingredients: IngredientCatalogStore,
        consume: (Connection, GuestMakeAgainAccess) -> T,
        complete: (Connection, GuestMakeAgainAccess, T) -> Unit,
        completeAt: (Connection, GuestMakeAgainAccess, T, Instant) -> Unit): T = safe {
        require(operation in setOf("saveRecipe", "completeCookSession"))
        require(hasSavingRightsOwner(rights) && ingredients.environment == environment)
        sessions.withMakeAgainCompletion(token, operation, action = { c, actor -> safe {
            val guard = Guard(c, actor)
            for ((version, name) in listOf(1 to "durable_platform", 3 to "private_planning",
                5 to "private_cooking", 6 to "private_saved_recipes", 17 to "planning_manifests",
                19 to "guest_planning_preparations", 20 to "manifest_plan_lineage", 21 to "recipe_copy_rights",
                22 to "private_feedback", 23 to "make_again_actions")) checkMigration(c, version, name)
            val access = GuestMakeAgainAccess(
                GuestSavedRecipeAccess(environment, c, actor, catalog, policy, rights, "saveRecipe"),
                GuestFeedbackAccess(environment, c, actor, catalog, ingredients, policy),
                if (operation == "completeCookSession") GuestCookingPlanAccess(environment, c, actor, catalog, policy) else null)
            MakeAgainRead(access, consume(c, access)).also { access.revalidate(); guard.check() }
        } }, complete = { c, actor, checked -> safe {
            val guard = Guard(c, actor)
            checked.access.revalidate()
            complete(c, checked.access, checked.result)
            checked.access.revalidate()
            guard.check()
        } }, completeAt = { c, _, checked, at -> safe {
            checked.access.saving.checkAt(c, at)
            completeAt(c, checked.access, checked.result, at)
        } }).result
    }
    private class MakeAgainRead<T>(val access: GuestMakeAgainAccess, val result: T)

    fun prepareCreate(token: String, commandKey: UUID, body: JsonObject): GuestPlanningPreparation = safe {
        val request = request(body)
        sessions.withCurrent(token, "createPlan") { c, actor -> safe {
            val guard = Guard(c, actor)
            checkMigration(c, 17, "planning_manifests"); checkMigration(c, 19, "guest_planning_preparations")
            guard.check()
            val identity = CommandIdentity(PrincipalScope(environment, CommandActor.GUEST, actor.principalId),
                "createPlan", commandKey, body = body)
            // Actual session store holds installation -> principal -> guest locks. This
            // relation is checked again here; caller-supplied guest IDs are never accepted.
            val owner = query(c, "SELECT g.id,g.daily_plan_limit FROM identity.principals p JOIN identity.guest_sessions g " +
                "ON g.environment=p.environment AND g.id=p.guest_session_id WHERE p.environment=? AND p.id=? " +
                "AND p.kind='guest' AND p.status='active' FOR UPDATE OF p,g", { owner(actor) }) {
                it.getObject(1, UUID::class.java) to it.getInt(2)
            } ?: fail(PlanningFailureCode.UNAUTHENTICATED)
            guard.check()
            val old = query(c, "SELECT request_sha256,guest_session_id,policy_sha256,manifest_id FROM planning.guest_preparations " +
                "WHERE environment=? AND principal_id=? AND command_key=? FOR UPDATE", {
                owner(actor); setObject(3, commandKey)
            }) { Original(it.getString(1), it.getObject(2, UUID::class.java), it.getString(3), it.getObject(4, UUID::class.java)) }
            guard.check()
            if (old != null) {
                if (old.requestHash != identity.requestHash) throw GuestSessionFailure(GuestSessionFailureCode.ORIGINAL_MISMATCH)
                if (old.guest != owner.first || old.policyHash != policy.bindingSha256) fail(PlanningFailureCode.NOT_CONFIGURED)
                // No content is read or returned here. Metadata cannot replace D5 acceptance.
                GuestPlanningPreparation(old.manifest, true)
            } else {
                // A canonical receipt belongs to the later materializer. Never repurpose a
                // previously executed v1 command as a fresh preparation with the same key.
                val existingReceipt = query(c, "SELECT 1 FROM platform.idempotency WHERE principal_scope=? AND operation_id='createPlan' AND key=?", {
                    setString(1, identity.scope.storageKey); setObject(2, commandKey)
                }) { it.getInt(1) }
                if (existingReceipt != null) throw GuestSessionFailure(GuestSessionFailureCode.ORIGINAL_MISMATCH)
                val day = reserve(c, owner.first, owner.second)
                guard.check()
                val current = inputs.lock(c, actor, request)
                val view = catalog.openView(c)
                requireDirectSource(body, view)
                val at = now(c)
                val manifest = UUID.randomUUID()
                val header = header(actor, manifest, request, current, view, at)
                insertHeader(c, header, view)
                val rows = PlanningManifestRowsDigest(header.sha256)
                val scan = RecipePlanningCatalogScanner(view, policy.ranking).scan(request, current.context(), policy.budget,
                    policy.pageSize) { source, rank ->
                    guard.check()
                    val row = PlanningManifestRankRow.fromScan(source, rank)
                    insertRank(c, actor, manifest, row)
                    rows.append(row)
                }
                val complete = when (scan) {
                    is PortResult.Value -> scan.value
                    is PortResult.Failure -> fail(when (scan.reason) {
                        FailureReason.NOT_CONFIGURED -> PlanningFailureCode.NOT_CONFIGURED
                        FailureReason.CONFLICT -> PlanningFailureCode.INPUTS_CHANGED
                        FailureReason.INVALID_DATA -> PlanningFailureCode.INPUT_INVALID
                        else -> PlanningFailureCode.STORAGE_UNAVAILABLE
                    })
                }
                guard.check(); view.checkCurrent()
                if (complete.traversedCount != view.versionCount) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                val decision = PlanningManifestFirstDecision.fromScan(complete)
                val rowHash = rows.finish(complete.eligibleCount)
                execute(c, "INSERT INTO planning.manifest_seals(environment,actor_kind,principal_id,manifest_id,header_sha256," +
                    "traversed_count,eligible_count,rows_sha256,first_decision_text,first_decision_sha256) VALUES(?,'guest',?,?,?,?,?,?,?,?)") {
                    owner(actor); setObject(3, manifest); setString(4, header.sha256); setLong(5, complete.traversedCount)
                    setLong(6, complete.eligibleCount); setString(7, rowHash)
                    setString(8, decision.copyForStorage().encodeUtf8().decodeToString()); setString(9, decision.sha256)
                }
                execute(c, "INSERT INTO planning.guest_preparations(environment,actor_kind,principal_id,command_key,request_sha256," +
                    "guest_session_id,policy_sha256,manifest_id,window_date) VALUES(?,'guest',?,?,?,?,?,?,?)") {
                    owner(actor); setObject(3, commandKey); setString(4, identity.requestHash); setObject(5, owner.first)
                    setString(6, policy.bindingSha256); setObject(7, manifest); setObject(8, day)
                }
                guard.check(); view.checkCurrent()
                // Waiting or scanning cannot hand off an already unusable preparation.
                val finalTime = now(c)
                if (!header.expiresAt.isAfter(finalTime) || !header.cursorExpiresAt.isAfter(finalTime))
                    fail(PlanningFailureCode.PLAN_EXPIRED)
                // GuestSessionStore performs the final real policy/lifecycle/expiry checks
                // AFTER this return and before idle renewal/commit. Any refusal rolls all back.
                GuestPlanningPreparation(manifest, false)
            }
        } }
    }

    private fun reserve(c: Connection, guest: UUID, limit: Int): LocalDate {
        if (limit !in 1..10000) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        execute(c, "INSERT INTO identity.guest_planning_policies(environment,guest_session_id,policy_sha256) VALUES(?,?,?) ON CONFLICT DO NOTHING", false) {
            setString(1, environment); setObject(2, guest); setString(3, policy.bindingSha256)
        }
        val binding = query(c, "SELECT policy_sha256 FROM identity.guest_planning_policies WHERE environment=? AND guest_session_id=? FOR SHARE", {
            setString(1, environment); setObject(2, guest)
        }) { it.getString(1) }
        if (binding != policy.bindingSha256) fail(PlanningFailureCode.NOT_CONFIGURED)
        // UTC admission-day semantics are explicit and pinned in this policy's hash. No
        // refund/move occurs if the scan crosses midnight; failure rolls back the reservation.
        val day = now(c).atOffset(ZoneOffset.UTC).toLocalDate()
        execute(c, "INSERT INTO identity.guest_plan_windows(environment,guest_session_id,window_date,policy_sha256,used_count) VALUES(?,?,?,?,0) ON CONFLICT DO NOTHING", false) {
            setString(1, environment); setObject(2, guest); setObject(3, day); setString(4, policy.bindingSha256)
        }
        val count = c.prepareStatement("UPDATE identity.guest_plan_windows SET used_count=used_count+1 " +
            "WHERE environment=? AND guest_session_id=? AND window_date=? AND policy_sha256=? AND used_count<?").use {
            it.setString(1, environment); it.setObject(2, guest); it.setObject(3, day); it.setString(4, policy.bindingSha256)
            it.setInt(5, limit); it.executeUpdate()
        }
        if (count != 1) throw GuestSessionFailure(GuestSessionFailureCode.LIMIT_REACHED)
        return day
    }

    private fun header(actor: VerifiedKitchenPrincipal, id: UUID, request: WireDocument,
        inputs: PlanningPrivateInputsSnapshot, view: RecipeCatalogReadView, at: Instant): PlanningManifestHeaderV2 {
        val requestBytes = request.encodeUtf8(); val inputBytes = inputs.copyForStorage().encodeUtf8()
        return PlanningManifestHeaderV2.decode(buildJsonObject {
            put("version", 2); put("owner", buildJsonObject {
                put("environment", environment); put("actorKind", "guest"); put("principalId", actor.principalId.toString()); put("manifestId", id.toString())
            })
            put("requestText", requestBytes.decodeToString()); put("requestSha256", digest(requestBytes))
            put("inputsText", inputBytes.decodeToString()); put("inputsSha256", digest(inputBytes))
            put("policy", buildJsonObject {
                put("version", policy.ranking.version); put("heatEnabled", policy.ranking.heatEnabled)
                put("improveEnabled", policy.ranking.improveEnabled); put("relatedTasteExplicitlyRequested", policy.ranking.relatedTasteExplicitlyRequested)
            })
            put("comparator", PlanningManifestHeaderV2.COMPARATOR)
            put("catalogAnchor", buildJsonObject {
                put("releaseId", view.releaseId.toString()); put("revision", view.revision.toString()); put("requestSha256", view.requestSha256)
                put("taxonomyRevision", view.taxonomyRevision); put("taxonomySha256", view.taxonomySha256); put("versionCount", view.versionCount.toString())
            })
            put("createdAt", at.toString()); put("expiresAt", at.plusSeconds(policy.retentionSeconds.toLong()).toString())
            put("cursorExpiresAt", at.plusSeconds(policy.cursorLifetimeSeconds.toLong()).toString())
        }.toString().encodeToByteArray(throwOnInvalidSequence = true))
    }

    private fun insertHeader(c: Connection, h: PlanningManifestHeaderV2, v: RecipeCatalogReadView) = execute(c,
        "INSERT INTO planning.manifest_headers(environment,actor_kind,principal_id,manifest_id,header_text,header_sha256,catalog_release_id," +
            "catalog_revision,catalog_request_sha256,taxonomy_revision,taxonomy_sha256,version_count,comparator) VALUES(?,'guest',?,?,?,?,?,?,?,?,?,?,'lexicographic-v1')") {
        setString(1, environment); setObject(2, h.principalId); setObject(3, h.manifestId)
        setString(4, h.copyForStorage().encodeUtf8().decodeToString()); setString(5, h.sha256); setObject(6, v.releaseId)
        setLong(7, v.revision); setString(8, v.requestSha256); setString(9, v.taxonomyRevision)
        setString(10, v.taxonomySha256); setLong(11, v.versionCount)
    }

    private fun insertRank(c: Connection, actor: VerifiedKitchenPrincipal, manifest: UUID, r: PlanningManifestRankRow) = execute(c,
        "INSERT INTO planning.manifest_ranks(environment,actor_kind,principal_id,manifest_id,recipe_version_id,recipe_id,source_release_id," +
            "source_revision,source_request_sha256,recipe_sha256,review_sha256,taste_matches,disliked_ingredients,confirmed_ingredients,active_minutes,cleanup_minutes) " +
            "VALUES(?,'guest',?,?,?,?,?,?,?,?,?,?,?,?,?,?)") {
        owner(actor); setObject(3, manifest); setObject(4, r.recipeVersionId); setObject(5, r.recipeId); setObject(6, r.sourceReleaseId)
        setLong(7, r.sourceRevision); setString(8, r.sourceRequestSha256); setString(9, r.recipeSha256); setString(10, r.reviewSha256)
        setInt(11, r.tasteMatches); setInt(12, r.dislikedIngredients); setInt(13, r.confirmedIngredients)
        if (r.activeMinutes == null) setNull(14, Types.NUMERIC) else setBigDecimal(14, r.activeMinutes.toBigDecimal())
        if (r.cleanupMinutes == null) setNull(15, Types.NUMERIC) else setBigDecimal(15, r.cleanupMinutes.toBigDecimal())
    }

    private fun requireDirectSource(body: JsonObject, view: RecipeCatalogReadView) {
        val id = body["sourceRecipeVersionId"]?.jsonPrimitive?.content ?: return
        val value = view.lookupCurrent(UUID.fromString(id)) ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val status = value.entry.recipe.getValue("reviewStatus").jsonPrimitive.content
        if (status == "recalled") fail(PlanningFailureCode.RECIPE_RECALLED)
        if (status != "published" || !value.entry.review.getValue("freeCatalogEligible").jsonPrimitive.boolean)
            fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
    }

    private fun request(body: JsonObject): WireDocument {
        val doc = try { WireDocument.parse(body.toString(), WireLimits(65536, 32)) }
            catch (_: IllegalArgumentException) { fail(PlanningFailureCode.INPUT_INVALID) }
        if (validator.validateRequest("createPlan", doc.encodeUtf8(), "application/json") != BodyValidationResult.Valid)
            fail(PlanningFailureCode.INPUT_INVALID)
        if (listOf("sourcePostId", "savedRecipeId", "sourceRecipeVersionId").count(body::containsKey) > 1)
            fail(PlanningFailureCode.INPUT_INVALID)
        if (listOf("sourcePostId", "savedRecipeId").any(body::containsKey) || body["naturalLanguage"]?.jsonPrimitive?.content?.isNotEmpty() == true ||
            body["intent"]?.jsonPrimitive?.content == "tonight" || body.getValue("constraints").jsonObject.keys.any { it.startsWith("household") })
            fail(PlanningFailureCode.NOT_CONFIGURED)
        return doc
    }

    private fun checkMigration(c: Connection, version: Int, name: String) {
        val file = "/db/migration/V${version.toString().padStart(3, '0')}__$name.sql"
        val bytes = GuestPlanningStore::class.java.getResourceAsStream(file)?.use { it.readBytes() }
            ?: fail(PlanningFailureCode.NOT_CONFIGURED)
        val actual = query(c, "SELECT checksum FROM platform.schema_migrations WHERE version=?", { setInt(1, version) }) { it.getString(1) }
        if (actual != digest(bytes)) fail(PlanningFailureCode.NOT_CONFIGURED)
    }
    private fun PreparedStatement.owner(actor: VerifiedKitchenPrincipal) { setString(1, environment); setObject(2, actor.principalId) }
    private fun now(c: Connection): Instant = query(c, "SELECT date_trunc('milliseconds',clock_timestamp())", {}) {
        it.getObject(1, OffsetDateTime::class.java).toInstant()
    } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    private fun execute(c: Connection, sql: String, requireOne: Boolean = true, bind: PreparedStatement.() -> Unit) {
        c.prepareStatement(sql).use { it.bind(); if (it.executeUpdate() != 1 && requireOne) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    }
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T? =
        c.prepareStatement(sql).use { s -> s.bind(); s.executeQuery().use { r ->
            if (!r.next()) null else read(r).also { if (r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
        } }
    private inner class Guard(private val connection: Connection, private val actor: VerifiedKitchenPrincipal) {
        private val thread = Thread.currentThread()
        private val transaction = id()
        fun check() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest planning interrupted")
            if (Thread.currentThread() !== thread || actor.environment != environment || actor.kind != CommandActor.GUEST ||
                actor.deviceSessionId != null || connection.isClosed || connection.autoCommit || id() != transaction)
                fail(PlanningFailureCode.UNAUTHENTICATED)
        }
        private fun id(): Long {
            if (connection.isClosed || connection.autoCommit) fail(PlanningFailureCode.UNAUTHENTICATED)
            return query(connection, "SELECT txid_current()", {}) { it.getLong(1) } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        }
    }
    private class Original(val requestHash: String, val guest: UUID, val policyHash: String, val manifest: UUID)
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: PlanningServiceFailure) { throw failure }
        catch (failure: com.feedme.server.cooking.CookingFailure) { throw failure }
        catch (failure: com.feedme.server.memory.SavedRecipeFailure) { throw failure }
        catch (failure: com.feedme.server.memory.FeedbackFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: RecipeCatalogFailure) { fail(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
            PlanningFailureCode.NOT_CONFIGURED else PlanningFailureCode.STORAGE_UNAVAILABLE) }
        // Same-transaction consumers must let the actual PgTransactions owner rebuild
        // the whole attempt on serialization/deadlock, never retry only a partial save.
        catch (failure: java.sql.SQLException) { if (failure.sqlState in setOf("40001", "40P01")) throw failure
            else fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
        catch (_: Exception) { fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
    override fun toString() = "GuestPlanningStore(<redacted>)"
    private companion object { val validator by lazy { ContractBodyValidator.bundled() } }
}
