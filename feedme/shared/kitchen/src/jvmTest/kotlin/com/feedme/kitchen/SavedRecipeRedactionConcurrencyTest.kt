package com.feedme.kitchen

import com.feedme.core.ports.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlin.test.*

/** Real independent serialized dispatchers exercise the ACTUAL common process registry. */
class SavedRecipeRedactionConcurrencyTest {
    @Test fun concurrentBoundariesKeepIndependentFencesAndRemoveOnlyTheirOwnLease() = runBlocking<Unit> {
        val dispatchers = List(4) { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
        try {
            val start = CompletableDeferred<Unit>()
            val jobs = dispatchers.mapIndexed { lane, dispatcher -> async(dispatcher) {
                start.await()
                repeat(200) { iteration ->
                    val scope = StorageScope("redaction-concurrency", ActorKind.ACCOUNT, "$lane-$iteration")
                    val boundary = SessionBoundary(); val lease = boundary.activate(scope)
                    val context = context(scope, boundary, dispatcher)
                    val field = listOf("sourcePostId", "grantId", "creatorLabel")[lane % 3]
                    assertEquals(FailureReason.OUTCOME_UNKNOWN, assertIs<PortResult.Failure>(context.guarded(lease) {
                        context.markSavedRedaction(lease, ID, setOf(field))
                    }).reason)
                    yield()
                    assertEquals(setOf(field), assertIs<PortResult.Value<Set<String>>>(context.guarded(lease) {
                        context.savedRedactions(lease, ID)
                    }).value)
                    boundary.clear()
                    val replacement = boundary.activate(scope)
                    assertTrue(assertIs<PortResult.Value<Set<String>>>(context.guarded(replacement) {
                        context.savedRedactions(replacement, ID)
                    }).value.isEmpty())
                    boundary.clear()
                }
            } }
            start.complete(Unit); jobs.awaitAll()
        } finally { dispatchers.forEach { it.close() } }
    }

    @Test fun synchronousInvalidationDuringMarkerReadCannotRepopulateOldEntryOrNewLease() = runBlocking<Unit> {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> withContext(dispatcher) {
            val scope = StorageScope("redaction-invalidation", ActorKind.ACCOUNT, "owner")
            val boundary = SessionBoundary(); val lease = boundary.activate(scope)
            var invalidate = true
            val context = context(scope, boundary, dispatcher) {
                if (invalidate) { invalidate = false; boundary.clear() }
            }
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(context.guarded(lease) {
                context.markSavedRedaction(lease, ID, setOf("creatorLabel"))
            }).reason)
            // Admission with an already stale lease cannot register another retained entry.
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(context.guarded(lease) {
                context.markSavedRedaction(lease, ID, setOf("grantId"))
            }).reason)
            val replacement = boundary.activate(scope)
            assertTrue(assertIs<PortResult.Value<Set<String>>>(context.guarded(replacement) {
                context.savedRedactions(replacement, ID)
            }).value.isEmpty())
            boundary.clear()
        } }
    }

    private fun context(scope: StorageScope, boundary: SessionBoundary, dispatcher: CoroutineDispatcher,
        onRead: () -> Unit = {}): KitchenContext = KitchenContext(scope, object : PrivateStateStore {
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            onRead(); return PortResult.Value(null)
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> =
            PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("No erasure authority")
    }, boundary, dispatcher, EpochClock { 0 }, object : AccountTransport {
        override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = error("No network")
    })

    private companion object { const val ID = "123e4567-e89b-12d3-a456-426614174777" }
}
