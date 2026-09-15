package com.feedme.mealflow

import com.feedme.contracts.*
import com.feedme.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Detached atomic-store and authenticated GET fixtures, not native encryption/provider proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class IngredientPickerControllerTest {
    @Test fun canonicalSearchUsesActualLabelsAndExplicitQueryWithoutMutationFields() = runTest {
        fixture { f ->
            val state = f.controller.search("leaf & bean").value()
            val call = f.calls.single()
            assertEquals("searchIngredients", call.operationId)
            assertEquals(mapOf("q" to listOf("leaf & bean"), "limit" to listOf("2")), call.queryParameters)
            assertNull(call.body); assertNull(call.idempotencyKey); assertNull(call.ifMatch)
            assertTrue(call.pathParameters.isEmpty())
            assertEquals("Actual catalog label", state.searchResults.single().name)
            assertEquals(listOf("Downloaded alias"), state.searchResults.single().aliases)
            assertEquals(ONE, state.searchResults.single().id); assertFalse(state.searchResults.single().historical)
            assertEquals(setOf(CACHE), f.store.records.keys); assertEquals(0, f.store.erases)
        }
    }

    @Test fun bothActualGuestAndAccountLeasesUseCanonicalSearchAndPantryOperations() = runTest {
        for (guest in listOf(false, true)) fixture(guest = guest) { f ->
            f.controller.search("").value(); f.controller.refreshPantry().value()
            assertEquals(listOf("searchIngredients", "listPantry"), f.calls.map { it.operationId })
            assertEquals(listOf(""), f.calls.first().queryParameters["q"])
        }
    }

    @Test fun collectingAndRestoreNeverAutomaticallyFetchOrSubmit() = runTest {
        fixture { f ->
            assertEquals(IngredientPickerPhase.IDLE, f.controller.states.value.searchPhase)
            val state = f.controller.restore().value()
            assertTrue(state.knownIngredients.isEmpty()); assertTrue(state.pantryItems.isEmpty())
            assertTrue(f.calls.isEmpty()); assertTrue(f.store.records.isEmpty()); assertEquals(0, f.store.writes)
        }
    }

    @Test fun searchPagingPreservesQueryAndOpaqueCursorAndEnforcesConfiguredBound() = runTest {
        fixture { f ->
            f.handler = { call -> ok(page(listOf(ingredient(if ("cursor" in call.queryParameters) TWO else ONE)),
                if ("cursor" in call.queryParameters) "second" else "first")) }
            assertTrue(f.controller.search("same query").value().searchHasMore)
            val state = f.controller.nextSearchPage().value()
            assertEquals(listOf(ONE, TWO), state.searchResults.map { it.id }); assertFalse(state.searchHasMore)
            assertEquals(mapOf("q" to listOf("same query"), "limit" to listOf("2"), "cursor" to listOf("first")), f.calls.last().queryParameters)
            assertEquals(IngredientPickerIssue.PAGE_LIMIT, f.controller.nextSearchPage().value().issue)
            assertEquals(2, f.calls.size)
        }
    }

    @Test fun duplicatedCatalogIdentityOrCyclicCursorNeverAppendsOrCachesPage() = runTest {
        for (duplicate in listOf(false, true)) fixture { f ->
            f.handler = { ok(page(listOf(ingredient(ONE)), "next")) }
            f.controller.search("first").value(); val record = f.store.text(CACHE)
            f.handler = { ok(page(listOf(ingredient(if (duplicate) ONE else TWO)), if (duplicate) null else "next")) }
            failure(f.controller.nextSearchPage(), FailureReason.CONFLICT)
            assertEquals(record, f.store.text(CACHE)); assertEquals(listOf(ONE), f.controller.states.value.searchResults.map { it.id })
        }
    }

    @Test fun queryLimitsCountUnicodeScalarsAndRejectMalformedInputBeforeAnyIo() = runTest {
        fixture { f ->
            f.controller.search("\uD83E\uDD6C".repeat(100)).value()
            assertEquals("\uD83E\uDD6C".repeat(100), f.calls.single().queryParameters["q"]!!.single())
            val reads = f.store.reads
            for (bad in listOf("x".repeat(101), "\uD800", "x\n")) {
                failure(f.controller.search(bad), FailureReason.INVALID_DATA)
                assertEquals(IngredientPickerIssue.INVALID_QUERY, f.controller.states.value.issue)
            }
            assertEquals(1, f.calls.size); assertEquals(reads, f.store.reads)
        }
    }

    @Test fun boundCanonicalProblemIsUnavailableAndMalformedProblemNeverAccepted() = runTest {
        fixture { f ->
            f.handler = { PortResult.Value(problem(403, "FORBIDDEN")) }
            failure(f.controller.search("private"), FailureReason.FORBIDDEN)
            assertEquals(IngredientPickerPhase.UNAVAILABLE, f.controller.states.value.searchPhase)
            f.handler = { PortResult.Value(ApiReply(403, bytes("{}"), contentType = "application/problem+json")) }
            failure(f.controller.search("private"), FailureReason.INVALID_DATA)
            assertTrue(f.store.records.isEmpty())
        }
    }

    @Test fun rateLimitAndTransportFailureStayExplicitWithoutAutomaticRetry() = runTest {
        fixture { f ->
            f.handler = { PortResult.Failure(FailureReason.RATE_LIMITED, 12) }
            failure(f.controller.search("x"), FailureReason.RATE_LIMITED)
            assertEquals(12L, f.controller.states.value.retryAfterSeconds)
            assertEquals(IngredientPickerIssue.RETRY_LATER, f.controller.states.value.issue)
            f.handler = { throw IllegalStateException("private transport material") }
            failure(f.controller.search("x"), FailureReason.UNAVAILABLE)
            assertEquals(2, f.calls.size); assertFalse(f.controller.states.value.toString().contains("private transport"))
        }
    }

    @Test fun offlineRecreationSearchesOnlyDownloadedNamesAndAliasesWithHistoricalDisclosure() = runTest {
        fixture { f ->
            f.controller.search("initial").value(); val saved = f.store.text(CACHE)
            assertFalse(saved.contains("initial")); assertFalse(saved.contains("query"))
            f.reopen(); f.online = false
            val state = f.controller.search("downloaded ALIAS").value()
            assertEquals(IngredientPickerPhase.OFFLINE, state.searchPhase)
            assertEquals(ONE, state.searchResults.single().id); assertTrue(state.searchResults.single().historical)
            assertTrue(f.controller.search("never downloaded label").value().searchResults.isEmpty())
            assertEquals(1, f.calls.size); assertEquals(saved, f.store.text(CACHE))
        }
    }

    @Test fun offlinePrivateAccessNeverUsesTransportEvenWhenConnectivityOnline() = runTest {
        fixture(allowed = false) { f ->
            assertEquals(IngredientPickerPhase.OFFLINE, f.controller.search("x").value().searchPhase)
            assertEquals(IngredientPickerPhase.OFFLINE, f.controller.refreshPantry().value().pantryPhase)
            assertTrue(f.calls.isEmpty()); assertTrue(f.store.records.isEmpty())
        }
    }

    @Test fun cacheExpiryPrunesExplicitlyAndNeverReconstructsLabels() = runTest {
        fixture { f ->
            f.controller.search("x").value(); f.time += 1_001
            assertTrue(f.controller.refreshPantry().value().knownIngredients.isEmpty())
            assertNull(f.controller.states.value.pantryItems.single().resolvedIngredient)
            f.online = false
            val restored = f.controller.restore().value()
            assertTrue(restored.knownIngredients.isEmpty()); assertEquals(IngredientPickerIssue.CACHE_EXPIRED, restored.issue)
            assertTrue(f.controller.search("Actual").value().searchResults.isEmpty())
            assertEquals(2, f.store.writes); assertEquals(2, f.calls.size)
        }
    }

    @Test fun backwardsClockAndFutureStoredTimeAreRejectedWithoutOverwrite() = runTest {
        fixture { f ->
            f.controller.search("x").value(); val text = f.store.text(CACHE); f.time--
            failure(f.controller.search("x"), FailureReason.CONFLICT)
            f.reopen(); failure(f.controller.restore(), FailureReason.CONFLICT)
            assertEquals(text, f.store.text(CACHE)); assertEquals(1, f.calls.size)
        }
    }

    @Test fun catalogVersionRegressionAndSameVersionMetadataChangeFailButHigherVersionSucceeds() = runTest {
        fixture { f ->
            f.handler = { ok(page(listOf(ingredient(ONE, "2", "Second")))) }; f.controller.search("x").value()
            val original = f.store.text(CACHE)
            for ((version, label) in listOf("1" to "First", "2" to "Changed")) {
                f.handler = { ok(page(listOf(ingredient(ONE, version, label)))) }
                failure(f.controller.search("x"), FailureReason.CONFLICT); assertEquals(original, f.store.text(CACHE))
            }
            f.handler = { ok(page(listOf(ingredient(ONE, "3", "Reviewed update")))) }
            assertEquals("Reviewed update", f.controller.search("x").value().searchResults.single().name)
        }
    }

    @Test fun exactIntegerVersionsDoNotRoundThroughFloatingPoint() {
        assertTrue(pickerCompareVersion(ingredient(ONE, "9007199254740993"), ingredient(ONE, "9007199254740992")) > 0)
        assertEquals(0, pickerCompareVersion(ingredient(ONE, "1e2"), ingredient(ONE, "100.0")))
        assertFailsWith<MealFailure> { pickerVersion(ingredient(ONE, "0")) }
    }

    @Test fun boundedCacheEvictsResolvedLabelsByCountAndEncodedBytes() {
        val countPolicy = IngredientPickerPolicy(2, 2, 1, 1_000)
        val one = IngredientPickerCache(ORIGIN, countPolicy)
        val kept = one.merge(IngredientCache(5, listOf(CachedIngredient(ingredient(ONE), 5))), listOf(CachedIngredient(ingredient(TWO), 6)), 6)
        assertEquals(listOf(TWO), kept.items.map { it.id })
        val codec = IngredientPickerCache(ORIGIN, IngredientPickerPolicy(50, 2, 512, 1_000))
        val incoming = (1..40).map { CachedIngredient(ingredient(id(it), name = "\\".repeat(12_000)), 10) }
        incoming.forEach { codec.validate(it.document) }
        val bounded = codec.merge(IngredientCache(10, emptyList()), incoming, 10)
        assertTrue(bounded.items.isNotEmpty()); assertTrue(bounded.items.size < incoming.size)
        val encoded = codec.encode(bounded); assertTrue(encoded.copyForCodec().size <= 524_288)
        assertEquals(bounded.items.size, codec.decode(encoded).items.size)
    }

    @Test fun unsuccessfulOrFalseCacheReceiptsNeverPublishFreshSearchSuccess() = runTest {
        for (fault in Fault.entries) fixture { f ->
            f.store.fault = fault
            val result = f.controller.search("x")
            assertIs<PortResult.Failure>(result)
            assertTrue(f.controller.states.value.searchResults.isEmpty())
            assertEquals(IngredientPickerPhase.ERROR, f.controller.states.value.searchPhase)
            if (fault == Fault.AFTER) {
                f.reopen(); f.online = false
                assertTrue(f.controller.search("Actual").value().searchResults.single().historical)
            }
        }
    }

    @Test fun corruptWrongOriginAndUnknownSchemaCacheFailClosedWithoutReset() = runTest {
        fixture { f ->
            f.controller.search("x").value(); val valid = f.store.records.getValue(CACHE)
            for (bad in listOf(valid.copy(payload = bytes("{}")), valid.copy(schemaVersion = 2),
                valid.copy(payload = bytes(valid.payload.copyForCodec().decodeToString().replace(ORIGIN, TWO))))) {
                f.store.records[CACHE] = bad
                failure(f.controller.restore(), FailureReason.INVALID_DATA)
                assertEquals(1, f.store.writes); assertEquals(0, f.store.erases)
                assertContentEquals(bad.payload.copyForCodec(), f.store.records.getValue(CACHE).payload.copyForCodec())
            }
        }
    }

    @Test fun pantryPresenceAndConfirmationRemainDistinctAndNeverInventMissingLabels() = runTest {
        fixture { f ->
            f.controller.search("label").value()
            f.handler = { ok(page(listOf(pantry(ONE, "usuallyHave", "usual"), pantry(TWO, "uncertain", null)), "more")) }
            val state = f.controller.refreshPantry().value()
            assertEquals(listOf("usuallyHave", "uncertain"), state.pantryItems.map { it.presence })
            assertEquals("usual", assertIs<WireField.Value<String>>(state.pantryItems[0].confirmationStatus).value)
            assertEquals(WireField.Missing, state.pantryItems[1].confirmationStatus)
            assertEquals(WireField.Null, state.pantryItems[0].confirmedAt)
            assertEquals("Actual catalog label", state.pantryItems[0].resolvedIngredient!!.name)
            assertNull(state.pantryItems[1].resolvedIngredient); assertFalse(state.pantryItems[1].historical)
            assertEquals(setOf(CACHE), f.store.records.keys)
            assertTrue(f.calls.all { it.operationId in setOf("searchIngredients", "listPantry") })
        }
    }

    @Test fun pantryPaginationIsBoundedAndNeverPersistsOrInfersMissingStock() = runTest {
        fixture { f ->
            f.handler = { call -> ok(page(listOf(pantry(if ("cursor" in call.queryParameters) TWO else ONE, "out", "unavailable")),
                if ("cursor" in call.queryParameters) "unfetched" else "first")) }
            assertTrue(f.controller.refreshPantry().value().pantryHasMore)
            val state = f.controller.nextPantryPage().value()
            assertEquals(listOf(ONE, TWO), state.pantryItems.map { it.ingredientId }); assertFalse(state.pantryHasMore)
            assertEquals(listOf("first"), f.calls.last().queryParameters["cursor"])
            assertEquals(IngredientPickerIssue.PAGE_LIMIT, f.controller.nextPantryPage().value().issue)
            assertTrue(f.store.records.isEmpty()); assertEquals(2, f.calls.size)
        }
    }

    @Test fun duplicatePantryRowIngredientOrCursorCannotMixPages() = runTest {
        for (kind in 0..2) fixture { f ->
            f.handler = { ok(page(listOf(pantry(ONE)), "next")) }; f.controller.refreshPantry().value()
            val incoming = when (kind) { 0 -> pantry(ONE); 1 -> pantry(TWO, rowId = ONE); else -> pantry(TWO) }
            f.handler = { ok(page(listOf(incoming), if (kind == 2) "next" else null)) }
            failure(f.controller.nextPantryPage(), FailureReason.CONFLICT)
            assertEquals(listOf(ONE), f.controller.states.value.pantryItems.map { it.ingredientId })
        }
    }

    @Test fun restoreReadsTheExistingSingleMealPantryPageWithoutCreatingAnotherAuthority() = runTest {
        fixture { f ->
            val pantry = page(listOf(pantry(ONE, "usuallyHave", "usual")), "original-next")
            val original = MealFlowCodec(MealRequestBuilder(), ORIGIN).encode(FlowRecord(f.time, f.time, pantry = pantry))
            f.store.records[MEAL] = PrivateRecord(7, 1, original)
            val state = f.controller.restore().value()
            assertTrue(state.pantryItems.single().historical); assertNull(state.pantryItems.single().checkedAtMillis)
            assertTrue(state.pantryHasMore); assertEquals(0, f.store.writes); assertTrue(f.calls.isEmpty())
            f.controller.refreshPantry().value()
            assertEquals(setOf(MEAL), f.store.records.keys)
            assertContentEquals(original.copyForCodec(), f.store.records.getValue(MEAL).payload.copyForCodec())
        }
    }

    @Test fun malformedOrOversizedPagesAndMetadataNeverBecomePickerChoices() = runTest {
        fixture { f ->
            for (body in listOf(page(listOf(ingredient(ONE), ingredient(TWO), ingredient(id(3)))),
                page(listOf(ingredient(ONE, name = "x".repeat(33_000)))), doc(buildJsonObject { put("items", JsonArray(emptyList())) }))) {
                f.handler = { ok(body) }; assertIs<PortResult.Failure>(f.controller.search("x"))
                assertTrue(f.controller.states.value.searchResults.isEmpty()); assertEquals(0, f.store.writes)
            }
        }
    }

    @Test fun supersedingSearchFencesSuspendedOldResponseAndOnlyNewQueryIsCached() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.queryParameters["q"] == listOf("old")) {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; ok(page(listOf(ingredient(ONE))))
            } else ok(page(listOf(ingredient(TWO)))) }
            val old = async { f.controller.search("old") }; entered.await()
            val fresh = async { f.controller.search("new") }; runCurrent(); release.complete(Unit)
            failure(old.await(), FailureReason.STALE_SESSION)
            assertEquals(TWO, fresh.await().value().searchResults.single().id)
            assertEquals(listOf(TWO), f.controller.states.value.knownIngredients.map { it.id }); assertEquals(1, f.store.writes)
        }
    }

    @Test fun searchAndPantryDoNotInvalidateEachOtherAndDuplicatePageTapConflicts() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { call -> if (call.operationId == "searchIngredients") {
                entered.complete(Unit); release.await(); ok(page(listOf(ingredient(ONE))))
            } else ok(page(listOf(pantry(ONE)))) }
            val search = async { f.controller.search("x") }; entered.await()
            failure(f.controller.nextSearchPage(), FailureReason.CONFLICT)
            val pantry = async { f.controller.refreshPantry() }; runCurrent(); release.complete(Unit)
            search.await().value(); assertNotNull(pantry.await().value().pantryItems.single().resolvedIngredient)
        }
    }

    @Test fun cancellationAfterNonCooperativeNetworkNeverCachesOrPublishesLateRows() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; ok(page(listOf(ingredient(ONE)))) }
            val job = async { f.controller.search("cancelled private query") }; entered.await(); job.cancel(); release.complete(Unit)
            assertFailsWith<CancellationException> { job.await() }
            assertEquals(0, f.store.writes); assertTrue(f.controller.states.value.searchResults.isEmpty())
            f.handler = { ok(page(listOf(ingredient(TWO)))) }
            assertEquals(TWO, f.controller.search("next").value().searchResults.single().id)
        }
    }

    @Test fun leaseInvalidationSynchronouslyRedactsBothPanesAndFencesDelayedTransport() = runTest {
        fixture { f ->
            f.controller.search("private").value(); f.controller.refreshPantry().value()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.handler = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; ok(page(listOf(ingredient(TWO)))) }
            val job = async { f.controller.search("pending query") }; entered.await()
            val newer = f.boundary.activate(f.scope)
            val redacted = f.controller.states.value
            assertEquals("", redacted.searchQuery); assertTrue(redacted.knownIngredients.isEmpty()); assertTrue(redacted.pantryItems.isEmpty())
            release.complete(Unit); failure(job.await(), FailureReason.STALE_SESSION)
            assertTrue(f.boundary.isCurrent(newer)); assertEquals(1, f.store.writes)
        }
    }

    @Test fun closeFencesDelayedReadAndReleasesOnlyControllerOwnership() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.store.afterRead = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val job = async { f.controller.restore() }; entered.await(); f.controller.close().value(); release.complete(Unit)
            failure(job.await(), FailureReason.STALE_SESSION); assertTrue(f.boundary.isCurrent(f.lease))
            assertEquals(0, f.store.erases); f.store.afterRead = null
            f.controller = f.newController(); f.controller.restore().value()
        }
    }

    @Test fun duplicateControllerClaimRequiresExplicitCloseButInvalidationReleasesOldClaim() = runTest {
        fixture { f ->
            f.controller.restore().value(); val duplicate = f.newController()
            try { failure(duplicate.restore(), FailureReason.CONFLICT) } finally { duplicate.close().value() }
            f.reopen(); f.controller.restore().value()
            f.boundary.activate(f.scope); failure(f.controller.restore(), FailureReason.STALE_SESSION)
            assertTrue(f.controller.states.value.knownIngredients.isEmpty())
        }
    }

    @Test fun changedLeaseOrCancellationDuringCacheAcknowledgementCannotPublishOrEraseWrittenOwnerRecord() = runTest {
        fixture { f ->
            f.store.afterCommit = { f.boundary.activate(f.scope) }
            failure(f.controller.search("x"), FailureReason.STALE_SESSION)
            assertTrue(f.controller.states.value.knownIngredients.isEmpty()); assertEquals(1, f.store.writes)
            assertEquals(setOf(CACHE), f.store.records.keys); assertEquals(0, f.store.erases)
        }
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.store.afterCommit = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val job = async { f.controller.search("cancelled") }; entered.await(); job.cancel(); release.complete(Unit)
            assertFailsWith<CancellationException> { job.await() }
            assertTrue(f.controller.states.value.knownIngredients.isEmpty()); assertEquals(1, f.store.writes)
            assertEquals(0, f.store.erases); assertTrue(f.boundary.isCurrent(f.lease))
            f.store.afterCommit = null; f.reopen(); f.online = false
            assertTrue(f.controller.search("Actual").value().searchResults.single().historical)
        }
    }

    @Test fun publicModelsDetachCollectionsAndRedactPrivateDetails() = runTest {
        fixture { f ->
            val state = f.controller.search("private query").value(); f.controller.refreshPantry().value()
            val aliases = state.searchResults.single().aliases as MutableList<String>; runCatching { aliases.clear() }
            assertEquals(listOf("Downloaded alias"), state.searchResults.single().aliases)
            val rows = state.searchResults as MutableList<IngredientOption>; runCatching { rows.clear() }
            assertEquals(1, state.searchResults.size)
            for (text in listOf(state.toString(), state.searchResults.single().toString(), f.controller.states.value.pantryItems.single().toString())) {
                assertFalse(text.contains("private query")); assertFalse(text.contains("Actual catalog")); assertFalse(text.contains(ONE))
            }
        }
    }

    private suspend fun TestScope.fixture(guest: Boolean = false, allowed: Boolean = true, action: suspend (Fixture) -> Unit) {
        val f = Fixture(this, guest, allowed)
        try { action(f) } finally { f.controller.close().value() }
    }
    private class Fixture(test: TestScope, guest: Boolean, allowed: Boolean) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = StorageScope("fixture", if (guest) ActorKind.GUEST else ActorKind.ACCOUNT, "private-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); val store = Store(scope)
        var time = 10_000L; var online = true; val calls = mutableListOf<ApiCall>()
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { call ->
            ok(page(listOf(if (call.operationId == "searchIngredients") ingredient(ONE) else pantry(ONE))))
        }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Fixture.lease, lease); calls += call; return handler(call)
            }
        }, allowed)
        var controller = newController()
        fun newController() = IngredientPickerController(access, boundary, dispatcher, EpochClock { time },
            ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }, IngredientPickerPolicy(2, 2, 8, 1_000))
        suspend fun reopen() { controller.close().value(); controller = newController() }
    }
    private enum class Fault { BEFORE, AFTER, BAD_REVISION, BAD_READBACK }
    private class Store(private val scope: StorageScope) : PrivateStateStore {
        val records = mutableMapOf<RecordKey, PrivateRecord>(); var writes = 0; var reads = 0; var erases = 0
        var fault: Fault? = null; var afterRead: (suspend () -> Unit)? = null; var afterCommit: (suspend () -> Unit)? = null
        private var corrupt = false
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            assertEquals(this.scope, scope); reads++
            val record = records[key]?.let { it.copy(payload = PrivateBytes(it.payload.copyForCodec())) }
            afterRead?.invoke()
            return PortResult.Value(if (corrupt) { corrupt = false; record?.copy(payload = bytes("{}")) } else record)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            assertEquals(this.scope, scope)
            val put = assertIs<StoreMutation.Put>(mutations.single()); assertEquals(CACHE, put.key)
            if (put.expectedRevision != records[put.key]?.revision) return PortResult.Failure(FailureReason.CONFLICT)
            val failure = fault; fault = null
            if (failure == Fault.BEFORE) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            val revision = (records[put.key]?.revision ?: 0) + 1
            records[put.key] = PrivateRecord(revision, put.schemaVersion, PrivateBytes(put.payload.copyForCodec())); writes++
            afterCommit?.invoke()
            if (failure == Fault.AFTER) return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            if (failure == Fault.BAD_READBACK) corrupt = true
            return PortResult.Value(mapOf(put.key to if (failure == Fault.BAD_REVISION) 0L else revision))
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { erases++; return PortResult.Failure(FailureReason.NOT_CONFIGURED) }
        fun text(key: RecordKey) = records.getValue(key).payload.copyForCodec().decodeToString()
    }
    companion object {
        private const val ORIGIN = "00000000-0000-4000-8000-000000000099"
        private const val ONE = "00000000-0000-4000-8000-000000000001"
        private const val TWO = "00000000-0000-4000-8000-000000000002"
        private const val TIME = "2026-09-13T10:00:00Z"
        private val CACHE = RecordKey("mealflow.ingredients.v1", ORIGIN)
        private val MEAL = RecordKey("mealflow.v1", ORIGIN)
        private fun id(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
        private fun ingredient(id: String, version: String = "1", name: String = "Actual catalog label") = doc(buildJsonObject {
            put("id", id); put("version", Json.parseToJsonElement(version)); put("createdAt", TIME); put("updatedAt", TIME)
            put("name", name); put("aliases", JsonArray(listOf(JsonPrimitive("Downloaded alias"))))
            put("category", "fixture category"); put("supportedUnits", JsonArray(listOf(JsonPrimitive("g"))))
        })
        private fun pantry(ingredientId: String, presence: String = "available", confirmation: String? = "confirmed", rowId: String = ingredientId) = doc(buildJsonObject {
            put("id", rowId); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME); put("ingredientId", ingredientId)
            put("presence", presence); confirmation?.let { put("confirmationStatus", it) }; put("confirmedAt", JsonNull); put("staple", false)
        })
        private fun page(items: List<WireDocument>, cursor: String? = null) = doc(buildJsonObject {
            put("items", JsonArray(items.map { it.json() })); put("nextCursor", cursor?.let(::JsonPrimitive) ?: JsonNull); put("serverTime", TIME)
        })
        private fun ok(document: WireDocument): PortResult<ApiReply> = PortResult.Value(ApiReply(200, PrivateBytes(document.encodeUtf8()), contentType = "application/json"))
        private fun problem(status: Int, code: String) = ApiReply(status, bytes(buildJsonObject {
            put("type", "about:blank"); put("title", "Unavailable"); put("status", status); put("code", code); put("traceId", "trace")
        }.toString()), traceId = "trace", contentType = "application/problem+json")
        private fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        private fun <T> PortResult<T>.value(): T = assertIs<PortResult.Value<T>>(this).value
        private fun failure(result: PortResult<*>, reason: FailureReason) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
