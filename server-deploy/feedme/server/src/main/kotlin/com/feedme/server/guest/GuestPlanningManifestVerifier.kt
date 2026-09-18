package com.feedme.server.guest

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.PortResult
import com.feedme.planning.PlanningStatus
import com.feedme.planning.PlanningDecision
import com.feedme.server.catalog.*
import com.feedme.server.db.CommandActor
import com.feedme.server.db.CommandIdentity
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.PrincipalScope
import com.feedme.server.kitchen.VerifiedKitchenPrincipal
import com.feedme.server.planning.*
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Checked historical material only, not a portable current-access grant or a Plan receipt.
 * The actual guest owner must call the SAME verifier's revalidate with this exact result in
 * its original transaction after final authority checks. An escaped value cannot authorize use.
 * The decision retains exact historical bytes, including its historical eligibility revision;
 * currentEligibilityCatalogRevision separately names the current view actually checked here.
 */
internal class GuestVerifiedManifest internal constructor(
    val header: PlanningManifestHeaderV2,
    val decision: PlanningManifestFirstDecision,
    val eligibleCount: Long,
    val traversedCount: Long,
    val currentEligibilityCatalogRevision: String,
    val originalCommandKey: UUID,
    val commandRequestHash: String,
    val originalPolicyBindingSha256: String,
    // Non-null only for the actual stored root row resolved by verifyStoredFirstPlan.
    val selectedPlanId: UUID?,
    // The actual completed scan result, never reconstructed from a stored envelope.
    // This is historical computation, not portable authorization for materialization.
    val engineDecision: PlanningDecision,
) {
    override fun toString() = "GuestVerifiedManifest(<redacted>)"
}

/** No token verification, transaction creation, SQL writes or accepting authority callback.
 * This kernel is called ONLY by the real guest owner inside its withCurrent transaction,
 * after that owner resolves the original command/guest/manifest/policy/quota relation.
 *
 * A matching seal alone cannot prove completeness. The full original historical catalog is
 * rescanned, using the retained request/private inputs/policy and exact original taxonomy;
 * every eligible source/rank is merge-compared with every stored source-UUID-ordered row.
 * Missing, extra or self-consistently rehashed omissions therefore fail before disclosure.
 * Current inputs and selected recipe eligibility remain separate from historical facts.
 * One verifier instance owns one verification attempt. Its bound result can be revalidated
 * only on the original connection/actor/thread/transaction; a copied result is not evidence.
 */
