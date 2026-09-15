package com.feedme.server.cooking

import com.feedme.server.cooking.CookingTestFixture.Companion.arr
import com.feedme.server.cooking.CookingTestFixture.Companion.commandReply
import com.feedme.server.db.*
import com.feedme.server.planning.*
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Real PostgreSQL/real planning engine; provider/catalog facts are explicitly synthetic test adapters. */
class CookingStoreIntegrationTest {
    @Test fun createPinsExactImmutablePlanInitialStepZeroSequenceAndOneAtomicFact() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID(); val session = body(f.store.createCookSession(f.account, key, start(plan)))
        assertEquals("active", session.text("status")); assertEquals("mix", session.text("currentStepId")); assertEquals(JsonPrimitive(0), session["deviceSequence"])
        assertEquals(arr(), session["completedStepIds"]); assertEquals(arr(), session["timers"]); assertFalse(session.containsKey("completedAt"))
        assertEquals(plan, Json.parseToJsonElement(f.value("SELECT plan_snapshot_text FROM cooking.cook_sessions")))
        assertEquals(f.value("SELECT snapshot_hash FROM planning.plans"), f.value("SELECT plan_snapshot_hash FROM cooking.cook_sessions"))
        assertEquals(session, f.store.getCookSession(f.account, id(session)).body)
        assertEquals(session, body(assertIs<CommandResult.Replayed>(f.store.createCookSession(f.account, key, start(plan)))))
        assertCounts(f, 1, 1, 1); assertEquals(2, f.count("platform.idempotency")); assertEquals(2, f.count("platform.outbox"))
    }
    @Test fun suppliedMathematicalSequenceIsRetainedAndChangedOriginalBodyIsMismatch() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID(); val input = JsonObject(start(plan) + ("deviceSequence" to Json.parseToJsonElement("1e1")))
        val result = f.store.createCookSession(f.account, key, input); assertEquals(JsonPrimitive(10), body(result)["deviceSequence"])
        assertEquals("\"1\"", commandReply(result).etag)
        assertIs<CommandResult.Replayed>(f.store.createCookSession(f.account, key, JsonObject(start(plan) + ("deviceSequence" to JsonPrimitive(10)))))
        assertIs<CommandResult.Mismatch>(f.store.createCookSession(f.account, key, JsonObject(start(plan) + ("deviceSequence" to JsonPrimitive(11)))))
        assertCounts(f, 1, 1, 1)
    }
    @Test fun accountGuestEnvironmentAndForeignPlanIdsNeverEnumeratePrivateSessions() {
        val f = fixture(); val guest = f.principal(CommandActor.GUEST, f.account.principalId); val other = f.principal(CommandActor.ACCOUNT)
        val plan = f.seedPlan(); val session = create(f, plan = plan); val guestSession = create(f, guest)
        for (actor in listOf(guest, other)) {
            denied(CookingFailureCode.COOK_SESSION_UNAVAILABLE) { f.store.getCookSession(actor, id(session)) }
            denied(CookingFailureCode.COOK_SESSION_UNAVAILABLE) { f.store.getCookSession(actor, UUID.randomUUID()) }
            denied(CookingFailureCode.PLAN_UNAVAILABLE) { f.store.createCookSession(actor, UUID.randomUUID(), start(plan)) }
        }
        denied(CookingFailureCode.COOK_SESSION_UNAVAILABLE) { f.store.getCookSession(f.account, id(guestSession)) }
        denied(CookingFailureCode.UNAUTHENTICATED) { f.store.getCookSession(VerifiedCookingPrincipal("other", CommandActor.ACCOUNT, f.account.principalId, f.account.deviceSessionId), id(session)) }
        assertCounts(f, 2, 2, 2)
    }
    @Test fun currentDeviceRevocationAndGuestExpiryPrecedeCachedDisclosure() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID(); val session = body(f.store.createCookSession(f.account, key, start(plan)))
        f.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='${f.account.deviceSessionId}'")
        denied(CookingFailureCode.UNAUTHENTICATED) { f.store.createCookSession(f.account, key, start(plan)) }
        denied(CookingFailureCode.UNAUTHENTICATED) { f.store.getCookSession(f.account, id(session)) }
        val guest = f.principal(CommandActor.GUEST); val guestPlan = f.seedPlan(guest); val guestKey = UUID.randomUUID()
        f.store.createCookSession(guest, guestKey, start(guestPlan))
        f.sql("UPDATE cooking_test.sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE session_id='${guest.guestSessionId}'")
        denied(CookingFailureCode.UNAUTHENTICATED) { f.store.createCookSession(guest, guestKey, start(guestPlan)) }
        assertCounts(f, 2, 2, 2)
    }
    @Test fun wrongVerifiedSessionCannotUseAnotherDeviceCursorOrOriginalReceipt() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID(); f.store.createCookSession(f.account, key, start(plan))
        val wrong = VerifiedCookingPrincipal("test", CommandActor.ACCOUNT, f.account.principalId, UUID.randomUUID())
        denied(CookingFailureCode.UNAUTHENTICATED) { f.store.createCookSession(wrong, key, start(plan)) }
        denied(CookingFailureCode.COMMAND_CONFLICT) { f.store.createCookSession(f.secondDevice(), key, start(plan)) }
        assertCounts(f, 1, 1, 1)
    }
    @Test fun noGetInitializationOrNonreadyPlanCanCreateCookingRows() {
        val f = fixture()
        denied(CookingFailureCode.COOK_SESSION_UNAVAILABLE) { f.store.getCookSession(f.account, UUID.randomUUID()) }
        f.changeEvidence { e -> JsonObject(e + ("catalog" to JsonObject(e.getValue("catalog").jsonObject + ("candidates" to arr())))) }
        val noMatch = f.seedPlan(); assertEquals("noMatch", noMatch.text("status"))
        denied(CookingFailureCode.PLAN_NOT_READY) { f.store.createCookSession(f.account, UUID.randomUUID(), start(noMatch)) }
        assertCounts(f, 0, 0, 0); assertEquals(1, f.count("platform.idempotency"))
    }
    @Test fun newSelectionRechecksPreferencesPantryAndExactTaxonomyButExistingPinDoesNotRerank() {
        for (change in listOf("preferences", "pantry", "taxonomy", "composition")) {
            val f = fixture(); val plan = f.seedPlan(); val session = create(f, plan = plan)
            f.changeEvidence { e -> when (change) {
                "preferences" -> JsonObject(e + ("preferences" to JsonObject(e.getValue("preferences").jsonObject + ("revision" to JsonPrimitive("2")))))
                "pantry" -> JsonObject(e + ("pantry" to JsonObject(e.getValue("pantry").jsonObject + ("revision" to JsonPrimitive("pantry-2")))))
                else -> JsonObject(e + ("catalog" to JsonObject(e.getValue("catalog").jsonObject + if (change == "taxonomy")
                    mapOf("taxonomyRevision" to JsonPrimitive("taxonomy-2")) else mapOf("ingredients" to buildJsonArray {
                        add(buildJsonObject { put("ingredientId", CookingTestFixture.INGREDIENT); put("componentIds", arr(UUID.randomUUID().toString())) })
                    }))))
            } }
            denied(CookingFailureCode.INPUTS_CHANGED) { f.store.createCookSession(f.account, UUID.randomUUID(), start(plan)) }
            assertEquals(session, f.store.getCookSession(f.account, id(session)).body)
            assertEquals(plan, f.plans.getPlan(f.planningActor(f.account), id(plan)).body)
            assertEquals("serve", body(f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", patch(1)))["currentStepId"]?.jsonPrimitive?.content)
        }
    }
    @Test fun retiredVersionAllowsOwnedPinButNeverFreshStartOrSilentRecipeReplacement() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID(); val session = body(f.store.createCookSession(f.account, key, start(plan)))
        f.changeRecipe { JsonObject(it + mapOf("version" to JsonPrimitive(2), "reviewStatus" to JsonPrimitive("retired"), "updatedAt" to JsonPrimitive("2026-09-14T00:00:00Z"))) }
        assertEquals(session, body(f.store.createCookSession(f.account, key, start(plan))))
        assertEquals(plan, f.plans.getPlan(f.planningActor(f.account), id(plan)).body)
        denied(CookingFailureCode.RECIPE_UNAVAILABLE) { f.store.createCookSession(f.account, UUID.randomUUID(), start(plan)) }
        val updated = body(f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", patch(1)))
        assertEquals(id(plan), UUID.fromString(updated.text("planId")))
        assertEquals(plan, Json.parseToJsonElement(f.value("SELECT plan_snapshot_text FROM cooking.cook_sessions")))
    }
    @Test fun learnedRecallBlocksReadProgressCompletionAndCachedCreateWithoutEffects() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID(); val session = body(f.store.createCookSession(f.account, key, start(plan)))
        f.changeRecipe { JsonObject(it + mapOf("version" to JsonPrimitive(2), "reviewStatus" to JsonPrimitive("recalled"))) }
        for (action in listOf<() -> Any?>({ f.store.getCookSession(f.account, id(session)) },
            { f.store.createCookSession(f.account, key, start(plan)) },
            { f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", patch(1)) },
            { f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(1)) })) denied(CookingFailureCode.RECIPE_RECALLED, action)
        assertCounts(f, 1, 1, 1); assertEquals(2, f.count("platform.idempotency"))
    }
    @Test fun currentRightsOrSameVersionChangedRecipeCannotAcknowledgeAnOwnedPin() {
        for (change in listOf("rights", "body")) {
            val f = fixture(); val session = create(f)
            if (change == "body") f.changeRecipe { JsonObject(it + ("title" to JsonPrimitive("changed without version"))) }
            else f.changeEvidence { e ->
                val c = e.getValue("catalog").jsonObject; val candidate = c.getValue("candidates").jsonArray.single().jsonObject
                val review = JsonObject(candidate.getValue("review").jsonObject + ("freeCatalogEligible" to JsonPrimitive(false)))
                val candidates = JsonArray(listOf(JsonObject(candidate + ("review" to review))))
                JsonObject(e + ("catalog" to JsonObject(c + ("candidates" to candidates))))
            }
            denied(CookingFailureCode.RECIPE_UNAVAILABLE) { f.store.getCookSession(f.account, id(session)) }
            assertCounts(f, 1, 1, 1)
        }
    }
    @Test fun actualOwnedPinRetainsHistoricalPlanPastPlanningExpiryWithoutGrantingNewStart() {
        val f = fixture(); val plan = f.seedPlan(); val session = create(f, plan = plan); f.expirePlans()
        assertEquals(plan, f.plans.getPlan(f.planningActor(f.account), id(plan)).body)
        assertEquals(session, f.store.getCookSession(f.account, id(session)).body)
        denied(CookingFailureCode.PLAN_EXPIRED) { f.store.createCookSession(f.account, UUID.randomUUID(), start(plan)) }
        assertIs<CommandResult.Applied>(f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", patch(1)))
        f.expireSession(id(session))
        denied(CookingFailureCode.SESSION_EXPIRED) { f.store.getCookSession(f.account, id(session)) }
        assertEquals(PlanningFailureCode.PLAN_EXPIRED, assertFailsWith<PlanningServiceFailure> { f.plans.getPlan(f.planningActor(f.account), id(plan)) }.code)
    }
    @Test fun existingPinEnumAndAnotherOwnersOrAbsentSessionNeverBypassPlanExpiry() {
        val f = fixture(); val plan = f.seedPlan(); val session = create(f, plan = plan); f.expirePlans()
        for (fake in listOf<UUID?>(null, UUID.randomUUID())) {
            assertEquals(PlanningFailureCode.PLAN_UNAVAILABLE, assertFailsWith<PlanningServiceFailure> {
                PgTransactions(f.source).run { c -> f.authority.lockPrincipal(c, f.account)
                    f.plans.lockCookingPlan(c, f.planningActor(f.account), id(plan), CookingPlanUse.EXISTING_PIN, fake) }
            }.code)
        }
        val other = f.principal(CommandActor.ACCOUNT)
        assertEquals(PlanningFailureCode.PLAN_UNAVAILABLE, assertFailsWith<PlanningServiceFailure> {
            PgTransactions(f.source).run { c -> f.authority.lockPrincipal(c, other)
                f.plans.lockCookingPlan(c, f.planningActor(other), id(plan), CookingPlanUse.EXISTING_PIN, id(session)) }
        }.code)
    }
    @Test fun progressUsesOriginalVersionAndBodyAndExactCurrentReplayOnly() {
        val f = fixture(); val session = create(f); val key = UUID.randomUUID(); val input = patch(1)
        val changed = body(f.store.updateCookSession(f.account, key, id(session), "\"1\"", input))
        assertEquals(JsonPrimitive(2), changed["version"]); assertEquals("serve", changed.text("currentStepId"))
        assertEquals(changed, body(assertIs<CommandResult.Replayed>(f.store.updateCookSession(f.account, key, id(session), "\"1\"", input))))
        assertIs<CommandResult.Mismatch>(f.store.updateCookSession(f.account, key, id(session), "\"2\"", input))
        assertIs<CommandResult.Mismatch>(f.store.updateCookSession(f.account, key, id(session), "\"1\"", JsonObject(input + ("status" to JsonPrimitive("paused")))))
        denied(CookingFailureCode.VERSION_CONFLICT) { f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", patch(2)) }
        f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"2\"", patch(2))
        denied(CookingFailureCode.VERSION_CONFLICT) { f.store.updateCookSession(f.account, key, id(session), "\"1\"", input) }
        assertCounts(f, 1, 3, 1)
    }
    @Test fun exactAggregateSequenceAndPerVerifiedDeviceCursorsPreventStaleCompletion() {
        val f = fixture(); val session = create(f); val second = f.secondDevice()
        for (sequence in listOf(0L, 2L, Long.MAX_VALUE)) denied(CookingFailureCode.SEQUENCE_CONFLICT) {
            f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", patch(sequence)) }
        f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", patch(1))
        f.store.updateCookSession(second, UUID.randomUUID(), id(session), "\"2\"", patch(2))
        denied(CookingFailureCode.SEQUENCE_CONFLICT) { f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(2)) }
        val completed = body(f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(3)))
        assertEquals(JsonPrimitive(3), completed["deviceSequence"]); assertCounts(f, 1, 4, 2)
    }
    @Test fun maximumInitialSequenceDoesNotOverflowOrResetOnLaterMutation() {
        val f = fixture(); val plan = f.seedPlan(); val initial = JsonObject(start(plan) + ("deviceSequence" to JsonPrimitive(Long.MAX_VALUE)))
        val session = body(f.store.createCookSession(f.account, UUID.randomUUID(), initial))
        denied(CookingFailureCode.SEQUENCE_CONFLICT) { f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(0)) }
        assertEquals(JsonPrimitive(Long.MAX_VALUE), f.store.getCookSession(f.account, id(session)).body!!.jsonObject["deviceSequence"])
        assertCounts(f, 1, 1, 1)
    }
    @Test fun completionPreservesAllAcknowledgedProgressTimersNotesAndUsesAcceptedDatabaseTime() {
        val f = fixture(); val session = create(f); val timer = timer(); val notes = notes("private user note")
        val progress = JsonObject(patch(1) + mapOf("status" to JsonPrimitive("paused"), "timers" to JsonArray(listOf(timer)), "personalNotes" to notes))
        val acknowledged = body(f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", progress))
        val key = UUID.randomUUID(); val input = JsonObject(complete(2) + ("finishedAtClient" to JsonPrimitive("2001-01-01T00:00:00Z")))
        val result = body(f.store.completeCookSession(f.account, key, id(session), input))
        assertEquals("completed", result.text("status")); assertEquals(result["updatedAt"], result["completedAt"])
        assertNotEquals(input["finishedAtClient"], result["completedAt"])
        for (field in listOf("currentStepId", "completedStepIds", "timers", "personalNotes")) assertEquals(acknowledged[field], result[field])
        assertEquals(arr("mix"), result["completedStepIds"]); assertFalse(result.toString().contains("finishedAtClient"))
        assertEquals(input, Json.parseToJsonElement(f.value("SELECT payload::text FROM cooking.step_events WHERE kind='completed'")))
        assertEquals(result, body(assertIs<CommandResult.Replayed>(f.store.completeCookSession(f.account, key, id(session), input))))
        assertCounts(f, 1, 3, 1)
    }
    @Test fun completionIsTerminalOnlyItsExactCurrentKeyReplaysAndMakeAgainIsNeverInvented() {
        val f = fixture(); val plan = f.seedPlan(); val startKey = UUID.randomUUID(); val session = body(f.store.createCookSession(f.account, startKey, start(plan)))
        val key = UUID.randomUUID(); val completed = body(f.store.completeCookSession(f.account, key, id(session), complete(1)))
        denied(CookingFailureCode.TERMINAL_CONFLICT) { f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(2)) }
        denied(CookingFailureCode.TERMINAL_CONFLICT) { f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"2\"", patch(2)) }
        denied(CookingFailureCode.VERSION_CONFLICT) { f.store.createCookSession(f.account, startKey, start(plan)) }
        denied(CookingFailureCode.NOT_CONFIGURED) { f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), JsonObject(complete(2) + ("makeAgain" to JsonPrimitive(true)))) }
        assertEquals(completed, f.store.getCookSession(f.account, id(session)).body); assertCounts(f, 1, 2, 1)
        assertEquals(1, f.value("SELECT count(*) FROM platform.outbox WHERE event_type='cooking.session.completed.v1'").toInt())
    }
    @Test fun explicitAbandonIsTerminalButDoesNotPretendTimersOrStepsCompleted() {
        val f = fixture(); val session = create(f); val input = JsonObject(patch(1) + mapOf("status" to JsonPrimitive("abandoned"), "timers" to JsonArray(listOf(timer()))))
        val key = UUID.randomUUID(); val abandoned = body(f.store.updateCookSession(f.account, key, id(session), "\"1\"", input))
        assertFalse(abandoned.containsKey("completedAt")); assertEquals(input["timers"], abandoned["timers"])
        assertEquals(abandoned, body(f.store.updateCookSession(f.account, key, id(session), "\"1\"", input)))
        denied(CookingFailureCode.TERMINAL_CONFLICT) { f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(2)) }
        assertEquals(0, f.value("SELECT count(*) FROM platform.outbox WHERE event_type='cooking.session.completed.v1'").toInt())
    }
    @Test fun unsupportedNotesSourceAndMakeAgainCannotMutateOrCacheAFalseSuccess() {
        val f = fixture(); val session = create(f)
        for (n in listOf(notes("not a reviewed instruction", "communityTip"), buildJsonArray { add(buildJsonObject {
            put("text", "private copied source"); put("label", "myNote"); put("shortcutId", UUID.randomUUID().toString()) }) }))
            denied(CookingFailureCode.NOT_CONFIGURED) { f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", JsonObject(patch(1) + ("personalNotes" to n))) }
        denied(CookingFailureCode.NOT_CONFIGURED) { f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), JsonObject(complete(1) + ("makeAgain" to JsonPrimitive(true)))) }
        assertCounts(f, 1, 1, 1); assertEquals(2, f.count("platform.idempotency"))
    }
    @Test fun foreignDuplicateAndIncompleteTimersFailWithoutDiscardingThePreviousState() {
        val f = fixture(); val session = create(f); val t = timer()
        val invalid = listOf(JsonArray(listOf(t, t)), JsonArray(listOf(JsonObject(t + ("stepId" to JsonPrimitive("foreign"))))),
            JsonArray(listOf(JsonObject(t - "endAt"))), JsonArray(listOf(JsonObject(t + ("status" to JsonPrimitive("paused")) - "endAt"))),
            JsonArray(listOf(JsonObject(t + ("pausedRemainingSeconds" to JsonPrimitive(61))))) )
        for (timers in invalid) denied(CookingFailureCode.INPUT_INVALID) {
            f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", JsonObject(patch(1) + ("timers" to timers))) }
        assertEquals(session, f.store.getCookSession(f.account, id(session)).body); assertCounts(f, 1, 1, 1)
    }
    @Test fun stepMembershipAndDuplicateCompletionIdsAreNotSchemaOnlyChecks() {
        val f = fixture(); val session = create(f)
        for (fields in listOf(mapOf("currentStepId" to JsonPrimitive("foreign")), mapOf("completedStepIds" to arr("mix", "mix")), mapOf("completedStepIds" to arr("foreign"))))
            denied(CookingFailureCode.INPUT_INVALID) { f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", JsonObject(patch(1) + fields)) }
        assertCounts(f, 1, 1, 1)
    }
    @Test fun responseSizeIsValidatedBeforeAnyDomainReceiptOrOutboxCommit() {
        val f = fixture(); val plan = f.seedPlan(); val tiny = f.newStore(1)
        denied(CookingFailureCode.RESPONSE_TOO_LARGE) { tiny.createCookSession(f.account, UUID.randomUUID(), start(plan)) }
        assertCounts(f, 0, 0, 0); assertEquals(1, f.count("platform.idempotency"))
        val session = create(f, plan = plan); val bound = f.newStore(1024)
        val input = JsonObject(patch(1) + ("personalNotes" to JsonArray(List(4) { notes("x".repeat(500)).single() })))
        denied(CookingFailureCode.RESPONSE_TOO_LARGE) { bound.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", input) }
        assertEquals(session, f.store.getCookSession(f.account, id(session)).body); assertCounts(f, 1, 1, 1)
    }
    @Test fun preCommitOutboxFailuresRollbackCreateProgressCompletionAndOriginalPendingReceipt() {
        for (operation in listOf("create", "patch", "complete")) {
            val f = fixture(); val plan = f.seedPlan(); val session = if (operation == "create") null else create(f, plan = plan)
            val old = listOf(f.count("cooking.cook_sessions"), f.count("cooking.step_events"), f.count("platform.idempotency"), f.count("platform.outbox"))
            f.faults.outboxFailure = true
            denied(CookingFailureCode.STORAGE_UNAVAILABLE) { when (operation) {
                "create" -> f.store.createCookSession(f.account, UUID.randomUUID(), start(plan))
                "patch" -> f.store.updateCookSession(f.account, UUID.randomUUID(), id(session!!), "\"1\"", patch(1))
                else -> f.store.completeCookSession(f.account, UUID.randomUUID(), id(session!!), complete(1))
            } }
            assertEquals(old, listOf(f.count("cooking.cook_sessions"), f.count("cooking.step_events"), f.count("platform.idempotency"), f.count("platform.outbox")))
            if (session != null) assertEquals(session, f.store.getCookSession(f.account, id(session)).body)
        }
    }
    @Test fun lostApplicationCommitAcknowledgementReconcilesExactOriginalCommandWithoutDuplicateEffects() {
        for (operation in listOf("create", "patch", "complete")) {
            val f = fixture(); val plan = f.seedPlan(); val session = if (operation == "create") null else create(f, plan = plan); val key = UUID.randomUUID()
            val action = { when (operation) {
                "create" -> f.newStore().createCookSession(f.account, key, start(plan))
                "patch" -> f.newStore().updateCookSession(f.account, key, id(session!!), "\"1\"", patch(1))
                else -> f.newStore().completeCookSession(f.account, key, id(session!!), complete(1))
            } }
            f.faults.loseCommit = true; assertFailsWith<CommitOutcomeUnknown> { action() }
            val committed = listOf(f.count("cooking.step_events"), f.count("platform.idempotency"), f.count("platform.outbox"))
            assertIs<CommandResult.Replayed>(action())
            assertEquals(committed, listOf(f.count("cooking.step_events"), f.count("platform.idempotency"), f.count("platform.outbox")))
        }
    }
    @Test fun concurrentSameKeyCommandsCommitOneSessionAndOneStartedFact() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID()
        val results = race(List(3) { { f.newStore().createCookSession(f.account, key, start(plan)) } })
        assertEquals(1, results.count { it is CommandResult.Applied }); assertEquals(2, results.count { it is CommandResult.Replayed })
        assertEquals(1, results.map { body(it as CommandResult) }.toSet().size); assertCounts(f, 1, 1, 1)
    }
    @Test fun concurrentDifferentKeysWithSameVersionPreserveOneWinnerAndVersionConflict() {
        val f = fixture(); val session = create(f); val second = f.secondDevice()
        val results = race(listOf(f.account, second).map { actor -> { f.newStore().updateCookSession(actor, UUID.randomUUID(), id(session), "\"1\"", patch(1)) } })
        assertEquals(1, results.count { it is CommandResult.Applied }); assertEquals(1, results.count { it is CookingFailure && it.code == CookingFailureCode.VERSION_CONFLICT })
        assertCounts(f, 1, 2, if ((results[0] as? CommandResult.Applied) != null) 1 else 2)
    }
    @Test fun completionVersusConcurrentProgressNeverConsumesAnOlderCompletionSequence() {
        val f = fixture(); val session = create(f); val second = f.secondDevice()
        val results = race(listOf({ f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(1)) },
            { f.newStore().updateCookSession(second, UUID.randomUUID(), id(session), "\"1\"", patch(1)) }))
        assertEquals(1, results.count { it is CommandResult.Applied }); assertEquals(1, results.count { it is CookingFailure && it.code in setOf(CookingFailureCode.SEQUENCE_CONFLICT, CookingFailureCode.TERMINAL_CONFLICT) })
        assertEquals(2, f.count("cooking.step_events")); assertEquals(JsonPrimitive(1), f.store.getCookSession(f.account, id(session)).body!!.jsonObject["deviceSequence"])
    }
    @Test fun sameUuidAcrossOperationsCannotCreateTwoStepEventsOrBypassNamespaceFence() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID(); val session = body(f.store.createCookSession(f.account, key, start(plan)))
        denied(CookingFailureCode.COMMAND_CONFLICT) { f.store.updateCookSession(f.account, key, id(session), "\"1\"", patch(1)) }
        denied(CookingFailureCode.COMMAND_CONFLICT) { f.store.completeCookSession(f.account, key, id(session), complete(1)) }
        assertCounts(f, 1, 1, 1)
    }
    @Test fun databaseRejectsPinReplacementVersionSkipsAndTerminalReopen() {
        val f = fixture(); val session = create(f)
        for (set in listOf("plan_snapshot_hash=repeat('0',64)", "version=version+2,device_sequence=device_sequence+1", "expires_at=expires_at+interval '1 day'"))
            assertFailsWith<SQLException> { f.sql("UPDATE cooking.cook_sessions SET $set") }
        f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(1))
        assertFailsWith<SQLException> { f.sql("UPDATE cooking.cook_sessions SET status='active',version=version+1,device_sequence=device_sequence+1") }
        assertFailsWith<SQLException> { f.sql("UPDATE cooking.step_events SET kind='progressed'") }
        assertEquals("completed", f.store.getCookSession(f.account, id(session)).body!!.jsonObject.text("status"))
    }
    @Test fun cancellationAfterAuthorityOrPlanReadStopsBeforeAnyCookingEffects() {
        for (boundary in listOf("principal", "plan")) {
            val f = fixture(); val plan = f.seedPlan()
            if (boundary == "principal") f.authority.afterPrincipal = { Thread.currentThread().interrupt() }
            else f.authority.afterSnapshot = { Thread.currentThread().interrupt() }
            try { assertFailsWith<InterruptedException> { f.store.createCookSession(f.account, UUID.randomUUID(), start(plan)) } }
            finally { Thread.interrupted(); f.authority.afterPrincipal = null; f.authority.afterSnapshot = null }
            assertCounts(f, 0, 0, 0); assertEquals(1, f.count("platform.idempotency"))
        }
        val f = fixture(); val plan = f.seedPlan()
        f.authority.afterSnapshot = { throw kotlinx.coroutines.CancellationException("synthetic caller cancellation") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { f.store.createCookSession(f.account, UUID.randomUUID(), start(plan)) }
        assertCounts(f, 0, 0, 0); assertEquals(1, f.count("platform.idempotency"))
    }
    @Test fun disabledNewCookingDoesNotEraseOwnedProgressOrMakeHistoricalPinANewStart() {
        val f = fixture(); val plan = f.seedPlan(); val session = create(f, plan = plan); f.authority.enabled = false
        denied(CookingFailureCode.NOT_CONFIGURED) { f.store.createCookSession(f.account, UUID.randomUUID(), start(plan)) }
        assertEquals(session, f.store.getCookSession(f.account, id(session)).body)
        assertIs<CommandResult.Applied>(f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", patch(1)))
    }
    @Test fun cookingOutboxCarriesOnlyRegisteredFieldsAndNoPrivateProgressOrTokens() {
        val f = fixture(); val session = create(f); val input = JsonObject(patch(1) + ("personalNotes" to notes("private-secret-note")))
        f.store.updateCookSession(f.account, UUID.randomUUID(), id(session), "\"1\"", input)
        f.store.completeCookSession(f.account, UUID.randomUUID(), id(session), complete(2))
        f.source.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT event_type,payload::text,aggregate_type,producer,aggregate_version FROM platform.outbox WHERE producer='cooking' ORDER BY aggregate_version").use { r ->
            var count = 0
            while (r.next()) { count++; val event = Json.parseToJsonElement(r.getString(2)).jsonObject
                assertEquals(if (count == 2) setOf("principalId", "sessionId", "deviceSequence") else setOf("principalId", "sessionId", "planId"), event.keys)
                assertEquals("cook_session", r.getString(3)); assertEquals("cooking", r.getString(4)); assertEquals(count.toLong(), r.getLong(5))
                assertFalse(r.getString(2).contains("private-secret-note")); assertFalse(r.getString(2).contains(CookingTestFixture.INGREDIENT))
            }; assertEquals(3, count)
        } } }
    }
    @Test fun samePrincipalLifecycleLockMakesConcurrentRecallWinBeforeNewSelection() {
        val f = fixture(); val plan = f.seedPlan(); val executor = Executors.newSingleThreadExecutor(); val entered = CountDownLatch(1)
        f.source.connection.use { lifecycle ->
            lifecycle.autoCommit = false
            f.authority.lockPrincipal(lifecycle, f.account)
            // Real lifecycle writer uses the shared exclusive principal lock, not fixture changeEvidence.
            val recalled = CookingTestFixture.evidence().let { e ->
                val catalog = e.getValue("catalog").jsonObject; val candidate = catalog.getValue("candidates").jsonArray.single().jsonObject
                val recipe = JsonObject(candidate.getValue("recipe").jsonObject + mapOf("reviewStatus" to JsonPrimitive("recalled"), "version" to JsonPrimitive(2)))
                val candidates = JsonArray(listOf(JsonObject(candidate + ("recipe" to recipe))))
                JsonObject(e + ("catalog" to JsonObject(catalog + ("candidates" to candidates))))
            }
            lifecycle.prepareStatement("UPDATE cooking_test.inputs SET snapshot_text=? WHERE kind='account' AND id=?").use {
                it.setString(1, recalled.toString()); it.setObject(2, f.account.principalId); assertEquals(1, it.executeUpdate()) }
            val future = executor.submit<CookingFailureCode> {
                entered.countDown()
                assertFailsWith<CookingFailure> { f.store.createCookSession(f.account, UUID.randomUUID(), start(plan)) }.code
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<java.util.concurrent.TimeoutException> { future.get(200, TimeUnit.MILLISECONDS) }
                lifecycle.commit()
                assertEquals(CookingFailureCode.RECIPE_RECALLED, future.get(10, TimeUnit.SECONDS))
            } finally {
                lifecycle.rollback(); future.cancel(true); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
        assertCounts(f, 0, 0, 0); assertEquals(1, f.count("platform.idempotency"))
    }
    companion object {
        private lateinit var cluster: PostgresTestCluster
        @JvmField @ClassRule val timeout = Timeout(10, TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun startCluster() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun closeCluster() { if (::cluster.isInitialized) cluster.close() }
        private fun fixture() = CookingTestFixture(cluster.database())
        private fun start(plan: JsonObject) = buildJsonObject { put("planId", plan.getValue("id")) }
        private fun patch(sequence: Long) = buildJsonObject { put("deviceSequence", sequence); put("currentStepId", "serve"); put("completedStepIds", arr("mix")) }
        private fun complete(sequence: Long) = buildJsonObject { put("makeAgain", false); put("deviceSequence", sequence) }
        private fun timer() = buildJsonObject { put("timerId", UUID.randomUUID().toString()); put("stepId", "mix"); put("status", "running"); put("durationSeconds", 60); put("endAt", "2026-09-14T03:00:00Z") }
        private fun notes(text: String, label: String = "myNote") = buildJsonArray { add(buildJsonObject { put("text", text); put("label", label) }) }
        private fun create(f: CookingTestFixture, actor: VerifiedCookingPrincipal = f.account, plan: JsonObject = f.seedPlan(actor)) = body(f.store.createCookSession(actor, UUID.randomUUID(), start(plan)))
        private fun body(result: CommandResult) = commandReply(result).body!!.jsonObject
        private fun id(body: JsonObject) = UUID.fromString(body.text("id"))
        private fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content
        private fun denied(code: CookingFailureCode, action: () -> Any?) = assertEquals(code, assertFailsWith<CookingFailure> { action() }.code)
        private fun assertCounts(f: CookingTestFixture, sessions: Int, events: Int, devices: Int) {
            assertEquals(sessions, f.count("cooking.cook_sessions")); assertEquals(events, f.count("cooking.step_events")); assertEquals(devices, f.count("cooking.device_cursors"))
        }
        private fun race(actions: List<() -> Any>): List<Any> {
            val pool = Executors.newFixedThreadPool(actions.size); val ready = CountDownLatch(actions.size); val go = CountDownLatch(1)
            val jobs = actions.map { action -> pool.submit<Any> { ready.countDown(); check(go.await(5, TimeUnit.SECONDS)); try { action() } catch (failure: CookingFailure) { failure } } }
            return try { assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown(); jobs.map { it.get(20, TimeUnit.SECONDS) } }
            finally { go.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
}
