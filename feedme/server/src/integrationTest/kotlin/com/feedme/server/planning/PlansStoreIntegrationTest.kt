package com.feedme.server.planning

import com.feedme.contracts.WireDocument
import com.feedme.server.db.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Real PostgreSQL transactions; identity/catalog facts below are explicitly synthetic adapters. */
class PlansStoreIntegrationTest {
    @Test fun manualPlanPersistsExactRecipeReasonsInputEvidenceAndOneAtomicEvent() {
        val f = Fixture(); val key = UUID.randomUUID(); val input = request(servings = "1.0")
        val result = f.store.createPlan(f.account, key, input); val plan = body(result)
        assertEquals("ready", plan.string("status")); assertEquals("assemble", plan.string("mode")); assertEquals("\"1\"", reply(result).etag)
        assertEquals(recipe(), plan.getValue("recipeSnapshot")); assertEquals(1, f.count("planning.plans")); assertEquals(1, f.count("planning.plan_requests"))
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
        assertEquals(plan, f.store.getPlan(f.account, id(plan)).body)
        assertEquals(plan, body(assertIs<CommandResult.Replayed>(f.store.createPlan(f.account, key, input))))
        val proof = parse(f.value("SELECT proof_text FROM planning.plans")); assertEquals(plan["reasons"], proof["facts"])
        assertEquals("pantry-1", proof.string("pantryRevision")); assertEquals("test-rank-1", proof.string("rankingVersion"))
        val event = parse(f.value("SELECT payload::text FROM platform.outbox"))
        assertEquals(setOf("principalId", "planId", "recipeVersionId", "status", "rankingVersion"), event.keys)
        assertFalse(event.toString().contains(INGREDIENT)); assertFalse(event.toString().contains("Synthetic private"))
        assertFalse(result.toString().contains(INGREDIENT)); assertFalse(reply(result).toString().contains(INGREDIENT))
    }

    @Test fun guestAndAccountPlansArePrivateEvenWhenOpaquePrincipalIdsCollide() {
        val f = Fixture(); val guest = f.principal(CommandActor.GUEST, f.account.principalId); val stranger = f.principal(CommandActor.GUEST)
        val accountPlan = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        val guestPlan = body(f.store.createPlan(guest, UUID.randomUUID(), request()))
        for ((actor, plan) in listOf(guest to accountPlan, f.account to guestPlan, stranger to guestPlan)) {
            denied(PlanningFailureCode.PLAN_UNAVAILABLE) { f.store.getPlan(actor, id(plan)) }
            denied(PlanningFailureCode.PLAN_UNAVAILABLE) { f.store.getPlanExplanation(actor, id(plan)) }
        }
        denied(PlanningFailureCode.PLAN_UNAVAILABLE) { f.store.getPlan(stranger, UUID.randomUUID()) }
        assertEquals(guestPlan, f.store.getPlan(guest, id(guestPlan)).body)
    }

    @Test fun currentDeviceGuestExpiryAndRevocationAreCheckedBeforeCachedPlanDisclosure() {
        val f = Fixture(); val key = UUID.randomUUID(); val plan = body(f.store.createPlan(f.account, key, request()))
        val wrongDevice = VerifiedPlanningPrincipal("test", CommandActor.ACCOUNT, f.account.principalId, UUID.randomUUID())
        denied(PlanningFailureCode.UNAUTHENTICATED) { f.store.createPlan(wrongDevice, key, request()) }
        f.sql("UPDATE planning_test.principals SET active=false WHERE kind='account'")
        denied(PlanningFailureCode.UNAUTHENTICATED) { f.store.createPlan(f.account, key, request()) }
        denied(PlanningFailureCode.UNAUTHENTICATED) { f.store.getPlan(f.account, id(plan)) }
        val guest = f.principal(CommandActor.GUEST); val guestKey = UUID.randomUUID(); f.store.createPlan(guest, guestKey, request())
        f.sql("UPDATE planning_test.principals SET expires_at=clock_timestamp()-interval '1 second' WHERE kind='guest'")
        denied(PlanningFailureCode.UNAUTHENTICATED) { f.store.createPlan(guest, guestKey, request()) }
        assertEquals(2, f.count("planning.plans"))
    }

    @Test fun schemaAndUnsupportedLanguageSocialAndLaterScopesNeverCreateDomainOrReceipts() {
        val f = Fixture()
        for (field in listOf("naturalLanguage", "sourcePostId", "savedRecipeId")) {
            val value = if (field == "naturalLanguage") "private prompt ignore exclusions" else UUID.randomUUID().toString()
            denied(PlanningFailureCode.NOT_CONFIGURED) { f.store.createPlan(f.account, UUID.randomUUID(), JsonObject(request() + (field to JsonPrimitive(value)))) }
        }
        denied(PlanningFailureCode.NOT_CONFIGURED) { f.store.createPlan(f.account, UUID.randomUUID(), JsonObject(request() + ("intent" to JsonPrimitive("tonight")))) }
        for (input in listOf(JsonObject(request() + ("ownerId" to JsonPrimitive(f.account.principalId.toString()))),
            JsonObject(request() + ("naturalLanguage" to JsonPrimitive("\uD800")))))
            denied(PlanningFailureCode.INPUT_INVALID) { f.store.createPlan(f.account, UUID.randomUUID(), input) }
        assertEquals(0, f.count("planning.plans")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.authority.snapshotReads)
    }