internal class GuestPlanningManifestVerifier(
    private val environment: String,
    private val journal: RecipeCatalogJournal,
    private val policy: GuestPlanningPolicy,
) {
    init {
        require(environment.matches(Regex("[a-z][a-z0-9-]{0,39}")))
        require(journal.environment == environment)
    }
    private val inputs = GuestPlanningInputs(environment)
    private val attempted = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val retained = AtomicReference<Binding?>(null)

    fun verify(c: Connection, actor: VerifiedKitchenPrincipal, manifestId: UUID,
        originalKey: UUID, expectedRequestHash: String): GuestVerifiedManifest = safe { exclusively {
        if (!attempted.compareAndSet(false, true)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val guard = Guard(c, actor)
        requireHash(expectedRequestHash)
        verifyManifest(c, actor, manifestId, originalKey, expectedRequestHash, guard, Purpose.Selection)
    } }

    /** Historical access to ONE actual owned format-2 first Plan. This is not a new
     * selection, arbitrary manifest read, alternative or permission to start cooking.
     * The original command relation and displayed row are resolved here, never supplied
     * as caller assertions. Original expiry is waived only by an exact retained V2 pin. */
    fun verifyStoredFirstPlan(c: Connection, actor: VerifiedKitchenPrincipal, planId: UUID): GuestVerifiedManifest = safe { exclusively {
        if (!attempted.compareAndSet(false, true)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val guard = Guard(c, actor)
        val stored = loadStored(c, actor, planId, guard)
        verifyManifest(c, actor, stored.plan.manifest, stored.original.key, stored.original.requestHash,
            guard, Purpose.Stored(stored))
    } }

    /** Fresh cooking admission uses the existing READY Plan, not a new recommendation or
     * alternative. Its own Plan lifetime and current inputs/ranking/taxonomy apply; an
     * expired alternative cursor or changed configured retention cannot rewrite that TTL. */
    fun verifyNewCookFirstPlan(c: Connection, actor: VerifiedKitchenPrincipal, planId: UUID): GuestVerifiedManifest = safe { exclusively {
        if (!attempted.compareAndSet(false, true)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val guard = Guard(c, actor)
        val stored = loadStored(c, actor, planId, guard)
        verifyManifest(c, actor, stored.plan.manifest, stored.original.key, stored.original.requestHash,
            guard, Purpose.NewCook(stored))
    } }

    /** Existing cooking always resolves this EXACT owned, unexpired session pin, even if
     * the Plan is still live. No other pin, caller hash or enum can substitute for it. */
    fun verifyPinnedCookFirstPlan(c: Connection, actor: VerifiedKitchenPrincipal, planId: UUID,
        exactSessionId: UUID): GuestVerifiedManifest = safe { exclusively {
        if (!attempted.compareAndSet(false, true)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val guard = Guard(c, actor)
        val stored = loadStored(c, actor, planId, guard)
        verifyManifest(c, actor, stored.plan.manifest, stored.original.key, stored.original.requestHash,
            guard, Purpose.PinnedCook(stored, exactSessionId))
    } }

    /** Resolve exact owned recipe material for a separately authorized NEW copy. This
     * proves historical ownership/materialization only: no Plan/cooking lifetime is a
     * copy grant. The actual saving owner must independently obtain and revalidate a
     * positive current copy permission before disclosing or committing any saved data. */
    fun verifyCopyFirstPlan(c: Connection, actor: VerifiedKitchenPrincipal, planId: UUID): GuestVerifiedManifest = safe { exclusively {
        if (!attempted.compareAndSet(false, true)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val guard = Guard(c, actor)
        val stored = loadStored(c, actor, planId, guard)
        verifyManifest(c, actor, stored.plan.manifest, stored.original.key, stored.original.requestHash,
            guard, Purpose.NewCopy(stored))
    } }

    /** Private feedback provenance only. A historical meal can still be described after
     * its selection/cooking lifetime or a recall. This purpose never grants recipe
     * disclosure, cooking, copying or current ranking authority. */
    fun verifyFeedbackFirstPlan(c: Connection, actor: VerifiedKitchenPrincipal, planId: UUID): GuestVerifiedManifest = safe { exclusively {
        if (!attempted.compareAndSet(false, true)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val guard = Guard(c, actor)
        val stored = loadStored(c, actor, planId, guard)
        verifyManifest(c, actor, stored.plan.manifest, stored.original.key, stored.original.requestHash,
            guard, Purpose.Feedback(stored))
    } }

    private fun verifyManifest(c: Connection, actor: VerifiedKitchenPrincipal, manifestId: UUID,
        originalKey: UUID, expectedRequestHash: String, guard: Guard, purpose: Purpose): GuestVerifiedManifest {
        val header = loadHeader(c, actor, manifestId)
        guard.check()
        val identity = CommandIdentity(PrincipalScope(environment, CommandActor.GUEST, actor.principalId),
            "createPlan", originalKey, body = json(header.request))
        if (identity.requestHash != expectedRequestHash) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        when (purpose) {
            Purpose.Selection -> {
                val ranking = header.policy
                if (ranking.version != policy.ranking.version || ranking.heatEnabled != policy.ranking.heatEnabled ||
                    ranking.improveEnabled != policy.ranking.improveEnabled ||
                    ranking.relatedTasteExplicitlyRequested != policy.ranking.relatedTasteExplicitlyRequested)
                    fail(PlanningFailureCode.NOT_CONFIGURED)
                if (header.expiresAt != header.createdAt.plusSeconds(policy.retentionSeconds.toLong()) ||
                    header.cursorExpiresAt != header.createdAt.plusSeconds(policy.cursorLifetimeSeconds.toLong()))
                    fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            }
            is Purpose.Stored -> {
                requireStoredHeader(purpose.stored, header)
                val at = now(c)
                if (at < header.createdAt) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                if (!header.expiresAt.isAfter(at)) {
                    requireCookingMigration(c)
                    purpose.pin = loadPin(c, actor, purpose.stored.plan, header, null)
                        ?: fail(PlanningFailureCode.PLAN_EXPIRED)
                }
            }
            is Purpose.NewCook -> {
                requireStoredHeader(purpose.stored, header)
                if (purpose.stored.plan.status != "ready" || purpose.stored.plan.recipe == null)
                    fail(PlanningFailureCode.MODE_CONFIRMATION_REQUIRED)
                if (!sameRanking(header)) fail(PlanningFailureCode.INPUTS_CHANGED)
            }
            is Purpose.PinnedCook -> {
                requireStoredHeader(purpose.stored, header)
                requireCookingMigration(c)
                purpose.pin = loadPin(c, actor, purpose.stored.plan, header, purpose.sessionId)
                    ?: fail(PlanningFailureCode.PLAN_UNAVAILABLE)
            }
            is Purpose.NewCopy, is Purpose.Feedback -> {
                requireStoredHeader(purpose.stored, header)
                if (purpose.stored.plan.status != "ready" || purpose.stored.plan.recipe == null)
                    fail(PlanningFailureCode.MODE_CONFIRMATION_REQUIRED)
            }
        }
        live(c, actor, header, purpose)
        val seal = loadSeal(c, actor, manifestId, header)
        guard.check()
        if (usesCurrentInputs(purpose) && !header.inputs.samePrivateInputs(inputs.lock(c, actor, header.request)))
            fail(PlanningFailureCode.INPUTS_CHANGED)
        guard.check(); live(c, actor, header, purpose)
        val current = journal.openView(c)
        val anchor = json(header.catalogAnchorForStorage())
        val historical = current.openHistorical(UUID.fromString(anchor.text("releaseId")), anchor.text("revision").toLong(),
            anchor.text("requestSha256"), anchor.text("taxonomyRevision"), anchor.text("taxonomySha256"), anchor.text("versionCount").toLong())
        guard.check(); live(c, actor, header, purpose)
        if (seal.traversed != historical.versionCount || seal.eligible > seal.traversed)
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        if (usesCurrentInputs(purpose) && (current.taxonomyRevision != historical.taxonomyRevision || current.taxonomySha256 != historical.taxonomySha256))
            fail(PlanningFailureCode.INPUTS_CHANGED)

        val retainedDecision = PlanningManifestFirstDecision.decode(seal.decisionBytes)
        val rowsHash = PlanningManifestRowsDigest(header.sha256)
        var compared = 0L
        val scan = c.prepareStatement("SELECT * FROM planning.manifest_ranks WHERE environment=? AND actor_kind='guest' " +
            "AND principal_id=? AND manifest_id=? ORDER BY recipe_version_id").use { statement ->
            statement.owner(actor, manifestId); statement.fetchSize = 32
            statement.executeQuery().use { rows ->
                guard.check()
                val completed = RecipePlanningCatalogScanner(historical, header.policy).scan(header.request,
                    header.inputs.context(), policy.budget, policy.pageSize) { source, rank ->
                    guard.check()
                    if (!rows.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                    guard.check()
                    requireOwner(rows, actor, manifestId)
                    val retained = rankRow(rows)
                    val actual = PlanningManifestRankRow.fromScan(source, rank)
                    if (!retained.matchesSource(source) || !retained.copyForStorage().encodeUtf8().contentEquals(actual.copyForStorage().encodeUtf8()))
                        fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                    if (compared == Long.MAX_VALUE) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                    rowsHash.append(retained); compared++
                    guard.check()
                }
                guard.check()
                if (rows.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                guard.check()
                when (completed) {
                    is PortResult.Value -> completed.value
                    is PortResult.Failure -> fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                }
            }
        }
        guard.check(); current.checkCurrent(); historical.checkCurrent(); live(c, actor, header, purpose)
        if (scan.traversedCount != historical.versionCount || scan.traversedCount != seal.traversed ||
            scan.eligibleCount != seal.eligible || compared != seal.eligible || rowsHash.finish(compared) != seal.rowsHash)
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val actualDecision = PlanningManifestFirstDecision.fromScan(scan)
        if (!actualDecision.copyForStorage().encodeUtf8().contentEquals(seal.decisionBytes))
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        if (isCooking(purpose) && retainedDecision.status != PlanningStatus.READY)
            fail(PlanningFailureCode.MODE_CONFIRMATION_REQUIRED)

        // Recheck the unscaled original at its historical source, NEVER compare a scaled
        // Plan-local snapshot with current catalog publication or treat it as a new source.
        if (retainedDecision.status == PlanningStatus.READY) {
            requireCurrentRecipe(current, historical, UUID.fromString(checkNotNull(retainedDecision.recipe).id.value), purpose)
        }
        if (purpose === Purpose.Selection) json(header.request)["sourceRecipeVersionId"]?.jsonPrimitive?.content?.let {
            requireCurrentRecipe(current, historical, UUID.fromString(it), purpose)
        }
        guard.check(); current.checkCurrent(); historical.checkCurrent()
        if (usesCurrentInputs(purpose) && !header.inputs.samePrivateInputs(inputs.lock(c, actor, header.request)))
            fail(PlanningFailureCode.INPUTS_CHANGED)
        if (purpose is Purpose.OwnedPlan) {
            requireStoredMaterial(purpose.stored, header, retainedDecision, scan.decision, seal.eligible, current.revision)
            requireSameStored(c, actor, purpose.stored, guard)
        }
        guard.check(); live(c, actor, header, purpose); guard.check()
        val result = GuestVerifiedManifest(header, retainedDecision, seal.eligible, seal.traversed, current.revision.toString(),
            originalKey, expectedRequestHash, when (purpose) {
                Purpose.Selection -> policy.bindingSha256
                is Purpose.OwnedPlan -> purpose.stored.original.policyHash
            }, (purpose as? Purpose.OwnedPlan)?.stored?.plan?.id, scan.decision)
        retained.set(Binding(c, actor, result, guard, current, historical, purpose))
        return result
    }

    /** Rejection-only completion inside the actual guest owner's original transaction. No
     * authentication is accepted here and no second scan, fresh head, transaction or TTL is
     * created. Held locks exclude other writers, not a final policy callback on this very
     * connection, so actual private inputs and publication/review state must be read again.
     * Any failed owned revalidation permanently discards this instance's retained binding.
     */
    fun revalidate(c: Connection, actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest): Unit = safe { exclusively {
        revalidateBinding(requireBinding(c, actor, verified))
    } }

    /** Exact material for the caller's already-owned cooking transaction, never a portable
     * authorization grant. GET/create evidence cannot enter this bridge. The same retained
     * cooking purpose is checked now and must be checked again after final guest authority. */
    fun requireCookingSnapshot(c: Connection, actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest): CookingPlanSnapshot = safe { exclusively {
        val binding = requireBinding(c, actor, verified)
        if (!isCooking(binding.purpose)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        revalidateBinding(binding)
        val plan = (binding.purpose as Purpose.OwnedPlan).stored.plan
        CookingPlanSnapshot(plan.snapshotText, plan.snapshotHash, plan.proofHash, verified.header.sha256)
    } }

    /** Recipe material only; only the original copy-purpose binding can retrieve it. */
    fun requireCopyRecipe(c: Connection, actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest): JsonObject = safe { exclusively {
        val binding = requireBinding(c, actor, verified)
        val purpose = binding.purpose as? Purpose.NewCopy ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        revalidateBinding(binding)
        Json.parseToJsonElement(purpose.stored.plan.snapshotText).jsonObject["recipeSnapshot"]?.jsonObject
            ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    } }

    /** Internal exact pin used solely to validate a selected private feedback target. */
    fun requireFeedbackSnapshot(c: Connection, actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest): CookingPlanSnapshot = safe { exclusively {
        val binding = requireBinding(c, actor, verified)
        val purpose = binding.purpose as? Purpose.Feedback ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        revalidateBinding(binding)
        val plan = purpose.stored.plan
        CookingPlanSnapshot(plan.snapshotText, plan.snapshotHash, plan.proofHash, verified.header.sha256)
    } }

    private fun requireBinding(c: Connection, actor: VerifiedKitchenPrincipal, verified: GuestVerifiedManifest): Binding {
        val binding = retained.get() ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        if (binding.result !== verified || binding.connection !== c || binding.actor !== actor)
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return binding
    }

    private fun revalidateBinding(binding: Binding) {
        val c = binding.connection; val actor = binding.actor; val verified = binding.result
        try {
            binding.guard.check()
            binding.current.checkCurrent(); binding.historical.checkCurrent()
            val purpose = binding.purpose
            if (usesCurrentInputs(purpose)) {
                if (!verified.header.inputs.samePrivateInputs(inputs.lock(c, actor, verified.header.request)))
                    fail(PlanningFailureCode.INPUTS_CHANGED)
                binding.guard.check()
                binding.current.checkCurrent(); binding.historical.checkCurrent()
                if (binding.current.taxonomyRevision != binding.historical.taxonomyRevision ||
                    binding.current.taxonomySha256 != binding.historical.taxonomySha256 ||
                    purpose is Purpose.NewCook && !sameRanking(verified.header))
                    fail(PlanningFailureCode.INPUTS_CHANGED)
            }
            if (purpose is Purpose.OwnedPlan) {
                if (verified.selectedPlanId != purpose.stored.plan.id) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                requireSameStored(c, actor, purpose.stored, binding.guard)
                requireStoredHeader(purpose.stored, verified.header)
            }
            if (verified.decision.status == PlanningStatus.READY)
                requireCurrentRecipe(binding.current, binding.historical, UUID.fromString(checkNotNull(verified.decision.recipe).id.value), binding.purpose)
            if (binding.purpose === Purpose.Selection) json(verified.header.request)["sourceRecipeVersionId"]?.jsonPrimitive?.content?.let {
                requireCurrentRecipe(binding.current, binding.historical, UUID.fromString(it), binding.purpose)
            }
            binding.guard.check()
            binding.current.checkCurrent(); binding.historical.checkCurrent()
            live(c, actor, verified.header, binding.purpose); binding.guard.check()
            if (retained.get() !== binding) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        } catch (failure: Throwable) {
            retained.compareAndSet(binding, null)
            throw failure
        }
    }

    private fun <T> exclusively(action: () -> T): T {
        if (!active.compareAndSet(false, true)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        try { return action() } finally { active.set(false) }
    }

    private class Binding(val connection: Connection, val actor: VerifiedKitchenPrincipal,
        val result: GuestVerifiedManifest, val guard: Guard, val current: RecipeCatalogReadView,
        val historical: RecipeCatalogHistoricalReadView, val purpose: Purpose)

    // No externally selectable weakening flags. Stored is created only after querying the
    // actual displayed row and its durable original relation on this transaction.
    private sealed interface Purpose {
        data object Selection : Purpose
        sealed class OwnedPlan(val stored: StoredFirst) : Purpose
        class Stored(stored: StoredFirst) : OwnedPlan(stored) { var pin: CookingPin? = null }
        class NewCook(stored: StoredFirst) : OwnedPlan(stored)
        class NewCopy(stored: StoredFirst) : OwnedPlan(stored)
        class Feedback(stored: StoredFirst) : OwnedPlan(stored)
        class PinnedCook(stored: StoredFirst, val sessionId: UUID) : OwnedPlan(stored) { var pin: CookingPin? = null }
    }
    private fun usesCurrentInputs(purpose: Purpose) = purpose === Purpose.Selection || purpose is Purpose.NewCook
    private fun isCooking(purpose: Purpose) = purpose is Purpose.NewCook || purpose is Purpose.PinnedCook
    private fun sameRanking(header: PlanningManifestHeaderV2): Boolean = header.policy.let {
        it.version == policy.ranking.version && it.heatEnabled == policy.ranking.heatEnabled &&
            it.improveEnabled == policy.ranking.improveEnabled &&
            it.relatedTasteExplicitlyRequested == policy.ranking.relatedTasteExplicitlyRequested
    }
    private data class StoredFirst(val plan: StoredPlan, val lineage: StoredLineage, val original: StoredOriginal)
    private data class StoredPlan(val id: UUID, val manifest: UUID, val status: String, val recipe: UUID?,
        val position: Long, val snapshotText: String, val snapshotHash: String, val proofText: String,
        val proofHash: String, val cursorHash: String?, val createdAt: Instant)
    private data class StoredLineage(val manifest: UUID, val key: UUID, val commandHash: String,
        val requestText: String, val requestHash: String, val policyText: String, val currentPlan: UUID,
        val version: Long, val createdAt: Instant, val expiresAt: Instant, val cursorExpiresAt: Instant)
    private data class StoredOriginal(val manifest: UUID, val key: UUID, val requestHash: String,
        val policyHash: String, val guest: UUID, val window: LocalDate, val dailyLimit: Int, val usedCount: Int)
    private data class CookingPin(val id: UUID, val createdAt: Instant, val expiresAt: Instant)

    private fun loadStored(c: Connection, actor: VerifiedKitchenPrincipal, planId: UUID, guard: Guard): StoredFirst {
        val plan = query(c, "SELECT * FROM planning.plans WHERE environment=? AND actor_kind='guest' AND principal_id=? AND id=? FOR SHARE", {
            owner(actor, planId)
        }) { r ->
            requireStoredOwner(r, actor)
            if (r.getObject("id", UUID::class.java) != planId) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            if (r.getString("storage_format") != "2" || r.getObject("parent_plan_id") != null)
                fail(PlanningFailureCode.PLAN_UNAVAILABLE)
            if (positiveLong(r, "version") != 1L) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val snapshot = boundedText(r, "snapshot_text", 262_144)
            val proof = boundedText(r, "proof_text", 32_768)
            val snapshotHash = r.getString("snapshot_hash"); val proofHash = r.getString("proof_hash")
            requireHash(snapshotHash); requireHash(proofHash)
            if (digest(snapshot.encodeToByteArray(throwOnInvalidSequence = true)) != snapshotHash ||
                digest(proof.encodeToByteArray(throwOnInvalidSequence = true)) != proofHash)
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val position = r.getString("position")
            if (position !in setOf("-1", "0")) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val cursor = r.getString("next_cursor_hash"); cursor?.let(::requireHash)
            StoredPlan(planId, r.getObject("request_id", UUID::class.java), r.getString("status"),
                r.getObject("recipe_version_id", UUID::class.java), position.toLong(), snapshot, snapshotHash,
                proof, proofHash, cursor, instant(r, "created_at"))
        } ?: fail(PlanningFailureCode.PLAN_UNAVAILABLE)
        guard.check()
        val lineage = query(c, "SELECT * FROM planning.plan_requests WHERE environment=? AND actor_kind='guest' AND principal_id=? AND id=? FOR SHARE", {
            owner(actor, plan.manifest)
        }) { r ->
            requireStoredOwner(r, actor)
            if (r.getString("storage_format") != "2" || r.getObject("id", UUID::class.java) != plan.manifest ||
                r.getObject("manifest_id", UUID::class.java) != plan.manifest || r.getString("evidence_text") != null ||
                r.getString("evidence_hash") != null || r.getString("ordered_ids") != null)
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val version = positiveLong(r, "version")
            val head = r.getObject("current_plan_id", UUID::class.java)
            if (version < 2 || head == null) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            val commandHash = r.getString("command_request_sha256"); val requestHash = r.getString("request_hash")
            requireHash(commandHash); requireHash(requestHash)
            StoredLineage(plan.manifest, r.getObject("create_command_key", UUID::class.java), commandHash,
                boundedText(r, "request_text", PlanningManifestHeaderV2.MAX_REQUEST_BYTES), requestHash,
                boundedText(r, "policy_text", 32_768), head, version, instant(r, "created_at"),
                instant(r, "expires_at"), instant(r, "cursor_expires_at"))
        } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        guard.check()
        val original = query(c, "SELECT p.environment,p.actor_kind,p.principal_id,p.manifest_id,p.command_key,p.request_sha256," +
            "p.policy_sha256,p.guest_session_id,p.window_date,g.daily_plan_limit,w.used_count " +
            "FROM planning.guest_preparations p JOIN identity.principals a ON a.environment=p.environment AND a.id=p.principal_id " +
            "AND a.kind='guest' AND a.guest_session_id=p.guest_session_id " +
            "JOIN identity.guest_sessions g ON g.environment=p.environment AND g.id=p.guest_session_id " +
            "JOIN identity.guest_planning_policies b ON b.environment=p.environment AND b.guest_session_id=p.guest_session_id AND b.policy_sha256=p.policy_sha256 " +
            "JOIN identity.guest_plan_windows w ON w.environment=p.environment AND w.guest_session_id=p.guest_session_id " +
            "AND w.window_date=p.window_date AND w.policy_sha256=p.policy_sha256 " +
            "WHERE p.environment=? AND p.actor_kind='guest' AND p.principal_id=? AND p.manifest_id=? FOR SHARE OF p,b,w", {
                owner(actor, plan.manifest)
            }) { r ->
                requireOwner(r, actor, plan.manifest)
                val key = r.getObject("command_key", UUID::class.java)
                val hash = r.getString("request_sha256"); val policyHash = r.getString("policy_sha256")
                requireHash(hash); requireHash(policyHash)
                val limit = positiveLong(r, "daily_plan_limit"); val used = positiveLong(r, "used_count")
                if (key != lineage.key || hash != lineage.commandHash || limit > 10_000 || used > limit)
                    fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                StoredOriginal(plan.manifest, key, hash, policyHash, r.getObject("guest_session_id", UUID::class.java),
                    r.getObject("window_date", LocalDate::class.java), limit.toInt(), used.toInt())
            } ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        guard.check()
        return StoredFirst(plan, lineage, original)
    }

    private fun requireSameStored(c: Connection, actor: VerifiedKitchenPrincipal, stored: StoredFirst, guard: Guard) {
        if (loadStored(c, actor, stored.plan.id, guard) != stored) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun requireStoredHeader(stored: StoredFirst, header: PlanningManifestHeaderV2) {
        val lineage = stored.lineage
        val policyJson = boundedJson(lineage.policyText, 32_768)
        val headerPolicy = json(header.copyForStorage()).getValue("policy")
        if (stored.plan.manifest != header.manifestId || lineage.manifest != header.manifestId ||
            stored.original.manifest != header.manifestId || lineage.requestText != header.request.encodeUtf8().decodeToString() ||
            lineage.requestHash != digest(header.request.encodeUtf8()) || lineage.createdAt != header.createdAt ||
            lineage.expiresAt != header.expiresAt || lineage.cursorExpiresAt != header.cursorExpiresAt ||
            policyJson.keys != setOf("version", "guestPolicySha256", "ranking") || policyJson["version"] != JsonPrimitive(2) ||
            policyJson.text("guestPolicySha256") != stored.original.policyHash || policyJson["ranking"] != headerPolicy)
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun requireStoredMaterial(stored: StoredFirst, header: PlanningManifestHeaderV2,
        decision: PlanningManifestFirstDecision, engineDecision: PlanningDecision, eligibleCount: Long, currentRevision: Long) {
        val row = stored.plan
        val snapshot = boundedJson(row.snapshotText, 262_144); val proof = boundedJson(row.proofText, 32_768)
        val eligibility = proof.text("eligibilityCatalogRevision")
        if (!eligibility.matches(Regex("[1-9][0-9]{0,18}")) || eligibility.toLong() > currentRevision)
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val cursor = snapshot["nextAlternativeCursor"].takeUnless { it == JsonNull }?.jsonPrimitive?.content
        val exact = GuestPlanMaterializer.first(engineDecision, header, decision, eligibleCount, eligibility,
            row.id, row.createdAt, cursor)
        if (row.snapshotText != exact.snapshotText || row.snapshotHash != exact.snapshotHash || row.proofText != exact.proofText ||
            row.proofHash != exact.proofHash || row.recipe != exact.recipeVersionId || row.status != exact.status ||
            row.position != (if (exact.recipeVersionId == null) -1L else 0L) ||
            row.cursorHash != cursor?.let { digest(it.toByteArray(Charsets.US_ASCII)) })
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }

    private fun loadPin(c: Connection, actor: VerifiedKitchenPrincipal, plan: StoredPlan,
        header: PlanningManifestHeaderV2, exactId: UUID?): CookingPin? = query(c,
        "SELECT environment,actor_kind,principal_id,id,plan_id,plan_snapshot_text,plan_snapshot_hash,plan_proof_hash," +
            "plan_evidence_hash,created_at,expires_at FROM cooking.cook_sessions WHERE environment=? AND actor_kind='guest' " +
            "AND principal_id=? AND plan_id=? AND expires_at>clock_timestamp() AND plan_snapshot_hash=? " +
            "AND plan_proof_hash=? AND plan_evidence_hash=? AND plan_snapshot_text=?" +
            (if (exactId == null) "" else " AND id=?") + " ORDER BY id LIMIT 1 FOR SHARE", {
            owner(actor, plan.id); setString(4, plan.snapshotHash); setString(5, plan.proofHash)
            setString(6, header.sha256); setString(7, plan.snapshotText); exactId?.let { setObject(8, it) }
        }) { r ->
            requireStoredOwner(r, actor)
            val id = r.getObject("id", UUID::class.java)
            if (exactId != null && exactId != id || r.getObject("plan_id", UUID::class.java) != plan.id ||
                r.getString("plan_snapshot_text") != plan.snapshotText || r.getString("plan_snapshot_hash") != plan.snapshotHash ||
                r.getString("plan_proof_hash") != plan.proofHash || r.getString("plan_evidence_hash") != header.sha256)
                fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            CookingPin(id, instant(r, "created_at"), instant(r, "expires_at")).also {
                if (!it.expiresAt.isAfter(it.createdAt)) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            }
        }

    private fun requireCookingMigration(c: Connection) {
        val bytes = javaClass.getResourceAsStream("/db/migration/V005__private_cooking.sql")?.use { it.readBytes() }
            ?: fail(PlanningFailureCode.NOT_CONFIGURED)
        val actual = query(c, "SELECT checksum FROM platform.schema_migrations WHERE version=?", { setInt(1, 5) }) { it.getString(1) }
        if (actual != digest(bytes)) fail(PlanningFailureCode.NOT_CONFIGURED)
    }

    private fun requireStoredOwner(r: ResultSet, actor: VerifiedKitchenPrincipal) {
        if (r.getString("environment") != environment || r.getString("actor_kind") != "guest" ||
            r.getObject("principal_id", UUID::class.java) != actor.principalId) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun boundedText(r: ResultSet, column: String, maximum: Int): String {
        val text = r.getString(column) ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        if (text.length > maximum || text.encodeToByteArray(throwOnInvalidSequence = true).size !in 2..maximum)
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return text
    }
    private fun boundedJson(text: String, maximum: Int): JsonObject = json(WireDocument.parse(text, WireLimits(maximum, 32)))
    private fun instant(r: ResultSet, column: String) = r.getObject(column, OffsetDateTime::class.java).toInstant()
    private fun <T> query(c: Connection, sql: String, bind: PreparedStatement.() -> Unit, read: (ResultSet) -> T): T? =
        c.prepareStatement(sql).use { s -> s.bind(); s.executeQuery().use { r ->
            if (!r.next()) null else read(r).also { if (r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
        } }

    private fun loadHeader(c: Connection, actor: VerifiedKitchenPrincipal, manifest: UUID): PlanningManifestHeaderV2 =
        c.prepareStatement("SELECT * FROM planning.manifest_headers WHERE environment=? AND actor_kind='guest' " +
            "AND principal_id=? AND manifest_id=? FOR SHARE").use { s ->
            s.owner(actor, manifest); s.executeQuery().use { r ->
                if (!r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                requireOwner(r, actor, manifest)
                val text = r.getString("header_text")
                if (text.length > PlanningManifestHeaderV2.MAX_BYTES) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
                val header = PlanningManifestHeaderV2.decode(bytes)
                if (header.environment != environment || header.actorKind != "guest" || header.principalId != actor.principalId ||
                    header.manifestId != manifest || header.sha256 != r.getString("header_sha256"))
                    fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                val anchor = json(header.catalogAnchorForStorage())
                if (anchor.text("releaseId") != r.getObject("catalog_release_id", UUID::class.java).toString() ||
                    anchor.text("revision") != positiveLong(r, "catalog_revision").toString() ||
                    anchor.text("requestSha256") != r.getString("catalog_request_sha256") ||
                    anchor.text("taxonomyRevision") != r.getString("taxonomy_revision") ||
                    anchor.text("taxonomySha256") != r.getString("taxonomy_sha256") ||
                    anchor.text("versionCount") != nonnegativeLong(r, "version_count").toString() ||
                    r.getString("comparator") != PlanningManifestHeaderV2.COMPARATOR)
                    fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                if (r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                header
            }
        }

    private fun loadSeal(c: Connection, actor: VerifiedKitchenPrincipal, manifest: UUID, header: PlanningManifestHeaderV2): Seal =
        c.prepareStatement("SELECT * FROM planning.manifest_seals WHERE environment=? AND actor_kind='guest' " +
            "AND principal_id=? AND manifest_id=? FOR SHARE").use { s ->
            s.owner(actor, manifest); s.executeQuery().use { r ->
                if (!r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                requireOwner(r, actor, manifest)
                val text = r.getString("first_decision_text")
                if (text.length > PlanningManifestFirstDecision.MAX_BYTES) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
                val decisionHash = r.getString("first_decision_sha256"); val rowsHash = r.getString("rows_sha256")
                requireHash(decisionHash); requireHash(rowsHash)
                if (bytes.size > PlanningManifestFirstDecision.MAX_BYTES || digest(bytes) != decisionHash ||
                    r.getString("header_sha256") != header.sha256) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                val result = Seal(nonnegativeLong(r, "traversed_count"), nonnegativeLong(r, "eligible_count"), rowsHash, bytes)
                if (result.eligible > result.traversed || r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                result
            }
        }

    private fun rankRow(r: ResultSet): PlanningManifestRankRow = PlanningManifestRankRow.decode(buildJsonObject {
        put("version", 1); put("recipeVersionId", r.getObject("recipe_version_id", UUID::class.java).toString())
        put("recipeId", r.getObject("recipe_id", UUID::class.java).toString())
        put("sourceReleaseId", r.getObject("source_release_id", UUID::class.java).toString())
        put("sourceRevision", positiveLong(r, "source_revision").toString()); put("sourceRequestSha256", r.getString("source_request_sha256"))
        put("recipeSha256", r.getString("recipe_sha256")); put("reviewSha256", r.getString("review_sha256"))
        put("tasteMatches", score(r, "taste_matches", 4)); put("dislikedIngredients", score(r, "disliked_ingredients", 128))
        put("confirmedIngredients", score(r, "confirmed_ingredients", 128))
        put("activeMinutes", minutes(r, "active_minutes")?.let(::JsonPrimitive) ?: JsonNull)
        put("cleanupMinutes", minutes(r, "cleanup_minutes")?.let(::JsonPrimitive) ?: JsonNull)
    }.toString().encodeToByteArray(throwOnInvalidSequence = true))

    private fun requireCurrentRecipe(current: RecipeCatalogReadView, historical: RecipeCatalogHistoricalReadView, id: UUID, purpose: Purpose) {
        val original = historical.lookup(id) ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        val fresh = current.lookupCurrent(id) ?: fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val old = original.entry.recipe; val recipe = fresh.entry.recipe
        if (recipe.text("reviewStatus") == "recalled" && purpose !is Purpose.Feedback) fail(PlanningFailureCode.RECIPE_RECALLED)
        val status = recipe.text("reviewStatus")
        val retainedUse = purpose is Purpose.Stored || purpose is Purpose.PinnedCook
        val feedbackHistory = purpose is Purpose.Feedback && status in setOf("published", "retired", "recalled")
        if ((!feedbackHistory && status != "published" && (!retainedUse || status != "retired")) || !fresh.entry.review.bool("freeCatalogEligible"))
            fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
        val versionOrder = BigDecimal(recipe.getValue("version").jsonPrimitive.content)
            .compareTo(BigDecimal(old.getValue("version").jsonPrimitive.content))
        if (versionOrder < 0 || versionOrder == 0 && old != recipe ||
            JsonObject(old - lifecycleFields) != JsonObject(recipe - lifecycleFields) || original.entry.review != fresh.entry.review ||
            original.entry.rightsReference != fresh.entry.rightsReference)
            fail(PlanningFailureCode.RECIPE_UNAVAILABLE)
    }

    private fun now(c: Connection): Instant = c.createStatement().use { s -> s.executeQuery("SELECT clock_timestamp()").use { r ->
            if (!r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            r.getObject(1, OffsetDateTime::class.java).toInstant().also { if (r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
        } }

    private fun live(c: Connection, actor: VerifiedKitchenPrincipal, header: PlanningManifestHeaderV2, purpose: Purpose) {
        val at = now(c)
        if (at < header.createdAt) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        when (purpose) {
            Purpose.Selection -> {
                if (!header.expiresAt.isAfter(at)) fail(PlanningFailureCode.PLAN_EXPIRED)
                if (!header.cursorExpiresAt.isAfter(at)) fail(PlanningFailureCode.CURSOR_EXPIRED)
            }
            // Independent current copy rights, not this historical Plan's deadline,
            // govern a new saved copy. No cooking/read TTL exception is borrowed.
            is Purpose.NewCopy, is Purpose.Feedback -> Unit
            is Purpose.Stored -> {
                val pin = purpose.pin
                if (pin == null) {
                    // A read which began within the original lifetime cannot silently
                    // switch to a new pin after a final authority callback or wait.
                    if (!header.expiresAt.isAfter(at)) fail(PlanningFailureCode.PLAN_EXPIRED)
                } else {
                    requireLivePin(c, actor, purpose.stored.plan, header, pin)
                }
            }
            is Purpose.NewCook -> if (!header.expiresAt.isAfter(at)) fail(PlanningFailureCode.PLAN_EXPIRED)
            is Purpose.PinnedCook -> {
                val pin = purpose.pin ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                if (pin.id != purpose.sessionId) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                requireLivePin(c, actor, purpose.stored.plan, header, pin)
            }
        }
    }
    private fun requireLivePin(c: Connection, actor: VerifiedKitchenPrincipal, plan: StoredPlan,
        header: PlanningManifestHeaderV2, pin: CookingPin) {
        requireCookingMigration(c)
        if (loadPin(c, actor, plan, header, pin.id) != pin) fail(PlanningFailureCode.PLAN_EXPIRED)
        val after = now(c)
        if (after < pin.createdAt) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        if (!pin.expiresAt.isAfter(after)) fail(PlanningFailureCode.PLAN_EXPIRED)
    }
    private fun PreparedStatement.owner(actor: VerifiedKitchenPrincipal, manifest: UUID) {
        setString(1, environment); setObject(2, actor.principalId); setObject(3, manifest)
    }
    private fun requireOwner(r: ResultSet, actor: VerifiedKitchenPrincipal, manifest: UUID) {
        if (r.getString("environment") != environment || r.getString("actor_kind") != "guest" ||
            r.getObject("principal_id", UUID::class.java) != actor.principalId || r.getObject("manifest_id", UUID::class.java) != manifest)
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun nonnegativeLong(r: ResultSet, field: String): Long {
        val text = r.getString(field)
        if (text == null || !text.matches(Regex("0|[1-9][0-9]{0,18}"))) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return text.toLong()
    }
    private fun positiveLong(r: ResultSet, field: String): Long = nonnegativeLong(r, field).also {
        if (it == 0L) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
    }
    private fun decimal(r: ResultSet, field: String): BigDecimal? {
        val text = r.getString(field) ?: return null
        if (text.length > 64) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return BigDecimal(text).stripTrailingZeros().also {
            if (it.signum() < 0 || it.precision() > 9 || it.scale() !in -6..0) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        }
    }
    private fun score(r: ResultSet, field: String, maximum: Int): Int {
        val value = decimal(r, field)?.intValueExact() ?: fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        if (value !in 0..maximum) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        return value
    }
    private fun minutes(r: ResultSet, field: String): String? = decimal(r, field)?.toPlainString()
    private fun requireHash(hash: String) { if (!hash.matches(Regex("[0-9a-f]{64}"))) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    private class Seal(val traversed: Long, val eligible: Long, val rowsHash: String, val decisionBytes: ByteArray)
    private inner class Guard(private val c: Connection, private val actor: VerifiedKitchenPrincipal) {
        private val thread = Thread.currentThread()
        private val transaction = id()
        fun check() { if (id() != transaction) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
        private fun id(): Long {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest manifest read interrupted")
            if (Thread.currentThread() !== thread || actor.environment != environment || actor.kind != CommandActor.GUEST ||
                actor.deviceSessionId != null || c.isClosed || c.autoCommit) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
            return c.createStatement().use { s -> s.executeQuery("SELECT txid_current()").use { r ->
                if (!r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
                r.getLong(1).also { if (r.next()) fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
            } }
        }
    }
    private fun <T> safe(action: () -> T): T = try { action() }
        catch (failure: PlanningServiceFailure) { throw failure }
        catch (failure: GuestSessionFailure) { throw failure }
        catch (failure: CommitOutcomeUnknown) { throw failure }
        catch (failure: CancellationException) { throw failure }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw failure }
        catch (failure: RecipeCatalogFailure) { fail(if (failure.code == RecipeCatalogFailureCode.NOT_CONFIGURED)
            PlanningFailureCode.NOT_CONFIGURED else PlanningFailureCode.STORAGE_UNAVAILABLE) }
        catch (failure: java.sql.SQLException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Guest manifest read interrupted")
            if (failure.sqlState in setOf("40001", "40P01")) throw failure
            fail(PlanningFailureCode.STORAGE_UNAVAILABLE)
        }
        catch (_: Exception) { fail(PlanningFailureCode.STORAGE_UNAVAILABLE) }
    private fun fail(code: PlanningFailureCode): Nothing = throw PlanningServiceFailure(code)
    override fun toString() = "GuestPlanningManifestVerifier(<redacted>)"
    private companion object {
        val lifecycleFields = setOf("version", "updatedAt", "reviewStatus", "recallReasonCode", "reviewedAt", "reviewerLabel")
    }
}
