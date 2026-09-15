package com.feedme.server.kitchen

import com.feedme.server.db.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Real isolated PostgreSQL, with explicitly synthetic current identity/catalog/provisioning adapters. */
class KitchenStoreIntegrationTest {
    @Test fun missingPreferencesGetDoesNotProvisionRowsReceiptsOrPermissiveDefaults() {
        val f = Fixture()
        denied(KitchenFailureCode.PREFERENCES_UNAVAILABLE) { f.store.getPreferences(f.account) }
        denied(KitchenFailureCode.PREFERENCES_UNAVAILABLE) { f.store.updatePreferences(f.account, UUID.randomUUID(), "\"1\"", patch()) }
        assertEquals(0, f.count("profile.preferences")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("platform.outbox"))
        assertEquals(emptyList(), f.pantryItems())
    }
    @Test fun explicitProvisioningRequiresCompleteUserFieldsAndIndependentAuthority() {
        val f = Fixture(); f.authority.provisionAllowed = false
        denied(KitchenFailureCode.NOT_CONFIGURED) { f.provision() }
        f.authority.provisionAllowed = true
        denied(KitchenFailureCode.INPUT_INVALID) { f.store.provisionPreferences(f.account, buildJsonObject {}) }
        val initial = f.provision(); assertEquals("\"1\"", initial.etag)
        assertEquals(initial.body, f.store.getPreferences(f.account).body)
        assertEquals(initial.body, f.provision().body); assertEquals(1, f.count("profile.preferences")); assertEquals(1, f.count("platform.outbox"))
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.provisionPreferences(f.account, JsonObject(seed() + patch())) }
    }
    @Test fun preferencePatchPreservesOmittedExclusionsAndCommitsOneVersionReceiptAndSafeEvent() {
        val f = Fixture(); val initial = f.provision().body!!.jsonObject; val key = UUID.randomUUID()
        val changed = body(f.store.updatePreferences(f.account, key, "\"1\"", patch()))
        assertEquals(initial["id"], changed["id"]); assertEquals(initial["createdAt"], changed["createdAt"])
        assertEquals(initial["hardExcludedIngredientIds"], changed["hardExcludedIngredientIds"]); assertEquals(JsonPrimitive(2), changed["version"])
        assertEquals(changed, body(assertIs<CommandResult.Replayed>(f.store.updatePreferences(f.account, key, "\"1\"", patch()))))
        val event = parse(f.value("SELECT payload::text FROM platform.outbox WHERE aggregate_version=2"))
        assertEquals(setOf("principalId", "preferenceVersion", "changedFieldKinds"), event.keys)
        assertEquals(arr("defaultEnergy"), event["changedFieldKinds"])
        assertFalse(event.toString().contains(INGREDIENT)); assertEquals(1, f.count("platform.idempotency")); assertEquals(2, f.count("platform.outbox"))
    }
    @Test fun preferenceExactOriginalPreconditionAndBodyArePartOfDurableIdentity() {
        val f = Fixture(); f.provision(); val key = UUID.randomUUID()
        f.store.updatePreferences(f.account, key, "\"1\"", patch())
        assertIs<CommandResult.Mismatch>(f.store.updatePreferences(f.account, key, "\"2\"", patch()))
        assertIs<CommandResult.Mismatch>(f.store.updatePreferences(f.account, key, "\"1\"", patch("happy")))
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.updatePreferences(f.account, UUID.randomUUID(), "\"1\"", patch("happy")) }
        assertEquals(1, f.count("platform.idempotency"))
    }
    @Test fun newerPreferenceStateCannotBeOverwrittenOrDisclosedAsAnOldCachedAcknowledgement() {
        val f = Fixture(); f.provision(); val key = UUID.randomUUID()
        f.store.updatePreferences(f.account, key, "\"1\"", patch()); val latest = body(f.store.updatePreferences(f.account, UUID.randomUUID(), "\"2\"", patch("happy")))
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.updatePreferences(f.account, key, "\"1\"", patch()) }
        assertEquals(latest, f.store.getPreferences(f.account).body); assertEquals(2, f.count("platform.idempotency"))
    }
    @Test fun currentIdentityDeviceAndGuestExpiryAreCheckedBeforeEveryCachedDisclosure() {
        val f = Fixture(); f.provision(); val key = UUID.randomUUID(); f.store.updatePreferences(f.account, key, "\"1\"", patch())
        val wrong = VerifiedKitchenPrincipal("test", CommandActor.ACCOUNT, f.account.principalId, UUID.randomUUID())
        denied(KitchenFailureCode.UNAUTHENTICATED) { f.store.updatePreferences(wrong, key, "\"1\"", patch()) }
        f.sql("UPDATE kitchen_test.principals SET active=false")
        denied(KitchenFailureCode.UNAUTHENTICATED) { f.store.getPreferences(f.account) }
        denied(KitchenFailureCode.UNAUTHENTICATED) { f.store.updatePreferences(f.account, key, "\"1\"", patch()) }
        val guest = f.principal(CommandActor.GUEST); val guestKey = UUID.randomUUID(); f.store.upsertPantryItem(guest, guestKey, write())
        f.sql("UPDATE kitchen_test.principals SET expires_at=clock_timestamp()-interval '1 second' WHERE kind='guest'")
        denied(KitchenFailureCode.UNAUTHENTICATED) { f.store.upsertPantryItem(guest, guestKey, write()) }
    }
    @Test fun accountGuestEnvironmentAndOtherPrincipalScopesNeverShareRowsOrCommands() {
        val f = Fixture(); val guest = f.principal(CommandActor.GUEST, f.account.principalId); val other = f.principal(CommandActor.ACCOUNT)
        val key = UUID.randomUUID(); val a = body(f.store.upsertPantryItem(f.account, key, write())); val g = body(f.store.upsertPantryItem(guest, key, write()))
        assertNotEquals(a["id"], g["id"]); assertEquals(1, f.store.listPantry(guest).body!!.jsonObject["items"]!!.jsonArray.size)
        assertEquals(0, f.store.listPantry(other).body!!.jsonObject["items"]!!.jsonArray.size)
        denied(KitchenFailureCode.PANTRY_ITEM_UNAVAILABLE) { f.store.removePantryItem(other, UUID.randomUUID(), UUID.fromString(INGREDIENT), "\"1\"") }
        val foreign = VerifiedKitchenPrincipal("other", f.account.kind, f.account.principalId, f.account.deviceSessionId)
        denied(KitchenFailureCode.UNAUTHENTICATED) { f.store.listPantry(foreign) }; assertEquals(2, f.count("platform.idempotency"))
    }
    @Test fun newRoughPantryReportHasExplicitNullConfirmationAndNoInventedQuantity() {
        val f = Fixture(); val key = UUID.randomUUID(); val result = f.store.upsertPantryItem(f.account, key, write())
        assertEquals(200, reply(result).status); assertEquals("\"1\"", reply(result).etag)
        val item = body(result); assertEquals(JsonNull, item["confirmedAt"]); assertEquals(JsonPrimitive(false), item["staple"])
        assertFalse(item.containsKey("quantity")); assertFalse(item.containsKey("unit")); assertFalse(item.containsKey("confirmationStatus"))
        assertEquals(item, body(assertIs<CommandResult.Replayed>(f.store.upsertPantryItem(f.account, key, write()))))
        val event = parse(f.value("SELECT payload::text FROM platform.outbox"))
        assertEquals(setOf("principalId", "ingredientId", "action"), event.keys); assertEquals(JsonPrimitive("upserted"), event["action"])
    }
    @Test fun explicitUncertainStapleAndConfirmationFieldsAreNeverUpgradedOrAutoConsumed() {
        val f = Fixture(); val input = JsonObject(write(presence = "usuallyHave") + buildJsonObject {
            put("confirmationStatus", "usual"); put("confirmedAt", JsonNull); put("staple", true)
        })
        val original = body(f.store.upsertPantryItem(f.account, UUID.randomUUID(), input))
        assertEquals(JsonPrimitive("usual"), original["confirmationStatus"]); assertEquals(JsonNull, original["confirmedAt"])
        val updated = body(f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(expected = 1, presence = "uncertain")))
        assertEquals(JsonPrimitive(true), updated["staple"]); assertEquals(JsonPrimitive("usual"), updated["confirmationStatus"])
        assertEquals(JsonNull, updated["confirmedAt"]); repeat(2) { assertEquals(updated, f.pantryItems().single()) }
    }
    @Test fun pantryOverwriteRequiresExactExpectedVersionWithoutHiddenIfMatch() {
        val f = Fixture(); f.store.upsertPantryItem(f.account, UUID.randomUUID(), write())
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write()) }
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(expected = 2)) }
        val result = f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(expected = 1, presence = "out"))
        assertEquals("\"2\"", reply(result).etag); assertEquals(JsonPrimitive("out"), body(result)["presence"])
        assertEquals(2, f.count("platform.idempotency"))
    }
    @Test fun deletionIsBodylessAndOnlyExactOriginalCommandCanReplayMissingRow() {
        val f = Fixture(); f.store.upsertPantryItem(f.account, UUID.randomUUID(), write()); val key = UUID.randomUUID()
        val first = reply(f.store.removePantryItem(f.account, key, UUID.fromString(INGREDIENT), "\"1\""))
        assertEquals(204, first.status); assertNull(first.body); assertNull(first.etag); assertEquals(emptyList(), f.pantryItems())
        assertIs<CommandResult.Replayed>(f.store.removePantryItem(f.account, key, UUID.fromString(INGREDIENT), "\"1\""))
        denied(KitchenFailureCode.PANTRY_ITEM_UNAVAILABLE) { f.store.removePantryItem(f.account, UUID.randomUUID(), UUID.fromString(INGREDIENT), "\"1\"") }
        assertEquals("1", f.value("SELECT count(*) FROM pantry.pantry_items WHERE fields IS NULL")); assertEquals(2, f.count("platform.outbox"))
    }
    @Test fun deleteRecreateUsesNewIncarnationAndIncreasingVersionAgainstBothOldWritesAndDeletes() {
        val f = Fixture(); val createKey = UUID.randomUUID(); val old = body(f.store.upsertPantryItem(f.account, createKey, write())); val removeKey = UUID.randomUUID()
        f.store.removePantryItem(f.account, removeKey, UUID.fromString(INGREDIENT), "\"1\"")
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(expected = 1)) }
        val replacement = body(f.store.upsertPantryItem(f.account, UUID.randomUUID(), write()))
        assertNotEquals(old["id"], replacement["id"]); assertEquals(JsonPrimitive(3), replacement["version"])
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.upsertPantryItem(f.account, createKey, write()) }
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.removePantryItem(f.account, removeKey, UUID.fromString(INGREDIENT), "\"1\"") }
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.removePantryItem(f.account, UUID.randomUUID(), UUID.fromString(INGREDIENT), "\"1\"") }
        assertEquals(replacement, f.pantryItems().single()); assertEquals(3, f.count("platform.idempotency"))
    }
    @Test fun secondRemovalCannotAcknowledgeAnEarlierDeletionEvenAfterReplacementAlsoDisappears() {
        val f = Fixture(); f.store.upsertPantryItem(f.account, UUID.randomUUID(), write()); val old = UUID.randomUUID()
        f.store.removePantryItem(f.account, old, UUID.fromString(INGREDIENT), "\"1\"")
        f.store.upsertPantryItem(f.account, UUID.randomUUID(), write()); val next = UUID.randomUUID()
        f.store.removePantryItem(f.account, next, UUID.fromString(INGREDIENT), "\"3\"")
        denied(KitchenFailureCode.VERSION_CONFLICT) { f.store.removePantryItem(f.account, old, UUID.fromString(INGREDIENT), "\"1\"") }
        assertIs<CommandResult.Replayed>(f.store.removePantryItem(f.account, next, UUID.fromString(INGREDIENT), "\"3\""))
    }
    @Test fun retiredCatalogRejectsNewSelectionButPreservesOwnedReadsExactReplayAndRemoval() {
        val f = Fixture(); val key = UUID.randomUUID(); f.store.upsertPantryItem(f.account, key, write())
        f.sql("UPDATE kitchen_test.ingredients SET active=false")
        val replay = body(assertIs<CommandResult.Replayed>(f.store.upsertPantryItem(f.account, key, write())))
        denied(KitchenFailureCode.INGREDIENT_UNAVAILABLE) { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(expected = 1)) }
        assertEquals(replay, f.pantryItems().single())
        val removal = UUID.randomUUID(); f.store.removePantryItem(f.account, removal, UUID.fromString(INGREDIENT), "\"1\"")
        assertIs<CommandResult.Replayed>(f.store.removePantryItem(f.account, removal, UUID.fromString(INGREDIENT), "\"1\""))
    }
    @Test fun retiredPreferenceReferencesRemainReadableAndReplayableWithoutGrantingNewSelection() {
        val f = Fixture(); f.provision(); val key = UUID.randomUUID(); f.store.updatePreferences(f.account, key, "\"1\"", patch())
        f.sql("UPDATE kitchen_test.ingredients SET active=false")
        val replay = body(assertIs<CommandResult.Replayed>(f.store.updatePreferences(f.account, key, "\"1\"", patch())))
        assertEquals(replay, f.store.getPreferences(f.account).body)
        denied(KitchenFailureCode.INGREDIENT_UNAVAILABLE) { f.store.updatePreferences(f.account, UUID.randomUUID(), "\"2\"", patch("happy")) }
        f.sql("UPDATE kitchen_test.ingredients SET active=true"); f.authority.preferenceAllowed = false
        assertEquals(replay, body(assertIs<CommandResult.Replayed>(f.store.updatePreferences(f.account, key, "\"1\"", patch()))))
        denied(KitchenFailureCode.NOT_CONFIGURED) { f.store.updatePreferences(f.account, UUID.randomUUID(), "\"2\"", patch("happy")) }
        assertEquals(1, f.count("platform.idempotency"))
    }
    @Test fun typedInputUnknownIngredientAndUnitFailuresRollbackAllCommandArtifacts() {
        val f = Fixture()
        for (input in listOf(JsonObject(write() + ("ownerId" to JsonPrimitive(f.account.principalId.toString()))),
            JsonObject(write() + ("unit" to JsonPrimitive("\uD800"))), JsonObject(write() + ("quantity" to JsonPrimitive(-1)))))
            denied(KitchenFailureCode.INPUT_INVALID) { f.store.upsertPantryItem(f.account, UUID.randomUUID(), input) }
        denied(KitchenFailureCode.INGREDIENT_UNAVAILABLE) { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(UUID.randomUUID().toString())) }
        denied(KitchenFailureCode.INPUT_INVALID) { f.store.upsertPantryItem(f.account, UUID.randomUUID(), JsonObject(write() + ("unit" to JsonPrimitive("invented")))) }
        assertEquals(0, f.count("pantry.pantry_items")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("platform.outbox"))
    }
    @Test fun exactDecimalValuesSurvivePostgresJsonbAndCanonicalEquivalentRetry() {
        val f = Fixture(); val key = UUID.randomUUID(); val input = parse("""{"ingredientId":"$INGREDIENT","presence":"low","quantity":0.0000001234567890123456789,"unit":"g"}""")
        val item = body(f.store.upsertPantryItem(f.account, key, input))
        assertEquals("0.0000001234567890123456789".toBigDecimal(), item.getValue("quantity").jsonPrimitive.content.toBigDecimal())
        assertEquals(item, body(assertIs<CommandResult.Replayed>(f.store.upsertPantryItem(f.account, key, input))))
        val exponentKey = UUID.randomUUID(); val exponent = JsonObject(write(OTHER) + ("quantity" to Json.parseToJsonElement("1e2")))
        val normalized = body(f.store.upsertPantryItem(f.account, exponentKey, exponent))
        assertEquals(normalized, body(assertIs<CommandResult.Replayed>(f.store.upsertPantryItem(f.account, exponentKey,
            JsonObject(write(OTHER) + ("quantity" to Json.parseToJsonElement("100.0")))))))
    }
    @Test fun boundedOwnerCursorPagesExcludeTombstonesAndCannotGrantForeignAccess() {
        val f = Fixture(); for (id in listOf(INGREDIENT, OTHER, THIRD)) f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(id))
        f.store.removePantryItem(f.account, UUID.randomUUID(), UUID.fromString(OTHER), "\"1\"")
        val first = f.store.listPantry(f.account, limit = 1); assertNull(first.etag)
        val page = first.body!!.jsonObject; val cursor = page.getValue("nextCursor").jsonPrimitive.content
        assertEquals(INGREDIENT, page.getValue("items").jsonArray.single().jsonObject.getValue("ingredientId").jsonPrimitive.content)
        val second = f.store.listPantry(f.account, cursor, 1).body!!.jsonObject
        assertEquals(THIRD, second.getValue("items").jsonArray.single().jsonObject.getValue("ingredientId").jsonPrimitive.content); assertEquals(JsonNull, second["nextCursor"])
        val other = f.principal(CommandActor.ACCOUNT)
        denied(KitchenFailureCode.CURSOR_INVALID) { f.store.listPantry(other, cursor) }
        denied(KitchenFailureCode.CURSOR_INVALID) { f.store.listPantry(f.account, cursor + "x") }
        // Sign with this fixture's explicit synthetic key; integration tests do not widen internals.
        val expired = expiredCursor(f.account, UUID.fromString(INGREDIENT))
        denied(KitchenFailureCode.CURSOR_EXPIRED) { f.store.listPantry(f.account, expired) }
    }
    @Test fun responseBoundIsEnforcedBeforeMutationReceiptAndOutboxCanCommit() {
        val f = Fixture(); f.provision(); val bounded = f.newStore(512); val before = f.snapshot()
        denied(KitchenFailureCode.RESPONSE_TOO_LARGE) { bounded.updatePreferences(f.account, UUID.randomUUID(), "\"1\"", buildJsonObject { put("consentVersion", "x".repeat(1000)) }) }
        denied(KitchenFailureCode.RESPONSE_TOO_LARGE) { bounded.upsertPantryItem(f.account, UUID.randomUUID(), JsonObject(write() + ("unit" to JsonPrimitive("x".repeat(1000))))) }
        assertEquals(before, f.snapshot())
        assertEquals(512, bounded.policy.maxResponseBytes)
    }
    @Test fun outboxFailureRollsBackDomainVersionAndReceiptAsOneTransaction() {
        val f = Fixture(); f.provision(); val before = f.snapshot(); f.faults.outboxFailure = true; val key = UUID.randomUUID()
        denied(KitchenFailureCode.STORAGE_UNAVAILABLE) { f.store.updatePreferences(f.account, key, "\"1\"", patch()) }
        assertEquals(before, f.snapshot()); assertIs<CommandResult.Applied>(f.store.updatePreferences(f.account, key, "\"1\"", patch()))
    }
    @Test fun lostApplicationReceiptAfterRealCommitRetriesExactOneEffectWithoutReplacingKey() {
        val f = Fixture(); val key = UUID.randomUUID(); f.faults.loseCommit = true
        assertFailsWith<CommitOutcomeUnknown> { f.store.upsertPantryItem(f.account, key, write()) }
        val committed = f.pantryItems().single(); assertEquals(1, f.count("platform.outbox"))
        assertEquals(committed, body(assertIs<CommandResult.Replayed>(f.store.upsertPantryItem(f.account, key, write()))))
        assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }
    @Test fun simulatedBeforeCommitFailureRemainsUnknownAndSameCommandCanApplyOnce() {
        val f = Fixture(); val key = UUID.randomUUID(); f.faults.failBeforeCommit = true
        assertFailsWith<CommitOutcomeUnknown> { f.store.upsertPantryItem(f.account, key, write()) }
        assertEquals(0, f.count("pantry.pantry_items")); assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("platform.outbox"))
        assertIs<CommandResult.Applied>(f.store.upsertPantryItem(f.account, key, write()))
    }
    @Test fun concurrentSameKeyCommandsSerializeOneMutationAndExactReplay() {
        val f = Fixture(); val key = UUID.randomUUID(); val results = race {
            f.store.upsertPantryItem(f.account, key, write())
        }
        assertEquals(1, results.count { it is CommandResult.Applied }); assertEquals(3, results.count { it is CommandResult.Replayed })
        assertEquals(1, f.count("pantry.pantry_items")); assertEquals(1, f.count("platform.idempotency")); assertEquals(1, f.count("platform.outbox"))
    }
    @Test fun concurrentDifferentKeysWithSameExpectedVersionCannotLoseAnUpdate() {
        val f = Fixture(); f.store.upsertPantryItem(f.account, UUID.randomUUID(), write())
        val results = race { runCatching { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(expected = 1, presence = "low")) } }
        assertEquals(1, results.count { it.isSuccess }); assertEquals(3, results.count { (it.exceptionOrNull() as? KitchenFailure)?.code == KitchenFailureCode.VERSION_CONFLICT })
        assertEquals(JsonPrimitive(2), f.pantryItems().single().jsonObject["version"]); assertEquals(2, f.count("platform.idempotency"))
    }
    @Test fun cancellationAndUnexpectedAuthorityExceptionsNeverBecomeAuthSuccessOrLeakyFailures() {
        val f = Fixture(); f.authority.failure = CancellationException("synthetic private cancellation")
        assertFailsWith<CancellationException> { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write()) }
        f.authority.failure = IllegalStateException("private provider detail")
        val failure = assertFailsWith<KitchenFailure> { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write()) }
        assertEquals(KitchenFailureCode.STORAGE_UNAVAILABLE, failure.code); assertFalse(failure.toString().contains("private provider detail"))
        assertEquals(0, f.count("platform.idempotency")); assertEquals(0, f.count("pantry.pantry_items"))
    }
    @Test fun expiredReceiptNeverExecutesAgainAndVersionExhaustionFailsClosed() {
        val f = Fixture(); val key = UUID.randomUUID(); f.store.upsertPantryItem(f.account, key, write())
        f.sql("UPDATE platform.idempotency SET expires_at=clock_timestamp()-interval '1 second'")
        assertIs<CommandResult.ReceiptExpired>(f.store.upsertPantryItem(f.account, key, write()))
        f.sql("UPDATE pantry.pantry_items SET version=9223372036854775807")
        denied(KitchenFailureCode.STORAGE_UNAVAILABLE) { f.store.upsertPantryItem(f.account, UUID.randomUUID(), write(expected = Long.MAX_VALUE)) }
        assertEquals(1, f.count("platform.outbox"))
    }
    @Test fun malformedPersistedFieldsCannotHideByOverwritingResourceMetadata() {
        val f = Fixture(); f.provision(); f.sql("UPDATE profile.preferences SET fields=fields || '{\"id\":\"injected\"}'::jsonb")
        denied(KitchenFailureCode.STORAGE_UNAVAILABLE) { f.store.getPreferences(f.account) }
        f.store.upsertPantryItem(f.account, UUID.randomUUID(), write())
        f.sql("UPDATE pantry.pantry_items SET fields=fields || '{\"ingredientId\":\"foreign\"}'::jsonb")
        denied(KitchenFailureCode.STORAGE_UNAVAILABLE) { f.store.listPantry(f.account) }
    }
    @Test fun searchRunsInsideCurrentPrincipalTransactionPreservesNullableQueryAndValidatesResponse() {
        val f = Fixture(); var calls = 0; var observed: String? = "not-called"; var observedCursor: String? = null
        val search = KitchenIngredientSearch { c, actor, q, cursor, limit ->
            assertFalse(c.autoCommit); assertEquals(f.account.principalId, actor.principalId); assertEquals(20, limit)
            observed = q; observedCursor = cursor; calls++
            buildJsonObject { put("items", JsonArray(emptyList())); put("nextCursor", JsonNull); put("serverTime", Instant.now().toString()) }
        }
        f.store.searchIngredients(f.account, null, null, 20, search); assertNull(observed)
        f.store.searchIngredients(f.account, "", null, 20, search); assertEquals("", observed)
        val supplementary = "\uD83E\uDD55".repeat(100)
        f.store.searchIngredients(f.account, supplementary, null, 20, search); assertEquals(supplementary, observed)
        val supplementaryCursor = "\uD83E\uDD55".repeat(2048)
        f.store.searchIngredients(f.account, null, supplementaryCursor, 20, search); assertEquals(supplementaryCursor, observedCursor)
        for (cursor in listOf(supplementaryCursor + "x", "\uD800", "private\u0085cursor"))
            denied(KitchenFailureCode.INPUT_INVALID) { f.store.searchIngredients(f.account, null, cursor, 20, search) }
        for (q in listOf("a".repeat(101), supplementary + "\uD83E\uDD55", "\uD800", "private\nquery", "private\u0085query"))
            denied(KitchenFailureCode.INPUT_INVALID) { f.store.searchIngredients(f.account, q, null, 20, search) }
        f.sql("UPDATE kitchen_test.principals SET active=false")
        denied(KitchenFailureCode.UNAUTHENTICATED) { f.store.searchIngredients(f.account, null, null, 20, search) }; assertEquals(4, calls)
        f.sql("UPDATE kitchen_test.principals SET active=true")
        denied(KitchenFailureCode.STORAGE_UNAVAILABLE) { f.store.searchIngredients(f.account, null, null, 20, KitchenIngredientSearch { _, _, _, _, _ -> buildJsonObject { put("private", "wrong") } }) }
        assertEquals(0, f.count("platform.idempotency"))
    }

    private class Fixture {
        val dataSource = cluster.database(); val authority = TestAuthority(); val faults = Faults()
        val cursors = KitchenCursorCodec("test", mapOf("test" to ByteArray(32) { 7 }))
        val account: VerifiedKitchenPrincipal
        val store: KitchenStore
        init {
            PlatformMigrations(dataSource).migrate()
            sql("CREATE SCHEMA kitchen_test; CREATE TABLE kitchen_test.principals(kind text,id uuid,device uuid,active boolean NOT NULL,expires_at timestamptz NOT NULL,PRIMARY KEY(kind,id));" +
                "CREATE TABLE kitchen_test.ingredients(id uuid PRIMARY KEY,active boolean NOT NULL);")
            for (id in listOf(INGREDIENT, OTHER, THIRD)) sql("INSERT INTO kitchen_test.ingredients VALUES('$id',true)")
            account = principal(CommandActor.ACCOUNT); store = newStore()
        }
        fun newStore(max: Int = 65536) = KitchenStore("test", PgTransactions(faults.wrap(dataSource)), authority, cursors, KitchenServicePolicy(max, 600))
        fun principal(kind: CommandActor, id: UUID = UUID.randomUUID()) = VerifiedKitchenPrincipal("test", kind, id, if (kind == CommandActor.ACCOUNT) UUID.randomUUID() else null).also { actor ->
            dataSource.connection.use { c -> c.prepareStatement("INSERT INTO kitchen_test.principals VALUES(?,?,?,true,clock_timestamp()+interval '1 day')").use {
                it.setString(1, kind.name.lowercase()); it.setObject(2, id); it.setObject(3, actor.deviceSessionId); it.executeUpdate()
            } }
        }
        fun provision() = store.provisionPreferences(account, seed())
        fun pantryItems() = store.listPantry(account).body!!.jsonObject.getValue("items").jsonArray.toList()
        fun sql(sql: String) { dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } } }
        fun value(sql: String): String = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getString(1) } } }
        fun count(table: String) = value("SELECT count(*) FROM $table").toInt()
        fun snapshot() = listOf("profile.preferences", "pantry.pantry_items", "platform.idempotency", "platform.outbox").associateWith { table ->
            value("SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM $table r")
        }
    }
    private class TestAuthority : KitchenAuthority {
        var provisionAllowed = true; var preferenceAllowed = true; var failure: RuntimeException? = null
        override fun lockPrincipal(connection: Connection, principal: VerifiedKitchenPrincipal) {
            failure?.let { throw it }
            connection.prepareStatement("SELECT active,device,expires_at>clock_timestamp() FROM kitchen_test.principals WHERE kind=? AND id=? FOR UPDATE").use { s ->
                s.setString(1, principal.kind.name.lowercase()); s.setObject(2, principal.principalId); s.executeQuery().use {
                    if (!it.next() || !it.getBoolean(1) || it.getObject(2, UUID::class.java) != principal.deviceSessionId || !it.getBoolean(3))
                        throw KitchenFailure(KitchenFailureCode.UNAUTHENTICATED)
                }
            }
        }
        override fun requireProvisioningAllowed(connection: Connection, principal: VerifiedKitchenPrincipal) {
            if (!provisionAllowed) throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED)
        }
        override fun validatePreferences(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject) {
            if (!preferenceAllowed) throw KitchenFailure(KitchenFailureCode.NOT_CONFIGURED)
            listOf("hardExcludedIngredientIds", "dislikedIngredientIds").flatMap { proposed.getValue(it).jsonArray.map { id -> UUID.fromString(id.jsonPrimitive.content) } }
                .distinct().sortedBy(UUID::toString).forEach { ingredient(connection, it) }
        }
        override fun validatePantryItem(connection: Connection, principal: VerifiedKitchenPrincipal, proposed: JsonObject) {
            ingredient(connection, UUID.fromString(proposed.getValue("ingredientId").jsonPrimitive.content))
            if (proposed["unit"]?.jsonPrimitive?.content?.let { it !in setOf("g", "ml") } == true) throw KitchenFailure(KitchenFailureCode.INPUT_INVALID)
        }
        private fun ingredient(c: Connection, id: UUID) { c.prepareStatement("SELECT active FROM kitchen_test.ingredients WHERE id=? FOR SHARE").use {
            it.setObject(1, id); it.executeQuery().use { r -> if (!r.next() || !r.getBoolean(1)) throw KitchenFailure(KitchenFailureCode.INGREDIENT_UNAVAILABLE) }
        } }
    }
    private class Faults {
        @Volatile var outboxFailure = false; @Volatile var loseCommit = false; @Volatile var failBeforeCommit = false
        fun wrap(source: DataSource): DataSource = object : DataSource by source {
            override fun getConnection(): Connection {
                val actual = source.connection
                return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.contains("INSERT INTO platform.outbox") == true && outboxFailure) {
                        outboxFailure = false; throw SQLException("injected synthetic outbox fault", "XX000")
                    }
                    if (method.name == "commit" && failBeforeCommit) { failBeforeCommit = false; throw SQLException("injected before commit", "08006") }
                    try {
                        val result = method.invoke(actual, *(args ?: emptyArray()))
                        if (method.name == "commit" && loseCommit) { loseCommit = false; throw SQLException("injected app receipt loss after acknowledged commit", "08006") }
                        result
                    } catch (failure: InvocationTargetException) { throw failure.targetException }
                } as Connection
            }
        }
    }
    companion object {
        private const val INGREDIENT = "00000000-0000-4000-8000-000000000011"
        private const val OTHER = "00000000-0000-4000-8000-000000000012"
        private const val THIRD = "00000000-0000-4000-8000-000000000013"
        private lateinit var cluster: PostgresTestCluster
        @JvmField @ClassRule val timeout = Timeout(10, TimeUnit.MINUTES)
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun close() { if (::cluster.isInitialized) cluster.close() }
        private fun arr(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
        private fun seed() = buildJsonObject {
            put("hardExcludedIngredientIds", arr(INGREDIENT)); put("dietaryPatterns", arr()); put("dislikedIngredientIds", arr()); put("equipmentIds", arr("pan"))
        }
        private fun patch(energy: String = "little") = buildJsonObject { put("defaultEnergy", energy) }
        private fun write(ingredient: String = INGREDIENT, expected: Long? = null, presence: String = "available") = buildJsonObject {
            put("ingredientId", ingredient); put("presence", presence); expected?.let { put("expectedVersion", it) }
        }
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun expiredCursor(actor: VerifiedKitchenPrincipal, after: UUID): String {
            val payload = "test.$after.0"
            val mac = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256"))
                doFinal("feedme.pantry-cursor.v1\u0000${actor.environment}\u0000${actor.kind.name}\u0000${actor.principalId}\u0000$payload".toByteArray(Charsets.UTF_8))
            }
            return "$payload.${Base64.getUrlEncoder().withoutPadding().encodeToString(mac)}"
        }
        private fun reply(result: CommandResult) = when (result) {
            is CommandResult.Applied -> result.reply; is CommandResult.Replayed -> result.reply; else -> fail("Expected success")
        }
        private fun body(result: CommandResult) = reply(result).body!!.jsonObject
        private fun denied(code: KitchenFailureCode, action: () -> Any?) = assertEquals(code, assertFailsWith<KitchenFailure> { action() }.code)
        private fun <T> race(action: () -> T): List<T> {
            val start = CountDownLatch(1); val ready = CountDownLatch(4); val pool = Executors.newFixedThreadPool(4)
            val futures = (1..4).map { pool.submit<T> { ready.countDown(); check(start.await(3, TimeUnit.SECONDS)); action() } }
            return try { assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown(); futures.map { it.get(15, TimeUnit.SECONDS) } }
            finally { start.countDown(); futures.forEach { it.cancel(true) }; pool.shutdownNow(); assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS)) }
        }
    }
}
