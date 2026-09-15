package com.feedme.server.memory

import com.feedme.server.cooking.CookingTestFixture
import com.feedme.server.db.*
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

/** Real PG/actual PlansStore; copy/provider/editorial adapters below are explicitly synthetic. */
class SavedRecipeStoreIntegrationTest {
    @Test fun ownedPlanSaveCopiesOnlyExactMaterializedRecipeAndCreatesAtomicDefaultCookbook() {
        val f = fixture(); val plan = f.seedPlan(); val input = f.planInput(plan); val key = UUID.randomUUID()
        val response = SavedRecipeTestFixture.reply(f.store.saveRecipe(f.account, key, input)); val saved = response.body!!.jsonObject
        assertEquals(201, response.status); assertEquals("\"1\"", response.etag)
        assertEquals(plan["recipeSnapshot"], saved["snapshot"]); assertEquals("ownPlan", saved.text("sourceType"))
        assertFalse(saved.toString().contains("preferenceVersion")); assertFalse(saved.containsKey("planId")); assertFalse(saved.containsKey("created"))
        assertEquals(false, saved.getValue("recalled").jsonPrimitive.boolean)
        assertEquals(saved, f.store.getSavedRecipe(f.account, id(saved)).body)
        assertEquals(1, f.count("memory.saved_recipes")); assertEquals(1, f.count("memory.collection_items")); assertEquals(1, f.count("memory.collections"))
        assertEquals(2, f.count("platform.idempotency")); assertEquals(3, f.count("platform.outbox"))
        val col = firstCollection(f); assertEquals("My cookbook", col.text("name")); assertEquals(JsonNull, col["nextItemCursor"])
        assertEquals(JsonArray(listOf(saved.getValue("id"))), col["savedRecipeIds"])
    }
    @Test fun catalogAndUnscaledPlanDeduplicateWithoutRenamingOrIgnoringConflictingTitle() {
        val f = fixture(); val first = save(f, input = JsonObject(f.catalogInput() + ("title" to JsonPrimitive("First private title"))))
        val plan = f.seedPlan(); val before = f.count("platform.idempotency") to f.count("platform.outbox")
        denied(SavedRecipeFailureCode.COPY_CONFLICT) { save(f, input = JsonObject(f.planInput(plan) + ("title" to JsonPrimitive("Conflicting second title")))) }
        assertEquals(before, f.count("platform.idempotency") to f.count("platform.outbox"))
        val second = save(f, input = f.planInput(plan))
        assertEquals(first, second); assertEquals("catalog", second.text("sourceType")); assertEquals("First private title", second.text("title"))
        assertEquals(1, f.count("memory.saved_recipes")); assertEquals(1, f.count("memory.collection_items")); assertEquals(2, f.count("memory.save_commands"))
        assertEquals(1, f.value("SELECT count(*) FROM platform.outbox WHERE event_type='memory.recipe.saved.v1'").toInt())
        assertEquals(first, save(f, input = JsonObject(f.catalogInput() + ("title" to JsonPrimitive("First private title")))))
    }
    @Test fun twoPlansWithSameReviewedBytesShareCopyWhileScaledVariantsRemainDistinct() {
        val f = fixture()
        // Explicit synthetic reviewed range, not a permissive engine/scaling default.
        f.changeRecipe { JsonObject(it + mapOf("scalingMin" to JsonPrimitive(1), "scalingMax" to JsonPrimitive(4))) }
        val firstPlan = f.seedPlan(); val first = save(f, input = f.planInput(firstPlan))
        val otherPlan = f.seedPlan(); assertNotEquals(firstPlan["id"], otherPlan["id"])
        assertEquals(first, save(f, input = f.planInput(otherPlan)))
        val request = CookingTestFixture.planningRequest().let { JsonObject(it + ("constraints" to JsonObject(it.getValue("constraints").jsonObject + ("servings" to JsonPrimitive(2))))) }
        val scaled = f.seedPlan(body = request); assertEquals("ready", scaled.text("status"))
        val savedScaled = save(f, input = f.planInput(scaled)); assertNotEquals(first["id"], savedScaled["id"])
        assertEquals(scaled["recipeSnapshot"], savedScaled["snapshot"]); assertEquals(2, f.count("memory.saved_recipes"))
        assertEquals(JsonPrimitive(2), savedScaled.getValue("snapshot").jsonObject.getValue("ingredients").jsonArray.single().jsonObject["quantity"])
    }
    @Test fun bothSourceIdsMustAgreeAndAdapterCannotSubstituteAnotherPlanRecipe() {
        val f = fixture(); val plan = f.seedPlan(); val input = JsonObject(f.planInput(plan) + ("recipeVersionId" to JsonPrimitive(UUID.randomUUID().toString())))
        denied(SavedRecipeFailureCode.RECIPE_UNAVAILABLE) { f.store.saveRecipe(f.account, UUID.randomUUID(), input) }
        f.authority.substituteRecipe = JsonObject(f.recipe() + ("servings" to JsonPrimitive(3)))
        denied(SavedRecipeFailureCode.RECIPE_UNAVAILABLE) { f.store.saveRecipe(f.account, UUID.randomUUID(), f.planInput(plan)) }
        assertEquals(0, f.count("memory.library_heads")); assertEquals(1, f.count("platform.idempotency"))
    }
    @Test fun currentTrustedAccountGuestAndEnvironmentPrecedeEveryOwnedReadAndReceipt() {
        val f = fixture(); val key = UUID.randomUUID(); val input = f.catalogInput(); val own = save(f, key = key, input = input)
        val guest = f.principal(CommandActor.GUEST, f.account.principalId); val other = f.principal()
        for (p in listOf(guest, other)) {
            denied(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE) { f.store.getSavedRecipe(p, id(own)) }
            denied(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE) { f.store.getSavedRecipe(p, UUID.randomUUID()) }
            denied(SavedRecipeFailureCode.COLLECTION_UNAVAILABLE) { f.store.getCollection(p, id(firstCollection(f))) }
            assertTrue(f.store.listSavedRecipes(p).body!!.jsonObject.getValue("items").jsonArray.isEmpty())
        }
        f.sql("UPDATE cooking_test.sessions SET active=false WHERE session_id='${f.account.deviceSessionId}'")
        denied(SavedRecipeFailureCode.UNAUTHENTICATED) { f.store.saveRecipe(f.account, key, input) }
        denied(SavedRecipeFailureCode.UNAUTHENTICATED) { f.store.listCollections(f.account) }
        val wrong = VerifiedSavedRecipePrincipal("foreign", CommandActor.ACCOUNT, f.account.principalId, f.account.deviceSessionId)
        denied(SavedRecipeFailureCode.UNAUTHENTICATED) { f.store.getSavedRecipe(wrong, id(own)) }
    }
    @Test fun guestExpiryAndWrongVerifiedDeviceNeverBecomeOwnerAuthority() {
        val f = fixture(); val guest = f.principal(CommandActor.GUEST); val input = f.catalogInput(guest); val key = UUID.randomUUID()
        val saved = save(f, guest, key, input)
        val wrong = VerifiedSavedRecipePrincipal("test", CommandActor.ACCOUNT, f.account.principalId, UUID.randomUUID())
        denied(SavedRecipeFailureCode.UNAUTHENTICATED) { f.store.listCollections(wrong) }
        f.sql("UPDATE cooking_test.sessions SET expires_at=clock_timestamp()-interval '1 second' WHERE session_id='${guest.guestSessionId}'")
        denied(SavedRecipeFailureCode.UNAUTHENTICATED) { f.store.saveRecipe(guest, key, input) }
        denied(SavedRecipeFailureCode.UNAUTHENTICATED) { f.store.getSavedRecipe(guest, id(saved)) }
    }
    @Test fun noGetCreatesImplicitPreferencesContentOrCookbook() {
        val f = fixture()
        assertTrue(f.store.listSavedRecipes(f.account).body!!.jsonObject.getValue("items").jsonArray.isEmpty())
        assertTrue(f.store.listCollections(f.account).body!!.jsonObject.getValue("items").jsonArray.isEmpty())
        denied(SavedRecipeFailureCode.COLLECTION_UNAVAILABLE) { f.store.getCollection(f.account, UUID.randomUUID()) }
        denied(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE) { f.store.getSavedRecipe(f.account, UUID.randomUUID()) }
        for (table in listOf("memory.library_heads", "memory.saved_recipes", "memory.collections", "platform.idempotency", "platform.outbox")) assertEquals(0, f.count(table))
        assertEquals(0, f.authority.newCalls)
    }
    @Test fun malformedRequestAndTrueMakeAgainFailWithoutReceiptOrAnyDomainEffects() {
        val f = fixture(); val input = f.catalogInput()
        for (bad in listOf(JsonObject(emptyMap()), JsonObject(input + ("unexpected" to JsonPrimitive(1))), JsonObject(input + ("title" to JsonPrimitive("\uD800")))))
            denied(SavedRecipeFailureCode.INPUT_INVALID) { f.store.saveRecipe(f.account, UUID.randomUUID(), bad) }
        denied(SavedRecipeFailureCode.NOT_CONFIGURED) { f.store.saveRecipe(f.account, UUID.randomUUID(), JsonObject(input + ("markMakeAgain" to JsonPrimitive(true)))) }
        assertEquals(0, f.authority.newCalls); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("memory.library_heads"))
    }
    @Test fun exactOriginalKeyReturnsCurrentCopyAndChangedRequestIsMismatch() {
        val f = fixture(); val key = UUID.randomUUID(); val input = f.catalogInput(); val saved = save(f, key = key, input = input)
        assertEquals(saved, SavedRecipeTestFixture.reply(assertIs<CommandResult.Replayed>(f.store.saveRecipe(f.account, key, input))).body)
        assertIs<CommandResult.Mismatch>(f.store.saveRecipe(f.account, key, JsonObject(input + ("title" to JsonPrimitive("Changed")))))
        assertEquals(1, f.authority.newCalls); assertEquals(1, f.count("memory.save_commands")); assertEquals(2, f.count("platform.outbox"))
        assertEquals(saved, SavedRecipeTestFixture.reply(f.store.saveRecipe(f.secondDevice(), key, input)).body)
    }
    @Test fun replayReauthorizesEstablishedCopyAndDoesNotBorrowFreshPublicationOrPlanTtl() {
        val f = fixture(); val plan = f.seedPlan(); val key = UUID.randomUUID(); val input = f.planInput(plan); val saved = save(f, key = key, input = input)
        f.base.expirePlans(); f.changeRecipe { JsonObject(it + mapOf("version" to JsonPrimitive(2), "reviewStatus" to JsonPrimitive("retired"))) }
        assertEquals(saved, SavedRecipeTestFixture.reply(f.store.saveRecipe(f.account, key, input)).body)
        assertEquals(saved, f.store.getSavedRecipe(f.account, id(saved)).body)
        denied(SavedRecipeFailureCode.RECIPE_UNAVAILABLE) { f.store.saveRecipe(f.account, UUID.randomUUID(), input) }
        f.authority.existingCopiesAllowed = false
        denied(SavedRecipeFailureCode.RECIPE_UNAVAILABLE) { f.store.saveRecipe(f.account, key, input) }
        assertEquals(1, f.count("memory.saved_recipes"))
    }
    @Test fun expiredPlanIsNotImplicitCopyGrantButPositiveIndependentCopyDecisionCanAuthorize() {
        val f = fixture(); val plan = f.seedPlan(); f.base.expirePlans()
        denied(SavedRecipeFailureCode.RECIPE_UNAVAILABLE) { f.store.saveRecipe(f.account, UUID.randomUUID(), f.planInput(plan)) }
        assertEquals(0, f.count("memory.library_heads"))
        f.authority.allowExpiredPlanCopy = true
        val saved = save(f, input = f.planInput(plan)); assertEquals(plan["recipeSnapshot"], saved["snapshot"])
        // The positive test adapter is the grant; no caught getter exception or existing cooking pin.
        assertEquals(0, f.count("cooking.cook_sessions"))
    }
    @Test fun recalledCopyFailsClosedReadPagesAndCachedSaveButOwnedDeletionStillWorks() {
        val f = fixture(); val key = UUID.randomUUID(); val input = f.catalogInput(); val saved = save(f, key = key, input = input); val col = firstCollection(f)
        f.authority.recalled = true
        for (read in listOf<() -> Any?>({ f.store.getSavedRecipe(f.account, id(saved)) }, { f.store.listSavedRecipes(f.account) },
            { f.store.getCollection(f.account, id(col)) }, { f.store.listCollections(f.account) }, { f.store.saveRecipe(f.account, key, input) }))
            denied(SavedRecipeFailureCode.RECIPE_RECALLED, read)
        val deletion = f.store.deleteSavedRecipe(f.account, UUID.randomUUID(), id(saved), "\"1\"")
        assertEquals(204, SavedRecipeTestFixture.reply(deletion).status); assertEquals(0, f.count("memory.collection_items"))
    }
    @Test fun sourceRetirementAndNewCopyDisableDoNotTrapPrivateRemoval() {
        val f = fixture(); val saved = save(f); f.authority.newCopiesAllowed = false; f.authority.existingCopiesAllowed = false
        f.changeRecipe { JsonObject(it + ("reviewStatus" to JsonPrimitive("retired"))) }
        val key = UUID.randomUUID(); assertEquals(204, SavedRecipeTestFixture.reply(f.store.deleteSavedRecipe(f.account, key, id(saved), "\"1\"")).status)
        assertIs<CommandResult.Replayed>(f.store.deleteSavedRecipe(f.account, key, id(saved), "\"1\""))
        assertEquals(0, f.authority.existingCalls)
    }
    @Test fun deletionRequiresExactOwnedVersionAndPreservesOtherPrincipals() {
        val f = fixture(); val saved = save(f); val other = f.principal()
        denied(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE) { f.store.deleteSavedRecipe(other, UUID.randomUUID(), id(saved), "\"1\"") }
        denied(SavedRecipeFailureCode.VERSION_CONFLICT) { f.store.deleteSavedRecipe(f.account, UUID.randomUUID(), id(saved), "\"2\"") }
        for (etag in listOf("1", "W/\"1\"", "\"0\"", "\"9223372036854775808\""))
            denied(SavedRecipeFailureCode.INPUT_INVALID) { f.store.deleteSavedRecipe(f.account, UUID.randomUUID(), id(saved), etag) }
        assertEquals(saved, f.store.getSavedRecipe(f.account, id(saved)).body); assertEquals(1, f.count("platform.idempotency"))
    }
    @Test fun deleteTombstoneDropsContentAndMembershipWhileOldSaveCannotResurrect() {
        val f = fixture(); val saveKey = UUID.randomUUID(); val input = f.catalogInput(); val saved = save(f, key = saveKey, input = input); val deleteKey = UUID.randomUUID()
        val deleted = SavedRecipeTestFixture.reply(f.store.deleteSavedRecipe(f.account, deleteKey, id(saved), "\"1\"")); assertNull(deleted.body); assertNull(deleted.etag)
        assertEquals("1", f.value("SELECT count(*) FROM memory.saved_recipes WHERE snapshot IS NULL AND copy_evidence IS NULL AND deleted"))
        denied(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE) { f.store.saveRecipe(f.account, saveKey, input) }
        assertIs<CommandResult.Replayed>(f.store.deleteSavedRecipe(f.account, deleteKey, id(saved), "\"1\""))
        denied(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE) { f.store.deleteSavedRecipe(f.account, UUID.randomUUID(), id(saved), "\"1\"") }
        assertEquals(0, f.count("memory.collection_items")); assertEquals(1, f.count("memory.collections"))
        // Original generic receipt body retains its bounded seven-day retention; no erase-all claim.
    }
    @Test fun resaveCreatesNewIdentityAndGenerationWhichOldDeleteCannotAcknowledge() {
        val f = fixture(); val originalKey = UUID.randomUUID(); val input = f.catalogInput(); val old = save(f, key = originalKey, input = input); val deleteKey = UUID.randomUUID()
        f.store.deleteSavedRecipe(f.account, deleteKey, id(old), "\"1\"")
        val fresh = save(f, input = input); assertNotEquals(old["id"], fresh["id"]); assertEquals(JsonPrimitive(1), fresh["version"])
        assertEquals("2", f.value("SELECT max(generation) FROM memory.saved_recipes"))
        denied(SavedRecipeFailureCode.VERSION_CONFLICT) { f.store.deleteSavedRecipe(f.account, deleteKey, id(old), "\"1\"") }
        denied(SavedRecipeFailureCode.SAVED_RECIPE_UNAVAILABLE) { f.store.saveRecipe(f.account, originalKey, input) }
        assertEquals(fresh, f.store.getSavedRecipe(f.account, id(fresh)).body)
    }
    @Test fun explicitCollectionMustAlreadyExistAndBeOwnedWithoutSilentDefaultFallback() {
        val f = fixture(); val input = f.catalogInput(); val other = f.principal(); save(f, other, input = f.catalogInput(other))
        val foreign = f.store.listCollections(other).body!!.jsonObject.getValue("items").jsonArray.single().jsonObject
        for (collection in listOf(id(foreign), UUID.randomUUID())) denied(SavedRecipeFailureCode.COLLECTION_UNAVAILABLE) {
            f.store.saveRecipe(f.account, UUID.randomUUID(), JsonObject(input + ("collectionId" to JsonPrimitive(collection.toString()))))
        }
        assertEquals(1, f.count("memory.collections")); assertEquals(1, f.count("memory.library_heads"))
    }
    @Test fun onlyDefaultTargetIsWritableAndReplayRequiresCurrentCapabilityAndMembership() {
        val f = fixture(); val saved = save(f); val default = id(firstCollection(f)); val second = UUID.randomUUID()
        f.sql("INSERT INTO memory.collections SELECT environment,actor_kind,principal_id,'$second',1,false,'Existing owned collection',NULL,created_at,updated_at FROM memory.collections")
        val before = f.count("platform.idempotency") to f.count("platform.outbox")
        val customInput = JsonObject(f.catalogInput() + ("collectionId" to JsonPrimitive(second.toString())))
        denied(SavedRecipeFailureCode.NOT_CONFIGURED) { save(f, input = customInput) }
        assertEquals(before, f.count("platform.idempotency") to f.count("platform.outbox")); assertEquals(1, f.count("memory.collection_items"))
        assertEquals(JsonArray(emptyList()), f.store.getCollection(f.account, second).body!!.jsonObject["savedRecipeIds"])
        val input = JsonObject(f.catalogInput() + ("collectionId" to JsonPrimitive(default.toString()))); val key = UUID.randomUUID()
        assertEquals(saved, save(f, key = key, input = input)); assertEquals(saved, save(f, key = key, input = input))
        f.sql("UPDATE memory.collections SET is_default=false WHERE id='$default'") // Test-only later capability/lifecycle change.
        denied(SavedRecipeFailureCode.NOT_CONFIGURED) { f.store.saveRecipe(f.account, key, input) }
        f.sql("UPDATE memory.collections SET is_default=true WHERE id='$default'; DELETE FROM memory.collection_items WHERE collection_id='$default'")
        denied(SavedRecipeFailureCode.COPY_CONFLICT) { f.store.saveRecipe(f.account, key, input) }
        assertEquals(1, f.count("memory.saved_recipes"))
    }
    @Test fun savedPagesAreExactOrderedBoundedAndFilterBoundWithoutSavingQuota() {
        val f = fixture(); seedDistinct(f, 7); val all = mutableListOf<String>(); var cursor: String? = null
        do {
            val page = f.store.listSavedRecipes(f.account, cursor = cursor, limit = 2).body!!.jsonObject
            val items = page.getValue("items").jsonArray; assertTrue(items.size <= 2); all += items.map { it.jsonObject.text("id") }; cursor = page["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        assertEquals(7, all.size); assertEquals(7, all.toSet().size); assertEquals(all.sorted(), all)
        val filtered = f.store.listSavedRecipes(f.account, q = "Recipe 1", limit = 50).body!!.jsonObject
        assertEquals(1, filtered.getValue("items").jsonArray.size)
        val first = f.store.listSavedRecipes(f.account, limit = 1).body!!.jsonObject.text("nextCursor")
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { f.store.listSavedRecipes(f.account, q = "", cursor = first) }
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { f.store.listSavedRecipes(f.principal(), cursor = first) }
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { f.store.listCollections(f.account, first) }
    }
    @Test fun unicodeSearchUsesScalarLimitAndRejectsAmbiguousInvalidInputsBeforeAuthority() {
        val f = fixture(); assertTrue(f.store.listSavedRecipes(f.account, "🍜".repeat(100)).body!!.jsonObject.getValue("items").jsonArray.isEmpty())
        for (query in listOf("🍜".repeat(101), "\uD800", "a\nb")) denied(SavedRecipeFailureCode.INPUT_INVALID) { f.store.listSavedRecipes(f.account, query) }
        for (limit in listOf(0, 51)) denied(SavedRecipeFailureCode.INPUT_INVALID) { f.store.listCollections(f.account, limit = limit) }
        assertEquals(0, f.count("memory.library_heads"))
    }
    @Test fun nestedCollectionItemCursorIsNotOuterCursorAndPinsExactCollectionVersion() {
        val f = fixture(); seedDistinct(f, 23); val col = firstCollection(f); val cursor = col.text("nextItemCursor")
        assertEquals(20, col.getValue("savedRecipeIds").jsonArray.size)
        val continued = f.store.getCollection(f.account, id(col), cursor, 2); assertEquals("\"${col.text("version")}\"", continued.etag)
        val next = continued.body!!.jsonObject; assertEquals(2, next.getValue("savedRecipeIds").jsonArray.size)
        val terminal = f.store.getCollection(f.account, id(col), next.text("nextItemCursor"), 50).body!!.jsonObject
        assertEquals(1, terminal.getValue("savedRecipeIds").jsonArray.size); assertEquals(JsonNull, terminal["nextItemCursor"])
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { f.store.listCollections(f.account, cursor) }
        seedDistinct(f, 1, start = 100)
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { f.store.getCollection(f.account, id(col), cursor) }
    }
    @Test fun guestCanFollowOwnedCollectionItemsButNeverAnotherGuestOrAccountCursor() {
        val f = fixture(); val guest = f.principal(CommandActor.GUEST)
        repeat(21) { f.changeRecipe(guest) { recipe -> JsonObject(recipe + ("id" to JsonPrimitive(UUID.randomUUID().toString()))) }; save(f, guest, input = f.catalogInput(guest)) }
        val col = f.store.listCollections(guest).body!!.jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals(20, col.getValue("savedRecipeIds").jsonArray.size)
        val page = f.store.getCollection(guest, id(col), cursor = col.text("nextItemCursor"), limit = 1)
        assertEquals(1, page.body!!.jsonObject.getValue("savedRecipeIds").jsonArray.size); assertEquals(JsonNull, page.body!!.jsonObject["nextItemCursor"])
        assertFalse(col.getValue("savedRecipeIds").jsonArray.contains(page.body!!.jsonObject.getValue("savedRecipeIds").jsonArray.single()))
        denied(SavedRecipeFailureCode.COLLECTION_UNAVAILABLE) { f.store.getCollection(f.account, id(col)) }
        denied(SavedRecipeFailureCode.COLLECTION_UNAVAILABLE) { f.store.getCollection(f.principal(CommandActor.GUEST), id(col)) }
    }
    @Test fun changedLibraryGenerationRejectsOldSavedAndCollectionOuterPages() {
        val f = fixture(); seedDistinct(f, 3); val old = f.store.listSavedRecipes(f.account, limit = 1).body!!.jsonObject.text("nextCursor")
        val first = f.store.listSavedRecipes(f.account, limit = 1).body!!.jsonObject.getValue("items").jsonArray.single().jsonObject
        f.store.deleteSavedRecipe(f.account, UUID.randomUUID(), id(first), "\"1\"")
        denied(SavedRecipeFailureCode.CURSOR_INVALID) { f.store.listSavedRecipes(f.account, cursor = old) }
        assertEquals(2, f.store.listSavedRecipes(f.account).body!!.jsonObject.getValue("items").jsonArray.size)
    }
    @Test fun responseBudgetRejectsBeforeCommitAndLargeItemsPageWithoutSkipping() {
        val f = fixture(); val tiny = f.newStore(128)
        denied(SavedRecipeFailureCode.RESPONSE_TOO_LARGE) { tiny.saveRecipe(f.account, UUID.randomUUID(), f.catalogInput()) }
        assertEquals(0, f.count("memory.library_heads")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("platform.outbox"))
        val bounded = f.newStore(4096); seedDistinct(f, 4, store = bounded)
        val first = bounded.listSavedRecipes(f.account, limit = 50).body!!.jsonObject
        assertTrue(first.getValue("items").jsonArray.size in 1..3); assertNotEquals(JsonNull, first["nextCursor"])
        val seen = first.getValue("items").jsonArray.toMutableList(); var cursor = first["nextCursor"]?.jsonPrimitive?.contentOrNull
        while (cursor != null) { val next = bounded.listSavedRecipes(f.account, cursor = cursor, limit = 50).body!!.jsonObject
            seen.addAll(next.getValue("items").jsonArray); cursor = next["nextCursor"]?.jsonPrimitive?.contentOrNull }
        assertEquals(4, seen.size); assertEquals(4, seen.map { it.jsonObject.text("id") }.toSet().size)
    }
    @Test fun unknownSaveCommitPreservesOneCopyReceiptMembershipAndOriginalReplay() {
        val f = fixture(); val input = f.catalogInput(); val key = UUID.randomUUID(); f.faults.loseCommit = true
        assertFailsWith<CommitOutcomeUnknown> { f.store.saveRecipe(f.account, key, input) }
        assertEquals(1, f.count("memory.saved_recipes")); assertEquals(1, f.count("memory.save_commands")); assertEquals(1, f.count("platform.idempotency"))
        val replay = assertIs<CommandResult.Replayed>(f.store.saveRecipe(f.account, key, input)); assertEquals(201, replay.reply.status)
        assertEquals(2, f.count("platform.outbox"))
    }
    @Test fun unknownDeleteCommitCannotDeleteNewlyResavedIncarnationOnRetry() {
        val f = fixture(); val saved = save(f); val key = UUID.randomUUID(); f.faults.loseCommit = true
        assertFailsWith<CommitOutcomeUnknown> { f.store.deleteSavedRecipe(f.account, key, id(saved), "\"1\"") }
        val current = save(f)
        denied(SavedRecipeFailureCode.VERSION_CONFLICT) { f.store.deleteSavedRecipe(f.account, key, id(saved), "\"1\"") }
        assertEquals(current, f.store.getSavedRecipe(f.account, id(current)).body)
    }
    @Test fun outboxFailureRollsBackCopyDefaultMembershipAndReceiptTogether() {
        val f = fixture(); f.faults.outboxFailure = true
        denied(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) { f.store.saveRecipe(f.account, UUID.randomUUID(), f.catalogInput()) }
        for (table in listOf("memory.library_heads", "memory.saved_recipes", "memory.collections", "memory.collection_items", "memory.save_commands", "platform.idempotency", "platform.outbox")) assertEquals(0, f.count(table))
        val saved = save(f); f.faults.outboxFailure = true
        denied(SavedRecipeFailureCode.STORAGE_UNAVAILABLE) { f.store.deleteSavedRecipe(f.account, UUID.randomUUID(), id(saved), "\"1\"") }
        assertEquals(saved, f.store.getSavedRecipe(f.account, id(saved)).body); assertEquals(1, f.count("memory.collection_items"))
    }
    @Test fun interruptAfterPositiveCopyCheckStopsAllDomainAndReceiptEffects() {
        val f = fixture(); f.authority.afterNewCopy = { Thread.currentThread().interrupt() }
        try { assertFailsWith<InterruptedException> { f.store.saveRecipe(f.account, UUID.randomUUID(), f.catalogInput()) } }
        finally { Thread.interrupted(); f.authority.afterNewCopy = null }
        assertEquals(0, f.count("memory.saved_recipes")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("memory.collections"))
    }
    @Test fun concurrentSameAndDistinctKeysSerializeOneNormalizedCopyAndOneSavedFact() {
        val f = fixture(); val input = f.catalogInput(); val executor = Executors.newFixedThreadPool(6); val sharedKey = UUID.randomUUID()
        try {
            val futures = (0 until 6).map { i -> executor.submit<CommandResult> { f.store.saveRecipe(f.account, if (i < 3) sharedKey else UUID.randomUUID(), input) } }
            val ids = futures.map { SavedRecipeTestFixture.reply(it.get(20, TimeUnit.SECONDS)).body!!.jsonObject.text("id") }
            assertEquals(1, ids.toSet().size); assertEquals(1, f.count("memory.saved_recipes")); assertEquals(1, f.count("memory.collection_items"))
            assertEquals(4, f.count("platform.idempotency")); assertEquals(1, f.value("SELECT count(*) FROM platform.outbox WHERE event_type='memory.recipe.saved.v1'").toInt())
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    @Test fun lifecyclePrincipalLockMakesRevocationWinBeforeConcurrentCachedDisclosure() {
        val f = fixture(); val input = f.catalogInput(); val key = UUID.randomUUID(); save(f, key = key, input = input)
        val entered = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
        f.source.connection.use { lifecycle ->
            lifecycle.autoCommit = false; f.authority.lockPrincipal(lifecycle, f.account)
            lifecycle.prepareStatement("UPDATE cooking_test.sessions SET active=false WHERE session_id=?").use { it.setObject(1, f.account.deviceSessionId); assertEquals(1, it.executeUpdate()) }
            val future = executor.submit<SavedRecipeFailureCode> { entered.countDown(); assertFailsWith<SavedRecipeFailure> { f.store.saveRecipe(f.account, key, input) }.code }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFailsWith<java.util.concurrent.TimeoutException> { future.get(200, TimeUnit.MILLISECONDS) }
                lifecycle.commit(); assertEquals(SavedRecipeFailureCode.UNAUTHENTICATED, future.get(10, TimeUnit.SECONDS))
            } finally { lifecycle.rollback(); future.cancel(true); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
        assertEquals(1, f.count("platform.idempotency")); assertEquals(2, f.count("platform.outbox"))
    }
    @Test fun databaseConstraintsRejectCrossOwnerMembershipDuplicateLiveCopyAndContentRewrite() {
        val f = fixture(); val saved = save(f); val other = f.principal(); val foreign = save(f, other, input = f.catalogInput(other)); val col = firstCollection(f)
        assertFailsWith<SQLException> { f.sql("INSERT INTO memory.collection_items VALUES('test','account','${f.account.principalId}','${id(col)}','${id(foreign)}',2)") }
        assertFailsWith<SQLException> { f.sql("UPDATE memory.saved_recipes SET snapshot=jsonb_set(snapshot,'{title}','\"rewritten\"') WHERE id='${id(saved)}'") }
        assertFailsWith<SQLException> { f.sql("INSERT INTO memory.saved_recipes SELECT environment,actor_kind,principal_id,'${UUID.randomUUID()}',generation+1,version,recipe_version_id,recipe_hash,source_type,source_id,origin_plan_id,content_license,snapshot,copy_evidence,deleted,deletion_key,created_at,updated_at FROM memory.saved_recipes WHERE id='${id(saved)}'") }
        assertEquals(saved, f.store.getSavedRecipe(f.account, id(saved)).body)
    }
    @Test fun registeredEventsContainOnlyIdsKindsAndActionsNeverTitlesRecipesOrProof() {
        val f = fixture(); val saved = save(f, input = JsonObject(f.catalogInput() + ("title" to JsonPrimitive("private-secret-title"))))
        f.store.deleteSavedRecipe(f.account, UUID.randomUUID(), id(saved), "\"1\"")
        f.source.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT event_type,payload::text,producer FROM platform.outbox ORDER BY occurred_at,event_id").use { r ->
            var count = 0
            while (r.next()) { count++; val data = Json.parseToJsonElement(r.getString(2)).jsonObject
                assertEquals("memory", r.getString(3)); assertFalse(r.getString(2).contains("private-secret-title")); assertFalse(r.getString(2).contains("synthetic-positive-copy"))
                assertEquals(when (r.getString(1)) {
                    "memory.recipe.saved.v1" -> setOf("principalId", "savedRecipeId", "sourceType")
                    "memory.recipe.deleted.v1" -> setOf("principalId", "savedRecipeId")
                    "memory.collection.changed.v1" -> setOf("principalId", "collectionId", "action")
                    else -> error("Unexpected event")
                }, data.keys)
            }; assertEquals(4, count)
        } } }
    }
    companion object {
        private lateinit var cluster: PostgresTestCluster
        @JvmField @ClassRule val timeout = Timeout(10, TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun startCluster() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun closeCluster() { if (::cluster.isInitialized) cluster.close() }
        private fun fixture() = SavedRecipeTestFixture(cluster.database())
        private fun save(f: SavedRecipeTestFixture, actor: VerifiedSavedRecipePrincipal = f.account, key: UUID = UUID.randomUUID(), input: JsonObject = f.catalogInput(actor)) =
            SavedRecipeTestFixture.reply(f.store.saveRecipe(actor, key, input)).body!!.jsonObject
        private fun firstCollection(f: SavedRecipeTestFixture) = f.store.listCollections(f.account).body!!.jsonObject.getValue("items").jsonArray.first().jsonObject
        private fun seedDistinct(f: SavedRecipeTestFixture, count: Int, start: Int = 0, store: SavedRecipeStore = f.store) {
            repeat(count) { index -> f.changeRecipe { JsonObject(it + mapOf("id" to JsonPrimitive(UUID.randomUUID().toString()), "title" to JsonPrimitive("Recipe ${index + start}"))) }
                store.saveRecipe(f.account, UUID.randomUUID(), f.catalogInput()) }
        }
        private fun id(body: JsonObject) = UUID.fromString(body.text("id"))
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        private fun denied(code: SavedRecipeFailureCode, action: () -> Any?) = assertEquals(code, assertFailsWith<SavedRecipeFailure> { action() }.code)
    }
}
