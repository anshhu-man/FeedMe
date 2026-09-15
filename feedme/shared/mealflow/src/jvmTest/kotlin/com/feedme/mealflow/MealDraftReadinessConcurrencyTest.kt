package com.feedme.mealflow

import com.feedme.core.ports.*
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlin.test.*

/** Real independent serialized dispatchers exercise global membership, never native storage. */
class MealDraftReadinessConcurrencyTest {
    @Test fun independentBoundaryDispatchersRegisterAndInvalidateWithoutCrossSessionMembershipRaces(): Unit = runBlocking {
        val dispatchers = List(4) { Executors.newSingleThreadExecutor().asCoroutineDispatcher() }
        try { coroutineScope { dispatchers.mapIndexed { index, dispatcher -> async(dispatcher) {
            repeat(100) {
                val f = Fixture(dispatcher, "actor-$index-$it")
                try {
                    val readiness = f.meals.draftReadiness
                    val attempt = readiness.begin(edit = true)
                    readiness.beforeWrite(attempt); readiness.observed(attempt, RECORD); readiness.succeeded(attempt)
                    assertTrue(readiness.isReady)
                    readiness.invalidate(); assertFalse(readiness.isReady)
                    f.boundary.activate(f.scope); assertFalse(readiness.isReady)
                } finally { f.meals.close(); f.boundary.clear() }
            }
        } }.awaitAll() } } finally { dispatchers.forEach { it.close() } }
    }
    @Test fun alreadyStaleSynchronousConstructionCannotRegisterOrRetainAnAcknowledgedFence(): Unit = runBlocking {
        val f = Fixture(Dispatchers.Unconfined, "stale")
        try {
            f.boundary.clear()
            repeat(20) {
                val stale = f.newMeals()
                val r = stale.draftReadiness; val attempt = r.begin(true)
                r.observed(attempt, RECORD); r.succeeded(attempt)
                assertFalse(r.isReady); assertIs<PortResult.Failure>(stale.restore()); stale.close()
            }
        } finally { f.meals.close(); f.boundary.clear() }
    }
    @Test fun sameLeaseButDifferentActualStoreNeverInheritsReadinessProof(): Unit = runBlocking {
        val f = Fixture(Dispatchers.Unconfined, "store-pair")
        try {
            val r = f.meals.draftReadiness; val attempt = r.begin(true)
            r.beforeWrite(attempt); r.observed(attempt, RECORD); r.succeeded(attempt); assertTrue(r.isReady)
            val otherAccess = AuthenticatedMealPlanningAccess(f.lease, ORIGIN, EmptyStore(), f.access.transport, true)
            val other = MealRequestController(otherAccess, f.boundary, Dispatchers.Unconfined, EpochClock { 1L },
                ConnectivityPort { Connectivity.OFFLINE }, MealOperationIds { error("No IDs") }, POLICY)
            try {
                assertFalse(other.draftReadiness.isReady)
                assertFalse(r.matches(f.meals, otherAccess, f.boundary))
            } finally { other.close() }
        } finally { f.meals.close(); f.boundary.clear() }
    }
    @Test fun oldUnacknowledgedAttemptCannotReleaseAYoungerDirtyGeneration(): Unit = runBlocking {
        val f = Fixture(Dispatchers.Unconfined, "late")
        try {
            val r = f.meals.draftReadiness; val old = r.begin(true)
            r.beforeWrite(old); r.observed(old, RECORD); r.invalidate()
            r.succeeded(old); assertFalse(r.isReady)
            val current = r.begin(true); r.beforeWrite(current); r.observed(current, RECORD.copy(revision = 2)); r.succeeded(current)
            r.failed(old); assertTrue(r.isReady)
        } finally { f.meals.close(); f.boundary.clear() }
    }
    private class Fixture(val dispatcher: CoroutineDispatcher, actor: String) {
        val scope = StorageScope("fixture", ActorKind.ACCOUNT, actor)
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, EmptyStore(), object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> = error("No transport")
        }, true)
        val meals = newMeals()
        fun newMeals() = MealRequestController(access, boundary, dispatcher, EpochClock { 1L },
            ConnectivityPort { Connectivity.OFFLINE }, MealOperationIds { error("No IDs") }, POLICY)
    }
    private class EmptyStore : PrivateStateStore {
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> = PortResult.Value(null)
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> = error("No writes")
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("No erasure")
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000001"
        val POLICY = MealFlowPolicy(86_400_000, 86_400_000, 5, 100)
        val RECORD = PrivateRecord(1, 1, PrivateBytes("test-only-evidence".encodeToByteArray()))
    }
}