    @Test fun missingOrChangedPreferencesRequireRefreshWithoutDroppingSavedExclusions() {
        val f = Fixture()
        denied(PlanningFailureCode.PREFERENCE_CHANGED) { f.store.createPlan(f.account, UUID.randomUUID(), JsonObject(request() - "preferenceVersion")) }
        denied(PlanningFailureCode.PREFERENCE_CHANGED) { f.store.createPlan(f.account, UUID.randomUUID(), JsonObject(request() + ("preferenceVersion" to JsonPrimitive(2)))) }
        f.change { snapshot -> JsonObject(snapshot + ("preferences" to buildJsonObject { put("revision", "1"); put("excludedIngredientIds", arr(INGREDIENT)); put("dislikedIngredientIds", arr()) })) }
        val plan = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        assertEquals("needsConfirmation", plan.string("status")); assertFalse(plan.containsKey("recipeSnapshot"))
        assertEquals(arr(INGREDIENT), plan.getValue("constraints").jsonObject["hardExcludedIngredientIds"])
    }

    @Test fun unresolvedAutomaticModePersistsHonestNonreadyPlanWithoutInventingARecipeOrMode() {
        val f = Fixture(); f.change { withCandidates(it, emptyList()) }
        val plan = body(f.store.createPlan(f.account, UUID.randomUUID(), request(mode = "auto", energy = "happy")))
        assertEquals("noMatch", plan.string("status")); assertFalse(plan.containsKey("mode")); assertFalse(plan.containsKey("recipeSnapshot"))
        assertEquals(plan, f.store.getPlan(f.account, id(plan)).body)
    }

    @Test fun manualNoMatchAndUncertainInputsRemainSuccessfulNonCookablePlans() {
        val f = Fixture(); val missing = request(ingredients = emptyList())
        val uncertain = body(f.store.createPlan(f.account, UUID.randomUUID(), missing))
        assertEquals("needsConfirmation", uncertain.string("status")); assertFalse(uncertain.containsKey("recipeSnapshot"))
        f.change { withCandidates(it, emptyList()) }
        val none = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        assertEquals("noMatch", none.string("status")); assertEquals("assemble", none.string("mode")); assertFalse(none.containsKey("recipeSnapshot"))
        assertTrue(f.store.getPlanExplanation(f.account, id(none)).body!!.jsonObject.getValue("items").jsonArray.isNotEmpty())
    }

    @Test fun improveNeedsExactConfirmedBaseEvidenceAndUsesActualReviewedCompatibleAddition() {
        val f = Fixture(); val base = buildJsonObject { put("description", "prepared rice"); put("preparationState", "alreadyPrepared"); put("ingredientIds", arr(OTHER)) }
        val input = JsonObject(request(mode = "improve") + ("baseMeal" to base))
        val pending = body(f.store.createPlan(f.account, UUID.randomUUID(), input)); assertEquals("needsConfirmation", pending.string("status"))
        f.change { snapshot -> JsonObject(withCandidates(snapshot, listOf(candidate(addition = true))) + ("baseMeal" to buildJsonObject {
            put("document", base); put("catalogType", "rice"); put("compositionComplete", true)
        })) }
        val ready = body(f.store.createPlan(f.account, UUID.randomUUID(), input)); assertEquals("ready", ready.string("status")); assertEquals("improve", ready.string("mode"))
        assertEquals("Synthetic private recipe", ready.getValue("recipeSnapshot").jsonObject.string("title"))
    }

    @Test fun reviewedQuantityScalingPersistsMaterializedPlanWithoutReplacingCatalogSource() {
        val f = Fixture(); f.change { withCandidates(it, listOf(candidate(recipeFields = mapOf("scalingMin" to JsonPrimitive(1), "scalingMax" to JsonPrimitive(4))))) }
        val sourceBefore = f.value("SELECT snapshot_text FROM planning_test.inputs")
        val plan = body(f.store.createPlan(f.account, UUID.randomUUID(), request(servings = "2.7500")))
        val snapshot = plan.getValue("recipeSnapshot").jsonObject
        assertEquals("2.75", snapshot.getValue("ingredients").jsonArray.first().jsonObject.getValue("quantity").jsonPrimitive.content)
        assertEquals(sourceBefore, f.value("SELECT snapshot_text FROM planning_test.inputs"))
        assertTrue(plan.getValue("changes").jsonArray.isNotEmpty())
        assertEquals(plan, f.store.getPlan(f.account, id(plan)).body)
    }

    @Test fun durableAlternativeOrderingSurvivesNewServiceInstanceAndKeepsParentsImmutable() {
        val f = Fixture(candidates = listOf(candidate(VERSION3), candidate(VERSION), candidate(VERSION2)))
        val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request())); assertEquals(VERSION, first.string("recipeVersionId"))
        val second = body(f.newStore().nextPlan(f.account, UUID.randomUUID(), id(first), next(first))); assertEquals(VERSION2, second.string("recipeVersionId"))
        assertEquals(id(first).toString(), second.string("parentPlanId")); assertNotEquals(id(first), id(second))
        val third = body(f.newStore().nextPlan(f.account, UUID.randomUUID(), id(second), next(second))); assertEquals(VERSION3, third.string("recipeVersionId"))
        assertEquals(JsonNull, third["nextAlternativeCursor"]); assertEquals(first, f.store.getPlan(f.account, id(first)).body)
        assertEquals(3, f.count("planning.plans")); assertEquals(1, f.count("planning.plan_requests")); assertEquals(3, f.count("platform.outbox"))
    }

    @Test fun cursorCannotAuthorizeAnotherOwnerParentOrConstraintSet() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        val other = f.principal(CommandActor.ACCOUNT)
        denied(PlanningFailureCode.PLAN_UNAVAILABLE) { f.store.nextPlan(other, UUID.randomUUID(), id(first), next(first)) }
        denied(PlanningFailureCode.CURSOR_INVALID) { f.store.nextPlan(f.account, UUID.randomUUID(), id(first), JsonObject(next(first) + ("continuationCursor" to JsonPrimitive("x".repeat(43))))) }
        val changed = JsonObject(first.getValue("constraints").jsonObject + ("servings" to JsonPrimitive(2)))
        denied(PlanningFailureCode.INPUTS_CHANGED) { f.store.nextPlan(f.account, UUID.randomUUID(), id(first), JsonObject(next(first) + ("constraints" to changed))) }
        assertEquals(1, f.count("planning.plans"))
    }

    @Test fun sameKeyConcurrentAlternativesReturnOneOriginalChildWithoutSkippingTwice() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2), candidate(VERSION3))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        val key = UUID.randomUUID(); val outcomes = race(List(3) { { f.newStore().nextPlan(f.account, key, id(first), next(first)) } })
        assertEquals(1, outcomes.count { it is CommandResult.Applied }); assertEquals(2, outcomes.count { it is CommandResult.Replayed })
        assertEquals(1, outcomes.map { body(it as CommandResult) }.toSet().size); assertEquals(2, f.count("planning.plans")); assertEquals(2, f.count("platform.outbox"))
    }

    @Test fun differentCommandKeysCannotConsumeTheSameParentCursorTwice() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2), candidate(VERSION3))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        val outcomes = race(List(2) { { f.newStore().nextPlan(f.account, UUID.randomUUID(), id(first), next(first)) } })
        assertEquals(1, outcomes.count { it is CommandResult.Applied }); assertEquals(1, outcomes.count { it is PlanningServiceFailure && it.code == PlanningFailureCode.CURSOR_INVALID })
        assertEquals(2, f.count("planning.plans")); assertEquals(2, f.count("platform.idempotency"))
    }

    @Test fun newPreferencesOrPantryRevisionFenceSelectionButHistoricalPlanRemainsExact() {
        for (preferenceChange in listOf(false, true)) {
            val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2))); val key = UUID.randomUUID(); val first = body(f.store.createPlan(f.account, key, request()))
            f.change { s -> if (preferenceChange) JsonObject(s + ("preferences" to JsonObject(s.getValue("preferences").jsonObject + ("revision" to JsonPrimitive("2")))))
                else JsonObject(s + ("pantry" to JsonObject(s.getValue("pantry").jsonObject + ("revision" to JsonPrimitive("pantry-2"))))) }
            val code = if (preferenceChange) PlanningFailureCode.PREFERENCE_CHANGED else PlanningFailureCode.INPUTS_CHANGED
            denied(code) { f.store.nextPlan(f.account, UUID.randomUUID(), id(first), next(first)) }
            denied(code) { f.store.createPlan(f.account, key, request()) }
            assertEquals(first, f.store.getPlan(f.account, id(first)).body)
            assertEquals(first["reasons"], f.store.getPlanExplanation(f.account, id(first)).body!!.jsonObject["items"])
        }
    }

    @Test fun recalledPendingCandidatesAreSkippedWithoutRerankingOrRecycling() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2), candidate(VERSION3))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        f.change { s -> withCandidates(s, listOf(candidate(), candidate(VERSION2, recipeFields = mapOf("reviewStatus" to JsonPrimitive("recalled"))), candidate(VERSION3)), "catalog-2") }
        val child = body(f.store.nextPlan(f.account, UUID.randomUUID(), id(first), next(first)))
        assertEquals(VERSION3, child.string("recipeVersionId")); assertEquals("catalog-1", child.string("catalogRevision"))
        assertEquals("catalog-2", parse(f.value("SELECT proof_text FROM planning.plans WHERE id='${id(child)}'")).string("eligibilityCatalogRevision"))
    }

    @Test fun exhaustedAutoContinuationReturnsCanonicalNoMatchWithoutMadeUpMode() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request(mode = "auto", energy = "happy")))
        f.change { withCandidates(it, listOf(candidate(), candidate(VERSION2, recipeFields = mapOf("reviewStatus" to JsonPrimitive("recalled"))))) }
        val exhausted = body(f.store.nextPlan(f.account, UUID.randomUUID(), id(first), next(first)))
        assertEquals("noMatch", exhausted.string("status")); assertFalse(exhausted.containsKey("mode")); assertFalse(exhausted.containsKey("recipeSnapshot"))
        assertEquals(JsonNull, exhausted["nextAlternativeCursor"])
    }

    @Test fun recallDeniesNewReadsAndReplayWhileRetirementPreservesOnlyHistoricalRead() {
        for (status in listOf("retired", "recalled")) {
            val f = Fixture(); val key = UUID.randomUUID(); val original = body(f.store.createPlan(f.account, key, request()))
            f.change { withCandidates(it, listOf(candidate(recipeFields = mapOf("version" to JsonPrimitive(2), "reviewStatus" to JsonPrimitive(status))))) }
            denied(if (status == "recalled") PlanningFailureCode.RECIPE_RECALLED else PlanningFailureCode.RECIPE_UNAVAILABLE) { f.store.createPlan(f.account, key, request()) }
            if (status == "retired") assertEquals(original, f.store.getPlan(f.account, id(original)).body)
            else { denied(PlanningFailureCode.RECIPE_RECALLED) { f.store.getPlan(f.account, id(original)) }; denied(PlanningFailureCode.RECIPE_RECALLED) { f.store.getPlanExplanation(f.account, id(original)) } }
        }
    }

    @Test fun historicalLifecycleUpdatesRequireMonotonicVersionAndUnchangedRecipeMaterialAndReviewEvidence() {
        for (change in listOf("retired", "downgrade", "same-version-status", "same-version-review", "content", "proof")) {
            val f = Fixture(candidates = listOf(candidate(recipeFields = mapOf("version" to JsonPrimitive(2)))))
            val key = UUID.randomUUID(); val original = body(f.store.createPlan(f.account, key, request()))
            val fields = buildMap<String, JsonElement> {
                put("version", JsonPrimitive(when (change) { "downgrade" -> 1; "same-version-status", "same-version-review" -> 2; else -> 3 }))
                put("reviewStatus", JsonPrimitive(if (change == "same-version-review") "published" else "retired"))
                put("updatedAt", JsonPrimitive("2026-09-13T11:00:00Z"))
                if (change == "same-version-review") put("reviewerLabel", JsonPrimitive("Changed lifecycle reviewer"))
                if (change == "content") put("title", JsonPrimitive("Changed immutable title"))
            }
            val proof = if (change == "proof") mapOf("reviewReference" to JsonPrimitive("changed-review-proof")) else emptyMap()
            f.change { withCandidates(it, listOf(candidate(recipeFields = fields, reviewFields = proof))) }
            if (change == "retired") {
                assertEquals(original, f.store.getPlan(f.account, id(original)).body)
                assertEquals(original["reasons"], f.store.getPlanExplanation(f.account, id(original)).body!!.jsonObject["items"])
            } else {
                denied(PlanningFailureCode.RECIPE_UNAVAILABLE) { f.store.getPlan(f.account, id(original)) }
                denied(PlanningFailureCode.RECIPE_UNAVAILABLE) { f.store.getPlanExplanation(f.account, id(original)) }
            }
            // Safe historical retirement does not authorize a new ready selection/replay.
            denied(PlanningFailureCode.RECIPE_UNAVAILABLE) { f.store.createPlan(f.account, key, request()) }
            for (table in listOf("planning.plan_requests", "planning.plans", "platform.idempotency", "platform.outbox"))
                assertEquals(1, f.count(table))
        }
    }

    @Test fun changedSameIdImmutableContentOrEditorialProofCannotBecomeAnAlternative() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        f.change { withCandidates(it, listOf(candidate(), candidate(VERSION2, recipeFields = mapOf("title" to JsonPrimitive("Changed same ID")))) ) }
        denied(PlanningFailureCode.INPUTS_CHANGED) { f.store.nextPlan(f.account, UUID.randomUUID(), id(first), next(first)) }
        assertEquals(1, f.count("planning.plans")); assertEquals(first.string("id"), f.value("SELECT current_plan_id FROM planning.plan_requests"))
    }

    @Test fun taxonomyChangeAndUnpresentedRecipeExclusionsFailWithoutConsumingCursor() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        denied(PlanningFailureCode.INPUT_INVALID) { f.store.nextPlan(f.account, UUID.randomUUID(), id(first), JsonObject(next(first) + ("excludeRecipeVersionIds" to arr(VERSION2)))) }
        f.change { s -> JsonObject(s + ("catalog" to JsonObject(s.getValue("catalog").jsonObject + ("taxonomyRevision" to JsonPrimitive("taxonomy-2"))))) }
        denied(PlanningFailureCode.INPUTS_CHANGED) { f.store.nextPlan(f.account, UUID.randomUUID(), id(first), next(first)) }
        assertEquals(1, f.count("planning.plans"))
    }

    @Test fun currentTaxonomyRevisionAndCompositionFenceReadyCommandReplayWithoutChangingHistoricalPlans() {
        for (change in listOf("revision", "composition", "unknown")) {
            val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2)))
            val createKey = UUID.randomUUID(); val nextKey = UUID.randomUUID()
            val original = body(f.store.createPlan(f.account, createKey, request()))
            val child = body(f.store.nextPlan(f.account, nextKey, id(original), next(original)))
            val tables = listOf("planning.plan_requests", "planning.plans", "platform.idempotency", "platform.outbox")
            val before = tables.associateWith(f::count)
            f.change { snapshot ->
                val catalog = snapshot.getValue("catalog").jsonObject
                val changed = if (change == "revision") catalog + ("taxonomyRevision" to JsonPrimitive("taxonomy-2"))
                else catalog + ("ingredients" to JsonArray(catalog.getValue("ingredients").jsonArray.map { value ->
                    val ingredient = value.jsonObject
                    if (ingredient.string("ingredientId") == INGREDIENT) JsonObject(ingredient +
                        ("componentIds" to if (change == "unknown") JsonNull else arr(OTHER))) else ingredient
                }))
                JsonObject(snapshot + ("catalog" to JsonObject(changed)))
            }
            denied(PlanningFailureCode.INPUTS_CHANGED) { f.newStore().createPlan(f.account, createKey, request()) }
            denied(PlanningFailureCode.INPUTS_CHANGED) { f.newStore().nextPlan(f.account, nextKey, id(original), next(original)) }
            assertEquals(before, tables.associateWith(f::count))
            assertEquals(child.string("id"), f.value("SELECT current_plan_id FROM planning.plan_requests"))
            assertEquals(original, f.store.getPlan(f.account, id(original)).body)
            assertEquals(child, f.store.getPlan(f.account, id(child)).body)
            assertEquals(original["reasons"], f.store.getPlanExplanation(f.account, id(original)).body!!.jsonObject["items"])
        }
    }

    @Test fun cursorAndPlanExpiryAreAuthoritativeAndRecheckedAfterPolicyWait() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        f.sql("UPDATE planning.plan_requests SET cursor_expires_at=clock_timestamp()+interval '1 second'")
        f.authority.afterSnapshot = { Thread.sleep(1200) }
        denied(PlanningFailureCode.CURSOR_EXPIRED) { f.store.nextPlan(f.account, UUID.randomUUID(), id(first), next(first)) }
        assertEquals(1, f.count("planning.plans")); f.authority.afterSnapshot = null
        f.sql("UPDATE planning.plan_requests SET created_at=clock_timestamp()-interval '2 days',cursor_expires_at=clock_timestamp()-interval '1 day',expires_at=clock_timestamp()-interval '1 second'")
        denied(PlanningFailureCode.PLAN_EXPIRED) { f.store.getPlan(f.account, id(first)) }
    }

    @Test fun outboxFailureRollsBackPlanLineageAndReceiptThenOriginalKeyRetries() {
        val f = Fixture(); val key = UUID.randomUUID(); f.faults.outboxFailure = true
        denied(PlanningFailureCode.STORAGE_UNAVAILABLE) { f.store.createPlan(f.account, key, request()) }
        for (table in listOf("planning.plans", "planning.plan_requests", "platform.idempotency", "platform.outbox")) assertEquals(0, f.count(table))
        assertIs<CommandResult.Applied>(f.store.createPlan(f.account, key, request()))
    }

    @Test fun lostCommitResponseRecoversExactChildAndNeverAdvancesTheCursorAgain() {
        val f = Fixture(candidates = listOf(candidate(), candidate(VERSION2), candidate(VERSION3))); val first = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        val key = UUID.randomUUID(); f.faults.loseCommit = true
        assertFailsWith<CommitOutcomeUnknown> { f.store.nextPlan(f.account, key, id(first), next(first)) }
        val replay = assertIs<CommandResult.Replayed>(f.newStore().nextPlan(f.account, key, id(first), next(first)))
        assertEquals(VERSION2, body(replay).string("recipeVersionId")); assertEquals(2, f.count("planning.plans")); assertEquals(2, f.count("platform.outbox"))
    }

    @Test fun immutableRowsCannotBeOverwrittenAndTamperedEvidenceFailsClosed() {
        val f = Fixture(); val plan = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        assertFailsWith<SQLException> { f.sql("UPDATE planning.plans SET snapshot_text=snapshot_text") }
        f.sql("UPDATE planning.plan_requests SET evidence_hash=repeat('0',64)")
        denied(PlanningFailureCode.STORAGE_UNAVAILABLE) { f.store.getPlan(f.account, id(plan)) }
        assertEquals(1, f.count("planning.plans"))
    }

    @Test fun explanationsAreStoredFactsWithPrivatePlanBoundPaginationAndNoLanguageCall() {
        val f = Fixture(); val plan = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        val first = f.store.getPlanExplanation(f.account, id(plan), limit = 1).body!!.jsonObject; val cursor = first.string("nextCursor")
        val second = f.store.getPlanExplanation(f.account, id(plan), cursor, 1).body!!.jsonObject
        assertEquals(plan.getValue("reasons").jsonArray, JsonArray(first.getValue("items").jsonArray + second.getValue("items").jsonArray))
        assertEquals(JsonNull, second["nextCursor"])
        val otherPlan = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        denied(PlanningFailureCode.CURSOR_INVALID) { f.store.getPlanExplanation(f.account, id(otherPlan), cursor) }
        assertEquals(2, f.count("planning.plans")); assertEquals(2, f.count("platform.outbox"))
    }

    @Test fun directRecipeSourceRequiresCurrentAuthorityAndCannotGrantAnUnreviewedOrPaidMatch() {
        val f = Fixture(); val input = JsonObject(request() + ("sourceRecipeVersionId" to JsonPrimitive(VERSION)))
        f.authority.sourceAllowed = false
        denied(PlanningFailureCode.RECIPE_UNAVAILABLE) { f.store.createPlan(f.account, UUID.randomUUID(), input) }
        f.authority.sourceAllowed = true
        f.change { withCandidates(it, listOf(candidate(reviewFields = mapOf("freeCatalogEligible" to JsonPrimitive(false))))) }
        denied(PlanningFailureCode.RECIPE_UNAVAILABLE) { f.store.createPlan(f.account, UUID.randomUUID(), input) }
        assertEquals(0, f.count("planning.plans")); assertEquals(0, f.count("platform.idempotency"))
    }

    @Test fun boundedEvidenceFailureAndCreationKillGateNeverDisableExistingOwnedReads() {
        val f = Fixture(); val original = body(f.store.createPlan(f.account, UUID.randomUUID(), request()))
        f.authority.enabled = false
        denied(PlanningFailureCode.NOT_CONFIGURED) { f.store.createPlan(f.account, UUID.randomUUID(), request()) }
        assertEquals(original, f.store.getPlan(f.account, id(original)).body)
        f.authority.enabled = true; f.change { withCandidates(it, List(129) { candidate() }) }
        denied(PlanningFailureCode.STORAGE_UNAVAILABLE) { f.store.createPlan(f.account, UUID.randomUUID(), request()) }
        assertEquals(1, f.count("planning.plans"))
    }

    private class Fixture(candidates: List<JsonObject> = listOf(candidate())) {
        val dataSource = cluster.database(); val faults = Faults(); val authority = TestAuthority()
        val account: VerifiedPlanningPrincipal
        val store: PlansStore
        private val initial = evidence(candidates)
        init {
            PlatformMigrations(dataSource).migrate()
            sql("CREATE SCHEMA planning_test; CREATE TABLE planning_test.principals(kind text NOT NULL,id uuid NOT NULL,device uuid NULL,active boolean NOT NULL,expires_at timestamptz NOT NULL,PRIMARY KEY(kind,id));" +
                "CREATE TABLE planning_test.inputs(kind text NOT NULL,id uuid NOT NULL,snapshot_text text NOT NULL,PRIMARY KEY(kind,id))")
            account = principal(CommandActor.ACCOUNT); store = newStore()
        }
        fun principal(kind: CommandActor, id: UUID = UUID.randomUUID()) = VerifiedPlanningPrincipal("test", kind, id, if (kind == CommandActor.ACCOUNT) UUID.randomUUID() else null).also { actor ->
            dataSource.connection.use { c ->
                c.prepareStatement("INSERT INTO planning_test.principals VALUES(?,?,?,true,clock_timestamp()+interval '1 day')").use { it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.setObject(3, actor.deviceSessionId); it.executeUpdate() }
                c.prepareStatement("INSERT INTO planning_test.inputs VALUES(?,?,?)").use { it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.setString(3, initial.toString()); it.executeUpdate() }
            }
        }
        fun newStore() = PlansStore("test", PgTransactions(faults.wrap(dataSource)), authority,
            PlanningServicePolicy("test-rank-1", false, true, 86400, 600), PlanningCursors("v1", mapOf("v1" to ByteArray(32) { 7 })))
        fun change(transform: (JsonObject) -> JsonObject) { dataSource.connection.use { c ->
            c.prepareStatement("SELECT snapshot_text FROM planning_test.inputs WHERE kind='account' AND id=?").use { s ->
                s.setObject(1, account.principalId); s.executeQuery().use { r -> r.next(); val changed = transform(parse(r.getString(1)))
                    c.prepareStatement("UPDATE planning_test.inputs SET snapshot_text=? WHERE kind='account' AND id=?").use { u -> u.setString(1, changed.toString()); u.setObject(2, account.principalId); u.executeUpdate() } }
            }
        } }
        fun sql(sql: String) { dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
        fun value(sql: String): String = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } } }
        fun count(table: String) = value("SELECT count(*) FROM $table").toInt()
    }
    private class TestAuthority : PlanningAuthority {
        var enabled = true; var sourceAllowed = true; var snapshotReads = 0; var afterSnapshot: (() -> Unit)? = null
        override fun lockPrincipal(connection: Connection, principal: VerifiedPlanningPrincipal) {
            connection.prepareStatement("SELECT active,device,expires_at>clock_timestamp() FROM planning_test.principals WHERE kind=? AND id=? FOR SHARE").use { s ->
                s.setString(1, principal.kind.name.lowercase()); s.setObject(2, principal.principalId); s.executeQuery().use { r ->
                    if (!r.next() || !r.getBoolean(1) || r.getObject(2, UUID::class.java) != principal.deviceSessionId || !r.getBoolean(3)) throw PlanningServiceFailure(PlanningFailureCode.UNAUTHENTICATED)
                }
            }
        }
        override fun requireNewPlanningEnabledAndQuota(connection: Connection, principal: VerifiedPlanningPrincipal) {
            if (!enabled) throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED)
        }
        override fun lockCurrentSnapshot(connection: Connection, principal: VerifiedPlanningPrincipal, request: WireDocument): PlanningEvidenceSnapshot {
            snapshotReads++
            if (parse(request.encodeUtf8().decodeToString()).containsKey("sourceRecipeVersionId") && !sourceAllowed) throw PlanningServiceFailure(PlanningFailureCode.RECIPE_UNAVAILABLE)
            val result = connection.prepareStatement("SELECT snapshot_text FROM planning_test.inputs WHERE kind=? AND id=? FOR SHARE").use { s ->
                s.setString(1, principal.kind.name.lowercase()); s.setObject(2, principal.principalId); s.executeQuery().use { r ->
                    check(r.next()); PlanningEvidenceSnapshot.fromAuthoritativeDocument(WireDocument.parse(r.getString(1))) }
            }
            afterSnapshot?.invoke(); return result
        }
    }
    private class Faults {
        @Volatile var outboxFailure = false; @Volatile var loseCommit = false
        fun wrap(source: DataSource): DataSource = object : DataSource by source {
            override fun getConnection(): Connection {
                val actual = source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement" && args?.firstOrNull() is String && (args[0] as String).contains("INSERT INTO platform.outbox") && outboxFailure) {
                        outboxFailure = false; throw SQLException("injected synthetic event failure", "XX000")
                    }
                    try { val result = method.invoke(actual, *(args ?: emptyArray()))
                        if (method.name == "commit" && loseCommit) { loseCommit = false; throw SQLException("injected application receipt loss", "08006") }; result
                    } catch (failure: InvocationTargetException) { throw failure.targetException }
                } as Connection
            }
        }
    }
    companion object {
        private const val INGREDIENT = "00000000-0000-4000-8000-000000000011"
        private const val OTHER = "00000000-0000-4000-8000-000000000012"
        private const val VERSION = "00000000-0000-4000-8000-000000000021"
        private const val VERSION2 = "00000000-0000-4000-8000-000000000022"
        private const val VERSION3 = "00000000-0000-4000-8000-000000000023"
        private const val RECIPE = "00000000-0000-4000-8000-000000000031"
        private const val TIME = "2026-09-13T10:00:00Z"
        private lateinit var cluster: PostgresTestCluster
        @JvmField @ClassRule val timeout = Timeout(10, TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun close() { if (::cluster.isInitialized) cluster.close() }
        private fun arr(vararg strings: String) = JsonArray(strings.map(::JsonPrimitive))
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
        private fun id(plan: JsonObject) = UUID.fromString(plan.string("id"))
        private fun reply(result: CommandResult): StoredReply = when (result) { is CommandResult.Applied -> result.reply; is CommandResult.Replayed -> result.reply; else -> fail("Expected command success") }
        private fun body(result: CommandResult) = reply(result).body!!.jsonObject
        private fun denied(code: PlanningFailureCode, action: () -> Any?) = assertEquals(code, assertFailsWith<PlanningServiceFailure> { action() }.code)
        private fun request(mode: String = "assemble", energy: String = "assemble", servings: String = "1", ingredients: List<String> = listOf(INGREDIENT)) = buildJsonObject {
            put("mode", mode); put("preferenceVersion", 1); put("constraints", buildJsonObject {
                put("ingredientIds", JsonArray(ingredients.map(::JsonPrimitive))); put("energy", energy); put("equipmentIds", arr("bowl"))
                put("servings", Json.parseToJsonElement(servings)); put("hardExcludedIngredientIds", arr()); put("tasteTags", arr())
            })
        }
        private fun next(plan: JsonObject) = buildJsonObject { put("reason", "alternative"); put("constraints", plan.getValue("constraints")); put("continuationCursor", plan.getValue("nextAlternativeCursor"))
            put("excludeRecipeVersionIds", arr(plan.string("recipeVersionId"))) }
        private fun recipe(id: String = VERSION, fields: Map<String, JsonElement> = emptyMap()): JsonObject = JsonObject(buildJsonObject {
            put("id", id); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("recipeId", RECIPE); put("title", "Synthetic private recipe")
            put("reviewStatus", "published"); put("reviewedAt", TIME); put("estimateBasis", "reviewerEstimate"); put("servings", 1)
            put("ingredients", buildJsonArray { add(buildJsonObject { put("ingredientId", INGREDIENT); put("quantity", 1); put("unit", "g"); put("optional", false) }) })
            put("steps", buildJsonArray { add(buildJsonObject { put("stepId", "mix"); put("position", 1); put("instruction", "Mix the ingredients."); put("ingredientIds", arr(INGREDIENT)); put("requiredEquipmentIds", arr("bowl")); put("mandatorySafetyStep", false) }) })
            put("activeMinutes", 5); put("totalMinutes", 10); put("utensilCount", 1); put("equipmentIds", arr("bowl")); put("modes", arr("assemble")); put("tasteTags", arr("crunch"))
            put("preparationTags", arr("noHeat", "oneBowl")); put("cleanupMinutes", 2)
        } + fields)
        private fun candidate(id: String = VERSION, addition: Boolean = false, recipeFields: Map<String, JsonElement> = emptyMap(), reviewFields: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
            put("recipe", recipe(id, recipeFields + if (addition) mapOf("modes" to arr("improve")) else emptyMap()))
            put("review", JsonObject(buildJsonObject { put("reviewReference", "synthetic-review"); put("policyVersion", "test-rank-1"); put("kind", if (addition) "ADDITION" else "MEAL")
                put("minimumEnergy", "ASSEMBLE"); put("heatingRequired", false); put("substantialPreparation", false); put("freeCatalogEligible", true)
                put("compatibleBaseTypes", if (addition) arr("rice") else arr()); put("linearQuantityScalingReviewed", true); put("stepsValidForScalingRange", true)
                put("effortValidForScalingRange", true); put("scalableUnits", arr("g")) } + reviewFields))
        }
        private fun evidence(candidates: List<JsonObject>) = buildJsonObject {
            put("version", 1); put("preferences", buildJsonObject { put("revision", "1"); put("excludedIngredientIds", arr()); put("dislikedIngredientIds", arr()) })
            put("pantry", buildJsonObject { put("revision", "pantry-1"); put("items", arr()) }); put("baseMeal", JsonNull)
            put("catalog", buildJsonObject { put("revision", "catalog-1"); put("taxonomyRevision", "taxonomy-1"); put("candidates", JsonArray(candidates))
                put("ingredients", JsonArray(listOf(INGREDIENT, OTHER).map { buildJsonObject { put("ingredientId", it); put("componentIds", arr()) } })) })
        }
        private fun withCandidates(s: JsonObject, candidates: List<JsonObject>, revision: String? = null) = JsonObject(s + ("catalog" to JsonObject(s.getValue("catalog").jsonObject +
            buildMap { put("candidates", JsonArray(candidates)); revision?.let { put("revision", JsonPrimitive(it)) } })))
        private fun race(actions: List<() -> Any>): List<Any> {
            val pool = Executors.newFixedThreadPool(actions.size); val ready = CountDownLatch(actions.size); val go = CountDownLatch(1)
            val results = actions.map { action -> pool.submit<Any> { ready.countDown(); check(go.await(5, TimeUnit.SECONDS)); try { action() } catch (failure: PlanningServiceFailure) { failure } } }
            return try { assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown(); results.map { it.get(20, TimeUnit.SECONDS) } }
            finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
}
